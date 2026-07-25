package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.MaskProjection
import com.loveqoo.queryguardian.ir.PredicateInjection
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.TableRename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 008 §3.0.3 — 재작성 결과 자체 검증. 여기서는 **일부러 잘못된 "재작성 결과"** 를 손으로 만들어
 * 검증기가 잡는지 본다. 재작성기 버그가 생겨도 잘못된 SQL이 실행되지 않게 하는 이중 방어이므로,
 * 검증기 자신이 눈감는 축이 있으면 그 방어가 없는 것과 같다.
 */
class RewriteVerifierTest {

    private val parser = DruidMySqlParser()
    private val verifier = RewriteVerifier(parser)

    /** 계획 기반 검사만 보려는 테스트용 — 마스킹 기대치를 두지 않는다. */
    private val noMasks: (String) -> Set<String> = { emptySet() }

    private fun irOf(sql: String) = (parser.parse(sql) as ParseResult.Success).ir

    /** 판정 IR을 명시하지 않는 테스트는 재작성 결과 자체를 원본으로 삼는다(계획 축만 검증). */
    private fun RewriteVerifier.verify(sql: String, plan: RewritePlan) = verify(sql, plan, irOf(sql), noMasks)

    private val maskPlan = RewritePlan(
        maskProjections = listOf(MaskProjection("s0", "users", "email", "mask_email({col})", "email")),
    )

    @Test
    fun `계획대로 된 결과는 통과한다`() {
        val problems = verifier.verify(
            "SELECT mask_email(users.email) AS email FROM demo_users users WHERE (users.id > 0) AND (users.id IS NOT NULL) LIMIT 1001",
            RewritePlan(
                maskProjections = listOf(MaskProjection("s0", "users", "email", "mask_email({col})", "email")),
                injections = listOf(PredicateInjection("s0", "users", "users.id IS NOT NULL", "무결성")),
                limitCap = LimitCap("s0", 1000),
                tableRenames = listOf(TableRename("s0", "users", "users", "demo_users")),
            ),
        )
        assertEquals(emptyList(), problems)
    }

    /** 가장 위험한 실패 — 마스킹이 적용되지 않았는데 통과하면 평문이 반환된다. */
    @Test
    fun `MASK 컬럼이 원본 투영으로 남아 있으면 잡는다`() {
        val problems = verifier.verify("SELECT email FROM users", maskPlan)
        assertTrue(problems.any { it.contains("원본 투영으로 남아 있습니다") }, "$problems")
    }

    @Test
    fun `주입 술어가 없으면 잡는다`() {
        val problems = verifier.verify(
            "SELECT id FROM users",
            RewritePlan(injections = listOf(PredicateInjection("s0", "users", "users.consent_yn = 'Y'", "동의"))),
        )
        assertTrue(problems.any { it.contains("최상위 조건으로 남아 있지 않습니다") }, "$problems")
    }

    /** OR 가지 안에 들어간 술어는 최상위 conjunct가 아니다 — 주입이 무력화된 상태를 잡아야 한다. */
    @Test
    fun `OR 가지 안의 술어는 최상위로 인정하지 않는다`() {
        val problems = verifier.verify(
            "SELECT id FROM users WHERE users.id = 1 OR users.consent_yn = 'Y'",
            RewritePlan(injections = listOf(PredicateInjection("s0", "users", "users.consent_yn = 'Y'", "동의"))),
        )
        assertTrue(problems.any { it.contains("최상위 조건으로 남아 있지 않습니다") }, "$problems")
    }

    @Test
    fun `물리명 치환이 안 됐으면 잡는다`() {
        val problems = verifier.verify(
            "SELECT id FROM users",
            RewritePlan(tableRenames = listOf(TableRename("s0", "users", "users", "demo_users"))),
        )
        assertTrue(problems.any { it.contains("치환이 적용되지 않았습니다") }, "$problems")
        assertTrue(problems.any { it.contains("논리 테이블이 아직 남아 있습니다") }, "$problems")
    }

    @Test
    fun `행 상한이 없거나 초과하면 잡는다`() {
        assertTrue(
            verifier.verify("SELECT id FROM users", RewritePlan(limitCap = LimitCap("s0", 1000)))
                .any { it.contains("행 상한이 적용되지 않았습니다") },
        )
        assertTrue(
            verifier.verify("SELECT id FROM users LIMIT 5000", RewritePlan(limitCap = LimitCap("s0", 1000)))
                .any { it.contains("행 상한이 적용되지 않았습니다") },
        )
        // 상한+1은 정상(truncated 판정용)
        assertEquals(
            emptyList(),
            verifier.verify("SELECT id FROM users LIMIT 1001", RewritePlan(limitCap = LimitCap("s0", 1000))),
        )
    }

    /** 재작성 산출물도 위생 게이트를 통과해야 한다 — 왕복 정합성(§2.8-4)의 실행 지점. */
    @Test
    fun `재작성 결과가 위생 위반이면 잡는다`() {
        val problems = verifier.verify("SELECT id FROM users -- 주석", RewritePlan())
        assertTrue(problems.any { it.contains("위생 게이트에 걸립니다") }, "$problems")
    }

    @Test
    fun `파싱 불가한 결과는 즉시 잡는다`() {
        // 판정 IR은 정상이고 **재작성 산출물만** 깨진 상황 — 재작성이 문장을 부순 경우다
        val problems = verifier.verify("SELECT FROM WHERE ((", maskPlan, irOf("SELECT email FROM users"), noMasks)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("다시 파싱할 수 없습니다"), "$problems")
    }

    /** 파생 스코프에 주입된 술어도 찾아야 한다 — 스코프 id는 재파싱하면 달라지므로 존재로 검증한다. */
    @Test
    fun `파생 스코프의 주입도 인정한다`() {
        val problems = verifier.verify(
            "SELECT d.id FROM (SELECT id FROM users WHERE users.consent_yn = 'Y') d",
            RewritePlan(injections = listOf(PredicateInjection("s1", "users", "users.consent_yn = 'Y'", "동의"))),
        )
        assertEquals(emptyList(), problems)
    }

    /**
     * 적대 검토 CRITICAL: "bare 투영으로 남지 않았다"만 보면 **아무 항등 표현식**으로 감싸도 통과한다.
     * `CONCAT(users.email, '')`은 사실상 평문 이메일을 반환한다 — 계획한 강제식과 대조해야 한다.
     */
    @Test
    fun `계획한 강제식이 아닌 표현식으로 감싸면 잡는다`() {
        val problems = verifier.verify(
            "SELECT CONCAT(users.email, '') AS email FROM demo_users users LIMIT 1001",
            RewritePlan(
                maskProjections = listOf(MaskProjection("s0", "users", "email", "mask_email({col})", "email")),
                limitCap = LimitCap("s0", 1000),
                tableRenames = listOf(TableRename("s0", "users", "users", "demo_users")),
            ),
        )
        assertTrue(problems.any { it.contains("계획한 마스킹 강제식이 적용되지 않았습니다") }, "$problems")
    }

    @Test
    fun `한정 비한정 두 형태의 강제식을 모두 인정한다`() {
        val plan = RewritePlan(maskProjections = listOf(MaskProjection("s0", "users", "email", "mask_email({col})", "email")))
        assertEquals(emptyList(), verifier.verify("SELECT mask_email(users.email) AS email FROM users", plan))
        assertEquals(emptyList(), verifier.verify("SELECT mask_email(email) AS email FROM users", plan))
    }

    /**
     * 적대 검토 CRITICAL 7: 기대치를 계획에서만 뽑으면 **계획이 마스킹을 빠뜨린 경우**에 검증기가 눈이 먼다.
     * 검토자는 마스킹이 없는 계획을 다른 파싱의 핸들에 적용해 평문 `email`을 반환시키고 `verify() == []`를 확인했다.
     * 이제 판정 IR + 카탈로그로 기대 마스킹을 **스스로 재도출**해 대조한다.
     */
    @Test
    fun `계획이 마스킹을 빠뜨리면 잡는다`() {
        val judged = irOf("SELECT email FROM users")
        val problems = verifier.verify(
            "SELECT email FROM demo_users users LIMIT 1001",
            RewritePlan(limitCap = LimitCap("s0", 1000)), // 마스킹 계획이 없다
            judged,
        ) { table -> if (table.equals("users", true)) setOf("email") else emptySet() }
        assertTrue(problems.any { it.contains("계획이 마스킹을 빠뜨렸습니다") }, "$problems")
    }

    /** 표현 불가한 사용이 남아 있는데 재작성이 진행됐다면(거부되어야 했다) 검증에서 잡힌다. */
    @Test
    fun `표현 불가 사용을 재작성했으면 잡는다`() {
        val judged = irOf("SELECT CONCAT(email, '') AS e FROM users")
        val problems = verifier.verify(
            "SELECT CONCAT(email, '') AS e FROM demo_users users LIMIT 1001",
            RewritePlan(limitCap = LimitCap("s0", 1000)),
            judged,
        ) { table -> if (table.equals("users", true)) setOf("email") else emptySet() }
        assertTrue(problems.any { it.contains("표현할 수 없는 마스킹 사용") }, "$problems")
    }
}
