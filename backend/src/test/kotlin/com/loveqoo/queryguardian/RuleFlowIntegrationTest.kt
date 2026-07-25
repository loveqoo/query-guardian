package com.loveqoo.queryguardian

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec 004 §9 E2E: 규칙 CRUD → 판정(requires/joins) → 매핑 삭제 가드 → 위반 통계. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RuleFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var consentColId = 0L
        var filterDefId = 0L
        var ruleId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private fun post(path: String, body: Any) = rest.postForEntity(path, body, Map::class.java)

    @Test
    @Order(1)
    fun `카탈로그 - marketing_consents + consent FILTER 매핑`() {
        val mc = post("/api/catalog/tables", mapOf(
            "name" to "marketing_consents",
            "columns" to listOf(
                mapOf("name" to "user_id", "type" to "BIGINT", "isPii" to false),
                mapOf("name" to "consent_yn", "type" to "CHAR(1)", "isPii" to false),
            ),
        )).body!!
        post("/api/catalog/tables", mapOf(
            "name" to "users",
            "columns" to listOf(mapOf("name" to "id", "type" to "BIGINT", "isPii" to false)),
        ))
        consentColId = ((mc["columns"] as List<*>).first { (it as Map<*, *>)["name"] == "consent_yn" } as Map<*, *>)
            .let { (it["id"] as Number).toLong() }
        filterDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "동의 필수", "expression" to "{col} = 'Y'",
        )).body!!["id"] as Number).toLong()
        assertEquals(HttpStatus.CREATED, post("/api/catalog/mappings",
            mapOf("columnId" to consentColId, "defId" to filterDefId)).statusCode)
    }

    @Test
    @Order(2)
    fun `규칙 생성 - MULTI 조인 플러스 동의 필수`() {
        val tree = mapOf("node" to "group", "combinator" to "all", "children" to listOf(
            mapOf("node" to "cond", "op" to "joins", "severity" to "BLOCK",
                "table" to "marketing_consents", "column" to "user_id", "refTable" to "users", "refColumn" to "id"),
            mapOf("node" to "cond", "op" to "requires", "severity" to "BLOCK",
                "table" to "marketing_consents", "column" to "consent_yn", "defId" to filterDefId),
        ))
        val r = post("/api/rules", mapOf("name" to "마케팅 동의 한정", "scope" to "MULTI", "tree" to tree))
        assertEquals(HttpStatus.CREATED, r.statusCode)
        ruleId = (r.body!!["id"] as Number).toLong()
        assertEquals(true, r.body!!["corrupt"] == false || r.body!!["corrupt"] == null)
    }

    @Test
    @Order(3)
    fun `판정 - 조인+동의 충족 통과, 누락 차단`() {
        val ok = post("/api/lint", mapOf("dialect" to "MYSQL",
            "sql" to "SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id WHERE mc.consent_yn = 'Y' LIMIT 10"))
        assertEquals(false, ok.body!!["blocked"])

        val noConsent = post("/api/lint", mapOf("dialect" to "MYSQL",
            "sql" to "SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id LIMIT 10"))
        assertEquals(true, noConsent.body!!["blocked"])
        assertTrue(noConsent.body!!["violations"].toString().contains("rule/$ruleId"))

        val leftJoin = post("/api/lint", mapOf("dialect" to "MYSQL",
            "sql" to "SELECT u.id FROM users u LEFT JOIN marketing_consents mc ON mc.user_id = u.id WHERE mc.consent_yn = 'Y' LIMIT 10"))
        assertEquals(true, leftJoin.body!!["blocked"]) // LEFT JOIN → joins 미충족 (C1)
    }

    @Test
    @Order(4)
    fun `등록 검증 - 매핑 안 된 defId·판정 불가 requires 거부`() {
        val unmapped = mapOf("node" to "group", "combinator" to "all", "children" to listOf(
            mapOf("node" to "cond", "op" to "requires", "severity" to "BLOCK",
                "table" to "marketing_consents", "column" to "consent_yn", "defId" to 99999),
        ))
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/rules",
            mapOf("name" to "잘못된 참조", "scope" to "SINGLE", "tree" to unmapped)).statusCode)

        val emptyGroup = mapOf("node" to "group", "combinator" to "all", "children" to emptyList<Any>())
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/rules",
            mapOf("name" to "빈 그룹", "scope" to "SINGLE", "tree" to emptyGroup)).statusCode)
    }

    @Test
    @Order(5)
    fun `매핑 삭제 역참조 가드 - 규칙이 참조 중이면 409`() {
        val mappingId = (rest.getForEntity("/api/catalog/mappings?columnId=$consentColId", List::class.java)
            .body!!.filterIsInstance<Map<*, *>>().first()["id"] as Number).toLong()
        val del = rest.exchange(org.springframework.http.RequestEntity
            .delete(java.net.URI("/api/catalog/mappings/$mappingId")).build(), Map::class.java)
        assertEquals(HttpStatus.CONFLICT, del.statusCode)
    }

    @Test
    @Order(6)
    fun `위반 통계 - 저장 시도 차단 시 hit 증가`() {
        // spec 005: 저장은 승인 요청을 요구하지만, 룰 게이트가 선행하므로 요청 없이도 422 + hit 증가여야 한다 (H4)
        val before = ruleHits()
        val res = rest.exchange(
            org.springframework.http.RequestEntity.post(java.net.URI("/api/queries"))
                .header("X-QG-Actor", "u1").header("Content-Type", "application/json")
                .body(mapOf("name" to "위반쿼리", "dialect" to "MYSQL",
                    "sql" to "SELECT u.id FROM marketing_consents mc JOIN users u ON mc.user_id = u.id LIMIT 10")),
            Map::class.java)
        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, res.statusCode) // 룰 선행
        assertTrue(ruleHits() > before, "저장 시도 위반 후 hit이 증가해야 함")
    }

    private fun ruleHits(): Long =
        (rest.getForEntity("/api/rules", List::class.java).body!!
            .filterIsInstance<Map<*, *>>().first { (it["id"] as Number).toLong() == ruleId }["hits"] as Number).toLong()
}
