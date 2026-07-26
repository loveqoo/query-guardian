package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.exec.AuditTarget

/**
 * 게이트 단계의 결과 — 통과했거나([Cleared]) 멈췄거나([Stopped]) 둘뿐이다.
 *
 * 선례는 집 안에 있다: `spring-jpa-kraft`의 `core/ResultExtensions.kt`가 `flatMap`/`zip`을 손으로 만들고,
 * `FormResolver.toEntity()`가 `validateForm(this).flatMap { createEntity() }` 한 줄로 읽힌다.
 * 같은 모양을 쓰되 타입만 우리 것이다 — `kotlin.Result`는 `Throwable`만 담는데 [GateStop]은
 * **예외가 아니어야** 하기 때문이다(spec 010 I7).
 *
 * 단계 **증거 타입**과 그 발급자는 여기 없다 — `GateSteps.kt`가 한 파일에 함께 들고 있어야
 * 발급 권한이 닫힌다(I2). 이 파일에는 어느 단계에도 속하지 않는 **운반 수단**만 둔다.
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
fun GateOutcome<Judged<*>>.judgedReport(): LintReportDto? = when (this) {
    is GateOutcome.Cleared -> value.report
    is GateOutcome.Stopped -> stop.report
}

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
