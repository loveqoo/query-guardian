package com.loveqoo.queryguardian

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 006 — 테스트 부채 상환. 적대 검토가 지적해 코드로는 막았지만 테스트로 고정되지 않았던 경로들.
 * T1 판정불가 requires 400 / T2 손상 규칙 격리 / T3 dangling fail-closed /
 * T4 동시 승인 경합 / T5 규칙 드리프트 배지 / T6 검토 전 재-lint 차단.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TestDebtIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var consentColId = 0L
        var filterDefId = 0L
        var goodRuleId = 0L
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun post(path: String, body: Any) = rest.postForEntity(path, body, Map::class.java)
    private fun postAs(path: String, actor: String, body: Any? = null) = rest.exchange(
        RequestEntity.post(URI(path)).header("X-QG-Actor", actor)
            .header("Content-Type", "application/json").body(body ?: emptyMap<String, Any>()),
        Map::class.java)

    private fun cond(op: String, table: String, column: String, defId: Long?) = buildMap {
        put("node", "cond"); put("op", op); put("severity", "BLOCK")
        put("table", table); put("column", column); if (defId != null) put("defId", defId)
    }

    private fun tree(vararg children: Any) = mapOf("node" to "group", "combinator" to "all", "children" to children.toList())

    @Test
    @Order(1)
    fun `준비 - 카탈로그와 정상 규칙`() {
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅"))
        val mc = post("/api/catalog/tables", mapOf("name" to "marketing_consents", "columns" to listOf(
            mapOf("name" to "user_id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "consent_yn", "type" to "CHAR(1)", "isPii" to false)))).body!!
        post("/api/catalog/tables", mapOf("name" to "users", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false))))
        consentColId = ((mc["columns"] as List<*>).first { (it as Map<*, *>)["name"] == "consent_yn" } as Map<*, *>)
            .let { (it["id"] as Number).toLong() }

        filterDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "동의 필수", "expression" to "{col} = 'Y'"))
            .body!!["id"] as Number).toLong()
        post("/api/catalog/mappings", mapOf("columnId" to consentColId, "defId" to filterDefId))

        goodRuleId = (post("/api/rules", mapOf("name" to "동의 규칙", "scope" to "SINGLE",
            "tree" to tree(cond("requires", "marketing_consents", "consent_yn", filterDefId))))
            .body!!["id"] as Number).toLong()
    }

    /** T1 (spec 004 H3): 매핑은 됐지만 판정 불가 형태(`<>`)를 requires로 참조 → 400. */
    @Test
    @Order(2)
    fun `T1 - 판정 불가 형태를 requires로 참조하면 400`() {
        val neqDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "부등호(판정 불가)", "expression" to "{col} <> 'N'"))
            .body!!["id"] as Number).toLong()
        // 매핑 자체가 거부되므로(spec 002 C2) 규칙 조건도 당연히 등록 불가 — 두 경로 모두 확인
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/mappings",
            mapOf("columnId" to consentColId, "defId" to neqDefId)).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/rules", mapOf(
            "name" to "판정불가 참조", "scope" to "SINGLE",
            "tree" to tree(cond("requires", "marketing_consents", "consent_yn", neqDefId)))).statusCode)
    }

    /** T2 (spec 004 H6): tree_json 손상 → 해당 규칙만 corrupt로 격리, 다른 규칙·시스템 룰 정상. */
    @Test
    @Order(3)
    fun `T2 - 손상된 규칙은 격리되고 나머지는 정상 동작`() {
        val brokenId = (post("/api/rules", mapOf("name" to "곧 손상될 규칙", "scope" to "SINGLE",
            "tree" to tree(cond("blocks", "users", "id", null)))).body!!["id"] as Number).toLong()
        // DB에서 직접 손상시킨다 (미지 op — 역직렬화 실패)
        jdbc.update("UPDATE rule SET tree_json = ? WHERE id = ?",
            """{"node":"group","combinator":"all","children":[{"node":"cond","op":"frobnicate","severity":"BLOCK"}]}""",
            brokenId)

        val rules = rest.getForEntity("/api/rules", List::class.java).body!!.filterIsInstance<Map<*, *>>()
        assertEquals(true, rules.first { (it["id"] as Number).toLong() == brokenId }["corrupt"])
        assertEquals(false, rules.first { (it["id"] as Number).toLong() == goodRuleId }["corrupt"])

        // 손상 규칙이 있어도 lint는 죽지 않고, 정상 규칙 판정은 그대로 발화한다
        val report = post("/api/lint", mapOf("dialect" to "MYSQL",
            "sql" to "SELECT user_id FROM marketing_consents LIMIT 10")).body!!
        assertEquals(true, report["blocked"])
        assertTrue(report["violations"].toString().contains("rule/$goodRuleId"))

        jdbc.update("DELETE FROM rule WHERE id = ?", brokenId) // 이후 테스트에 영향 없도록 정리
    }

    /**
     * T3 (spec 004 C4): 컬럼 삭제 → 매핑 연쇄 삭제(역참조 가드 우회 경로) → 규칙 조건 dangling.
     * 사용자 규칙은 defId를 명시 참조하므로 **fail-closed 차단**되어야 한다(조용한 통과 금지).
     */
    @Test
    @Order(4)
    fun `T3 - 컬럼 삭제로 dangling된 조건은 fail-closed 차단`() {
        val sql = "SELECT user_id FROM marketing_consents WHERE consent_yn = 'Y' LIMIT 10"
        // 지금은 조건 충족이라 통과
        assertEquals(false, post("/api/lint", mapOf("dialect" to "MYSQL", "sql" to sql)).body!!["blocked"])

        // consent_yn 컬럼을 빼고 테이블을 수정 → 매핑 연쇄 삭제
        val tableId = (rest.getForEntity("/api/catalog/tables", List::class.java).body!!
            .filterIsInstance<Map<*, *>>().first { it["name"] == "marketing_consents" }["id"] as Number).toLong()
        val updated = rest.exchange(RequestEntity.put(URI("/api/catalog/tables/$tableId"))
            .header("Content-Type", "application/json")
            .body(mapOf("name" to "marketing_consents", "columns" to listOf(
                mapOf("name" to "user_id", "type" to "BIGINT", "isPii" to false)))), Map::class.java)
        assertEquals(HttpStatus.OK, updated.statusCode)

        // 규칙 조건의 defId가 더는 그 컬럼에 매핑되지 않는다 → 평가기가 fail-closed로 차단
        val after = post("/api/lint", mapOf("dialect" to "MYSQL", "sql" to sql)).body!!
        assertEquals(true, after["blocked"], "dangling 조건이 조용히 통과함: $after")
        assertTrue(after["violations"].toString().contains("rule/$goodRuleId"))
    }

    /** T4 (spec 005 C4): 같은 단계 동시 승인 2건 → 하나만 성공, 나머지는 409(단계 미증가). */
    @Test
    @Order(5)
    fun `T4 - 동시 승인 경합은 하나만 성공한다`() {
        val created = postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "동시성 테스트", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"), mapOf("step" to 2, "approverId" to "ap2"))))
        val id = ((created.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()

        val pool = Executors.newFixedThreadPool(2)
        val tasks = List(2) { Callable { postAs("/api/approvals/$id/approve", "ap1").statusCode } }
        val results = pool.invokeAll(tasks).map { it.get() }
        pool.shutdown()

        assertEquals(1, results.count { it == HttpStatus.OK }, "동시 승인 중 하나만 성공해야 함: $results")
        assertEquals(1, results.count { it != HttpStatus.OK }, "나머지는 실패해야 함: $results")

        // 단계는 정확히 1 증가(2단계)이고 요청은 아직 PENDING이어야 한다 — 건너뛰기 없음
        val detail = rest.getForEntity("/api/approvals/$id", Map::class.java).body!!
        val summary = detail["summary"] as Map<*, *>
        assertEquals(2, (summary["currentStep"] as Number).toInt())
        assertEquals("PENDING", summary["status"])
    }

    /** T5 (spec 005 H2): 승인 후 규칙 트리를 편집하면 요청 상세에 변경 배지가 뜬다. */
    @Test
    @Order(6)
    fun `T5 - 승인 후 규칙이 바뀌면 changedSinceApproval`() {
        val created = postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "규칙 드리프트", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to listOf(goodRuleId), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val id = ((created.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()
        postAs("/api/approvals/$id/approve", "ap1")

        fun changed() = (rest.getForEntity("/api/approvals/$id", Map::class.java).body!!["rules"] as List<*>)
            .filterIsInstance<Map<*, *>>().first { (it["ruleId"] as Number).toLong() == goodRuleId }["changedSinceApproval"]
        assertEquals(false, changed())

        // 규칙 트리 편집(조건 severity 변경) → 스냅샷과 달라짐
        rest.exchange(RequestEntity.put(URI("/api/rules/$goodRuleId"))
            .header("Content-Type", "application/json")
            .body(mapOf("name" to "동의 규칙", "scope" to "SINGLE", "tree" to mapOf(
                "node" to "group", "combinator" to "all", "children" to listOf(
             buildMap<String, Any> {
                    put("node", "cond"); put("op", "blocks"); put("severity", "WARN")
                    put("table", "users"); put("column", "id")
                })))), Map::class.java)
        assertEquals(true, changed(), "승인 후 규칙 변경이 배지에 반영되지 않음")
    }

    /** T6 (spec 005 C6): 저장 후 BLOCK 규칙이 추가되면 검토 승인이 409로 막힌다. */
    @Test
    @Order(7)
    fun `T6 - 저장 후 규칙이 강화되면 검토 승인 409`() {
        val req = postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "검토 재lint", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val reqId = ((req.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()
        postAs("/api/approvals/$reqId/approve", "ap1")

        val saved = postAs("/api/queries", "u1", mapOf(
            "name" to "검토 대상", "dialect" to "MYSQL", "requestId" to reqId,
            "sql" to "SELECT id FROM users LIMIT 10"))
        assertEquals(HttpStatus.CREATED, saved.statusCode)
        val queryId = (saved.body!!["id"] as Number).toLong()

        // 저장 후 users.id를 차단하는 규칙 추가 → 같은 쿼리가 이제 BLOCK
        post("/api/rules", mapOf("name" to "id 조회 차단", "scope" to "SINGLE",
            "tree" to tree(cond("blocks", "users", "id", null))))

        val review = postAs("/api/queries/$queryId/review", "u4", mapOf("decision" to "APPROVED"))
        assertEquals(HttpStatus.CONFLICT, review.statusCode, "현재 BLOCK인데 검토 승인이 통과함")
    }
}
