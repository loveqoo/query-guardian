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

/**
 * spec 002 §7 E2E(백엔드 구간): 제약 정의 사전 → 컬럼 매핑 → 판정(파티션·FILTER·BLOCK) → 카탈로그 무결성.
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

        var partitionDefId = 0L
        var filterDefId = 0L
        var blockDefId = 0L
        var eventDateColId = 0L
        var consentColId = 0L
        var ssnColId = 0L
        var requestId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private val client by lazy { SessionClient(rest) }

    /** 무인증 호출은 없다 — 카탈로그/lint 등 공통 경로는 ADMIN 세션으로 (spec 007 §4·H6). */
    private fun post(path: String, body: Map<String, Any?>) = client.postAs(path, "adm1", body)

    /** 세션 주체가 의미를 갖는 API용 (spec 007 §10 — 시그니처 유지). */
    private fun postAs(path: String, actor: String, body: Any? = null) = client.postAs(path, actor, body)

    /** user_events + users를 커버하는 승인 요청을 만들고 승인까지 완료한다 (spec 005 H8 — 저장 게이트 선행 조건). */
    private fun createApprovedRequest() {
        val created = postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "통합 테스트", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "user_events"), mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to listOf("marketing"),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1")),
        ))
        requestId = ((created.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()
        postAs("/api/approvals/$requestId/approve", "ap1")
    }

    private fun columnId(table: Map<*, *>, name: String): Long =
        ((table["columns"] as List<*>).first { (it as Map<*, *>)["name"] == name } as Map<*, *>)
            .let { (it["id"] as Number).toLong() }

    @Test
    @Order(1)
    fun `카탈로그 구축 - 정의 사전과 매핑`() {
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅 조회"))

        val userEvents = post("/api/catalog/tables", mapOf(
            "name" to "user_events",
            "columns" to listOf(
                mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
                mapOf("name" to "event_date", "type" to "DATE", "isPii" to false),
                mapOf("name" to "consent_yn", "type" to "CHAR(1)", "isPii" to false),
            ),
        )).body!!
        val users = post("/api/catalog/tables", mapOf(
            "name" to "users",
            "columns" to listOf(
                mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
                mapOf("name" to "email", "type" to "VARCHAR(255)", "isPii" to true),
                mapOf("name" to "ssn", "type" to "CHAR(13)", "isPii" to true),
            ),
        )).body!!
        eventDateColId = columnId(userEvents, "event_date")
        consentColId = columnId(userEvents, "consent_yn")
        ssnColId = columnId(users, "ssn")

        // 클래스 자동 판별 확인: DATE → DATETIME, CHAR → STRING, isPii → PII
        assertEquals("DATETIME", ((userEvents["columns"] as List<*>).first { (it as Map<*, *>)["name"] == "event_date" } as Map<*, *>)["cls"])
        assertEquals("PII", ((users["columns"] as List<*>).first { (it as Map<*, *>)["name"] == "ssn" } as Map<*, *>)["cls"])

        partitionDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "DATETIME", "kind" to "PARTITION", "name" to "파티션 키 필수",
        )).body!!["id"] as Number).toLong()
        filterDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "마케팅 동의 필수",
            "expression" to "{col} = 'Y'",
        )).body!!["id"] as Number).toLong()
        blockDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "PII", "kind" to "BLOCK", "name" to "조회 전면 차단",
        )).body!!["id"] as Number).toLong()

        assertEquals(HttpStatus.CREATED, post("/api/catalog/mappings",
            mapOf("columnId" to eventDateColId, "defId" to partitionDefId)).statusCode)
        assertEquals(HttpStatus.CREATED, post("/api/catalog/mappings",
            mapOf("columnId" to consentColId, "defId" to filterDefId, "purposeCode" to "marketing")).statusCode)
        assertEquals(HttpStatus.CREATED, post("/api/catalog/mappings",
            mapOf("columnId" to ssnColId, "defId" to blockDefId)).statusCode)
    }

    @Test
    @Order(2)
    fun `대표 시나리오 - 신모델 경로로 차단과 통과`() {
        createApprovedRequest() // 저장 게이트는 승인된 요청을 요구한다 (spec 005 §4)

        val noPartition = postAs("/api/queries", "u1", mapOf(
            "name" to "잘못된 쿼리", "dialect" to "MYSQL", "requestId" to requestId,
            "sql" to "SELECT id FROM user_events LIMIT 10"))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noPartition.statusCode) // 룰 선행(H4)
        assertTrue(noPartition.body!!["violations"].toString().contains("require-partition-key"))

        // purposeCode는 클라이언트가 안 보낸다 — 요청의 purpose_code(marketing)에서 서버가 주입 (C1)
        val noConsent = postAs("/api/queries", "u1", mapOf(
            "name" to "동의 누락", "dialect" to "MYSQL", "requestId" to requestId,
            "sql" to "SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10"))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noConsent.statusCode)
        assertTrue(noConsent.body!!["violations"].toString().contains("require-predicate"))

        val ok = postAs("/api/queries", "u1", mapOf(
            "name" to "정상 쿼리", "dialect" to "MYSQL", "requestId" to requestId,
            "sql" to "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND consent_yn = 'Y' LIMIT 10"))
        assertEquals(HttpStatus.CREATED, ok.statusCode)
        assertEquals("PENDING_REVIEW", ok.body!!["reviewStatus"])

        val ssnBlocked = post("/api/lint", mapOf(
            "dialect" to "MYSQL", "sql" to "SELECT COUNT(ssn) FROM users LIMIT 10"))
        assertEquals(true, ssnBlocked.body!!["blocked"])
        assertTrue(ssnBlocked.body!!["violations"].toString().contains("no-blocked-column"))
    }

    @Test
    @Order(3)
    fun `정의 등록 검증 - 파싱·서브쿼리·col 누락`() {
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "col 누락", "expression" to "status = 'OPEN'")).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/defs", mapOf(
            "cls" to "KEY", "kind" to "FILTER", "name" to "서브쿼리",
            "expression" to "{col} IN (SELECT id FROM x)")).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "파싱 불가", "expression" to "{col} === !!")).statusCode)
    }

    @Test
    @Order(4)
    fun `매핑 검증 - 클래스 불일치·판정 미지원·중복·삭제 가드`() {
        // 클래스 불일치: STRING 정의를 DATETIME 컬럼(event_date)에 → 400
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/mappings",
            mapOf("columnId" to eventDateColId, "defId" to filterDefId)).statusCode)

        // 판정 미지원 FILTER: 등록은 201, 매핑은 400 (C2)
        val rangeDef = post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "기간 필터(미지원 형태)",
            "expression" to "{col} >= ':start'"))
        assertEquals(HttpStatus.CREATED, rangeDef.statusCode)
        // 지원 불가 형태의 대표: 부등호 비교는 §6.5 닫힌 목록 밖
        val unsupportedDefId = (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to "FILTER", "name" to "부등호(미지원)",
            "expression" to "{col} <> 'N'")).body!!["id"] as Number).toLong()
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/catalog/mappings",
            mapOf("columnId" to consentColId, "defId" to unsupportedDefId)).statusCode)

        // 중복 매핑 → 409
        assertEquals(HttpStatus.CONFLICT, post("/api/catalog/mappings",
            mapOf("columnId" to ssnColId, "defId" to blockDefId)).statusCode)

        // 매핑 있는 정의 삭제 → 409
        val defDelete = client.deleteAs("/api/catalog/defs/$blockDefId", "adm1")
        assertEquals(HttpStatus.CONFLICT, defDelete.statusCode)

        // purpose 참조 삭제 → 409
        val purposeId = (client.getListAs("/api/catalog/purposes", "adm1")
            .body!!.filterIsInstance<Map<*, *>>().first { it["code"] == "marketing" }["id"] as Number).toLong()
        val purposeDelete = client.deleteAs("/api/catalog/purposes/$purposeId", "adm1")
        assertEquals(HttpStatus.CONFLICT, purposeDelete.statusCode)
    }

    @Test
    @Order(5)
    fun `LIMIT 상한과 스키마 사전`() {
        val over = post("/api/lint", mapOf("dialect" to "MYSQL", "sql" to "SELECT id FROM users LIMIT 5000"))
        assertTrue(over.body!!["violations"].toString().contains("require-limit"))

        val schema = client.getAs("/api/catalog/schema", "adm1")
        assertTrue((schema.body!!["user_events"] as List<*>).contains("event_date"))
    }
}
