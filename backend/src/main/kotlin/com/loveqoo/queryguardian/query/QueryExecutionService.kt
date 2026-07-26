package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver
import com.loveqoo.queryguardian.exec.ExecutionAudit
import com.loveqoo.queryguardian.exec.ExecutionEvent
import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.exec.ExecutionResult
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.QueryExecutor
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.AppliedRewrite
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.springframework.stereotype.Service

/**
 * 재작성 거부 사유 → 감사 코드.
 *
 * 예전에는 `"REWRITE_" + refusal.name`이었다. 문자열 조립은 **[RewriteRefusal]에 값을 추가한 사람이
 * 감사 어휘를 확장했다는 사실을 모른 채** 지나가게 한다 — 새 코드가 조용히 생기고 아무 테스트도 그것을
 * 모른다. `when`을 망라적으로 두면 컴파일러가 그 자리를 막는다.
 *
 * (`ExecutionFailure.Kind`처럼 필드로 짝지을 수 없는 이유: [RewriteRefusal]은 `ir` 패키지에 있고
 * ArchUnit `irIsTheSharedVocabulary`가 `ir → audit` 의존을 금지한다. 번역이 게이트 쪽에 오는 것은
 * 그 경계 결정이 치르는 값이다.)
 */
private fun auditCodeOf(refusal: RewriteRefusal): AuditCode = when (refusal) {
    RewriteRefusal.MASK_NOT_EXPRESSIBLE -> AuditCode.REWRITE_MASK_NOT_EXPRESSIBLE
    RewriteRefusal.OUTER_JOIN_FILTER -> AuditCode.REWRITE_OUTER_JOIN_FILTER
    RewriteRefusal.EXPRESSION_NOT_USABLE -> AuditCode.REWRITE_EXPRESSION_NOT_USABLE
    RewriteRefusal.SCOPE_NOT_FOUND -> AuditCode.REWRITE_SCOPE_NOT_FOUND
    RewriteRefusal.VERIFY_FAILED -> AuditCode.REWRITE_VERIFY_FAILED
}

/** 열람 권한이 없어 본문을 기록하지 않을 때 감사에 남기는 자리표시자. */
private const val REDACTED_SQL = "(열람 권한 없음 — 본문을 기록하지 않는다)"

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
 * **게이트의 순서는 [runGate]의 본문이다** — 주석이 아니라. 예전에는 순서가 이 KDoc 한 줄에만 있었고
 * 본문은 그것을 열세 단계로 풀어 쓰면서 매 단계를 다른 문법으로 표현했다. 그 결과 목록과 본문이
 * 어긋났는데(승인 재판정과 행 상한이 목록에 없었다) 아무도 몰랐다. **순서가 정책이면 정의는 하나여야 한다.**
 *
 * 두 진입점의 차이는 [GateRequest] 하나뿐이다. 실측으로 자유변수는 셋이었다 —
 * `queryId` · `requestId` · `purposeCode`.
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

    // ---- 진입점 ----------------------------------------------------------

    fun execute(queryId: Long, actor: String, privileged: Boolean): ExecutedQuery {
        val query = visibleOrRecord(queryId, actor, privileged)
        val request = GateRequest(queryId, query.requestId, query.purposeCode, query.sqlText, actor)

        val executed = requireOwnExecution(query, request)
            .then { runGate(it) }
            .then { runQuery(it) }
            .orRaise(request)

        return exportOnlyIfRecorded(executed) {
            audit.record(
                queryId, actor, ExecutionOutcome.SUCCESS, request.sql,
                rewrittenSql = executed.rewrittenSql, applied = executed.applied, result = executed.result,
            )
        }
    }

    /**
     * **재작성 미리보기** (spec 008 §7) — 저장 전에 "무엇이 자동 적용되는지"를 보여준다. 실행은 하지 않는다.
     *
     * 실행 게이트와 **같은 [runGate]를 지난다** — 검사 목록을 다시 적지 않는다. 그러지 않으면 미리보기가
     * 게이트를 우회해 카탈로그를 캐는 창구가 된다(적용될 강제식 원문이 응답에 담기므로 어떤 컬럼이 MASK인지,
     * 마스크 식이 무엇인지를 알아낼 수 있다).
     *
     * 검토 상태는 보지 않는다 — 미리보기는 **저장 이전** 단계다. 대신 승인 요청의 요청자 본인만 쓸 수 있다
     * (요청자 일치는 [ApprovalGate.check]가 겸한다).
     */
    fun previewRewrite(sql: String, requestId: Long?, actor: String): PreviewedRewrite {
        val request = GateRequest(queryId = null, requestId = requestId, purposeCode = null, sql = sql, actor = actor)

        val ready = injectPurpose(request)
            .then { runGate(it) }
            .orRaise(request)

        // 미리보기도 **반출**이다 — 데이터 행은 없지만 적용될 강제식 원문이 나간다(카탈로그 오라클).
        // 데이터가 없다고 반출이 아닌 것이 아니므로 SUCCESS와 같은 등급을 받는다(spec 010 I4).
        return exportOnlyIfRecorded(PreviewedRewrite(ready.rewritten.sql, ready.rewritten.applied, ready.report)) {
            audit.record(
                null, actor, ExecutionOutcome.PREVIEW, sql,
                rewrittenSql = ready.rewritten.sql, applied = ready.rewritten.applied,
            )
        }
    }

    // ---- 줄기 ------------------------------------------------------------

    /**
     * **게이트 순서(spec 008 §5).** 두 진입점이 공유하는 유일한 정의다.
     *
     * 순서를 바꾸는 것은 정책 변경이다 — 예컨대 룰 재판정을 요청자 일치보다 앞에 두면, STEWARD가 남의
     * 쿼리 id로 실행을 시도하는 것만으로 그 쿼리의 판정 결과(어떤 컬럼이 PII·BLOCK인지)를 받아 간다.
     */
    private fun runGate(request: GateRequest): GateOutcome<Ready> =
        parseOnce(request)
            .then(::checkAccess)
            .then(::checkApproval)
            .then(::judgeRules)
            .then(::resolveMapping)
            .then(::planRewrite)
            .then(::rewriteAndVerify)

    // ---- 단계 ------------------------------------------------------------

    /** **파싱 1회** — IR·접수 위반·AST 핸들을 함께 얻어 판정과 재작성이 같은 AST를 쓴다(결정 13). */
    private fun parseOnce(request: GateRequest): GateOutcome<Parsed> {
        val inspected = parser.inspect(request.sql)
        val ir = when (val parsed = inspected.parse) {
            is ParseResult.Success -> parsed.ir
            is ParseResult.Failure -> return stopped(
                GateStop.Violated(AuditCode.PARSE_FAILED, LintReportDto.from(lintService.judge(inspected))),
            )
        }
        // 파싱 성공인데 핸들이 없는 조합은 성립하지 않는다. 성립한다면 상류 버그이므로 fail-closed로 떨어뜨린다
        // — 예전에는 이 자리가 `inspected.statement!!`였다(50줄 위의 분기를 근거로 삼는 `!!`).
        val statement = inspected.statement ?: return stopped(
            GateStop.Refused(AuditCode.PARSE_FAILED, "재작성 핸들을 얻지 못했습니다"),
        )
        return cleared(Parsed(request, inspected, ir, statement, approvalGate.physicalTables(ir)))
    }

    /** 데이터 권한은 **현재 기준**으로 다시 본다 — 저장 후 회수됐을 수 있다. 룰보다 앞(spec 007 §6.0). */
    private fun checkAccess(parsed: Parsed): GateOutcome<Parsed> = try {
        access.checkTables(parsed.request.actor, parsed.logicalTables)
        cleared(parsed)
    } catch (e: AccessBlockedException) {
        stopped(GateStop.AccessDenied(e))
    }

    /**
     * 승인도 **현재 상태로 재검사**한다(상태·요청자·테이블 커버). 예전에는 "승인은 APPROVED 이후 불변"과
     * "수정 시 게이트가 재실행된다"는 두 가정에 기대고 있었는데, 적대 검토가 두 번째 가정이 얼마나 얇은지
     * 보여줬다(소유권 탈취로 request_id가 바뀔 수 있었다). §5는 예외 없이 재판정을 요구한다.
     */
    private fun checkApproval(parsed: Parsed): GateOutcome<Parsed> = try {
        approvalGate.check(parsed.request.requestId, parsed.request.actor, parsed.ir)
        cleared(parsed)
    } catch (e: ApprovalBlockedException) {
        stopped(GateStop.ApprovalDenied(e))
    }

    /** 접수 검사 + 룰 **재판정** — 저장 시점 스냅샷은 표시용이고 게이트 근거가 아니다(§5). */
    private fun judgeRules(parsed: Parsed): GateOutcome<Judged> {
        val report = LintReportDto.from(lintService.judge(parsed.inspected, parsed.request.purposeCode))
        if (report.blocked) return stopped(GateStop.Violated(AuditCode.RULE_BLOCKED, report))
        return cleared(Judged(parsed, report))
    }

    /**
     * 데모 매핑 총체성 — §2.7-3의 **최후 방어선**이 여기서 실제로 작동한다.
     * 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 실재하는 거버넌스 테이블을 직격한다.
     */
    private fun resolveMapping(judged: Judged): GateOutcome<Mapped> =
        when (val resolved = demoTables.resolve(judged.logicalTables)) {
            is DemoMapping.Resolved -> cleared(Mapped(judged, resolved.byLogical))
            is DemoMapping.Incomplete -> stopped(GateStop.Refused(
                AuditCode.NO_DEMO_MAPPING,
                "실행 대상 매핑이 없는 테이블이 있습니다: ${resolved.unmapped.joinToString(", ")}",
            ))
            is DemoMapping.Invalid -> stopped(GateStop.Refused(
                AuditCode.INVALID_PHYSICAL_NAME,
                "실행 대상 테이블명이 식별자 규칙을 위반했습니다: ${resolved.badNames.joinToString(", ")}",
            ))
            DemoMapping.Empty -> stopped(GateStop.Refused(AuditCode.NO_DEMO_MAPPING, "실행할 대상 테이블이 없습니다"))
        }

    private fun planRewrite(mapped: Mapped): GateOutcome<Planned> =
        when (val planned = planner.plan(mapped.ir, mapped.request.purposeCode, mapped.mapping)) {
            is PlanOutcome.Planned -> cleared(Planned(mapped, planned.plan))
            is PlanOutcome.Refused -> stopped(GateStop.Refused(auditCodeOf(planned.refusal), planned.message))
        }

    /** 재작성 + 자체 검증(§3.0.3). 검증 기대치는 계획이 아니라 **카탈로그**에서 재도출한다. */
    private fun rewriteAndVerify(planned: Planned): GateOutcome<Ready> =
        when (val outcome = rewriter.rewrite(planned.statement, planned.plan, planned.ir, maskedColumnsOf())) {
            is RewriteOutcome.Rewritten -> cleared(Ready(planned, outcome))
            is RewriteOutcome.Refused -> stopped(GateStop.Refused(auditCodeOf(outcome.refusal), outcome.message))
        }

    // ---- 진입점 전용 단계 -------------------------------------------------

    /**
     * **대행 실행 불허**(결정 14): 열람은 STEWARD/ADMIN에게 열지만 실행은 요청자 본인만.
     * 감사 로그에서 "그 PII를 누가 봤는가"가 한 사람으로 남아야 한다.
     */
    private fun requireOwnExecution(query: SavedQuery, request: GateRequest): GateOutcome<GateRequest> {
        val approval = approvalGate.findRequest(query.requestId)
            ?: return stopped(GateStop.Refused(AuditCode.NO_REQUEST, "근거 승인 요청을 찾을 수 없어 실행할 수 없습니다"))
        if (approval.requester != request.actor) {
            return stopped(GateStop.Refused(AuditCode.REQUESTER_MISMATCH, "본인이 요청·작성한 쿼리만 실행할 수 있습니다"))
        }
        if (query.reviewStatus != ReviewStatus.APPROVED.name) {
            return stopped(GateStop.Refused(
                AuditCode.NOT_REVIEWED, "검토 승인된 쿼리만 실행할 수 있습니다 (현재 ${query.reviewStatus})"))
        }
        return cleared(request)
    }

    /**
     * purposeCode는 클라이언트 입력이 아니라 **승인 요청에서 서버가 주입**한다 (spec 005 C1).
     * 클라이언트가 purpose를 고를 수 있으면 purpose별 FILTER를 스스로 면제할 수 있다.
     */
    private fun injectPurpose(request: GateRequest): GateOutcome<GateRequest> {
        val approval = request.requestId?.let { approvalGate.findRequest(it) }
            ?: return stopped(GateStop.Refused(AuditCode.NO_REQUEST, "승인된 요청을 선택해야 재작성을 미리 볼 수 있습니다"))
        return cleared(request.copy(purposeCode = approval.purposeCode))
    }

    /**
     * 실행 — **인프라 경계**다. 이 게이트에서 예외를 잡는 유일한 자리이고, 잡은 즉시 값으로 번역한다.
     * 사용자에게는 분류 코드만, 원문(SQLState·vendor code)은 감사에만 (§6).
     */
    private fun runQuery(ready: Ready): GateOutcome<ExecutedQuery> {
        // 계획에 상한이 없으면 재작성이 LIMIT을 넣지 않았다는 뜻이다 — 상한 없는 실행은 허용하지 않는다(fail-closed)
        val cap = ready.plan.limitCap
            ?: return stopped(GateStop.Refused(AuditCode.REWRITE_NO_LIMIT, "행 상한을 적용하지 못했습니다 — 실행할 수 없습니다"))
        return try {
            val result = executor.execute(ready.rewritten.sql, cap.maxRows, cap.governanceCap)
            cleared(ExecutedQuery(result, ready.rewritten.sql, ready.rewritten.applied))
        } catch (e: ExecutionFailure) {
            stopped(GateStop.Failed(e, ready.rewritten))
        }
    }

    // ---- 경계 ------------------------------------------------------------

    /**
     * 게이트가 멈췄으면 **감사에 남기고 예외로 번역한다** — 이 파일에서 그 일이 벌어지는 유일한 곳이다.
     *
     * 예외 핸들러에 맡기지 않는 이유: 어떤 경로가 어디서 막혔는지가 흐려진다(spec 008 §6).
     * 등급별 분기는 [AuditGrade]가 정하고, 어떤 예외가 되는지는 [GateStop.raise]가 다형성으로 정한다.
     */
    private fun <T> GateOutcome<T>.orRaise(request: GateRequest): T = when (this) {
        is GateOutcome.Cleared -> value
        is GateOutcome.Stopped -> {
            recordStop(request, stop)
            stop.raise()
        }
    }

    /**
     * **반출이 없는 종결의 기록은 best-effort다** (spec 010 I5).
     *
     * 감사 저장이 실패해도 **원래 사유가 이긴다** — 감사 예외로 바꿔치면 "무엇이 막혔는지"를 잃고
     * 403이 500이 된다. 대신 유실은 조용히 지나가지 않는다: 실패 자체가 경보 대상이다.
     *
     * 이 `runCatching`이 안전한 이유는 게이트가 **트랜잭션을 열지 않기 때문**이다(spec 010 I6).
     * 감사의 `REQUIRES_NEW`가 롤백돼도 되돌릴 바깥 쓰기가 없다.
     */
    /**
     * **반출이 있는 종결은 기록이 반출의 선행 조건이다** (spec 010 I4).
     *
     * "누가 그 PII를 봤는가"를 남길 수 없으면 보여주지 않는 것이 이 제품의 통제 방식이다.
     * 그래서 여기서는 [recordStop]과 반대로 **기록 실패가 응답을 대신한다** — 잡지 않는다.
     * 순서를 뒤집을 수 없게 이름을 붙였다: 값은 이미 만들어져 있고, 기록이 성공해야만 밖으로 나간다.
     */
    private inline fun <T> exportOnlyIfRecorded(value: T, record: () -> Unit): T {
        record()
        return value
    }

    private fun recordStop(request: GateRequest, stop: GateStop) {
        runCatching {
            audit.record(
                request.queryId, request.actor, stop.outcome, request.sql,
                rewrittenSql = stop.rewritten?.sql, applied = stop.rewritten?.applied,
                errorCode = stop.code, errorDetail = stop.detail,
            )
        }.onFailure { audit.alertRecordFailure(it, stop.outcome, stop.code, request.actor) }
    }

    /**
     * 열람 권한. 차단도 **기록한다** — 남의 쿼리 id로 실행을 시도한 것 자체가 감사 대상이다
     * (열거 시도를 사후에 볼 수 있어야 한다). 본문은 남기지 않는다: 읽을 권한이 없는 SQL이다.
     */
    private fun visibleOrRecord(queryId: Long, actor: String, privileged: Boolean): SavedQuery = try {
        queries.visible(queryId, actor, privileged)
    } catch (e: ForbiddenException) {
        recordStop(
            GateRequest(queryId, null, null, REDACTED_SQL, actor),
            GateStop.Refused(AuditCode.FORBIDDEN_READ, e.message ?: "열람 권한이 없습니다"),
        )
        throw e
    }

    // ---- 부속 ------------------------------------------------------------

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
