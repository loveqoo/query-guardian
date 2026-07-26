package com.loveqoo.queryguardian.parser

import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 **A4** — `parse=Success ∧ statement=null` 조합이 **타입으로 표현 불가**함을 검사한다.
 *
 * ## 왜 리플렉션인가
 *
 * 이 성질은 "어떤 입력에도 그런 값이 안 나온다"가 아니라 **"그런 값을 만들 수 없다"** 이다.
 * 입력을 아무리 많이 넣어도 후자는 증명되지 않는다 — 입력 테스트는 *현재 구현*이 그 값을 안 만든다는
 * 것까지만 말하고, 내일 누가 `InspectResult`에 `statement: ParsedStatement? = null`을 하나 더 달면
 * 전부 통과한 채로 성질만 사라진다.
 *
 * `!!` 개수를 세는 것도 대리 변수다(A4 굿하트 항목) — `requireNotNull`·`as`로 숫자만 줄어든다.
 * 그래서 **타입의 모양 자체**를 단정한다.
 *
 * ## 되돌려 실패 (A8)
 *
 * `InspectResult`를 예전의 곱 타입(`parse` + `intakeViolations` + `statement: ParsedStatement? = null`)으로
 * 되돌리면 아래 셋이 동시에 깨진다: 갈래 수(2 → 0), 핸들 nullability, IR·핸들 동거.
 */
class InspectResultShapeTest {

    private val branches: List<KClass<out InspectResult>> = InspectResult::class.sealedSubclasses

    /** 밖에서 새 갈래를 만들 수 없어야 나머지 단정이 의미를 갖는다 — `sealed`가 그 조건이다. */
    @Test
    fun `InspectResult는 봉인된 두 갈래다`() {
        assertTrue(InspectResult::class.isSealed, "봉인이 풀리면 밖에서 세 번째 갈래를 만들 수 있다")
        assertEquals(
            setOf("Parsed", "Unparsed"),
            branches.map { it.simpleName }.toSet(),
            "갈래가 바뀌었다면 아래 단정들이 무엇을 지키는지 다시 적어야 한다",
        )
    }

    /**
     * **핵심 단정**: 어떤 갈래도 핸들을 nullable로 갖지 않는다.
     *
     * nullable 핸들을 가진 갈래가 하나라도 있으면 그것이 곧 "성공인데 핸들이 없다"의 자리다 —
     * 예전 곱 타입이 정확히 그 모양이었고, 그래서 호출부에 `inspected.statement!!`가 생겼다.
     */
    @Test
    fun `핸들을 nullable로 든 갈래가 없다`() {
        for (branch in branches) {
            val nullableHandles = branch.memberProperties
                .filter { it.returnType.jvmErasure == ParsedStatement::class && it.returnType.isMarkedNullable }
                .map { it.name }
            assertEquals(
                emptyList(), nullableHandles,
                "${branch.simpleName}가 핸들을 nullable로 든다 — '성공인데 핸들이 없다'가 표현 가능해졌다",
            )
        }
    }

    /**
     * **IR과 핸들은 같은 갈래에 산다.** 둘이 갈라지면 "판정에 쓴 AST를 재작성이 고친다"(spec 008 결정 13)가
     * 다시 규율이 된다 — 한쪽만 있는 값을 만들 수 있는 순간 두 대상이 갈라질 수 있다.
     */
    @Test
    fun `IR과 핸들은 한 갈래 안에 함께 있다`() {
        fun holds(branch: KClass<out InspectResult>, type: KClass<*>) =
            branch.memberProperties.any { it.returnType.jvmErasure == type }

        val withIr = branches.filter { holds(it, com.loveqoo.queryguardian.ir.QueryIR::class) }
        val withHandle = branches.filter { holds(it, ParsedStatement::class) }

        assertEquals(1, withIr.size, "IR을 든 갈래는 정확히 하나여야 한다: ${withIr.map { it.simpleName }}")
        assertEquals(withIr, withHandle, "IR과 핸들이 다른 갈래에 있다 — 둘은 같은 파싱의 두 얼굴이다")
    }

    /** 실패 사실과 성공 사실이 한 값 안에 공존하면 "어느 쪽이 진짜인가"가 다시 규율이 된다. */
    @Test
    fun `실패 사실과 IR이 공존하는 갈래가 없다`() {
        for (branch in branches) {
            val types = branch.memberProperties.map { it.returnType.jvmErasure }
            assertTrue(
                !(ParseResult.Failure::class in types && com.loveqoo.queryguardian.ir.QueryIR::class in types),
                "${branch.simpleName}가 실패와 IR을 함께 든다",
            )
        }
    }
}
