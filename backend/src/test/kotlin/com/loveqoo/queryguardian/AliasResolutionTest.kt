package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import com.loveqoo.queryguardian.Fixtures.assertNotBlocked
import kotlin.test.Test

/** §6.4 컬럼 귀속: 양의 귀속이 안 되면 요건 미충족(fail-closed). */
class AliasResolutionTest {

    @Test
    fun `단일 테이블에서는 비한정 컬럼도 귀속된다`() {
        assertNotBlocked("SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10")
    }

    @Test
    fun `다른 테이블의 동명 컬럼으로는 충족되지 않는다`() {
        assertBlockedBy(
            "SELECT ue.id FROM user_events ue, audit_events ae WHERE ae.event_date = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `다중 테이블에서 비한정 컬럼은 귀속 불가 - fail closed`() {
        assertBlockedBy(
            "SELECT ue.id FROM user_events ue, audit_events ae WHERE event_date = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `alias 한정이면 다중 테이블에서도 충족된다`() {
        assertNotBlocked(
            "SELECT ue.id FROM user_events ue, audit_events ae WHERE ue.event_date = '2026-01-01' LIMIT 10"
        )
    }
}
