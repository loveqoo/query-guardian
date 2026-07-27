package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.ForcedExpression
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.parser.InspectResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * spec 008 §3.5 M1-3 — 계획 수립.
 *
 * **남은 계획은 둘이다** — 행 상한(LIMIT)과 논리명→물리명 치환. spec 012가 마스킹 치환을(P2b),
 * spec 013 S2가 술어 주입을 걷어냈다. 둘 다 "서버가 사용자 SQL의 의미를 고치지 않는다"(spec 012 I1)의
 * 직접 결과이고, 남은 둘은 **양과 이름**이라 의미를 건드리지 않는다.
 */
class RewritePlannerTest {

    /**
     * `users.email` = MASK. **FILTER·INTEGRITY 강제식은 재작성이 더 이상 읽지 않는다**(S2) —
     * 그 어휘는 판정으로 갔다(`TableCatalog.requiredPredicates`, `IntegrityConstraintTest`가 잰다).
     */
    private class FakeCatalog(
        private val maskTemplate: String? = "mask_email({col})",
    ) : RewriteCatalog {
        override fun maskExpressions(tableName: String) =
            if (tableName.equals("users", true)) listOf(ForcedExpression("email", maskTemplate, "이메일 마스킹"))
            else emptyList()
    }

    private fun ir(sql: String): QueryIR =
        (Fixtures.parser.inspect(sql) as InspectResult.Parsed).ir

    private fun plan(
        sql: String,
        catalog: RewriteCatalog = FakeCatalog(),
        maxRows: Long = 1000,
    ): PlanOutcome = RewritePlanner(catalog, maxRows).plan(ir(sql), mapOf("users" to "demo_users"))

    // ---- MASK ----
    //
    // spec 012 P2b: **마스킹 계획을 세우지 않는다.** 서버가 사용자의 SQL을 고쳐 가려 주던 것이 전제였는데
    // 그 전제가 틀렸다(spec 008 §0 — AI가 디자인에서 유추한 것을 사용자 결정으로 적었다).
    //
    // 여기 있던 테스트 다섯을 **지웠다**(치환 계획·별칭 인스턴스 지목·비투영 거부·star 거부·파생 스코프 배치).
    // 기능이 없어졌으니 그 테스트는 없는 동작을 재는 것이 된다. 다만 그것들이 지키던 **성질 자체는
    // 없어지지 않았고 판정으로 옮겨갔다** — 비투영 위치·star·파생 스코프의 마스킹 사용은
    // `must-be-masked`가 차단하고, 그 커버리지는 `ShapeCoverageTest`의 E·M 축이 잰다.
    //
    // 남긴 것: "MASK 컬럼을 조회하지 않으면 계획이 없다"는 계획이 **비어 있음**을 재므로 여전히 유효하다.

    @Test
    fun `MASK 컬럼을 조회하지 않으면 계획이 없다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT id FROM users LIMIT 10"))
        assertTrue(outcome.plan.maskProjections.isEmpty())
    }

    @Test
    fun `마스킹 대상을 조회해도 계획하지 않는다`() {
        // 사용자가 직접 가려서 쓴 형태 — 서버가 또 감싸면 이중 마스킹이다
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT mask_email(email) FROM users LIMIT 10"))
        assertTrue(outcome.plan.maskProjections.isEmpty(), "${outcome.plan.maskProjections}")
    }

    // ---- FILTER / INTEGRITY ----
    //
    // **spec 013 S2: 술어 주입을 지웠다.** 여기 있던 테스트 여섯을 지웠다 — 인스턴스 한정 주입,
    // purpose 불일치, OUTER JOIN 거부(정·역방향), INNER 주입, INTEGRITY 동일 경로.
    //
    // 지운 테스트들이 지키던 것은 **주입의 안전성**이었다(괄호·극성·null 생성 쪽 회피). 주입이 없으면
    // 그 위험 자체가 없다 — 지금 그 조건을 쓰는 사람은 사용자이고, 사용자가 쓴 조건은 사용자가 의도한
    // 위치에 있다. 반대로 **조건이 요구된다는 사실**은 없어지지 않았고 판정이 들고 있다:
    // `require-predicate`가 FILTER와 INTEGRITY를 모두 요구하며(`IntegrityConstraintTest`),
    // 못 쓰면 막고 무엇을 써야 하는지 제안한다.
    //
    // 회수한 것: `OUTER_JOIN_FILTER` 거부가 사라졌다. LEFT JOIN 쿼리가 "주입하면 조인 의미가
    // INNER로 바뀐다"는 이유만으로 실행 단계에서 거절되던 오차단이었다.

    // ---- LIMIT ----

    @Test
    fun `상한은 사용자 LIMIT과 설정 상한의 최솟값이다`() {
        fun cap(sql: String) = assertIs<PlanOutcome.Planned>(plan(sql)).plan.limitCap!!.maxRows
        assertEquals(10, cap("SELECT id FROM users LIMIT 10"))
        assertEquals(1000, cap("SELECT id FROM users LIMIT 5000"))
        assertEquals(1000, cap("SELECT id FROM users"))
    }

    @Test
    fun `물리명은 계획의 tableRenames에만 담긴다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT email FROM users LIMIT 10"))
        val rename = outcome.plan.tableRenames.single()
        assertEquals("users", rename.logicalName)
        assertEquals("demo_users", rename.physicalName)
        // 계획의 다른 항목은 전부 **논리명·인스턴스 키**로만 말한다 — 물리명이 새면 카탈로그 조회가 0건이 된다
        assertTrue(outcome.plan.maskProjections.none { it.instanceKey.contains("demo_") })
    }

    /** 적대 검토 HIGH: `LIMIT 0`을 "미지정"으로 취급하면 0행 요청이 상한(1000)행으로 확대된다. */
    @Test
    fun `LIMIT 0도 사용자 의도로 존중한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT id FROM users LIMIT 0"))
        assertEquals(0, outcome.plan.limitCap!!.maxRows)
    }
}
