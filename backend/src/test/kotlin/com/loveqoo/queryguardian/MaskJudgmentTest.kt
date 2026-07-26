package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.ir.MaskUsage
import com.loveqoo.queryguardian.ir.maskUsageOf
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.RuleCondition
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.RuleGroup
import com.loveqoo.queryguardian.rules.RuleOp
import com.loveqoo.queryguardian.rules.RuleScope
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.rules.UserRule
import com.loveqoo.queryguardian.rules.UserRuleEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * spec 008 §3.1 / M1-6 — `must_be_masked` 판정. spec 004에서 "미판정"으로 남겼던 것을 재작성이 생기면서 판정한다.
 *
 * 두 경로가 **같은 기준**(`maskUsageOf`)을 쓰는지가 핵심이다. 판정과 재작성이 갈라지면
 * "저장은 통과했는데 실행은 마스킹 없이 통과"라는 최악의 실패가 가능해진다.
 */
class MaskJudgmentTest {

    private val parser = DruidMySqlParser()

    /** users.email이 MASK 매핑된 카탈로그. */
    private val catalog = InMemoryTableCatalog(
        tables = setOf("users", "marketing_consents"),
        masked = mapOf("users" to setOf("email")),
    )

    private fun lint(sql: String, vararg rules: UserRule) =
        LintService(parser, RuleEngine.withDefaultRules(userRuleEvaluator = UserRuleEvaluator { rules.toList() }), catalog)
            .lint(sql)

    private fun scope(sql: String) = ((parser.parse(sql)) as ParseResult.Success).ir.root

    private fun maskRule(table: String = "users", column: String = "email") = UserRule(
        1, "PII 마스킹 필수", RuleScope.SINGLE, true,
        RuleGroup(
            RuleGroup.Combinator.ALL,
            listOf(RuleCondition(RuleOp.MUST_BE_MASKED, Severity.BLOCK, table = table, column = column, defId = 1)),
        ),
    )

    // ---- 공용 판정 함수 ----

    @Test
    fun `사용 위치를 세 상태로 구분한다`() {
        assertEquals(MaskUsage.ABSENT, maskUsageOf(scope("SELECT id FROM users"), "users", "email"))
        assertEquals(MaskUsage.PROJECTION_ONLY, maskUsageOf(scope("SELECT email FROM users"), "users", "email"))
        assertEquals(
            MaskUsage.NOT_EXPRESSIBLE,
            maskUsageOf(scope("SELECT CONCAT(email, '') FROM users"), "users", "email"),
        )
        assertEquals(
            MaskUsage.NOT_EXPRESSIBLE,
            maskUsageOf(scope("SELECT id FROM users WHERE email = 'a@b.com'"), "users", "email"),
        )
        assertEquals(MaskUsage.NOT_EXPRESSIBLE, maskUsageOf(scope("SELECT * FROM users"), "users", "email"))
    }

    /** 한정된 star는 그 인스턴스만 덮는다 — `o.*`가 users의 마스킹 판정을 오염시키면 오차단이 된다. */
    @Test
    fun `한정된 star는 다른 테이블의 마스킹 판정을 오염시키지 않는다`() {
        val s = scope("SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id")
        assertEquals(MaskUsage.PROJECTION_ONLY, maskUsageOf(s, "u", "email"))
    }

    // ---- 시스템 룰 ----

    @Test
    fun `투영만 하면 자동 마스킹 안내 WARN이 뜨고 차단되지 않는다`() {
        val report = lint("SELECT email FROM users LIMIT 10")
        val violation = report.violations.single { it.ruleId == "must-be-masked" }
        assertEquals(Severity.WARN, violation.severity)
        assertTrue(violation.message.contains("자동으로 마스킹"), violation.message)
        assertFalse(report.blocked, "$report")
    }

    @Test
    fun `투영이 아닌 위치는 BLOCK이다`() {
        for (sql in listOf(
            "SELECT CONCAT(email, '') AS e FROM users LIMIT 10",
            "SELECT id FROM users WHERE email LIKE '%@naver.com' LIMIT 10",
            "SELECT id FROM users ORDER BY email LIMIT 10",
        )) {
            val report = lint(sql)
            val violation = report.violations.single { it.ruleId == "must-be-masked" }
            assertEquals(Severity.BLOCK, violation.severity, sql)
            assertTrue(report.blocked, sql)
        }
    }

    @Test
    fun `마스킹 컬럼을 조회하지 않으면 아무 위반도 없다`() {
        assertTrue(lint("SELECT id FROM users LIMIT 10").violations.none { it.ruleId == "must-be-masked" })
    }

    /** 파생 테이블·CTE 내부도 스코프다 — 안쪽에서 우회하면 안 된다 (§6.2). */
    @Test
    fun `파생 테이블 내부의 표현 불가 사용도 잡는다`() {
        val report = lint("SELECT d.e FROM (SELECT CONCAT(email, '') AS e FROM users) d LIMIT 10")
        assertTrue(report.blocked, "$report")
        assertTrue(report.violations.any { it.ruleId == "must-be-masked" && it.severity == Severity.BLOCK })
    }

    // ---- 사용자 규칙 ----

    @Test
    fun `사용자 규칙 must_be_masked는 이제 판정된다`() {
        // 투영만 → 충족(위반 없음)
        assertTrue(
            lint("SELECT email FROM users LIMIT 10", maskRule()).violations.none { it.ruleId == "rule/1" },
            "투영만 했으면 사용자 규칙 위반이 아니다",
        )
        // 표현 불가 위치 → 위반
        val violated = lint("SELECT LOWER(email) AS e FROM users LIMIT 10", maskRule())
        assertTrue(violated.violations.any { it.ruleId == "rule/1" && it.severity == Severity.BLOCK }, "$violated")
    }

    @Test
    fun `컬럼을 조회하지 않는 스코프에서는 중립이다`() {
        val report = lint("SELECT id FROM users LIMIT 10", maskRule())
        assertTrue(report.violations.none { it.ruleId == "rule/1" }, "조회하지 않으면 중립이어야 함: $report")
    }

    /** 대상 테이블·컬럼이 불명이면 fail-closed — 표현 불가로 보고 위반 처리한다. */
    @Test
    fun `대상이 불명한 조건은 위반으로 떨어진다`() {
        val report = lint("SELECT email FROM users LIMIT 10", maskRule(table = "users", column = ""))
        assertTrue(report.violations.any { it.ruleId == "rule/1" }, "$report")
    }

    /** 셀프 조인: 한쪽만 안전한 것은 안전이 아니다. */
    @Test
    fun `셀프 조인에서 한 인스턴스만 표현 불가여도 위반이다`() {
        val report = lint(
            "SELECT u.email, LOWER(v.email) AS x FROM users u JOIN users v ON v.id = u.id LIMIT 10",
            maskRule(),
        )
        assertTrue(report.violations.any { it.ruleId == "rule/1" && it.severity == Severity.BLOCK }, "$report")
    }

    /**
     * spec 008 결정 9의 소급 효과: `must_be_masked`만 가진 규칙은 이전에 `enforced=false`(미강제)였으나
     * 판정 전환으로 **강제된다**. 이 단정이 깨지면 판정 전환이 되돌아간 것이다.
     */
    @Test
    fun `must_be_masked만 가진 규칙도 이제 강제된다`() {
        // enforced 배지는 "판정 조건이 하나라도 있는가"(RuleService)로 계산된다 — 그 근거가 cond.judged다.
        val condition = maskRule().tree.children.single() as RuleCondition
        assertTrue(condition.judged, "must_be_masked가 판정 대상이 되었으므로 judged여야 한다")
    }

    /**
     * 적대 검토 HIGH(실측 확인): 마스킹은 **many-to-one**이다 —
     * `mask_email('jimin@naver.com') = mask_email('jaeho@naver.com') = 'j***@naver.com'`,
     * `COUNT(DISTINCT)` 3 → 2 (MySQL 8.4에서 직접 확인). 그래서 치환 후 중복 제거·그룹화·정렬을 하면
     * 원본과 결과 의미가 달라진다. 조용한 결과 변경보다 거부가 안전하다.
     */
    @Test
    fun `DISTINCT는 마스킹 후 중복이 합쳐지므로 표현 불가다`() {
        assertEquals(MaskUsage.NOT_EXPRESSIBLE, maskUsageOf(scope("SELECT DISTINCT email FROM users"), "users", "email"))
        assertTrue(lint("SELECT DISTINCT email FROM users LIMIT 10").blocked)
    }

    @Test
    fun `출력 별칭이나 서수로 투영을 가리키면 표현 불가다`() {
        for (sql in listOf(
            "SELECT email AS e FROM users GROUP BY e",
            "SELECT email AS e FROM users ORDER BY e",
            "SELECT email AS e, COUNT(*) c FROM users GROUP BY e HAVING e LIKE 'ab%'",
            "SELECT email FROM users GROUP BY 1",
            "SELECT email FROM users ORDER BY 1",
        )) {
            assertEquals(
                MaskUsage.NOT_EXPRESSIBLE,
                maskUsageOf(scope(sql), "users", "email"),
                "그룹·정렬 기준이 마스킹된 값으로 바뀐다: $sql",
            )
        }
    }

    /** 오차단 금지: 마스킹 컬럼과 무관한 정렬·그룹은 그대로 통과해야 한다. */
    @Test
    fun `무관한 정렬 그룹은 마스킹을 막지 않는다`() {
        assertEquals(
            MaskUsage.PROJECTION_ONLY,
            maskUsageOf(scope("SELECT email, id FROM users ORDER BY id"), "users", "email"),
        )
        assertEquals(
            MaskUsage.PROJECTION_ONLY,
            maskUsageOf(scope("SELECT email AS mail, id AS n FROM users ORDER BY n"), "users", "email"),
        )
    }
}
