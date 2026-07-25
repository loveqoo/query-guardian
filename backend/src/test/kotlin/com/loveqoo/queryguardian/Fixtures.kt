package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.RequiredPredicate
import com.loveqoo.queryguardian.rules.RuleEngine
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 공용 픽스처 — spec 001 대표 시나리오:
 * user_events: 파티션 키 event_date, purpose=marketing일 때 consent_yn='Y' 필수.
 */
object Fixtures {
    val parser = DruidMySqlParser()

    val catalog = InMemoryTableCatalog(
        partitionKeys = mapOf("user_events" to listOf("event_date")),
        required = listOf(
            InMemoryTableCatalog.Entry(
                table = "user_events",
                purposeCode = "marketing",
                predicate = RequiredPredicate("consent_yn = 'Y'", parser.parsePredicate("consent_yn = 'Y'")!!),
            ),
        ),
        // spec 002: users.ssn은 BLOCK 매핑 (디자인 표본 — 조회 전면 차단)
        blocked = mapOf("users" to setOf("ssn")),
        // spec 008: users.email은 MASK 매핑 (실행 시 자동 마스킹, 표현 불가 위치는 BLOCK)
        masked = mapOf("users" to setOf("email")),
        tables = setOf("user_events", "users"),
    )

    val service = LintService(parser, RuleEngine.withDefaultRules(), catalog)

    fun lint(sql: String, purpose: String? = null): LintReport = service.lint(sql, purpose)

    fun assertBlockedBy(sql: String, ruleIdPrefix: String, purpose: String? = null) {
        val report = lint(sql, purpose)
        assertTrue(report.blocked, "차단되어야 하는데 통과함: $sql\n$report")
        assertTrue(
            report.violations.any { it.ruleId.startsWith(ruleIdPrefix) },
            "룰 [$ruleIdPrefix] 위반이 있어야 함: $sql\n$report",
        )
    }

    fun assertNotBlocked(sql: String, purpose: String? = null) {
        val report = lint(sql, purpose)
        assertFalse(report.blocked, "통과해야 하는데 차단됨: $sql\n$report")
    }
}
