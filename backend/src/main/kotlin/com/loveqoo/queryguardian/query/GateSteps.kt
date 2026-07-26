package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.springframework.stereotype.Component

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
internal fun auditCodeOf(refusal: RewriteRefusal): AuditCode = when (refusal) {
    RewriteRefusal.MASK_NOT_EXPRESSIBLE -> AuditCode.REWRITE_MASK_NOT_EXPRESSIBLE
    RewriteRefusal.OUTER_JOIN_FILTER -> AuditCode.REWRITE_OUTER_JOIN_FILTER
    RewriteRefusal.EXPRESSION_NOT_USABLE -> AuditCode.REWRITE_EXPRESSION_NOT_USABLE
    RewriteRefusal.SCOPE_NOT_FOUND -> AuditCode.REWRITE_SCOPE_NOT_FOUND
    RewriteRefusal.VERIFY_FAILED -> AuditCode.REWRITE_VERIFY_FAILED
}

/**
 * 게이트의 **단계 단위** — 저장 게이트와 실행 게이트가 함께 쓴다.
 *
 * 같은 절차가 세 곳에 적혀 있었다(`execute`·`previewRewrite`·`QueryService.gate`). 앞의 둘은 사본이었고
 * 세 번째는 **이미 갈라져 있었다** — 저장 게이트만 파싱을 두 번 했다(`parse()` 한 번, `lint(sql)` 안에서
 * 또 한 번). "판정과 재작성이 같은 AST를 쓴다"(spec 008 결정 13)가 실행 게이트에서만 성립했던 것이다.
 *
 * **순서는 여기 없다.** 순서는 정책이고 두 게이트가 서로 다르다 — 저장은 룰 422가 승인 403보다
 * 앞서고(spec 005 H4), 실행은 신원 검사가 판정보다 앞선다(남의 쿼리 판정 결과를 흘리지 않기 위해).
 * 그래서 이 클래스는 **단계만** 제공하고 조립은 각 게이트가 한다.
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

    // ---- 단계 ------------------------------------------------------------

    /** **파싱 1회** — IR·접수 위반·AST 핸들을 함께 얻어 판정과 재작성이 같은 AST를 쓴다(결정 13). */
    fun parseOnce(request: GateRequest): GateOutcome<Parsed> {
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
            GateStop.Unprocessable(AuditCode.PARSE_FAILED, "재작성 핸들을 얻지 못했습니다"),
        )
        return cleared(Parsed(request, inspected, ir, statement, approvalGate.physicalTables(ir)))
    }

    /** 데이터 권한은 **현재 기준**으로 다시 본다 — 저장 후 회수됐을 수 있다. 룰보다 앞(spec 007 §6.0). */
    fun checkAccess(parsed: Parsed): GateOutcome<Parsed> = try {
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
    fun checkApproval(parsed: Parsed): GateOutcome<Parsed> = try {
        approvalGate.check(parsed.request.requestId, parsed.request.actor, parsed.ir)
        cleared(parsed)
    } catch (e: ApprovalBlockedException) {
        stopped(GateStop.ApprovalDenied(e))
    }

    /** 접수 검사 + 룰 **재판정** — 저장 시점 스냅샷은 표시용이고 게이트 근거가 아니다(§5). */
    fun judgeRules(parsed: Parsed): GateOutcome<Judged> {
        val report = LintReportDto.from(lintService.judge(parsed.inspected, parsed.request.purposeCode))
        if (report.blocked) return stopped(GateStop.Violated(AuditCode.RULE_BLOCKED, report))
        return cleared(Judged(parsed, report))
    }

    /**
     * 데모 매핑 총체성 — §2.7-3의 **최후 방어선**이 여기서 실제로 작동한다.
     * 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 실재하는 거버넌스 테이블을 직격한다.
     */
    fun resolveMapping(judged: Judged): GateOutcome<Mapped> =
        when (val resolved = demoTables.resolve(judged.logicalTables)) {
            is DemoMapping.Resolved -> cleared(Mapped(judged, resolved.byLogical))
            is DemoMapping.Incomplete -> stopped(GateStop.Unprocessable(
                AuditCode.NO_DEMO_MAPPING,
                "실행 대상 매핑이 없는 테이블이 있습니다: ${resolved.unmapped.joinToString(", ")}",
            ))
            is DemoMapping.Invalid -> stopped(GateStop.Unprocessable(
                AuditCode.INVALID_PHYSICAL_NAME,
                "실행 대상 테이블명이 식별자 규칙을 위반했습니다: ${resolved.badNames.joinToString(", ")}",
            ))
            DemoMapping.Empty -> stopped(GateStop.Unprocessable(AuditCode.NO_DEMO_MAPPING, "실행할 대상 테이블이 없습니다"))
        }

    fun planRewrite(mapped: Mapped): GateOutcome<Planned> =
        when (val planned = planner.plan(mapped.ir, mapped.request.purposeCode, mapped.mapping)) {
            is PlanOutcome.Planned -> cleared(Planned(mapped, planned.plan))
            is PlanOutcome.Refused -> stopped(GateStop.Unprocessable(auditCodeOf(planned.refusal), planned.message))
        }

    /** 재작성 + 자체 검증(§3.0.3). 검증 기대치는 계획이 아니라 **카탈로그**에서 재도출한다. */
    fun rewriteAndVerify(planned: Planned): GateOutcome<Ready> =
        when (val outcome = rewriter.rewrite(planned.statement, planned.plan, planned.ir, rewriteCatalog::maskedColumns)) {
            is RewriteOutcome.Rewritten -> cleared(Ready(planned, outcome))
            is RewriteOutcome.Refused -> stopped(GateStop.Unprocessable(auditCodeOf(outcome.refusal), outcome.message))
        }



    /**
     * 차단됐든 통과했든 **판정 보고서**를 꺼낸다.
     *
     * 저장 게이트가 룰 hit 통계를 기록할 때 쓴다 — 차단된 쿼리도 통계에 들어가야 한다.
     * "무엇이 자주 걸리는가"가 통계의 목적이므로 걸린 것을 빼면 목적이 뒤집힌다.
     */
    fun reportOf(outcome: GateOutcome<Judged>): LintReportDto? = when (outcome) {
        is GateOutcome.Cleared -> outcome.value.report
        is GateOutcome.Stopped -> (outcome.stop as? GateStop.Violated)?.report
    }
}
