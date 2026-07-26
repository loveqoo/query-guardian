package com.loveqoo.queryguardian.query.gate

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalRequest
import com.loveqoo.queryguardian.exec.ExecutionOrder
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.parser.InspectResult
import com.loveqoo.queryguardian.parser.ParsedStatement

/*
 * ============================================================================
 *  단계 증거 (spec 010 I2·I8) — **발급 권한이 폐쇄된** 타입들
 * ============================================================================
 *
 * P1은 단계에 **이름**을 줬고(읽히는 줄기), P2는 그 이름을 **위조할 수 없게** 만든다.
 * retrospect 013이 못 박은 문장이 이 단계의 전부다: *"'타입으로 막는다'의 본체는 필드가 아니라
 * 발급 권한 폐쇄다."*
 *
 * ## 폐쇄가 실제로 어디서 오는가 (실측으로 정정한 서술)
 *
 * I2가 요구하는 것은 셋이다: 밖에서 ⑴ 생성할 수 없고 ⑵ 구현할 수 없으며 ⑶ 전 단계의 전이 함수만
 * 다음 타입을 발급한다. Kotlin에 friend 가시성이 없어 **범위가 서로 다른 두 장치**를 겹쳐 쓴다:
 *
 * | 요구 | 장치 | **실제 범위** |
 * |---|---|---|
 * | ⑵ 구현 불가 | `sealed` | **패키지** 단위다(파일이 아니다 — 컴파일러로 확인). 그래서 게이트가 `query.gate` **전용 패키지**에 산다 |
 * | ⑴ 생성 불가 | `private` 구현체 | **파일** 단위. 그래서 구현체와 발급자가 `GateSteps.kt` 한 파일에 있다 |
 * | ⑶ 발급자 단일 | 위 둘의 결과 | 이 패키지에 발급 함수를 가진 파일이 하나뿐이다 |
 *
 * **처음에 "타입과 발급 함수가 한 파일에 있는 것이 폐쇄의 조건"이라고 적었는데 절반만 참이었다** —
 * 파일 제약은 `private`에만 걸린다. 계약을 이 파일로 가른 뒤에도 폐쇄가 유지되는 이유가 그것이고,
 * 대신 **패키지가 경계**가 되었으므로 `query.gate`에 파일을 늘릴 때는 그 사실을 알고 늘려야 한다.
 *
 * **`internal`로는 안 된다**: 모듈 전체에 열려 `QueryService`가 그대로 조립할 수 있다.
 *
 * ## 폐쇄의 한계 — 정직하게
 *
 * 이것은 **Kotlin 소스 수준** 폐쇄다. `private` 구현체는 JVM 바이트코드에서 package-private 클래스 +
 * public 생성자로 컴파일되므로, `setAccessible(true)`를 쓰는 리플렉션은 막지 못한다(모듈 시스템 미사용).
 * 리플렉션을 쓸 수 있는 코드는 이미 무엇이든 할 수 있으므로 이 경계의 목표가 아니다 — 목표는
 * **평범한 코드가 실수로 게이트를 건너뛰는 것**이고, 그건 컴파일 오류가 된다.
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
sealed interface ApprovalCovered : Authorized {
    val approval: ApprovalRequest
}

/**
 * 접수·룰 재판정을 통과했다. **무엇을 통과하고 판정됐는지**를 타입 파라미터가 기억한다.
 *
 * 제네릭이 필요한 이유는 두 게이트의 **순서가 다르기 때문**이다:
 *
 * ```
 * 실행:  Parsed → Authorized → ApprovalCovered → Judged<ApprovalCovered>   → Mapped → … → Executable
 * 저장:  Parsed → Authorized →           Judged<Authorized> → Storable
 * ```
 *
 * 저장은 룰 422가 승인 403보다 앞서고(spec 005 H4), 실행은 신원 검사가 판정보다 앞선다(남의 쿼리
 * 판정 결과를 흘리지 않기 위해). 순서가 정책이라 사슬 하나로 합칠 수 없다.
 *
 * 그래서 [resolveMapping]이 `Judged<ApprovalCovered>`를 요구한다 — **승인 검사 없는 매핑·계획·실행이
 * 컴파일되지 않는다**(I8 "권한 재확인 없는 판정"). 저장 게이트가 얻는 `Judged<Authorized>`는
 * 그 사슬로 넘어갈 수 없다.
 */
sealed interface Judged<out Prior : Authorized> {
    val prior: Prior
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
    val judged: Judged<ApprovalCovered>
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

