package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.ForcedExpression
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.RewriteCatalog
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.parser.ParseResult
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
        (Fixtures.parser.inspect(sql).parse as ParseResult.Success).ir

    private fun plan(
        sql: String,
        purpose: String? = "marketing",
        catalog: RewriteCatalog = FakeCatalog(),
        maxRows: Long = 1000,
    ): PlanOutcome = RewritePlanner(catalog, maxRows).plan(ir(sql), purpose, mapOf("users" to "demo_users"))

    // ---- MASK ----

    @Test
    fun `투영된 MASK 컬럼은 치환 계획이 된다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT email FROM users LIMIT 10"))
        val mask = outcome.plan.maskProjections.single()
        assertEquals("email", mask.column)
        assertEquals("users", mask.instanceKey)
        assertEquals("mask_email({col})", mask.expressionTemplate)
    }

    /** 동명 CTE가 있으면 전역 치환은 CTE 참조까지 물리명으로 바꿔 쿼리를 깨뜨린다 — 물리 인스턴스만 치환한다. */
    @Test
    fun `동명 CTE 참조는 물리명 치환 대상이 아니다`() {
        val outcome = assertIs<PlanOutcome.Planned>(
            plan("WITH users AS (SELECT id FROM users) SELECT id FROM users LIMIT 10"),
        )
        // CTE 본문의 물리 users 1건만 치환 대상 (루트의 users는 CTE 참조)
        assertEquals(1, outcome.plan.tableRenames.size)
    }

    @Test
    fun `alias로 참조된 인스턴스도 인스턴스 키로 지목한다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT u.email FROM users u LIMIT 10"))
        assertEquals("u", outcome.plan.maskProjections.single().instanceKey)
    }

    @Test
    fun `MASK 컬럼을 조회하지 않으면 계획이 없다`() {
        val outcome = assertIs<PlanOutcome.Planned>(plan("SELECT id FROM users LIMIT 10"))
        assertTrue(outcome.plan.maskProjections.isEmpty())
    }

    /**
     * §3.0.1: 투영이 아닌 위치(함수 인자·CASE·WHERE·GROUP BY)는 치환으로 표현할 수 없다.
     * 적대 검토가 실측한 `CONCAT(email,'')` 한 겹 우회가 여기서 거부된다.
     */
    @Test
    fun `투영이 아닌 위치의 MASK 컬럼은 거부한다`() {
        for (sql in listOf(
            "SELECT CONCAT(email, '') AS e FROM users LIMIT 10",
            "SELECT LOWER(email) AS e FROM users LIMIT 10",
            "SELECT id FROM users WHERE email = 'a@b.com' LIMIT 10",
            "SELECT id FROM users GROUP BY email LIMIT 10",
            "SELECT id FROM users ORDER BY email LIMIT 10",
            "SELECT CASE WHEN email IS NULL THEN 'x' ELSE 'y' END AS e FROM users LIMIT 10",
            "SELECT email FROM users WHERE email LIKE '%@naver.com' LIMIT 10",
        )) {
            val outcome = plan(sql)
            val refused = assertIs<PlanOutcome.Refused>(outcome, "거부되어야 함: $sql")
            assertEquals(RewriteRefusal.MASK_NOT_EXPRESSIBLE, refused.refusal, sql)
        }
    }

    @Test
    fun `star 투영은 무엇이 나갈지 알 수 없으므로 거부한다`() {
        val refused = assertIs<PlanOutcome.Refused>(plan("SELECT * FROM users LIMIT 10"))
        assertEquals(RewriteRefusal.MASK_NOT_EXPRESSIBLE, refused.refusal)
    }

    @Test
    fun `강제식이 없거나 col 자리표시자가 없으면 거부한다`() {
        val refused = assertIs<PlanOutcome.Refused>(
            plan("SELECT email FROM users LIMIT 10", catalog = FakeCatalog(maskTemplate = null)),
        )
        assertEquals(RewriteRefusal.EXPRESSION_NOT_USABLE, refused.refusal)
    }

    /** 파생 테이블 안에서 투영되면 **가장 안쪽 스코프**에 계획이 붙어야 한다(외곽 alias는 물리 테이블이 아니다). */
    @Test
    fun `파생 테이블 내부 투영은 그 스코프에 계획된다`() {
        val query = "SELECT d.email FROM (SELECT email FROM users) d LIMIT 10"
        val outcome = assertIs<PlanOutcome.Planned>(plan(query))
        val mask = outcome.plan.maskProjections.single()
        val root = ir(query).root
        assertTrue(mask.scopeId != root.scopeId, "루트가 아니라 파생 스코프에 붙어야 함")
        assertEquals(root.children.single().scopeId, mask.scopeId)
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
}
