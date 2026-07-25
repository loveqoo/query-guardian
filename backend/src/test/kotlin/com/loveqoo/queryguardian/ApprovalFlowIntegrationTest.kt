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
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 005 §9 E2E: 승인 라인 무결성·순차 승인·저장 게이트(순서·커버·purpose 승계)·검토 생명주기.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApprovalFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var approvedRequestId = 0L
        var queryId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private fun postAs(path: String, actor: String, body: Any? = null) = rest.exchange(
        RequestEntity.post(URI(path)).header("X-QG-Actor", actor)
            .header("Content-Type", "application/json").body(body ?: emptyMap<String, Any>()),
        Map::class.java,
    )

    private fun post(path: String, body: Any) = rest.postForEntity(path, body, Map::class.java)

    private fun requestBody(tables: List<String>, approverIds: List<String>) = mapOf(
        "purposeTitle" to "마케팅 캠페인 대상자 추출", "purposeCode" to "marketing",
        "tables" to tables.map { mapOf("tableName" to it) },
        "ruleIds" to emptyList<Long>(), "businessReqs" to listOf("marketing", "pii"),
        "approvers" to approverIds.mapIndexed { i, n -> mapOf("step" to i + 1, "approverId" to n) },
    )

    private fun idOf(body: Map<*, *>) = ((body["summary"] as Map<*, *>)["id"] as Number).toLong()
    private fun statusOf(body: Map<*, *>) = (body["summary"] as Map<*, *>)["status"]

    @Test
    @Order(1)
    fun `카탈로그 준비`() {
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅"))
        post("/api/catalog/tables", mapOf("name" to "user_events", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "event_date", "type" to "DATE", "isPii" to false))))
        post("/api/catalog/tables", mapOf("name" to "users", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false))))
    }

    @Test
    @Order(2)
    fun `승인 라인 무결성 - 승인자 0명·비연속·중복·미등록 400`() {
        assertEquals(HttpStatus.BAD_REQUEST,
            postAs("/api/approvals", "u1", requestBody(listOf("user_events"), emptyList())).statusCode) // C3
        assertEquals(HttpStatus.BAD_REQUEST, postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "t", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "user_events")),
            "approvers" to listOf(mapOf("step" to 2, "approverId" to "ap1")))).statusCode) // 비연속
        assertEquals(HttpStatus.BAD_REQUEST, postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("ap1", "ap1"))).statusCode) // 중복 인물
        assertEquals(HttpStatus.BAD_REQUEST, postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("nobody"))).statusCode) // 미등록 승인자
        assertEquals(HttpStatus.BAD_REQUEST, postAs("/api/approvals", "u1", requestBody(
            listOf("존재하지_않는_테이블"), listOf("ap1"))).statusCode) // 카탈로그 밖 테이블
    }

    @Test
    @Order(3)
    fun `순차 승인 - 2단계 전이와 순서 강제`() {
        val created = postAs("/api/approvals", "u1", requestBody(
            listOf("user_events", "users"), listOf("ap1", "ap2")))
        assertEquals(HttpStatus.CREATED, created.statusCode)
        val id = idOf(created.body!!)
        assertEquals("PENDING", statusOf(created.body!!))

        // 순서 아닌 승인자 → 409
        assertEquals(HttpStatus.CONFLICT, postAs("/api/approvals/$id/approve", "ap2").statusCode)

        val step1 = postAs("/api/approvals/$id/approve", "ap1")
        assertEquals(HttpStatus.OK, step1.statusCode)
        assertEquals("PENDING", statusOf(step1.body!!)) // 아직 2단계 남음

        val step2 = postAs("/api/approvals/$id/approve", "ap2")
        assertEquals("APPROVED", statusOf(step2.body!!))

        // 이미 결정된 요청 재승인 → 409
        assertEquals(HttpStatus.CONFLICT, postAs("/api/approvals/$id/approve", "ap2").statusCode)
        approvedRequestId = id
    }

    @Test
    @Order(4)
    fun `반려와 취소`() {
        val r1 = idOf(postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("ap1"))).body!!)
        val rejected = postAs("/api/approvals/$r1/reject", "ap1", mapOf("note" to "범위 과다"))
        assertEquals("REJECTED", statusOf(rejected.body!!))

        val r2 = idOf(postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("ap1"))).body!!)
        assertEquals(HttpStatus.CONFLICT, postAs("/api/approvals/$r2/cancel", "u2").statusCode) // 요청자만
        assertEquals("CANCELLED", statusOf(postAs("/api/approvals/$r2/cancel", "u1").body!!))
    }

    @Test
    @Order(5)
    fun `저장 게이트 - 요청 없음·미승인·요청자 불일치 403`() {
        val sql = "SELECT id FROM user_events WHERE event_date = '2026-01-01' LIMIT 10"
        val noReq = postAs("/api/queries", "u1", mapOf("name" to "요청없음", "dialect" to "MYSQL", "sql" to sql))
        assertEquals(HttpStatus.FORBIDDEN, noReq.statusCode)
        assertEquals("NO_REQUEST", noReq.body!!["code"])

        val pending = idOf(postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("ap1"))).body!!)
        val notApproved = postAs("/api/queries", "u1",
            mapOf("name" to "미승인", "dialect" to "MYSQL", "sql" to sql, "requestId" to pending))
        assertEquals(HttpStatus.FORBIDDEN, notApproved.statusCode)
        assertEquals("NOT_APPROVED", notApproved.body!!["code"])

        val mismatch = postAs("/api/queries", "u2",
            mapOf("name" to "남의승인", "dialect" to "MYSQL", "sql" to sql, "requestId" to approvedRequestId))
        assertEquals(HttpStatus.FORBIDDEN, mismatch.statusCode)
        assertEquals("REQUESTER_MISMATCH", mismatch.body!!["code"])
    }

    @Test
    @Order(6)
    fun `게이트 순서 - 룰 위반은 요청 없어도 422가 먼저`() {
        // SELECT * 는 BLOCK 룰 → 요청이 없어도 403이 아니라 422 (H4)
        val res = postAs("/api/queries", "u1",
            mapOf("name" to "룰위반", "dialect" to "MYSQL", "sql" to "SELECT * FROM user_events LIMIT 10"))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.statusCode)
        assertTrue(res.body!!["violations"].toString().contains("no-select-star"))
    }

    @Test
    @Order(7)
    fun `테이블 커버 우회 스위트 - 전부 403`() {
        // 승인 요청은 user_events만 커버하는 새 요청 생성
        val onlyEvents = idOf(postAs("/api/approvals", "u1", requestBody(
            listOf("user_events"), listOf("ap1"))).body!!)
        postAs("/api/approvals/$onlyEvents/approve", "ap1")

        fun save(sql: String) = postAs("/api/queries", "u1",
            mapOf("name" to "커버검사", "dialect" to "MYSQL", "sql" to sql, "requestId" to onlyEvents))

        // CTE 은닉 (C2 핵심) — 루트만 보면 물리 테이블 0개라 통과해버린다
        val cte = save("WITH x AS (SELECT id FROM users) SELECT id FROM x LIMIT 10")
        assertEquals(HttpStatus.FORBIDDEN, cte.statusCode)
        assertEquals("TABLES_NOT_COVERED", cte.body!!["code"])
        assertTrue(cte.body!!["uncoveredTables"].toString().contains("users"))

        // IN 서브쿼리 / UNION 팔 / 파생 테이블
        assertEquals(HttpStatus.FORBIDDEN, save(
            "SELECT id FROM user_events WHERE event_date='2026-01-01' AND id IN (SELECT id FROM users) LIMIT 10").statusCode)
        assertEquals(HttpStatus.FORBIDDEN, save(
            "SELECT id FROM user_events WHERE event_date='2026-01-01' UNION SELECT id FROM users").statusCode)
        assertEquals(HttpStatus.FORBIDDEN, save(
            "SELECT t.id FROM (SELECT id FROM users) t LIMIT 10").statusCode)
    }

    @Test
    @Order(8)
    fun `커버 오탐 - 백틱·대소문자·상위집합은 통과`() {
        val res = postAs("/api/queries", "u1", mapOf(
            "name" to "정상 저장", "dialect" to "MYSQL", "requestId" to approvedRequestId,
            "sql" to "SELECT id FROM `USER_EVENTS` WHERE event_date = '2026-01-01' LIMIT 10"))
        assertEquals(HttpStatus.CREATED, res.statusCode)
        assertEquals("PENDING_REVIEW", res.body!!["reviewStatus"])
        queryId = (res.body!!["id"] as Number).toLong()
    }

    @Test
    @Order(9)
    fun `검토 - 자가 검토 409, 타인 승인, 수정 시 재검토 리셋`() {
        // 자가 검토 금지 (요청자 김도현)
        assertEquals(HttpStatus.CONFLICT, postAs("/api/queries/$queryId/review", "u1",
            mapOf("decision" to "APPROVED")).statusCode)

        val reviewed = postAs("/api/queries/$queryId/review", "u4",
            mapOf("decision" to "APPROVED", "note" to "확인함"))
        assertEquals(HttpStatus.OK, reviewed.statusCode)
        assertEquals("APPROVED", reviewed.body!!["reviewStatus"])
        assertEquals("u4", reviewed.body!!["reviewer"])

        // 검토 승인된 쿼리를 수정 → PENDING_REVIEW로 리셋 (C5)
        val updated = rest.exchange(RequestEntity.put(URI("/api/queries/$queryId"))
            .header("X-QG-Actor", "u1").header("Content-Type", "application/json")
            .body(mapOf("name" to "수정본", "dialect" to "MYSQL", "requestId" to approvedRequestId,
                "sql" to "SELECT id FROM user_events WHERE event_date = '2026-02-01' LIMIT 10")),
            Map::class.java)
        assertEquals(HttpStatus.OK, updated.statusCode)
        assertEquals("PENDING_REVIEW", updated.body!!["reviewStatus"])
        assertEquals(null, updated.body!!["reviewer"])
    }
}
