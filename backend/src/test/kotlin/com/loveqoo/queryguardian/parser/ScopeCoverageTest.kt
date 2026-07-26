package com.loveqoo.queryguardian.parser

import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **구조 불변식: AST의 모든 쿼리 노드가 정확히 하나의 스코프로 등록된다.**
 *
 * 이 테스트가 존재하는 이유: 스코프 수집을 절별로 챙기는 방식은 계속 샌다. select 목록·WHERE는 챙겼지만
 * ORDER BY·GROUP BY는 빠져 있었고, 그 구멍으로 `ORDER BY (SELECT u.ssn LIKE '90%')`가 주민번호 불리언
 * 오라클이 됐다(적대 검토 CRITICAL 4, MySQL 실측). 등록되지 않은 스코프는 **권한·BLOCK·마스킹·필수조건·
 * 매핑 허용목록이 전부 무발화**하는 완전 사각이다.
 *
 * 그래서 "아는 절을 하나씩 추가"가 아니라 **AST 전체와 등록부를 대조**한다 — 새 절을 빠뜨리면 여기서 깨진다.
 */
class ScopeCoverageTest {

    private val parser = DruidMySqlParser()

    /** 쿼리 노드가 등장할 수 있는 위치를 최대한 넓게 덮는 코퍼스. */
    private val corpus = listOf(
        "select 목록의 스칼라 서브쿼리" to
            "SELECT u.id, (SELECT MAX(id) FROM user_events) AS m FROM users u",
        "select 목록의 상관 서브쿼리" to
            "SELECT u.id, (SELECT u.email) AS e FROM users u",
        "WHERE의 IN 서브쿼리" to
            "SELECT id FROM users WHERE id IN (SELECT id FROM user_events)",
        "WHERE의 EXISTS" to
            "SELECT id FROM users u WHERE EXISTS (SELECT 1 FROM user_events e WHERE e.id = u.id)",
        "WHERE의 NOT EXISTS" to
            "SELECT id FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_events e WHERE e.id = u.id)",
        "WHERE 비교 우변의 서브쿼리" to
            "SELECT id FROM users WHERE id = (SELECT MAX(id) FROM user_events)",
        "FROM 파생 테이블" to
            "SELECT d.id FROM (SELECT id FROM users) d",
        "FROM 파생 UNION" to
            "SELECT d.id FROM (SELECT id FROM users UNION ALL SELECT id FROM user_events) d",
        "CTE" to
            "WITH c AS (SELECT id FROM users) SELECT id FROM c",
        "다중 CTE" to
            "WITH a AS (SELECT id FROM users), b AS (SELECT id FROM a) SELECT id FROM b",
        "UNION 두 팔" to
            "SELECT id FROM users UNION ALL SELECT id FROM user_events",
        "중첩 UNION" to
            "SELECT id FROM users UNION ALL (SELECT id FROM user_events UNION ALL SELECT id FROM orders)",
        "ORDER BY 서브쿼리" to
            "SELECT u.id FROM users u ORDER BY (SELECT COUNT(*) FROM user_events)",
        "GROUP BY 서브쿼리" to
            "SELECT u.id FROM users u GROUP BY u.id, (SELECT COUNT(*) FROM user_events)",
        "HAVING 서브쿼리" to
            "SELECT u.id FROM users u GROUP BY u.id HAVING COUNT(*) > (SELECT COUNT(*) FROM user_events)",
        "INNER JOIN ON의 서브쿼리" to
            "SELECT u.id FROM users u JOIN orders o ON o.id = u.id AND o.id IN (SELECT id FROM user_events)",
        "OUTER JOIN ON의 서브쿼리" to
            "SELECT u.id FROM users u LEFT JOIN orders o ON o.id IN (SELECT id FROM user_events)",
        "함수 인자의 서브쿼리" to
            "SELECT COALESCE((SELECT MAX(id) FROM user_events), 0) AS m FROM users",
        "CASE 안의 서브쿼리" to
            "SELECT CASE WHEN (SELECT COUNT(*) FROM user_events) > 0 THEN 1 ELSE 0 END AS c FROM users",
        "BETWEEN 피연산자의 서브쿼리" to
            "SELECT id FROM users WHERE id BETWEEN (SELECT MIN(id) FROM user_events) AND (SELECT MAX(id) FROM user_events)",
        "깊게 중첩된 파생" to
            "SELECT a.e FROM (SELECT b.e FROM (SELECT email AS e FROM users) b) a",
        "파생 안의 EXISTS" to
            "SELECT d.id FROM (SELECT u.id FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.id = u.id)) d",
    )

    /**
     * AST에 실제로 존재하는 쿼리 노드(블록·UNION)를 **동일성**으로 수집한다.
     * 반드시 **핸들이 들고 있는 그 AST**를 훑어야 한다 — SQL을 다시 파싱하면 다른 객체가 나와 동일성 비교가
     * 무의미해진다(이 테스트를 처음 썼을 때 실제로 그렇게 틀렸고, 전 케이스가 "등록 0건"으로 나왔다).
     */
    private fun queryNodes(statement: SQLSelectStatement): Set<Any> {
        val found = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        statement.accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLSelectQueryBlock): Boolean { found.add(x); return true }
            override fun visit(x: SQLUnionQuery): Boolean { found.add(x); return true }
        })
        return found
    }

    @Test
    fun `모든 쿼리 노드가 스코프로 등록된다`() {
        val gaps = mutableListOf<String>()
        for ((label, sql) in corpus) {
            val result = parser.inspect(sql)
            val handle = (result as? InspectResult.Parsed)?.statement as? DruidMySqlParser.DruidParsedStatement
            if (handle == null) {
                gaps += "$label: 핸들이 없다 (파싱 실패) — $sql"
                continue
            }
            val registered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            registered.addAll(handle.scopeNodes.values)

            val expected = queryNodes(handle.statement)
            // 등록 누락 = 그 스코프의 위반을 아무 룰도 보지 못한다 = 완전 사각.
            // 예외 하나: **중첩 UNION 노드**는 평탄화되어 팔들만 스코프가 된다. 테이블·컬럼·술어는 팔에 있고
            // 연결자 노드에는 LIMIT만 붙을 수 있으므로, LIMIT이 없는 중첩 UNION은 판정 사각이 아니다.
            // (LIMIT을 들고 있으면 평탄화로 그 값을 잃으므로 아래에서 잡는다.)
            val missing = expected.filter { it !in registered }
            val realGaps = missing.filter { node -> node !is SQLUnionQuery || node.limit != null }
            if (realGaps.isNotEmpty()) {
                gaps += "$label: 쿼리 노드 ${expected.size}개 중 ${realGaps.size}개가 스코프로 등록되지 않았다 " +
                    "(${realGaps.map { it.javaClass.simpleName }}) — $sql"
            }
            // 중복 등록 = 같은 스코프가 두 번 판정된다(중복 위반 보고)
            if (handle.scopeNodes.values.size != registered.size) {
                gaps += "$label: 같은 노드가 두 번 등록됐다 (${handle.scopeNodes.size}개 id / ${registered.size}개 노드) — $sql"
            }
        }
        assertTrue(gaps.isEmpty(), "스코프 커버리지 구멍 ${gaps.size}건:\n${gaps.joinToString("\n")}")
    }
}
