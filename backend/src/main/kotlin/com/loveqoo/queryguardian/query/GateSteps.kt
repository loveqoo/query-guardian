package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.approval.ApprovalRequest
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver
import com.loveqoo.queryguardian.exec.ExecutionOrder
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.exec.auditCode
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.InspectResult
import com.loveqoo.queryguardian.parser.ParsedStatement
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.springframework.stereotype.Component

/*
 * ============================================================================
 *  단계 증거 (spec 010 I2·I8) — **발급 권한이 폐쇄된** 타입들
 * ============================================================================
 *
 * 타입과 발급 함수가 한 파일에 있는 것은 편의가 아니라 **폐쇄의 조건**이다.
 *
 * I2가 요구하는 것은 셋이다: 밖에서 ⑴ 생성할 수 없고 ⑵ 구현할 수 없으며 ⑶ 전 단계의 전이 함수만
 * 다음 타입을 발급한다. Kotlin에는 friend 가시성이 없으므로 이렇게 얻는다:
 *
 * - ⑵ = `sealed` — 밖에서 새 구현체를 만들 수 없다
 * - ⑴ = 구현체가 이 파일의 `private` 클래스 — 밖에서 생성자를 부를 수 없고, `copy()`도 함께 닫힌다
 * - ⑶ = 유일한 발급자 [GateSteps]가 **같은 파일**에 있다
 *
 * `internal`로는 안 된다: 모듈 전체(=`query` 패키지 전체, 즉 `QueryService`)에 열려 폐쇄가 처음부터
 * 샌다. 별도 Gradle 모듈로 가르는 안은 게이트 하나를 위해 치르기엔 비용이 크다.
 *
 * P1은 단계에 **이름**을 줬고(읽히는 줄기), P2는 그 이름을 **위조할 수 없게** 만든다.
 * retrospect 013이 못 박은 문장이 이 단계의 전부다: *"'타입으로 막는다'의 본체는 필드가 아니라
 * 발급 권한 폐쇄다."*
 *
 * 각 단계가 앞 단계를 **품는** 것은 마지막 단계가 인자 열다섯 개를 나르지 않게 하는 값이다(§4.1).
 */

/** SQL이 파싱됐다 — IR과 그 파싱의 핸들이 [InspectResult.Parsed] 안에서 함께 non-null이다. */
sealed interface Parsed {
    val request: GateRequest
    val inspected: InspectResult.Parsed
    val logicalTables: Set<String>

    val ir: QueryIR get() = inspected.ir
    val statement: ParsedStatement get() = inspected.statement
}

/** 데이터 권한을 **현재 기준으로** 다시 확인했다. */
sealed interface Authorized : Parsed

/**
 * 승인 커버까지 확인했다 — 요청 존재·APPROVED·요청자 일치·테이블 커버.
 *
 * **[Authorized]의 하위 타입인 이유**: 승인 커버 검사는 데이터 권한 검사를 대체하지 않고 **뒤에 온다**.
 * 하위로 두면 [judgeRules]가 두 게이트 모두에서 쓰이면서도 "권한 확인 없는 판정"은 여전히 불가능하다.
 */
sealed interface Covered : Authorized {
    val approval: ApprovalRequest
}

/**
 * 접수·룰 재판정을 통과했다. **무엇을 통과하고 판정됐는지**를 타입 파라미터가 기억한다.
 *
 * 제네릭이 필요한 이유는 두 게이트의 **순서가 다르기 때문**이다:
 *
 * ```
 * 실행:  Parsed → Authorized → Covered → Judged<Covered>   → Mapped → … → Executable
 * 저장:  Parsed → Authorized →           Judged<Authorized> → Storable
 * ```
 *
 * 저장은 룰 422가 승인 403보다 앞서고(spec 005 H4), 실행은 신원 검사가 판정보다 앞선다(남의 쿼리
 * 판정 결과를 흘리지 않기 위해). 순서가 정책이라 사슬 하나로 합칠 수 없다.
 *
 * 그래서 [resolveMapping]이 `Judged<Covered>`를 요구한다 — **승인 검사 없는 매핑·계획·실행이
 * 컴파일되지 않는다**(I8 "권한 재확인 없는 판정"). 저장 게이트가 얻는 `Judged<Authorized>`는
 * 그 사슬로 넘어갈 수 없다.
 */
sealed interface Judged<out P : Authorized> {
    val prior: P
    val report: LintReportDto

    val request: GateRequest get() = prior.request
    val logicalTables: Set<String> get() = prior.logicalTables
}

/** **저장 게이트의 종결** — 판정과 승인을 모두 확보했다. 실행 자격은 아니다(재작성을 거치지 않았다). */
sealed interface Storable {
    val judged: Judged<Authorized>
    val approval: ApprovalRequest

    val report: LintReportDto get() = judged.report
}

/** 데모 매핑 총체성을 통과했다 — 실행 대상 물리 테이블이 전부 정해졌다. */
sealed interface Mapped {
    val judged: Judged<Covered>
    val mapping: Map<String, String>

    val request: GateRequest get() = judged.request
    val ir: QueryIR get() = judged.prior.ir
}

/** 재작성 계획이 섰다. */
sealed interface Planned {
    val mapped: Mapped
    val plan: RewritePlan

    val ir: QueryIR get() = mapped.ir
    val statement: ParsedStatement get() = mapped.judged.prior.statement
}

/** 재작성과 자체 검증까지 끝났다 — **미리 보여줄 수** 있다. 실행에는 하나가 더 필요하다([Executable]). */
sealed interface Ready {
    val planned: Planned
    val rewritten: RewriteOutcome.Rewritten

    val plan: RewritePlan get() = planned.plan
    val report: LintReportDto get() = planned.mapped.judged.report
}

/**
 * 실행해도 되는 증거 — [Ready]에 **확인된 행 상한**이 더해졌다.
 *
 * 예전에는 상한 검사가 `runQuery` 안에 있었다. 그러면 "이 게이트가 무엇을 검사하는가"의 답이 다시
 * 줄기 **더하기** 실행 함수 앞머리가 되고, 그것이 정확히 옛 KDoc 목록이 이 항목을 빠뜨렸던 이유다.
 * 상한 없는 실행을 허용하지 않는 것은 **정책**이므로(fail-closed) 인프라 경계가 아니라 줄기에 선다.
 *
 * **이 값이 곧 실행 허가증이다**(I9). 참조가 `execute`의 스택 밖으로 나가지 않으므로 재사용·재결합·
 * 타인 전달이 성립하지 않는다 — 그 사실을 `GateEvidenceClosureTest`가 감시자로 고정한다.
 */
sealed interface Executable : ExecutionOrder {
    val ready: Ready
    val cap: LimitCap

    val rewritten: RewriteOutcome.Rewritten get() = ready.rewritten

    /** 실행기가 받는 것은 이 셋뿐이다 — SQL과 상한이 **같은 증거에서** 나오므로 짝이 어긋날 수 없다. */
    override val sql: String get() = rewritten.sql
    override val maxRows: Long get() = cap.maxRows
    override val governanceCap: Long get() = cap.governanceCap
}

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
) : Covered, Authorized by authorized

private data class JudgedEvidence<out P : Authorized>(
    override val prior: P,
    override val report: LintReportDto,
) : Judged<P>

private data class StorableEvidence(
    override val judged: Judged<Authorized>,
    override val approval: ApprovalRequest,
) : Storable

private data class MappedEvidence(
    override val judged: Judged<Covered>,
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
                GateStop.Violated(AuditCode.PARSE_FAILED, LintReportDto.from(lintService.judge(inspected))),
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
    fun checkApproval(authorized: Authorized): GateOutcome<Covered> =
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
    fun <P : Authorized> judgeRules(prior: P): GateOutcome<Judged<P>> {
        val report = LintReportDto.from(lintService.judge(prior.inspected, prior.request.purposeCode))
        if (report.blocked) return stopped(GateStop.Violated(AuditCode.RULE_BLOCKED, report))
        return cleared(JudgedEvidence(prior, report))
    }

    /**
     * 데모 매핑 총체성 — §2.7-3의 **최후 방어선**이 여기서 실제로 작동한다.
     * 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 실재하는 거버넌스 테이블을 직격한다.
     *
     * `Judged<Covered>`를 요구하는 것이 I8의 실행이다 — 승인 검사를 건너뛴 판정으로는 **여기까지 올 수 없다**.
     */
    fun resolveMapping(judged: Judged<Covered>): GateOutcome<Mapped> =
        when (val resolved = demoTables.resolve(judged.logicalTables)) {
            is DemoMapping.Resolved -> cleared(MappedEvidence(judged, resolved.byLogical))
            is DemoMapping.Failed -> stopped(GateStop.Unprocessable(resolved.auditCode, resolved.message))
        }

    fun planRewrite(mapped: Mapped): GateOutcome<Planned> =
        when (val planned = planner.plan(mapped.ir, mapped.request.purposeCode, mapped.mapping)) {
            is PlanOutcome.Planned -> cleared(PlannedEvidence(mapped, planned.plan))
            is PlanOutcome.Refused -> stopped(GateStop.Unprocessable(planned.auditCode, planned.message))
        }

    /** 재작성 + 자체 검증(§3.0.3). 검증 기대치는 계획이 아니라 **카탈로그**에서 재도출한다. */
    fun rewriteAndVerify(planned: Planned): GateOutcome<Ready> =
        when (val outcome = rewriter.rewrite(planned.statement, planned.plan, planned.ir, rewriteCatalog::maskedColumns)) {
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
