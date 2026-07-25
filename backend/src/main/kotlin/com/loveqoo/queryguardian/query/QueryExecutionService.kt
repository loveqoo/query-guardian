package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.BlockedException
import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver
import com.loveqoo.queryguardian.exec.ExecutionAudit
import com.loveqoo.queryguardian.exec.ExecutionEvent
import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.exec.ExecutionOutcome
import com.loveqoo.queryguardian.exec.ExecutionResult
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.QueryExecutor
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.AppliedRewrite
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.springframework.stereotype.Service

/** 재작성 미리보기 — **실행하지 않는다**. 데이터는 한 줄도 나가지 않는다. */
data class PreviewedRewrite(
    val rewrittenSql: String,
    val applied: List<AppliedRewrite>,
    val report: LintReportDto,
)

/** 실행 결과 — 결과 행은 응답에만 담긴다(저장 금지, spec 008 §6). */
data class ExecutedQuery(
    val result: ExecutionResult,
    val rewrittenSql: String,
    val applied: List<AppliedRewrite>,
)

/**
 * 실행 게이트 (spec 008 §5, M2-4).
 *
 * 이 계층이 **인증·승인·룰·재작성·실행을 모두 아는 유일한 곳**이다. `exec`에 둘 수 없다 —
 * ArchUnit이 `exec` → `auth` 의존을 금지하고, 그 이유가 "재작성이 권한에 따라 달라지면 권한 없는 사용자가
 * 마스킹을 덜 받는 역전"이기 때문이다.
 *
 * 순서(§5): 열람 권한 → 요청자 일치 → 검토 승인 → **파싱 1회** → 데이터 권한 재검사 → 접수·룰 재판정
 * → 데모 매핑 총체성 → 재작성 → 재작성 검증 → 실행.
 *
 * **모든 차단 지점에서 감사에 기록한 뒤 throw한다.** 예외 핸들러에 맡기면 어떤 경로가 어디서 막혔는지 흐려진다.
 */
@Service
class QueryExecutionService(
    private val queries: QueryService,
    private val approvalGate: ApprovalGate,
    private val access: AccessControl,
    private val parser: DialectParser,
    private val lintService: LintService,
    private val demoTables: DemoTableResolver,
    private val planner: RewritePlanner,
    private val rewriteCatalog: RewriteCatalog,
    private val rewriter: SqlRewriter,
    private val executor: QueryExecutor,
    private val audit: ExecutionAudit,
) {

    fun execute(queryId: Long, actor: String, privileged: Boolean): ExecutedQuery {
        // 열람 권한. 차단도 **기록한다** — 남의 쿼리 id로 실행을 시도한 것 자체가 감사 대상이다
        // (열거 시도를 사후에 볼 수 있어야 한다). 처음에는 "존재를 모르는 상태"라며 제외했는데,
        // 시도한 id가 응답에 이미 드러나 있으므로 기록하지 않을 이유가 없다.
        val query = try {
            queries.visible(queryId, actor, privileged)
        } catch (e: ForbiddenException) {
            audit.record(queryId, actor, ExecutionOutcome.BLOCKED, "(열람 권한 없음 — 본문을 기록하지 않는다)",
                errorCode = "FORBIDDEN_READ", errorDetail = e.message)
            throw e
        }
        val sql = query.sqlText

        fun blocked(code: String, message: String): Nothing {
            audit.record(queryId, actor, ExecutionOutcome.BLOCKED, sql, errorCode = code, errorDetail = message)
            throw ForbiddenException(message)
        }

        fun blockedByReport(code: String, report: LintReportDto): Nothing =
            blockedByReport(queryId, actor, sql, code, report)

        // **대행 실행 불허**(결정 14): 열람은 STEWARD/ADMIN에게 열지만 실행은 요청자 본인만.
        // 감사 로그에서 "그 PII를 누가 봤는가"가 한 사람으로 남아야 한다.
        val approval = approvalGate.findRequest(query.requestId)
            ?: blocked("NO_REQUEST", "근거 승인 요청을 찾을 수 없어 실행할 수 없습니다")
        if (approval.requester != actor) {
            blocked("REQUESTER_MISMATCH", "본인이 요청·작성한 쿼리만 실행할 수 있습니다")
        }
        if (query.reviewStatus != ReviewStatus.APPROVED.name) {
            blocked("NOT_REVIEWED", "검토 승인된 쿼리만 실행할 수 있습니다 (현재 ${query.reviewStatus})")
        }

        // **파싱 1회** — IR·접수 위반·AST 핸들을 함께 얻어 판정과 재작성이 같은 AST를 쓴다(결정 13)
        val inspected = parser.inspect(sql)
        val ir: QueryIR = when (val parsed = inspected.parse) {
            is ParseResult.Success -> parsed.ir
            is ParseResult.Failure -> blockedByReport("PARSE_FAILED", LintReportDto.from(lintService.judge(inspected)))
        }

        // 데이터 권한은 **현재 기준**으로 다시 본다 — 저장 후 회수됐을 수 있다. 룰보다 앞(spec 007 §6.0).
        val logicalTables = approvalGate.physicalTables(ir)
        try {
            access.checkTables(actor, logicalTables)
        } catch (e: AccessBlockedException) {
            audit.record(queryId, actor, ExecutionOutcome.BLOCKED, sql,
                errorCode = e.detail.code, errorDetail = e.detail.message)
            throw e
        }

        // 승인도 **현재 상태로 재검사**한다(상태·요청자·테이블 커버). 예전에는 "승인은 APPROVED 이후 불변"과
        // "수정 시 게이트가 재실행된다"는 두 가정에 기대고 있었는데, 적대 검토가 두 번째 가정이 얼마나 얇은지
        // 보여줬다(소유권 탈취로 request_id가 바뀔 수 있었다). §5는 예외 없이 재판정을 요구한다.
        try {
            approvalGate.check(query.requestId, actor, ir)
        } catch (e: ApprovalBlockedException) {
            audit.record(queryId, actor, ExecutionOutcome.BLOCKED, sql,
                errorCode = e.detail.code, errorDetail = e.detail.message)
            throw e
        }

        // 접수 검사 + 룰 **재판정** — 저장 시점 스냅샷은 표시용이고 게이트 근거가 아니다(§5)
        val report = LintReportDto.from(lintService.judge(inspected, query.purposeCode))
        if (report.blocked) blockedByReport("RULE_BLOCKED", report)

        // 데모 매핑 총체성 — §2.7-3의 **최후 방어선**이 여기서 실제로 작동한다.
        // 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 실재하는 거버넌스 테이블을 직격한다.
        val mapping = when (val resolved = demoTables.resolve(logicalTables)) {
            is DemoMapping.Resolved -> resolved.byLogical
            is DemoMapping.Incomplete -> blocked(
                "NO_DEMO_MAPPING",
                "실행 대상 매핑이 없는 테이블이 있습니다: ${resolved.unmapped.joinToString(", ")}",
            )
            is DemoMapping.Invalid -> blocked(
                "INVALID_PHYSICAL_NAME",
                "실행 대상 테이블명이 식별자 규칙을 위반했습니다: ${resolved.badNames.joinToString(", ")}",
            )
            DemoMapping.Empty -> blocked("NO_DEMO_MAPPING", "실행할 대상 테이블이 없습니다")
        }

        val plan = when (val planned = planner.plan(ir, query.purposeCode, mapping)) {
            is PlanOutcome.Planned -> planned.plan
            is PlanOutcome.Refused -> blocked("REWRITE_${planned.refusal.name}", planned.message)
        }

        // 재작성 + 자체 검증(§3.0.3). 검증 기대치는 계획이 아니라 **카탈로그**에서 재도출한다.
        val rewritten = when (
            val outcome = rewriter.rewrite(inspected.statement!!, plan, ir, maskedColumnsOf())
        ) {
            is RewriteOutcome.Rewritten -> outcome
            is RewriteOutcome.Refused -> blocked("REWRITE_${outcome.refusal.name}", outcome.message)
        }

        // 계획에 상한이 없으면 재작성이 LIMIT을 넣지 않았다는 뜻이다 — 상한 없는 실행을 허용하지 않는다(fail-closed)
        val limitCap = plan.limitCap
            ?: blocked("REWRITE_NO_LIMIT", "행 상한을 적용하지 못했습니다 — 실행할 수 없습니다")
        val result = try {
            executor.execute(rewritten.sql, limitCap.maxRows, limitCap.governanceCap)
        } catch (e: ExecutionFailure) {
            // 사용자에게는 분류 코드만, 원문(SQLState·vendor code)은 감사에만 (§6).
            // 감사 저장이 실패해도 **원래 실행 오류가 이긴다** — 감사 예외로 바꿔치면 무엇이 실패했는지 잃는다.
            // (성공 경로는 반대다: 기록에 실패하면 데이터를 내보내지 않는다 — 아래 참조.)
            runCatching {
                audit.record(
                    queryId, actor, ExecutionOutcome.ERROR, sql,
                    rewrittenSql = rewritten.sql, applied = rewritten.applied,
                    errorCode = e.kind.name, errorDetail = e.detail,
                )
            }
            throw e
        }

        // **기록 먼저, 반환 나중** — 감사 저장이 실패하면 데이터를 내보내지 않는다(fail-closed).
        // "누가 그 PII를 봤는가"를 남길 수 없으면 보여주지 않는 것이 이 제품의 통제 방식이다.
        audit.record(
            queryId, actor, ExecutionOutcome.SUCCESS, sql,
            rewrittenSql = rewritten.sql, applied = rewritten.applied, result = result,
        )
        return ExecutedQuery(result, rewritten.sql, rewritten.applied)
    }

    /**
     * **재작성 미리보기** (spec 008 §7) — 저장 전에 "무엇이 자동 적용되는지"를 보여준다. 실행은 하지 않는다.
     *
     * 실행 게이트와 **같은 검사를 통과해야** 한다(권한·접수·룰·승인 범위·매핑 총체성·재작성 검증).
     * 그러지 않으면 미리보기가 게이트를 우회해 카탈로그를 캐는 창구가 된다 — 적용될 강제식 원문이 응답에 담기므로
     * "어떤 컬럼이 MASK인지, 마스크 식이 무엇인지"를 알아낼 수 있다.
     *
     * 검토 상태는 보지 않는다 — 미리보기는 **저장 이전** 단계다. 대신 승인 요청의 요청자 본인만 쓸 수 있다.
     */
    fun previewRewrite(sql: String, requestId: Long?, actor: String): PreviewedRewrite {
        fun blocked(code: String, message: String): Nothing {
            audit.record(null, actor, ExecutionOutcome.BLOCKED, sql, errorCode = code, errorDetail = message)
            throw ForbiddenException(message)
        }

        // purposeCode는 클라이언트 입력이 아니라 **승인 요청에서 서버가 주입**한다 (spec 005 C1).
        // 클라이언트가 purpose를 고를 수 있으면 purpose별 FILTER를 스스로 면제할 수 있다.
        val approval = requestId?.let { approvalGate.findRequest(it) }
            ?: blocked("NO_REQUEST", "승인된 요청을 선택해야 재작성을 미리 볼 수 있습니다")

        val inspected = parser.inspect(sql)
        val ir: QueryIR = when (val parsed = inspected.parse) {
            is ParseResult.Success -> parsed.ir
            is ParseResult.Failure -> blockedByReport(null, actor, sql, "PARSE_FAILED", LintReportDto.from(lintService.judge(inspected)))
        }

        val logicalTables = approvalGate.physicalTables(ir)
        try {
            access.checkTables(actor, logicalTables)
        } catch (e: AccessBlockedException) {
            audit.record(null, actor, ExecutionOutcome.BLOCKED, sql, errorCode = e.detail.code, errorDetail = e.detail.message)
            throw e
        }

        // 승인 요청 검사(요청자 본인·승인 완료·테이블 커버) — 저장 게이트와 같은 계약을 쓴다.
        // **감사 래퍼 안에서** 호출한다: 예전엔 여기서 예외가 그대로 빠져나가 "403인데 감사 0건"이었다
        // (타사 검토 실측). "모든 시도를 기록한다"는 §6 계약이 깨지는 자리였다.
        try {
            approvalGate.check(requestId, actor, ir)
        } catch (e: ApprovalBlockedException) {
            audit.record(null, actor, ExecutionOutcome.BLOCKED, sql,
                errorCode = e.detail.code, errorDetail = e.detail.message)
            throw e
        }

        val report = LintReportDto.from(lintService.judge(inspected, approval.purposeCode))
        if (report.blocked) blockedByReport(null, actor, sql, "RULE_BLOCKED", report)

        val mapping = when (val resolved = demoTables.resolve(logicalTables)) {
            is DemoMapping.Resolved -> resolved.byLogical
            is DemoMapping.Incomplete -> blocked(
                "NO_DEMO_MAPPING", "실행 대상 매핑이 없는 테이블이 있습니다: ${resolved.unmapped.joinToString(", ")}")
            is DemoMapping.Invalid -> blocked(
                "INVALID_PHYSICAL_NAME", "실행 대상 테이블명이 식별자 규칙을 위반했습니다: ${resolved.badNames.joinToString(", ")}")
            DemoMapping.Empty -> blocked("NO_DEMO_MAPPING", "실행할 대상 테이블이 없습니다")
        }

        val plan = when (val planned = planner.plan(ir, approval.purposeCode, mapping)) {
            is PlanOutcome.Planned -> planned.plan
            is PlanOutcome.Refused -> blocked("REWRITE_${planned.refusal.name}", planned.message)
        }
        val rewritten = when (
            val outcome = rewriter.rewrite(inspected.statement!!, plan, ir, maskedColumnsOf())
        ) {
            is RewriteOutcome.Rewritten -> outcome
            is RewriteOutcome.Refused -> blocked("REWRITE_${outcome.refusal.name}", outcome.message)
        }

        // 미리보기도 기록한다 — 데이터는 나가지 않지만 **적용될 강제식**이 노출된다
        audit.record(
            null, actor, ExecutionOutcome.PREVIEW, sql,
            rewrittenSql = rewritten.sql, applied = rewritten.applied,
        )
        return PreviewedRewrite(rewritten.sql, rewritten.applied, report)
    }

    /** 룰·접수 위반으로 차단 — 차단 지점에서 기록한 뒤 throw한다(핸들러에 맡기면 경로가 흐려진다). */
    private fun blockedByReport(
        queryId: Long?,
        actor: String,
        sql: String,
        code: String,
        report: LintReportDto,
    ): Nothing {
        audit.record(
            queryId, actor, ExecutionOutcome.BLOCKED, sql,
            errorCode = code,
            errorDetail = report.violations.filter { it.severity.name == "BLOCK" }.joinToString("; ") { it.message },
        )
        throw BlockedException(report)
    }

    /** 검증이 계획을 맹신하지 않도록, 기대 마스킹을 카탈로그에서 재도출하는 함수 (§3.0.3). */
    private fun maskedColumnsOf(): (String) -> Set<String> = { table ->
        rewriteCatalog.maskExpressions(table).map { it.column.lowercase() }.toSet()
    }

    /** 실행 이력 — 본인 또는 STEWARD/ADMIN만(열람 스코프와 같은 기준). */
    fun history(queryId: Long, actor: String, privileged: Boolean): List<ExecutionEvent> {
        queries.visible(queryId, actor, privileged)
        return audit.historyOf(queryId)
    }
}
