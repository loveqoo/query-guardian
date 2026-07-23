package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertNotBlocked
import com.loveqoo.queryguardian.Fixtures.lint
import com.loveqoo.queryguardian.rules.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/** spec 001 §12 오탐 스위트 — 전부 통과해야 한다. 흔한 쿼리를 오차단하는 게이트는 신뢰를 잃는다. */
class FalsePositiveSuiteTest {

    @Test
    fun `COUNT star는 SELECT star가 아니다`() {
        assertNotBlocked("SELECT COUNT(*) FROM user_events WHERE event_date = '2026-01-01' LIMIT 10")
    }

    @Test
    fun `EXISTS SELECT star 관용구는 면제`() {
        assertNotBlocked(
            "SELECT o.id FROM orders o WHERE EXISTS (SELECT * FROM audit a WHERE a.order_id = o.id) LIMIT 10"
        )
    }

    @Test
    fun `alias 한정 컬럼은 올바른 테이블로 귀속`() {
        assertNotBlocked(
            "SELECT e.id FROM user_events e JOIN dims d ON d.k = e.k " +
                "WHERE e.event_date = '2026-01-01' AND e.consent_yn = 'Y' LIMIT 10",
            purpose = "marketing",
        )
    }

    @Test
    fun `IN과 BETWEEN은 유효한 파티션 프루닝 형태`() {
        assertNotBlocked("SELECT id FROM user_events WHERE event_date IN ('2026-01-01', '2026-01-02') LIMIT 10")
        assertNotBlocked("SELECT id FROM user_events WHERE event_date BETWEEN '2026-01-01' AND '2026-01-31' LIMIT 10")
    }

    @Test
    fun `등호 피연산자 순서와 IN 단일값 동치`() {
        assertNotBlocked(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND 'Y' = consent_yn LIMIT 10",
            purpose = "marketing",
        )
        assertNotBlocked(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND consent_yn IN ('Y') LIMIT 10",
            purpose = "marketing",
        )
    }

    @Test
    fun `purpose가 다르면 필수 술어 미적용`() {
        assertNotBlocked("SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10")
        assertNotBlocked("SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10", purpose = "ops")
    }

    @Test
    fun `미등록 테이블은 의미 룰 미적용`() {
        assertNotBlocked("SELECT id, status FROM orders WHERE status = 'OPEN' LIMIT 10")
    }

    @Test
    fun `LIMIT 없는 SELECT는 WARN이지 차단이 아니다`() {
        val report = lint("SELECT id FROM orders WHERE status = 'OPEN'")
        assertTrue(!report.blocked)
        assertTrue(report.violations.any { it.ruleId == "require-limit" && it.severity == Severity.WARN })
    }
}
