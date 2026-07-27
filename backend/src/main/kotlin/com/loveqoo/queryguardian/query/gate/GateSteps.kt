package com.loveqoo.queryguardian.query.gate

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.approval.ApprovalRequest
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.exec.auditCode
import com.loveqoo.queryguardian.ir.forcedExpressionForms
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.InspectResult
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.springframework.stereotype.Component

// ---- 구현체 — 이 파일 밖에서는 만들 수 없다 ----------------------------------

private data class ParsedEvidence(
    override val request: GateRequest,
    override val inspected: InspectResult.Parsed,
    override val logicalTables: Set<String>,
) : Parsed

private data class AuthorizedEvidence(private val parsed: Parsed) : Authorized, Parsed by parsed

private data class CoveredEvidence(
    private val authorized: Authorized,
    override val approval: ApprovalRequest,
) : ApprovalCovered, Authorized by authorized

private data class JudgedEvidence<out Prior : Authorized>(
    override val prior: Prior,
    override val report: LintReportDto,
) : Judged<Prior>

private data class StorableEvidence(
    override val judged: Judged<Authorized>,
    override val approval: ApprovalRequest,
) : Storable

private data class MappedEvidence(
    override val judged: Judged<ApprovalCovered>,
    override val mapping: Map<String, String>,
) : Mapped

private data class PlannedEvidence(
    override val mapped: Mapped,
    override val plan: RewritePlan,
) : Planned

private data class ReadyEvidence(
    override val planned: Planned,
    override val rewritten: RewriteOutcome.Rewritten,
) : Ready

private data class ExecutableEvidence(
    override val ready: Ready,
    override val cap: LimitCap,
) : Executable

// ---- 발급자 ----------------------------------------------------------------

/**
 * 게이트의 **단계 단위이자 유일한 증거 발급자** — 저장 게이트와 실행 게이트가 함께 쓴다.
 *
 * 같은 절차가 세 곳에 적혀 있었다(`execute`·`previewRewrite`·`QueryService.gate`). 앞의 둘은 사본이었고
 * 세 번째는 **이미 갈라져 있었다** — 저장 게이트만 파싱을 두 번 했다(`parse()` 한 번, `lint(sql)` 안에서
 * 또 한 번). "판정과 재작성이 같은 AST를 쓴다"(spec 008 결정 13)가 실행 게이트에서만 성립했던 것이다.
 *
 * **순서는 여기 없다.** 순서는 정책이고 두 게이트가 서로 다르다(위 [Judged] 참조). 그래서 이 클래스는
 * **단계만** 제공하고 조립은 각 게이트가 한다 — 다만 어떤 조립이 가능한지는 **타입이 정한다**.
 */
@Component
class GateSteps(
    private val parser: DialectParser,
    private val lintService: LintService,
    private val access: AccessControl,
    private val approvalGate: ApprovalGate,
    private val demoTables: DemoTableResolver,
    private val planner: RewritePlanner,
    private val rewriteCatalog: RewriteCatalog,
    private val rewriter: SqlRewriter,
) {

    /**
     * **파싱 1회** — IR·접수 위반·AST 핸들을 함께 얻어 판정과 재작성이 같은 AST를 쓴다(결정 13).
     *
     * 갈래가 둘뿐인 것은 [InspectResult]가 합 타입이 된 결과다. 예전에는 셋이었다 — 파싱 실패, 그리고
     * **"성공인데 핸들이 없다"** 는 성립하지 않는 조합을 fail-closed로 떨어뜨리는 분기. 그 분기는
     * 규율이었지 검사가 아니었고, 그 자리가 원래 `inspected.statement!!`였다.
     */
    fun parseOnce(request: GateRequest): GateOutcome<Parsed> {
        val inspected = parser.inspect(request.sql)
        if (inspected !is InspectResult.Parsed) {
            return stopped(
                GateStop.Violated(AuditCode.PARSE_FAILED, LintReportDto.from(lintService.judge(inspected, request.purposeCode))),
            )
        }
        return cleared(ParsedEvidence(request, inspected, approvalGate.physicalTables(inspected.ir)))
    }

    /** 데이터 권한은 **현재 기준**으로 다시 본다 — 저장 후 회수됐을 수 있다. 룰보다 앞(spec 007 §6.0). */
    fun checkAccess(parsed: Parsed): GateOutcome<Authorized> = try {
        access.checkTables(parsed.request.actor, parsed.logicalTables)
        cleared(AuthorizedEvidence(parsed))
    } catch (e: AccessBlockedException) {
        stopped(GateStop.Blocked(e.detail))
    }

    /**
     * **실행 게이트의 승인 검사** — 판정보다 **앞**이다. 남의 쿼리 id로 실행을 시도하는 것만으로
     * 그 쿼리의 판정 결과(어떤 컬럼이 PII·BLOCK인지)를 받아 가지 못하게 한다.
     */
    fun checkApproval(authorized: Authorized): GateOutcome<ApprovalCovered> =
        approvalOf(authorized.request, authorized.ir).then { cleared(CoveredEvidence(authorized, it)) }

    /**
     * **저장 게이트의 승인 검사** — 판정 **뒤**다(룰 422가 승인 403보다 앞선다, spec 005 H4).
     *
     * 같은 검사가 두 함수인 이유는 위치가 정책이기 때문이다. 번역점([approvalOf])은 하나를 공유한다.
     */
    fun requireApproval(judged: Judged<Authorized>): GateOutcome<Storable> =
        approvalOf(judged.request, judged.prior.ir).then { cleared(StorableEvidence(judged, it)) }

    /**
     * 접수 검사 + 룰 **재판정** — 저장 시점 스냅샷은 표시용이고 게이트 근거가 아니다(§5).
     *
     * 제네릭이라 **입력이 무엇이었는지가 결과에 남는다** — 그것이 아래 [resolveMapping]의 전제다.
     */
    fun <Prior : Authorized> judgeRules(prior: Prior): GateOutcome<Judged<Prior>> {
        val report = LintReportDto.from(lintService.judge(prior.inspected, prior.request.purposeCode))
        if (report.blocked) return stopped(GateStop.Violated(AuditCode.RULE_BLOCKED, report))
        return cleared(JudgedEvidence(prior, report))
    }

    /**
     * 데모 매핑 총체성 — §2.7-3의 **최후 방어선**이 여기서 실제로 작동한다.
     * 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 실재하는 거버넌스 테이블을 직격한다.
     *
     * `Judged<ApprovalCovered>`를 요구하는 것이 I8의 실행이다 — 승인 검사를 건너뛴 판정으로는 **여기까지 올 수 없다**.
     */
    fun resolveMapping(judged: Judged<ApprovalCovered>): GateOutcome<Mapped> =
        when (val resolved = demoTables.resolve(judged.logicalTables)) {
            is DemoMapping.Resolved -> cleared(MappedEvidence(judged, resolved.byLogical))
            is DemoMapping.Failed -> stopped(GateStop.Unprocessable(resolved.auditCode, resolved.message))
        }

    fun planRewrite(mapped: Mapped): GateOutcome<Planned> =
        when (val planned = planner.plan(mapped.ir, mapped.mapping)) {
            is PlanOutcome.Planned -> cleared(PlannedEvidence(mapped, planned.plan))
            is PlanOutcome.Refused -> stopped(GateStop.Unprocessable(planned.auditCode, planned.message))
        }

    /** 재작성 + 자체 검증(§3.0.3). 검증 기대치는 계획이 아니라 **카탈로그**에서 재도출한다. */
    fun rewriteAndVerify(planned: Planned): GateOutcome<Ready> =
        when (val outcome = rewriter.rewrite(
            planned.statement, planned.plan, planned.ir, rewriteCatalog::maskedColumns,
            // 검증기는 계획기와 **같은 허용 형태**를 봐야 한다 — 다르면 계획기는 건너뛴 것을
            // 검증기가 "표현 불가"로 신고한다 (spec 012 P0)
            { table, instanceKey, column ->
                rewriteCatalog.maskExpressions(table)
                    .filter { it.column.equals(column, ignoreCase = true) }
                    .flatMap { forcedExpressionForms(it.template ?: return@flatMap emptyList(), instanceKey, it.column) }
                    .toSet()
            },
        )) {
            is RewriteOutcome.Rewritten -> cleared(ReadyEvidence(planned, outcome))
            is RewriteOutcome.Refused -> stopped(GateStop.Unprocessable(outcome.refusal.auditCode, outcome.message))
        }

    /**
     * 계획에 상한이 없으면 재작성이 LIMIT을 넣지 않았다는 뜻이다 — **상한 없는 실행은 허용하지 않는다**
     * (fail-closed). 미리보기에는 이 단계가 없으므로 공유 줄기가 아니라 실행 조립에만 선다.
     */
    fun requireRowCap(ready: Ready): GateOutcome<Executable> {
        val cap = ready.plan.limitCap
            ?: return stopped(
                GateStop.Unprocessable(AuditCode.REWRITE_NO_LIMIT, "행 상한을 적용하지 못했습니다 — 실행할 수 없습니다"),
            )
        return cleared(ExecutableEvidence(ready, cap))
    }

    // ---- 경계 --------------------------------------------------------------

    /**
     * `ApprovalGate`가 던지는 차단을 값으로 옮기는 **유일한 번역점**.
     *
     * 승인은 **현재 상태로 재검사**한다(상태·요청자·테이블 커버). 예전에는 "승인은 APPROVED 이후 불변"과
     * "수정 시 게이트가 재실행된다"는 두 가정에 기대고 있었는데, 적대 검토가 두 번째 가정이 얼마나 얇은지
     * 보여줬다(소유권 탈취로 request_id가 바뀔 수 있었다). §5는 예외 없이 재판정을 요구한다.
     */
    private fun approvalOf(request: GateRequest, ir: QueryIR): GateOutcome<ApprovalRequest> = try {
        cleared(approvalGate.check(request.requestId, request.actor, ir))
    } catch (e: ApprovalBlockedException) {
        stopped(GateStop.Blocked(e.detail))
    }
}
