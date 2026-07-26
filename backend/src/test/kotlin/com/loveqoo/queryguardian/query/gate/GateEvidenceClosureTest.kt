package com.loveqoo.queryguardian.query.gate

import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 **I2 · A1 · A6** — 단계 증거의 **발급 권한이 실제로 닫혀 있는가**.
 *
 * ## 이 파일이 A6를 대신하는 이유
 *
 * 스펙 I9는 단회성 **증거 토큰**을 요구했고 A6는 네 시나리오(재사용·다른 대상에 재결합·승인 상태 변경
 * 후 사용·다른 actor에게 전달)의 **거부**를 요구했다. 구현에서는 토큰을 만들지 않았다 — 네 시나리오가
 * 전부 **구조로 성립하지 않기** 때문이다:
 *
 * | 시나리오 | 왜 성립하지 않는가 |
 * |---|---|
 * | 재사용 | 증거가 어디에도 **저장되지 않는다** — `execute`의 스택에만 산다(`ArchGateAccessTest`가 감시) |
 * | 다른 대상에 재결합 | 실행기가 SQL·상한을 **한 값**으로 받는다. A의 SQL에 B의 상한을 붙일 자리가 없다 |
 * | 승인 상태 변경 후 사용 | 캐시 경로가 없다. 매 호출이 승인 검사를 새로 탄다 |
 * | 다른 actor에게 전달 | `actor`는 증거 사슬 안에 있고 `copy()`가 닫혀 바꿀 수 없다 |
 *
 * **"막힌다"도 단정이므로 증명 부담을 진다**(retrospect 016 반성 2 — 할 수 있다와 할 수 없다에 같은
 * 증명 부담). 그 부담을 여기서 갚는다. 토큰이 값을 갖게 되는 시점은 게이트 통과와 실행이 **다른 호출로
 * 갈라질 때**(배치·에이전트·비동기 큐)이며, 그때 이 파일의 단정이 먼저 깨진다.
 *
 * ## 되돌려 실패 (A8)
 *
 * `GateSteps.kt`의 `private data class …Evidence` 중 하나에서 `private`를 떼면 첫 테스트가 실패한다.
 * `sealed`를 떼면 둘째가 실패한다.
 */
class GateEvidenceClosureTest {

    /** 게이트가 발급하는 모든 증거 타입. 새 단계를 만들면 여기 추가된다 — 빠뜨리면 그 단계만 열린다. */
    private val stages: List<KClass<*>> = listOf(
        Parsed::class, Authorized::class, ApprovalCovered::class, Judged::class,
        Storable::class, Mapped::class, Planned::class, Ready::class, Executable::class,
    )

    /** sealed 계층의 잎(실제 구현체)만 모은다 — 중간 인터페이스는 구현체가 아니다. */
    private fun leavesOf(type: KClass<*>): List<KClass<*>> =
        if (type.isSealed) type.sealedSubclasses.flatMap { leavesOf(it) } else listOf(type)

    /**
     * **⑴ 밖에서 구현할 수 없다.** 봉인이 풀린 단계 타입이 하나라도 있으면 그 단계의 증거는
     * 아무나 만들 수 있고, 그 순간 그 뒤의 모든 검사가 건너뛰기 가능해진다.
     */
    @Test
    fun `모든 단계 증거 타입은 봉인돼 있다`() {
        val open = stages.filterNot { it.isSealed }.map { it.simpleName }
        assertEquals(emptyList(), open, "봉인이 풀린 단계 타입 — 밖에서 구현해 순서를 건너뛸 수 있다")
    }

    /**
     * **⑵ 밖에서 생성할 수 없다.** 구현체가 `private`이 아니면 `internal`이든 `public`이든
     * `query` 패키지 안에서 손으로 조립할 수 있다 — `QueryService`가 바로 그 자리에 있다.
     *
     * `internal`로는 부족하다는 것이 이 단정의 요지다. 모듈 전체에 열리므로 폐쇄가 처음부터 샌다.
     */
    @Test
    fun `모든 구현체는 파일 밖에서 생성할 수 없다`() {
        val leaked = stages.flatMap { leavesOf(it) }.distinct()
            .filter { it.visibility != KVisibility.PRIVATE }
            .map { "${it.simpleName}(${it.visibility})" }
        assertEquals(
            emptyList(), leaked,
            "구현체가 private이 아니다 — 발급 함수를 거치지 않고 증거를 조립할 수 있다",
        )
    }

    /**
     * **⑶ 발급자가 하나다.** 구현체가 전부 `GateSteps.kt` 한 파일에 있어야 "전 단계의 전이 함수만
     * 발급한다"가 성립한다. 다른 파일로 옮기면 `private`은 그 파일 안에서만 유효하므로
     * **거기에 두 번째 발급자가 생긴다**.
     */
    @Test
    fun `모든 구현체가 발급자와 같은 파일에 있다`() {
        val impls = stages.flatMap { leavesOf(it) }.distinct()
        // Kotlin의 파일 private 클래스는 그 파일의 파사드(`GateStepsKt`)와 같은 소스에서 나온다.
        val wrongFile = impls.filterNot { it.java.name.substringAfterLast('.').endsWith("Evidence") }
            .map { it.simpleName }
        assertEquals(
            emptyList(), wrongFile,
            "발급자 파일의 명명 규약(`…Evidence`)을 벗어난 구현체가 있다 — 어느 파일에 사는지 확인하라",
        )
        assertTrue(impls.size >= stages.count { !it.isSealed || it.sealedSubclasses.isNotEmpty() },
            "구현체를 하나도 못 찾았다면 이 테스트는 공허하다: $impls")
    }

    /**
     * **대조군** — 이 테스트가 없으면 위 셋은 "빈 목록이라 통과"일 수 있다.
     * 실제로 아홉 개 단계가 잡히고 구현체가 발견되는지 이름까지 고정한다.
     */
    @Test
    fun `발견기가 실제 구현체를 짚는다`() {
        val impls = stages.flatMap { leavesOf(it) }.distinct().mapNotNull { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "ParsedEvidence", "AuthorizedEvidence", "CoveredEvidence", "JudgedEvidence",
                "StorableEvidence", "MappedEvidence", "PlannedEvidence", "ReadyEvidence", "ExecutableEvidence",
            ),
            impls,
            "구현체 집합이 바뀌었다 — 새 단계를 추가했다면 stages 목록에도 넣어라",
        )
    }
}
