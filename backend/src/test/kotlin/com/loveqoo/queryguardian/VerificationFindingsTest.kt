package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import com.loveqoo.queryguardian.Fixtures.assertNotBlocked
import com.loveqoo.queryguardian.Fixtures.lint
import kotlin.test.Test
import kotlin.test.assertTrue

/** M1 적대 검증(learning 003)에서 발견된 결함들의 회귀 테스트. F번호는 검증 리포트 기준. */
class VerificationFindingsTest {

    @Test
    fun `F1 백틱 식별자로 거버넌스 우회 불가`() {
        assertBlockedBy("SELECT id FROM `user_events` LIMIT 10", "require-partition-key")
        assertBlockedBy(
            "SELECT id FROM `user_events` WHERE `event_date` = '2026-01-01' LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `F1 백틱 식별자 정상 쿼리는 오차단하지 않는다`() {
        assertNotBlocked("SELECT id FROM user_events WHERE `event_date` = '2026-01-01' LIMIT 10")
        assertNotBlocked("SELECT `id` FROM `user_events` e WHERE e.`event_date` = '2026-01-01' LIMIT 10")
    }

    @Test
    fun `F2 셀프 조인 - 한 인스턴스의 조건이 다른 인스턴스를 면제하지 못한다`() {
        assertBlockedBy(
            "SELECT a.id FROM user_events a JOIN user_events b ON a.k = b.k " +
                "WHERE a.event_date = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
        assertNotBlocked(
            "SELECT a.id FROM user_events a JOIN user_events b ON a.k = b.k " +
                "WHERE a.event_date = '2026-01-01' AND b.event_date = '2026-01-01' LIMIT 10"
        )
    }

    @Test
    fun `F3 BETWEEN 경계의 서브쿼리도 스코프로 검사된다`() {
        assertBlockedBy(
            "SELECT id FROM orders WHERE id BETWEEN (SELECT MIN(id) FROM user_events) " +
                "AND (SELECT MAX(id) FROM user_events) LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `F4 표현 불가 SELECT 변형은 fail-closed`() {
        // VALUES 문은 Druid가 SQLSelectStatement로 파싱하지만 IR로 표현 불가 → 무조건 차단
        val report = lint("VALUES ROW(1, 2)")
        assertTrue(report.blocked, "VALUES 문이 차단되지 않음: $report")
    }

    @Test
    fun `F5 파생 테이블 alias가 거버넌스 테이블명과 겹쳐도 오차단하지 않는다`() {
        assertNotBlocked("SELECT id FROM (SELECT id FROM orders) user_events LIMIT 10")
    }
}
