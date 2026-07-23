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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * spec §12 E2E(백엔드 구간): 카탈로그 등록 → lint → 저장 게이트 → 목록/수정/삭제.
 * 대표 시나리오 2종(파티션 키, 필수 술어)의 차단·통과를 API 레벨에서 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class QueryFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private fun setupCatalog() {
        rest.postForEntity("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅 조회"), Map::class.java)
        val table = rest.postForEntity(
            "/api/catalog/tables",
            mapOf(
                "name" to "user_events",
                "description" to "유저 이벤트",
                "columns" to listOf(
                    mapOf("name" to "id", "type" to "BIGINT"),
                    mapOf("name" to "event_date", "type" to "DATE"),
                    mapOf("name" to "consent_yn", "type" to "CHAR(1)"),
                ),
            ),
            Map::class.java,
        )
        val tableId = (table.body!!["id"] as Number).toLong()
        rest.postForEntity(
            "/api/catalog/tables/$tableId/constraints",
            mapOf("kind" to "PARTITION_KEY", "columnName" to "event_date"),
            Map::class.java,
        )
        rest.postForEntity(
            "/api/catalog/tables/$tableId/constraints",
            mapOf("kind" to "REQUIRED_PREDICATE", "predicateSql" to "consent_yn = 'Y'", "purposeCode" to "marketing"),
            Map::class.java,
        )
    }

    @Test
    @Order(1)
    fun `대표 시나리오 - 차단과 통과`() {
        setupCatalog()

        // 파티션 키 누락 → 422
        val blocked = rest.postForEntity(
            "/api/queries",
            mapOf("name" to "잘못된 쿼리", "dialect" to "MYSQL", "sql" to "SELECT id FROM user_events LIMIT 10"),
            Map::class.java,
        )
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.statusCode)
        assertTrue(blocked.body!!["violations"].toString().contains("require-partition-key"))

        // 필수 술어 누락(marketing) → 422
        val noConsent = rest.postForEntity(
            "/api/queries",
            mapOf(
                "name" to "동의 누락", "dialect" to "MYSQL", "purposeCode" to "marketing",
                "sql" to "SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10",
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noConsent.statusCode)
        assertTrue(noConsent.body!!["violations"].toString().contains("require-predicate"))

        // 조건 충족 → 201
        val ok = rest.postForEntity(
            "/api/queries",
            mapOf(
                "name" to "정상 쿼리", "dialect" to "MYSQL", "purposeCode" to "marketing",
                "sql" to "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND consent_yn = 'Y' LIMIT 10",
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, ok.statusCode)
        assertEquals(false, (ok.body!!["lintReport"] as Map<*, *>)["blocked"])
    }

    @Test
    @Order(2)
    fun `lint는 저장 없이 위반을 알려준다`() {
        val report = rest.postForEntity(
            "/api/lint",
            mapOf("dialect" to "MYSQL", "sql" to "SELECT * FROM user_events"),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, report.statusCode)
        assertEquals(true, report.body!!["blocked"])
    }

    @Test
    @Order(3)
    fun `게이트 입력 검증 - dialect와 purpose`() {
        val badDialect = rest.postForEntity(
            "/api/lint", mapOf("dialect" to "ORACLE", "sql" to "SELECT 1"), Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, badDialect.statusCode)

        val badPurpose = rest.postForEntity(
            "/api/lint",
            mapOf("dialect" to "MYSQL", "sql" to "SELECT 1", "purposeCode" to "marketng"),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, badPurpose.statusCode)
    }

    @Test
    @Order(4)
    fun `수정도 동일 게이트, 삭제와 목록`() {
        val list = rest.getForEntity("/api/queries", List::class.java)
        assertEquals(HttpStatus.OK, list.statusCode)
        val saved = list.body!!.filterIsInstance<Map<*, *>>().first { it["name"] == "정상 쿼리" }
        val id = (saved["id"] as Number).toLong()

        // 위반 쿼리로 수정 시도 → 422, 원본 유지
        val badUpdate = rest.exchange(
            org.springframework.http.RequestEntity
                .put(java.net.URI("/api/queries/$id"))
                .body(mapOf("name" to "정상 쿼리", "dialect" to "MYSQL", "sql" to "SELECT * FROM user_events")),
            Map::class.java,
        )
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badUpdate.statusCode)
        val detail = rest.getForEntity("/api/queries/$id", Map::class.java)
        assertTrue(detail.body!!["sql"].toString().contains("consent_yn"))

        // 스키마 사전
        val schema = rest.getForEntity("/api/catalog/schema", Map::class.java)
        assertNotNull(schema.body!!["user_events"])

        rest.delete("/api/queries/$id")
        val after = rest.getForEntity("/api/queries/$id", Map::class.java)
        assertEquals(HttpStatus.NOT_FOUND, after.statusCode)
    }

    @Test
    @Order(5)
    fun `등록 불가 술어는 카탈로그가 거부한다`() {
        val tableId = (rest.getForEntity("/api/catalog/tables", List::class.java)
            .body!!.filterIsInstance<Map<*, *>>().first()["id"] as Number).toLong()
        val rejected = rest.postForEntity(
            "/api/catalog/tables/$tableId/constraints",
            mapOf("kind" to "REQUIRED_PREDICATE", "predicateSql" to "consent_yn != 'N'"),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, rejected.statusCode)
    }
}
