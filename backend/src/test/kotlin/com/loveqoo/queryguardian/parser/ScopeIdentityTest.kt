package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.SelectScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * spec 008 §3.5 M1-1의 계약: 재작성 계획이 스코프를 지목할 수 있어야 한다.
 *
 * ⑴ 모든 스코프에 **유일한** `scopeId`가 있다 — 중복이 있으면 계획이 엉뚱한 스코프를 고친다.
 * ⑵ 핸들의 `scopeIds`가 IR의 id 집합과 **정확히 일치**한다 — 한쪽에만 있는 id는 "계획은 세웠는데 고칠 노드가
 *    없다"(누락) 또는 "판정되지 않은 노드를 고친다"(과잉)를 뜻하고, 둘 다 판정-실행 분기다.
 */
class ScopeIdentityTest {

    private val parser = DruidMySqlParser()

    private fun allScopes(scope: SelectScope): List<SelectScope> =
        listOf(scope) + scope.children.flatMap { allScopes(it) }

    /** 루트 + CTE + 파생 + 파생 UNION(팔 2개) + IN 서브쿼리 + EXISTS — 스코프 종류를 한 번에 덮는다. */
    private val nested = """
        WITH recent AS (SELECT id FROM user_events WHERE event_date = '2026-01-01')
        SELECT r.id, d.c
        FROM recent r
          JOIN (SELECT id AS c FROM users UNION ALL SELECT id AS c FROM users) d ON d.c = r.id
        WHERE r.id IN (SELECT id FROM users)
          AND EXISTS (SELECT 1 FROM users u WHERE u.id = r.id)
        LIMIT 10
    """.trimIndent()

    @Test
    fun `모든 스코프에 유일한 식별자가 있다`() {
        val result = parser.inspect(nested)
        val ir = (result.parse as ParseResult.Success).ir
        val scopes = allScopes(ir.root)

        assertTrue(scopes.size >= 7, "중첩 스코프가 모두 만들어져야 함: ${scopes.size}개 — ${scopes.map { it.kind }}")
        assertTrue(scopes.none { it.scopeId.isBlank() }, "빈 scopeId가 있음: ${scopes.map { it.kind to it.scopeId }}")
        assertEquals(
            scopes.size,
            scopes.map { it.scopeId }.distinct().size,
            "scopeId가 중복됨: ${scopes.map { it.kind to it.scopeId }}",
        )
    }

    @Test
    fun `핸들의 식별자 집합이 IR과 정확히 일치한다`() {
        val result = parser.inspect(nested)
        val ir = (result.parse as ParseResult.Success).ir
        val handle = assertNotNull(result.statement, "파싱 성공 시 핸들이 있어야 함")

        assertEquals(allScopes(ir.root).map { it.scopeId }.toSet(), handle.scopeIds)
    }

    @Test
    fun `파싱 실패에는 핸들이 없다`() {
        val result = parser.inspect("SELECT FROM WHERE ((")
        assertTrue(result.parse is ParseResult.Failure)
        assertEquals(null, result.statement, "고칠 AST가 없으면 핸들도 없어야 한다")
    }

    /**
     * **다른 파싱의 id는 절대 겹치지 않는다.** 순번만 쓰면 모든 파싱이 `s0`부터 시작해 계획 A를 핸들 B에
     * 적용해도 짝 검증이 통과했고, 적대 검토가 그 경로로 평문이 나가는 것을 실증했다.
     * spec 008 결정 13("판정-실행 분기를 구조적으로 제거")은 이 성질 위에서만 성립한다.
     */
    @Test
    fun `다른 파싱의 스코프 id는 겹치지 않는다`() {
        val a = parser.inspect(nested)
        val b = parser.inspect(nested)
        assertTrue(a.statement !== b.statement, "핸들은 파싱마다 별개다")
        assertEquals(
            emptySet(),
            a.statement!!.scopeIds intersect b.statement!!.scopeIds,
            "같은 SQL이라도 파싱이 다르면 id가 겹쳐선 안 된다",
        )
    }
}
