package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.LintReportDto
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
 * **감사 없이** 경계로 내보낸다 — 저장 게이트용.
 *
 * 저장은 실행이 아니라 `execution_event`를 남기지 않는다(감사의 대상은 실행 시도다). 실행 게이트는
 * 대신 기록 후 내보내는 자기 경계를 쓴다.
 */
fun <T> GateOutcome<T>.orThrow(): T = when (this) {
    is GateOutcome.Cleared -> value
    is GateOutcome.Stopped -> stop.raise()
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
    val queryId: Long?,
    val requestId: Long?,
    /** 클라이언트 입력이 아니라 **서버가 승인 요청에서 주입**한다(spec 005 C1). */
    val purposeCode: String?,
    val sql: String,
    val actor: String,
)

/**
 * 단계가 진행되며 쌓이는 상태. 각 단계는 앞 단계를 **품고** 다음 사실을 더한다 — 마지막 단계가
 * 인자 열다섯 개를 나르지 않게 하는 값이다(spec 010 §4.1).
 *
 * 아직 **발급 권한이 폐쇄되어 있지 않다**(생성자가 공개다). 순서를 타입으로 강제하는 것은 P2의 몫이고,
 * P1은 단계에 **이름**을 주는 데까지다. 지금 이 타입들은 "읽히는 줄기"를 위한 것이지 위조 방지 장치가 아니다.
 */
data class Parsed(
    val request: GateRequest,
    val inspected: InspectResult,
    val ir: QueryIR,
    /** 파싱 성공과 **한 타입 안에서** 묶인다 — `inspected.statement!!`가 여기서 사라진다. */
    val statement: ParsedStatement,
    val logicalTables: Set<String>,
)

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
    val request: GateRequest get() = mapped.request
    val ir: QueryIR get() = mapped.ir
    val statement: ParsedStatement get() = mapped.judged.parsed.statement
}

/** 재작성과 자체 검증까지 끝났다 — 실행하거나 미리 보여줄 수 있다. */
data class Ready(val planned: Planned, val rewritten: RewriteOutcome.Rewritten) {
    val request: GateRequest get() = planned.request
    val plan: RewritePlan get() = planned.plan
    val report: LintReportDto get() = planned.mapped.judged.report
}
