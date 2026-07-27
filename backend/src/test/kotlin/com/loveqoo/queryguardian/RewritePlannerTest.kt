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
 * spec 008 §3.5 M1-3 — 계획 수립. 핵심은 **거부해야 할 때 거부하는가**다:
 * 표현 불가한 MASK 위치, 강제식 결손, OUTER JOIN null 생성 쪽 FILTER.
 */
class RewritePlannerTest {

    /** users.email = MASK, marketing_consents.consent_yn = FILTER(marketing), users.id = INTEGRITY. */
    private class FakeCatalog(
        private val maskTemplate: String? = "mask_email({col})",
        private val withIntegrity: Boolean = false,
    ) : RewriteCatalog {
        override fun maskExpressions(tableName: String) =
            if (tableName.equals("users", true)) listOf(ForcedExpression("email", maskTemplate, "이메일 마스킹"))
            else emptyList()

        override fun filterExpressions(tableName: String, purposeCode: String?) =
            if (tableName.equals("marketing_consents", true) && purposeCode == "marketing")
                listOf(ForcedExpression("consent_yn", "{col} = 'Y'", "동의 필수"))
            else emptyList()

        override fun integrityExpressions(tableName: String) =
            if (withIntegrity && tableName.equals("users", true))
                listOf(ForcedExpression("id", "{col} IS NOT NULL", "무결성"))
            else emptyList()
    }

    private fun ir(sql: String): QueryIR =
        (Fixtures.parser.inspect(sql) as InspectResult.Parsed).ir

    private fun plan(
        sql: String,
        purpose: String? = "marketing",
        catalog: RewriteCatalog = FakeCatalog(),
        maxRows: Long = 1000,
    ): PlanOutcome = RewritePlanner(catalog, maxRows).plan(ir(sql), purpose, mapOf("users" to "demo_users"))

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

    @Test
    fun `purpose에 등록된 FILTER는 인스턴스로 한정해 주입한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan("SELECT mc.id FROM marketing_consents mc LIMIT 10"),
        )
        val injection = outcome.plan.injections.single()
        assertEquals("mc.consent_yn = 'Y'", injection.predicateSql)
    }

    @Test
    fun `purpose가 다르면 FILTER를 주입하지 않는다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan("SELECT mc.id FROM marketing_consents mc LIMIT 10", purpose = "analytics"),
        )
        assertTrue(outcome.plan.injections.isEmpty())
    }

    /**
     * §3.0.2: OUTER JOIN의 null 생성 쪽에 WHERE를 주입하면 LEFT JOIN이 사실상 INNER가 되어 **의미가 변한다**.
     * 조용히 결과를 바꾸는 것보다 거부가 안전하다.
     */
    @Test
    fun `OUTER JOIN null 생성 쪽 FILTER는 거부한다`() {
        val refused = assertIs<PlanOutcome.Refused>(
            plan("SELECT u.id FROM users u LEFT JOIN marketing_consents mc ON mc.user_id = u.id LIMIT 10"),
        )
        assertEquals(RewriteRefusal.OUTER_JOIN_FILTER, refused.refusal)
    }

    @Test
    fun `INNER JOIN이면 같은 조건을 주입한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan("SELECT u.id FROM users u JOIN marketing_consents mc ON mc.user_id = u.id LIMIT 10"),
        )
        assertEquals("mc.consent_yn = 'Y'", outcome.plan.injections.single().predicateSql)
    }

    @Test
    fun `RIGHT JOIN에서는 왼쪽이 null 생성 쪽이다`() {
        val refused = assertIs<PlanOutcome.Refused>(
            plan("SELECT u.id FROM marketing_consents mc RIGHT JOIN users u ON mc.user_id = u.id LIMIT 10"),
        )
        assertEquals(RewriteRefusal.OUTER_JOIN_FILTER, refused.refusal)
    }

    @Test
    fun `INTEGRITY도 같은 경로로 주입된다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan("SELECT id FROM users LIMIT 10", catalog = FakeCatalog(withIntegrity = true)),
        )
        assertEquals("users.id IS NOT NULL", outcome.plan.injections.single().predicateSql)
    }

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
        assertTrue(outcome.plan.injections.none { it.predicateSql.contains("demo_") })
    }

    /** 적대 검토 HIGH: `LIMIT 0`을 "미지정"으로 취급하면 0행 요청이 상한(1000)행으로 확대된다. */
    @Test
    fun `LIMIT 0도 사용자 의도로 존중한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT id FROM users LIMIT 0"))
        assertEquals(0, outcome.plan.limitCap!!.maxRows)
    }

    /**
     * 한때 "이미 최상위 조건이 있으면 주입 생략"을 넣었다가 철회했다 — `WHERE mc.consent_yn <> 'Y'`도
     * "제약됨"으로 읽혀 필수 조건이 아예 주입되지 않는 fail-open이었다(적대 검토 지적).
     * 지금은 조건이 이미 있어도 **거부**한다(OUTER JOIN 오차단은 알려진 한계, INNER JOIN으로 우회).
     */
    @Test
    fun `조건이 이미 있어도 OUTER JOIN null 생성 쪽은 거부한다`() {
        val refused = assertIs<PlanOutcome.Refused>(
            plan(
                "SELECT u.id FROM users u LEFT JOIN marketing_consents mc ON mc.user_id = u.id " +
                    "WHERE mc.consent_yn <> 'Y' LIMIT 10",
            ),
        )
        assertEquals(RewriteRefusal.OUTER_JOIN_FILTER, refused.refusal)
    }

    /** 조건이 없으면 여전히 거부한다 — 완화가 OUTER 방어를 없애지 않았음을 고정한다. */
    @Test
    fun `조건이 없는 OUTER JOIN은 여전히 거부한다`() {
        val refused = assertIs<PlanOutcome.Refused>(
            plan("SELECT u.id FROM users u LEFT JOIN marketing_consents mc ON mc.user_id = u.id LIMIT 10"),
        )
        assertEquals(RewriteRefusal.OUTER_JOIN_FILTER, refused.refusal)
    }

    /**
     * 적대 검토 HIGH(실측): `NOT EXISTS` 스코프에 "동의 필수"를 주입하니 결과가 정확히 **비동의자만** 남았다
     * (`{3,6,9,12}`). 거버넌스가 보호하려던 모집단을 골라내는 도구가 된다 — 주입하지 않는 것이 정답이다.
     */
    @Test
    fun `부정 문맥 스코프에는 주입하지 않는다`() {
        for (sql in listOf(
            "SELECT u.id FROM users u WHERE NOT EXISTS " +
                "(SELECT 1 FROM marketing_consents mc WHERE mc.user_id = u.id) LIMIT 10",
            "SELECT u.id FROM users u WHERE u.id NOT IN " +
                "(SELECT mc.user_id FROM marketing_consents mc) LIMIT 10",
        )) {
            val outcome = assertIs<PlanOutcome.Planned>(plan(sql), sql)
            assertTrue(outcome.plan.injections.isEmpty(), "부정 문맥에 주입하면 필터가 반전된다: $sql → ${outcome.plan.injections}")
        }
    }

    /**
     * 적대 검토 HIGH(실측): 파생 테이블로 한 겹 감싸면 인스턴스 키가 `d`로 바뀌어 null 생성 검사를 우회하고,
     * 필터가 파생 스코프 안에 들어가 **행을 제한하지 못했다**(재작성 결과 12행 vs 의도 8행).
     */
    @Test
    fun `null 생성 쪽 파생 래퍼 안에도 주입하지 않는다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan(
                "SELECT u.id, d.consent_yn FROM users u LEFT JOIN " +
                    "(SELECT user_id, consent_yn FROM marketing_consents) d ON d.user_id = u.id LIMIT 10",
            ),
        )
        assertTrue(outcome.plan.injections.isEmpty(), "래퍼로 감싼 우회 경로: ${outcome.plan.injections}")
    }

    /** 대조군: INNER JOIN으로 감싼 파생 스코프에는 정상 주입된다(완화가 방어를 없애지 않았음). */
    @Test
    fun `INNER 파생 래퍼 안에는 주입한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan(
                "SELECT u.id, d.consent_yn FROM users u JOIN " +
                    "(SELECT user_id, consent_yn FROM marketing_consents) d ON d.user_id = u.id LIMIT 10",
            ),
        )
        assertEquals(
            "marketing_consents.consent_yn = 'Y'",
            outcome.plan.injections.single().predicateSql,
        )
    }
}
