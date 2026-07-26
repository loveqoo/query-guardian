package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.RequiredForm
import com.loveqoo.queryguardian.rules.RuleGroup
import com.loveqoo.queryguardian.rules.RuleCondition
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.RuleOp
import com.loveqoo.queryguardian.rules.RuleScope
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.rules.UserRule
import com.loveqoo.queryguardian.rules.UserRuleEvaluator
import com.loveqoo.queryguardian.rules.LintContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** spec 004 §9 — 사용자 규칙 판정(requires/blocks/joins)·조건 severity·deferred·AND/OR·joins 우회 스위트. */
class UserRuleEvaluatorTest {

    private val parser = DruidMySqlParser()

    // 카탈로그: marketing_consents.consent_yn에 매핑된 FILTER def(id=100) = consent_yn='Y'
    private val catalog = InMemoryTableCatalog(
        tables = setOf("users", "marketing_consents"),
        conditionPredicates = mapOf(100L to RequiredForm("consent_yn", "Y")),
    )

    private fun engineWith(vararg rules: UserRule): RuleEngine =
        RuleEngine.withDefaultRules(userRuleEvaluator = UserRuleEvaluator { rules.toList() })

    private fun lint(sql: String, vararg rules: UserRule) =
        when (val r = parser.parse(sql)) {
            is ParseResult.Success -> engineWith(*rules).lint(r.ir, catalog, LintContext(null))
            is ParseResult.Failure -> error("parse failed: ${r.message}")
        }

    private fun group(vararg c: RuleNodeLike): RuleGroup =
        RuleGroup(RuleGroup.Combinator.ALL, c.map { it.node })

    // 간편 빌더
    private class RuleNodeLike(val node: com.loveqoo.queryguardian.rules.RuleNode)
    private fun requires(table: String, col: String, defId: Long, sev: Severity = Severity.BLOCK) =
        RuleNodeLike(RuleCondition(RuleOp.REQUIRES, sev, table = table, column = col, defId = defId))
    private fun blocks(table: String, col: String, sev: Severity = Severity.BLOCK) =
        RuleNodeLike(RuleCondition(RuleOp.BLOCKS, sev, table = table, column = col, defId = 1))
    private fun joins(table: String, col: String, refTable: String, refCol: String, sev: Severity = Severity.BLOCK) =
        RuleNodeLike(RuleCondition(RuleOp.JOINS, sev, table = table, column = col, refTable = refTable, refColumn = refCol))
    private fun mustMask(table: String, col: String) =
        RuleNodeLike(RuleCondition(RuleOp.MUST_BE_MASKED, Severity.BLOCK, table = table, column = col, defId = 1))

    private fun rule(scope: RuleScope, tree: RuleGroup) = UserRule(1, "테스트 규칙", scope, true, tree)

    private fun anyGroup(vararg c: RuleNodeLike) = RuleGroup(RuleGroup.Combinator.ANY, c.map { it.node })

    // ---- requires ----

    @Test
    fun `requires - 필수 술어 누락 차단, 충족 통과`() {
        val r = rule(RuleScope.SINGLE, group(requires("marketing_consents", "consent_yn", 100)))
        assertTrue(lint("SELECT id FROM marketing_consents LIMIT 10", r).blocked)
        assertFalse(lint("SELECT id FROM marketing_consents WHERE consent_yn = 'Y' LIMIT 10", r).blocked)
    }

    @Test
    fun `requires - OR 가지로 우회 불가`() {
        val r = rule(RuleScope.SINGLE, group(requires("marketing_consents", "consent_yn", 100)))
        assertTrue(lint("SELECT id FROM marketing_consents WHERE consent_yn = 'Y' OR 1 = 1 LIMIT 10", r).blocked)
    }

    @Test
    fun `requires - dangling defId는 fail-closed 차단`() {
        val r = rule(RuleScope.SINGLE, group(requires("marketing_consents", "consent_yn", 999))) // 미등록 defId
        assertTrue(lint("SELECT id FROM marketing_consents WHERE consent_yn = 'Y' LIMIT 10", r).blocked)
    }

    // ---- blocks ----

    @Test
    fun `blocks - 컬럼 참조 차단, 미참조 통과`() {
        val r = rule(RuleScope.SINGLE, group(blocks("users", "ssn")))
        assertTrue(lint("SELECT COUNT(ssn) FROM users LIMIT 10", r).blocked)
        assertFalse(lint("SELECT id FROM users LIMIT 10", r).blocked)
    }

    // ---- joins 우회 스위트 (C1·C2·H1·M3) ----

    private fun joinRule() = rule(RuleScope.MULTI, group(joins("marketing_consents", "user_id", "users", "id")))

    @Test
    fun `joins - INNER 조인 존재 시 통과 (방향 무관)`() {
        assertFalse(lint("SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id LIMIT 10", joinRule()).blocked)
        assertFalse(lint("SELECT u.id FROM marketing_consents mc JOIN users u ON u.id = mc.user_id LIMIT 10", joinRule()).blocked)
    }

    @Test
    fun `joins - LEFT JOIN ON 등식은 충족 아님 (C1)`() {
        assertTrue(lint("SELECT u.id FROM users u LEFT JOIN marketing_consents mc ON mc.user_id = u.id LIMIT 10", joinRule()).blocked)
    }

    @Test
    fun `joins - OR 가지 조인은 충족 아님 (C2)`() {
        assertTrue(lint("SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id OR 1 = 1 LIMIT 10", joinRule()).blocked)
    }

    @Test
    fun `joins - 엉뚱한 컬럼 조인은 충족 아님 (H1)`() {
        assertTrue(lint("SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.status LIMIT 10", joinRule()).blocked)
    }

    @Test
    fun `joins - 대상 테이블 하나만 존재하면 미충족 (M3)`() {
        assertTrue(lint("SELECT id FROM marketing_consents LIMIT 10", joinRule()).blocked)
    }

    @Test
    fun `joins - 규칙 대상 테이블 미참조 쿼리는 적용 안 됨`() {
        assertFalse(lint("SELECT id FROM users LIMIT 10", joinRule()).blocked) // users는 대상(조건 table)이 아님
    }

    // ---- 조건 단위 severity ----

    @Test
    fun `조건 단위 severity - BLOCK과 WARN 분리 보고`() {
        val r = rule(RuleScope.SINGLE, group(
            blocks("users", "ssn", Severity.BLOCK),
            requires("users", "consent_yn", 100, Severity.WARN),
        ))
        val report = lint("SELECT ssn FROM users LIMIT 10", r)
        assertTrue(report.blocked) // ssn 참조 → BLOCK
        assertTrue(report.violations.any { it.severity == Severity.WARN }) // consent 누락 → WARN
    }

    // ---- deferred / 미강제 (C3) ----

    @Test
    fun `must_be_masked 전용 규칙은 위반 미발생 (미강제)`() {
        val r = rule(RuleScope.SINGLE, group(mustMask("users", "email")))
        val report = lint("SELECT email FROM users LIMIT 10", r)
        assertFalse(report.blocked)
        assertTrue(report.violations.none { it.ruleId == "rule/1" })
    }

    // ---- AND / OR ----

    @Test
    fun `any 그룹 - 한 팔만 충족해도 통과`() {
        val r = rule(RuleScope.SINGLE, anyGroup(
            requires("marketing_consents", "consent_yn", 100),
            blocks("marketing_consents", "nonexistent"),
        ))
        // consent 충족 → any 통과 (nonexistent 미참조라 blocks 충족이기도)
        assertFalse(lint("SELECT id FROM marketing_consents WHERE consent_yn = 'Y' LIMIT 10", r).blocked)
    }

    @Test
    fun `all 그룹 - 전부 필요`() {
        val r = rule(RuleScope.MULTI, group(
            joins("marketing_consents", "user_id", "users", "id"),
            requires("marketing_consents", "consent_yn", 100),
        ))
        // 조인은 있으나 consent 누락 → 차단
        assertTrue(lint("SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id LIMIT 10", r).blocked)
        // 둘 다 충족 → 통과
        assertFalse(lint(
            "SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id WHERE mc.consent_yn = 'Y' LIMIT 10", r).blocked)
    }

    @Test
    fun `비활성 규칙은 판정 안 함`() {
        val disabled = UserRule(1, "off", RuleScope.SINGLE, false, group(blocks("users", "ssn")))
        assertFalse(lint("SELECT ssn FROM users LIMIT 10", disabled).blocked)
    }
}
