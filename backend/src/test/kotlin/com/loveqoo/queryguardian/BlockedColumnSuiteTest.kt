package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import com.loveqoo.queryguardian.Fixtures.assertNotBlocked
import com.loveqoo.queryguardian.Fixtures.lint
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.Severity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * spec 002 §7 — no-blocked-column 우회/오탐 스위트 (적대 검토 C1의 IR columnRefs 확장 검증).
 * 픽스처: users.ssn = BLOCK 매핑.
 */
class BlockedColumnSuiteTest {

    // ---- 우회 시도: 전부 차단되어야 한다 ----

    @Test
    fun `함수 인자 속 참조도 차단`() {
        assertBlockedBy("SELECT COUNT(ssn) FROM users LIMIT 10", "no-blocked-column")
        assertBlockedBy("SELECT LEFT(ssn, 3) FROM users LIMIT 10", "no-blocked-column")
        assertBlockedBy("SELECT id FROM users WHERE LENGTH(ssn) = 13 LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `GROUP BY, HAVING, ORDER BY 속 참조도 차단`() {
        assertBlockedBy("SELECT id FROM users GROUP BY ssn LIMIT 10", "no-blocked-column")
        assertBlockedBy("SELECT dept FROM users GROUP BY dept HAVING MAX(ssn) > 0 LIMIT 10", "no-blocked-column")
        assertBlockedBy("SELECT id FROM users ORDER BY ssn LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `파생 테이블 재수출 차단`() {
        assertBlockedBy("SELECT s FROM (SELECT ssn AS s FROM users) t LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `서브쿼리와 CTE 속 참조 차단`() {
        assertBlockedBy("SELECT id FROM orders WHERE id IN (SELECT ssn FROM users) LIMIT 10", "no-blocked-column")
        assertBlockedBy("WITH x AS (SELECT ssn FROM users) SELECT 1 FROM x LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `백틱과 alias로 우회 불가`() {
        assertBlockedBy("SELECT `ssn` FROM `users` LIMIT 10", "no-blocked-column")
        assertBlockedBy("SELECT u.ssn FROM users u LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `JOIN ON 속 참조도 차단`() {
        assertBlockedBy("SELECT o.id FROM orders o LEFT JOIN users u ON u.ssn = o.ref LIMIT 10", "no-blocked-column")
    }

    @Test
    fun `귀속 불명 참조는 fail-closed 차단`() {
        // 다중 테이블에서 비한정 ssn — users의 차단 컬럼일 수 있으므로 차단 (§6.4)
        assertBlockedBy("SELECT o.id FROM users u, orders o WHERE ssn = '1' LIMIT 10", "no-blocked-column")
    }

    // ---- 오탐: 전부 통과해야 한다 ----

    @Test
    fun `차단 컬럼이 아닌 조회는 통과`() {
        // spec 012 P2: `email`은 마스킹 대상이라 맨몸으로 쓰면 막힌다 — 사용자가 직접 가려야 한다.
        // 이 테스트가 재는 것은 **차단 컬럼(ssn)이 아닌 조회**이므로 가려서 쓴 형태로 확인한다.
        assertNotBlocked("SELECT id, mask_email(email) FROM users LIMIT 10")
        assertNotBlocked("SELECT id FROM users WHERE created_at > NOW() - INTERVAL 1 DAY LIMIT 10")
    }

    @Test
    fun `타 테이블의 동명 컬럼은 차단 안 됨`() {
        val report = lint("SELECT ssn FROM legacy_archive LIMIT 10")
        assertFalse(report.blocked, "비거버넌스 테이블의 동명 컬럼이 오차단됨: $report")
    }

    // ---- 복합 파티션 (spec 002 C4) ----

    @Test
    fun `복합 파티션 - 각 키는 독립 요건`() {
        val catalog = InMemoryTableCatalog(
            partitionKeys = mapOf("hive_events" to listOf("event_date", "region")),
            tables = setOf("hive_events"),
        )
        val service = LintService(Fixtures.parser, RuleEngine.withDefaultRules(), catalog)
        val partial = service.lint("SELECT id FROM hive_events WHERE event_date = '2026-01-01' LIMIT 10")
        assertTrue(partial.blocked, "파티션 키 한 축만으로 통과함: $partial")
        val full = service.lint(
            "SELECT id FROM hive_events WHERE event_date = '2026-01-01' AND region = 'KR' LIMIT 10"
        )
        assertFalse(full.blocked, "두 축 모두 충족했는데 차단됨: $full")
    }

    // ---- LIMIT 상한 (spec 002 B13) ----

    @Test
    fun `LIMIT 상한 초과는 WARN`() {
        val over = lint("SELECT id FROM orders LIMIT 1500")
        assertFalse(over.blocked)
        assertTrue(over.violations.any { it.ruleId == "require-limit" && it.severity == Severity.WARN && it.message.contains("1000") })
        val ok = lint("SELECT id FROM orders LIMIT 100")
        assertTrue(ok.violations.none { it.ruleId == "require-limit" })
    }
}
