package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.BlockedException
import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
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
        // 열람 권한 — 없으면 404/403 (감사 대상 아님: 그 쿼리의 존재를 모르는 상태다)
        val query = queries.visible(queryId, actor, privileged)
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

        val cap = plan.limitCap?.maxRows ?: 0
        val result = try {
            executor.execute(rewritten.sql, cap)
        } catch (e: ExecutionFailure) {
            // 사용자에게는 분류 코드만, 원문(SQLState·vendor code)은 감사에만 (§6)
            audit.record(
                queryId, actor, ExecutionOutcome.ERROR, sql,
                rewrittenSql = rewritten.sql, applied = rewritten.applied,
                errorCode = e.kind.name, errorDetail = e.detail,
            )
            throw e
        }

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

        // 승인 요청 검사(요청자 본인·승인 완료·테이블 커버) — 저장 게이트와 같은 계약을 쓴다
        approvalGate.check(requestId, actor, ir)

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
