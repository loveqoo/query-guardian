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

    private val verifier = RewriteVerifier(DruidMySqlParser())

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
        val problems = verifier.verify("SELECT FROM WHERE ((", maskPlan)
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
}
