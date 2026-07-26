package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.exec.AuditTarget
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.parser.InspectResult
import com.loveqoo.queryguardian.parser.ParsedStatement

/**
 * 게이트 단계의 결과 — 통과했거나([Cleared]) 멈췄거나([Stopped]) 둘뿐이다.
 *
 * 선례는 집 안에 있다: `spring-jpa-kraft`의 `core/ResultExtensions.kt`가 `flatMap`/`zip`을 손으로 만들고,
 * `FormResolver.toEntity()`가 `validateForm(this).flatMap { createEntity() }` 한 줄로 읽힌다.
 * 같은 모양을 쓰되 타입만 우리 것이다 — `kotlin.Result`는 `Throwable`만 담는데 [GateStop]은
 * **예외가 아니어야** 하기 때문이다(spec 010 I7).
 */
sealed interface GateOutcome<out T> {
    data class Cleared<out T>(val value: T) : GateOutcome<T>
    data class Stopped(val stop: GateStop) : GateOutcome<Nothing>
}

/**
 * 단계를 잇는 **유일한 조합자**. `when`은 여기 한 번 있고 줄기에는 없다 —
 * 예전에는 호출부가 sealed 타입을 손으로 여덟 번 분해했다.
 */
inline fun <T, R> GateOutcome<T>.then(next: (T) -> GateOutcome<R>): GateOutcome<R> = when (this) {
    is GateOutcome.Cleared -> next(value)
    is GateOutcome.Stopped -> this
}

/** 단계가 값을 그대로 통과시킬 때. */
fun <T> cleared(value: T): GateOutcome<T> = GateOutcome.Cleared(value)

/** 단계가 멈출 때. */
fun stopped(stop: GateStop): GateOutcome<Nothing> = GateOutcome.Stopped(stop)

/**
 * 통과했든 멈췄든 **판정 보고서**를 꺼낸다 — 룰 hit 통계는 차단된 쿼리도 세야 한다.
 * 정지가 보고서를 가졌는지는 [GateStop.report]가 답한다(호출부가 변종을 캐스팅하지 않는다).
 */
fun GateOutcome<Judged>.judgedReport(): LintReportDto? = when (this) {
    is GateOutcome.Cleared -> value.report
    is GateOutcome.Stopped -> stop.report
}

// ---- 게이트 상태 -----------------------------------------------------------

/**
 * 게이트 입력 — **두 진입점의 유일한 차이**다.
 *
 * 실측(정규화 후 diff): `execute`와 `previewRewrite`의 파싱 이후 절차는 자유변수 **셋**만 달랐다 —
 * `queryId` · `requestId` · `purposeCode`. 그 셋이 여기 모였으므로 절차는 한 벌이면 된다.
 */
data class GateRequest(
    /** 미리보기는 저장된 쿼리가 없어 null이다 — 감사의 `query_id`가 된다. */
    override val queryId: Long?,
    val requestId: Long?,
    /** 클라이언트 입력이 아니라 **서버가 승인 요청에서 주입**한다(spec 005 C1). */
    val purposeCode: String?,
    val sql: String,
    override val actor: String,
) : AuditTarget {
    override val sqlByteLength: Int get() = sql.toByteArray().size
}

/**
 * 단계가 진행되며 쌓이는 상태. 각 단계는 앞 단계를 **품고** 다음 사실을 더한다 — 마지막 단계가
 * 인자 열다섯 개를 나르지 않게 하는 값이다(spec 010 §4.1).
 *
 * 아직 **발급 권한이 폐쇄되어 있지 않다**(생성자가 공개다). 순서를 타입으로 강제하는 것은 P2의 몫이고,
 * P1은 단계에 **이름**을 주는 데까지다. 지금 이 타입들은 "읽히는 줄기"를 위한 것이지 위조 방지 장치가 아니다.
 */
data class Parsed(
    val request: GateRequest,
    /** 파싱 성공 갈래이므로 IR과 핸들이 **여기 안에서** non-null이다 — `inspected.statement!!`가 사라진 자리. */
    val inspected: InspectResult.Parsed,
    val logicalTables: Set<String>,
) {
    val ir: QueryIR get() = inspected.ir
    val statement: ParsedStatement get() = inspected.statement
}

/** 권한·승인 재검사와 접수·룰 재판정을 통과했다. */
data class Judged(val parsed: Parsed, val report: LintReportDto) {
    val request: GateRequest get() = parsed.request
    val logicalTables: Set<String> get() = parsed.logicalTables
}

/** 데모 매핑 총체성을 통과했다 — 실행 대상 물리 테이블이 전부 정해졌다. */
data class Mapped(val judged: Judged, val mapping: Map<String, String>) {
    val request: GateRequest get() = judged.request
    val ir: QueryIR get() = judged.parsed.ir
}

/** 재작성 계획이 섰다. */
data class Planned(val mapped: Mapped, val plan: RewritePlan) {
    val ir: QueryIR get() = mapped.ir
    val statement: ParsedStatement get() = mapped.judged.parsed.statement
}

/** 재작성과 자체 검증까지 끝났다 — **미리 보여줄 수** 있다. 실행에는 한 가지가 더 필요하다([Executable]). */
data class Ready(val planned: Planned, val rewritten: RewriteOutcome.Rewritten) {
    val plan: RewritePlan get() = planned.plan
    val report: LintReportDto get() = planned.mapped.judged.report
}

/**
 * 실행해도 되는 증거 — [Ready]에 **확인된 행 상한**이 더해졌다.
 *
 * 예전에는 상한 검사가 `runQuery` 안에 있었다. 그러면 "이 게이트가 무엇을 검사하는가"의 답이 다시
 * 줄기 **더하기** 실행 함수 앞머리가 되고, 그것이 정확히 옛 KDoc 목록이 이 항목을 빠뜨렸던 이유다.
 * 상한 없는 실행을 허용하지 않는 것은 **정책**이므로(fail-closed) 인프라 경계가 아니라 줄기에 선다.
 */
data class Executable(val ready: Ready, val cap: LimitCap) {
    val rewritten: RewriteOutcome.Rewritten get() = ready.rewritten
}
