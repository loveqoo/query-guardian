package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.DemoMapping
import com.loveqoo.queryguardian.exec.DemoTableMapping
import com.loveqoo.queryguardian.exec.DemoTableResolver.Companion.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** spec 008 §2.7-3: demo_table_map은 매핑표이면서 실행 허용목록이다 — 총체성·식별자 위생 회귀. */
class DemoTableMapTest {

    private val mappings = listOf(
        DemoTableMapping(1, "users", "demo_users"),
        DemoTableMapping(2, "marketing_consents", "demo_marketing_consents"),
        DemoTableMapping(3, "user_events", "demo_user_events"),
    )

    @Test
    fun `전부 매핑되면 물리명으로 해석된다`() {
        val result = resolve(mappings, setOf("users", "user_events"))
        assertEquals(
            DemoMapping.Resolved(mapOf("users" to "demo_users", "user_events" to "demo_user_events")),
            result,
        )
    }

    @Test
    fun `대소문자는 무시한다`() {
        assertIs<DemoMapping.Resolved>(resolve(mappings, setOf("Users", "USER_EVENTS")))
    }

    /**
     * 부분 매핑을 허용하면 미매핑 테이블이 원래 이름으로 실행돼 **실재하는 거버넌스 테이블을 직격**한다
     * (`SELECT tree_json FROM rule`). 그래서 하나라도 빠지면 전체 거부다.
     */
    @Test
    fun `미매핑이 하나라도 있으면 거부한다`() {
        val result = resolve(mappings, setOf("users", "rule"))
        assertEquals(DemoMapping.Incomplete(listOf("rule")), result)
    }

    @Test
    fun `테이블 0개는 거부한다`() {
        assertEquals(DemoMapping.Empty, resolve(mappings, emptySet()))
    }

    @Test
    fun `식별자 위생을 위반한 물리명은 거부한다`() {
        val poisoned = mappings + DemoTableMapping(4, "orders", "demo_orders; DROP TABLE app_user --")
        assertIs<DemoMapping.Invalid>(resolve(poisoned, setOf("orders")))
        assertIs<DemoMapping.Invalid>(resolve(listOf(DemoTableMapping(5, "t", "back`tick")), setOf("t")))
        assertIs<DemoMapping.Invalid>(resolve(listOf(DemoTableMapping(6, "t", "")), setOf("t")))
    }
}
