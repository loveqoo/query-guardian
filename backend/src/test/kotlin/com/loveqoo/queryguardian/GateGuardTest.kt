package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import com.loveqoo.queryguardian.Fixtures.assertNotBlocked
import kotlin.test.Test

/** §5.1 입력 게이트: 멀티 스테이트먼트·비-SELECT·문법 오류·크기 초과는 위반 응답으로 차단(500 금지). */
class GateGuardTest {

    @Test
    fun `멀티 스테이트먼트는 차단`() {
        assertBlockedBy("SELECT 1; SELECT * FROM user_events", "parse/multi-statement")
        assertBlockedBy("SELECT 1; DELETE FROM user_events", "parse/multi-statement")
    }

    @Test
    fun `말미 세미콜론 하나는 허용`() {
        assertNotBlocked("SELECT id FROM orders WHERE status = 'OPEN' LIMIT 10;")
    }

    @Test
    fun `비-SELECT 문은 차단`() {
        assertBlockedBy("DELETE FROM user_events", "parse/not-select")
        assertBlockedBy("UPDATE user_events SET consent_yn = 'N'", "parse/not-select")
        assertBlockedBy("DROP TABLE user_events", "parse/not-select")
        assertBlockedBy("INSERT INTO user_events (id) VALUES (1)", "parse/not-select")
    }

    @Test
    fun `문법 오류는 차단`() {
        assertBlockedBy("SELEC id FRM user_events", "parse/syntax-error")
        assertBlockedBy("SELECT id FROM user_events WHERE (", "parse/syntax-error")
    }

    @Test
    fun `미완성 SELECT는 제대로 된 문법 오류로 차단 - NoClassDefFoundError 아님`() {
        // druid가 이 경로에서 commons-lang3를 요구한다 — 의존성 누락 시 오류 메시지가 클래스명이 된다
        val report = Fixtures.lint("SELECT e. FROM user_events e")
        kotlin.test.assertTrue(report.blocked)
        kotlin.test.assertTrue(
            report.violations.none { it.message.contains("org/apache") },
            "클래스패스 오류가 사용자에게 노출됨: ${report.violations}",
        )
    }

    @Test
    fun `64KB 초과 입력은 차단`() {
        val huge = "SELECT id FROM orders WHERE status = '" + "x".repeat(70 * 1024) + "' LIMIT 10"
        assertBlockedBy(huge, "parse/input-too-large")
    }
}
