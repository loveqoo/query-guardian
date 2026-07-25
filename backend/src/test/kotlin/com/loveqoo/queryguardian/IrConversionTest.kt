package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.ScopeKind
import com.loveqoo.queryguardian.parser.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Druid AST → IR 변환 코퍼스: 조인·서브쿼리·CTE·UNION·별칭 (spec §12). */
class IrConversionTest {

    private fun ir(sql: String): QueryIR {
        val result = Fixtures.parser.parse(sql)
        assertIs<ParseResult.Success>(result, "파싱 실패: $sql — $result")
        return result.ir
    }

    @Test
    fun `조인 - 테이블과 별칭 수집`() {
        val root = ir("SELECT e.id FROM user_events e JOIN dims d ON d.k = e.k WHERE e.event_date = '2026-01-01'").root
        assertEquals(listOf("user_events" to "e", "dims" to "d"), root.tables.map { it.name to it.alias })
    }

    @Test
    fun `최상위 AND만 평탄화, OR 트리는 보존`() {
        val root = ir("SELECT id FROM orders WHERE a = 1 AND b = 2 AND (c = 3 OR d = 4)").root
        assertEquals(3, root.whereConjuncts.size)
        assertIs<Predicate.Or>(root.whereConjuncts[2])
    }

    @Test
    fun `파생 테이블은 DERIVED 자식 스코프`() {
        val root = ir("SELECT id FROM (SELECT id FROM user_events) t").root
        val child = root.children.single()
        assertEquals(ScopeKind.DERIVED, child.kind)
        assertEquals("user_events", child.tables.single().name)
        // 파생 테이블 alias는 물리 테이블이 아니다 — 부모 스코프 테이블명은 alias 자신
        assertEquals("t", root.tables.single().name)
    }

    @Test
    fun `CTE 본문은 CTE 자식 스코프`() {
        val root = ir("WITH x AS (SELECT id FROM user_events) SELECT id FROM x").root
        assertTrue(root.children.any { it.kind == ScopeKind.CTE && it.tables.single().name == "user_events" })
    }

    @Test
    fun `UNION은 팔별 UNION_ARM 스코프`() {
        val root = ir("SELECT id FROM a1 UNION ALL SELECT id FROM a2 UNION SELECT id FROM a3").root
        assertEquals(3, root.children.size)
        assertTrue(root.children.all { it.kind == ScopeKind.UNION_ARM })
        assertEquals(listOf("a1", "a2", "a3"), root.children.map { it.tables.single().name })
    }

    @Test
    fun `EXISTS 서브쿼리는 EXISTS 스코프`() {
        val root = ir("SELECT o.id FROM orders o WHERE EXISTS (SELECT * FROM audit a WHERE a.oid = o.id)").root
        assertEquals(ScopeKind.EXISTS, root.children.single().kind)
    }

    @Test
    fun `LIMIT 캡처`() {
        assertEquals(10L, ir("SELECT id FROM orders LIMIT 10").root.limit)
        assertEquals(null, ir("SELECT id FROM orders").root.limit)
    }

    @Test
    fun `select item 스칼라 서브쿼리도 스코프로 등록`() {
        val root = ir("SELECT (SELECT MAX(id) FROM user_events) AS m FROM orders").root
        assertNotNull(root.children.singleOrNull { it.kind == ScopeKind.SUBQUERY })
    }

    /**
     * 적대 검토 MEDIUM: `number.toLong()`이 BigInteger 하위 64비트만 취해
     * `LIMIT 18446744073709551621`(=2^64+5)이 IR에서 **5**로 보였다 — 판정·표시·승인 화면이 실제 SQL과
     * 다른 숫자를 본다. Long 범위를 넘으면 미지정(null)으로 두어 상한이 그대로 적용되게 한다.
     */
    @Test
    fun `Long 범위를 넘는 LIMIT은 미지정으로 둔다`() {
        assertEquals(null, ir("SELECT id FROM users LIMIT 18446744073709551621").root.limit)
        assertEquals(5L, ir("SELECT id FROM users LIMIT 5").root.limit)
    }
}
