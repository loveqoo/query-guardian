package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import kotlin.test.Test

/** spec 001 §12 우회 시도 스위트 — 전부 차단되어야 한다. 하나라도 통과하면 게이트가 아니라 제안함이다. */
class BypassSuiteTest {

    @Test
    fun `OR 가지 세탁 - 필수 술어`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND (consent_yn = 'Y' OR 1 = 1) LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `OR 가지 세탁 - 파티션 키`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' OR user_id > 0 LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `파생 테이블에 숨긴 SELECT star와 거버넌스 테이블`() {
        assertBlockedBy("SELECT id FROM (SELECT * FROM user_events) t LIMIT 10", "no-select-star")
        assertBlockedBy("SELECT id FROM (SELECT id FROM user_events) t LIMIT 10", "require-partition-key")
    }

    @Test
    fun `CTE에 숨긴 위반`() {
        assertBlockedBy("WITH x AS (SELECT * FROM user_events) SELECT id FROM x LIMIT 10", "no-select-star")
        assertBlockedBy("WITH x AS (SELECT id FROM user_events) SELECT id FROM x LIMIT 10", "require-partition-key")
    }

    @Test
    fun `IN 서브쿼리에 숨긴 거버넌스 테이블`() {
        assertBlockedBy(
            "SELECT id FROM orders WHERE id IN (SELECT id FROM user_events) LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `UNION 더러운 팔 세탁`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' UNION ALL SELECT * FROM user_events",
            "no-select-star",
        )
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' UNION ALL SELECT id FROM user_events",
            "require-partition-key",
        )
    }

    @Test
    fun `부정형은 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND NOT (consent_yn = 'Y') LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND consent_yn <> 'Y' LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `함수로 감싼 파티션 키는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE DATE(event_date) = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `Raw로 떨어지는 술어는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND COALESCE(consent_yn, 'N') = 'Y' LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `주석 삽입은 술어가 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' -- AND consent_yn = 'Y'",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `OUTER JOIN ON의 술어는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT o.id FROM orders o LEFT JOIN user_events e ON e.event_date = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }
}
