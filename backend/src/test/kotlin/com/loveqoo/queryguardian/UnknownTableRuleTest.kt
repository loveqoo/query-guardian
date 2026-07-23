package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.lint
import com.loveqoo.queryguardian.rules.Severity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** unknown-table WARN 룰: 미등록 물리 테이블만 경고, CTE/파생 alias는 오탐하지 않는다. */
class UnknownTableRuleTest {

    private fun unknownWarnings(sql: String) =
        lint(sql).violations.filter { it.ruleId == "unknown-table" }

    @Test
    fun `미등록 테이블은 WARN - 차단은 아니다`() {
        val report = lint("SELECT id FROM orders WHERE status = 'OPEN' LIMIT 10")
        assertFalse(report.blocked)
        val warn = report.violations.single { it.ruleId == "unknown-table" }
        assertTrue(warn.severity == Severity.WARN && warn.message.contains("orders"))
    }

    @Test
    fun `등록 테이블은 경고 없음`() {
        assertTrue(unknownWarnings("SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10").isEmpty())
    }

    @Test
    fun `CTE 이름은 미등록 테이블이 아니다`() {
        val sql = "WITH x AS (SELECT id FROM user_events WHERE event_date = '2026-01-01') SELECT id FROM x LIMIT 10"
        assertTrue(unknownWarnings(sql).isEmpty(), "CTE 참조 x가 미등록 경고로 오탐됨: ${lint(sql)}")
    }

    @Test
    fun `파생 테이블 alias는 미등록 테이블이 아니다`() {
        val sql = "SELECT t.id FROM (SELECT id FROM user_events WHERE event_date = '2026-01-01') t LIMIT 10"
        assertTrue(unknownWarnings(sql).isEmpty())
    }

    @Test
    fun `조인에서 미등록 테이블만 골라 경고`() {
        val sql = "SELECT e.id FROM user_events e JOIN dims d ON d.k = e.k " +
            "WHERE e.event_date = '2026-01-01' LIMIT 10"
        val warnings = unknownWarnings(sql)
        assertTrue(warnings.size == 1 && warnings.single().message.contains("dims"))
    }
}
