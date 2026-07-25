package com.loveqoo.queryguardian

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 008 M2-7 E2E — **실제 실행**. 디자인의 결과 표(`j***@naver.com`)가 진짜가 되는 지점이다.
 *
 * 검증 축:
 * ⑴ 마스킹이 실제로 적용된다(그리고 **평문이 응답에 없다**) ⑵ 행 상한·truncated ⑶ 소유자만 실행
 * ⑷ 미검토 쿼리 거부 ⑸ 감사에 SUCCESS·BLOCKED가 남는다.
 *
 * 데모 스키마·실행 계정은 `docker/initdb/01-exec-isolation.sql`을 **그대로** 컨테이너에 적용한다 —
 * 운영 경로와 테스트 경로가 다른 SQL을 쓰면 "테스트는 통과하는데 실제로는 다른 것"이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExecutionFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        /** 실행 전용 접속을 같은 컨테이너의 데모 스키마로 향하게 한다. 상한 5행 — 12행 데모로 truncated를 본다. */
        @JvmStatic
        @DynamicPropertySource
        fun executionProperties(registry: DynamicPropertyRegistry) {
            registry.add("guardian.exec.url") {
                "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/queryguardian_demo"
            }
            registry.add("guardian.exec.username") { "qg_exec" }
            registry.add("guardian.exec.password") { "qg-exec-demo" }
            registry.add("guardian.exec.max-rows") { "5" }
        }

        var requestId = 0L
        var queryId = 0L
        var pendingQueryId = 0L
        private var demoReady = false
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private val client by lazy { SessionClient(rest) }
    private fun post(path: String, body: Map<String, Any?>) = client.postAs(path, "adm1", body)
    private fun postAs(path: String, actor: String, body: Any? = null) = client.postAs(path, actor, body)

    /** 운영 초기화 스크립트를 컨테이너에 그대로 적용한다(root로). */
    private fun ensureDemoSchema() {
        if (demoReady) return
        val script = Path.of("..", "docker", "initdb", "01-exec-isolation.sql")
        val sql = Files.readString(script)
        val result = mysql.execInContainer("mysql", "-uroot", "-p${mysql.password}", "-e", sql)
        check(result.exitCode == 0) { "데모 스키마 초기화 실패: ${result.stderr}" }
        demoReady = true
    }

    @Test
    @Order(1)
    fun `준비 - 카탈로그와 승인, 마스킹 매핑`() {
        ensureDemoSchema()
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅"))
        val users = post("/api/catalog/tables", mapOf("name" to "users", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "email", "type" to "VARCHAR(255)", "isPii" to true),
            mapOf("name" to "ssn", "type" to "CHAR(13)", "isPii" to true)))).body!!
        fun columnId(name: String) = ((users["columns"] as List<*>)
            .first { (it as Map<*, *>)["name"] == name } as Map<*, *>).let { (it["id"] as Number).toLong() }

        // 카탈로그의 강제식이 데모 스키마의 실제 함수를 부른다 — 이것이 맞물려야 마스킹이 진짜가 된다
        val maskDef = (post("/api/catalog/defs", mapOf(
            "cls" to "PII", "kind" to "MASK", "name" to "이메일 마스킹", "expression" to "mask_email({col})"))
            .body!!["id"] as Number).toLong()
        val blockDef = (post("/api/catalog/defs", mapOf(
            "cls" to "PII", "kind" to "BLOCK", "name" to "조회 차단")).body!!["id"] as Number).toLong()
        post("/api/catalog/mappings", mapOf("columnId" to columnId("email"), "defId" to maskDef))
        post("/api/catalog/mappings", mapOf("columnId" to columnId("ssn"), "defId" to blockDef))

        val created = postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "실행 E2E", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        requestId = ((created.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()
        postAs("/api/approvals/$requestId/approve", "ap1")
    }

    @Test
    @Order(2)
    fun `쿼리 저장과 검토 승인`() {
        val saved = postAs("/api/queries", "u1", mapOf(
            "name" to "이메일 조회", "dialect" to "MYSQL", "requestId" to requestId,
            "sql" to "SELECT email FROM users"))
        assertEquals(HttpStatus.CREATED, saved.statusCode, "저장 실패: ${saved.body}")
        queryId = (saved.body!!["id"] as Number).toLong()
        // 마스킹 대상을 투영만 했으므로 차단이 아니라 **안내(WARN)** 여야 한다
        assertTrue(saved.body!!["lintReport"].toString().contains("자동으로 마스킹"), "${saved.body}")

        // 미검토 쿼리 하나 더 — 실행 거부 확인용
        pendingQueryId = (postAs("/api/queries", "u1", mapOf(
            "name" to "미검토", "dialect" to "MYSQL", "requestId" to requestId,
            "sql" to "SELECT id FROM users")).body!!["id"] as Number).toLong()

        assertEquals(HttpStatus.OK, postAs("/api/queries/$queryId/review", "ap1",
            mapOf("decision" to "APPROVED")).statusCode)
    }

    /** 핵심: 실제 실행 결과에 **마스킹된 값만** 있고 평문 이메일이 없다. */
    @Test
    @Order(3)
    fun `실행하면 마스킹된 실제 값이 나온다`() {
        val response = postAs("/api/queries/$queryId/execute", "u1")
        assertEquals(HttpStatus.OK, response.statusCode, "실행 실패: ${response.body}")
        val body = response.body!!

        @Suppress("UNCHECKED_CAST")
        val rows = body["rows"] as List<List<String?>>
        assertTrue(rows.isNotEmpty(), "결과가 비어 있다: $body")
        for (row in rows) {
            val value = row.single()!!
            assertTrue(
                Regex("^.\\*\\*\\*@").containsMatchIn(value),
                "마스킹되지 않은 값이 반환됐다: $value",
            )
        }
        // 평문 로컬파트가 어디에도 없어야 한다 (데모 데이터의 실제 계정명)
        assertTrue(!body.toString().contains("jimin@"), "평문 이메일이 응답에 있다: $body")

        // 행 상한(5)으로 잘렸다 — 데모 사용자는 12명이다
        assertEquals(5, body["rowCount"])
        assertEquals(true, body["truncated"])

        // 무엇이 자동 적용됐는지 사용자에게 보인다
        assertTrue(body["applied"].toString().contains("MASK"), "${body["applied"]}")
        assertTrue(body["rewrittenSql"].toString().contains("mask_email"), "${body["rewrittenSql"]}")
        // 물리 테이블로 치환됐다 — 논리명이 그대로 실행되지 않았다
        assertTrue(body["rewrittenSql"].toString().contains("demo_users"), "${body["rewrittenSql"]}")
    }

    @Test
    @Order(4)
    fun `게이트 - 남의 쿼리와 미검토 쿼리는 실행할 수 없다`() {
        // 다른 분석가: 열람 자체가 막힌다
        assertEquals(HttpStatus.FORBIDDEN, postAs("/api/queries/$queryId/execute", "u2").statusCode)
        // STEWARD도 대행 실행 불허(결정 14) — 열람은 되지만 실행은 요청자 본인만
        assertEquals(HttpStatus.FORBIDDEN, postAs("/api/queries/$queryId/execute", "ap1").statusCode)
        // 검토 승인 전에는 실행 불가
        assertEquals(HttpStatus.FORBIDDEN, postAs("/api/queries/$pendingQueryId/execute", "u1").statusCode)
    }

    @Test
    @Order(5)
    fun `감사 - 성공과 차단이 모두 기록된다`() {
        val history = client.getListAs("/api/queries/$queryId/executions", "u1")
            .body!!.filterIsInstance<Map<*, *>>()
        assertTrue(history.any { it["outcome"] == "SUCCESS" }, "성공 기록 없음: $history")
        assertTrue(history.any { it["outcome"] == "BLOCKED" }, "차단 기록 없음(대행 실행 시도): $history")

        val success = history.first { it["outcome"] == "SUCCESS" }
        assertEquals(5, success["rowCount"])
        assertEquals(true, success["truncated"])
        assertTrue(success["rewrittenSql"].toString().contains("mask_email"))
        // 결과 값은 저장하지 않는다(§6 불변식) — 이력 어디에도 데이터가 없어야 한다
        assertTrue(!history.toString().contains("@naver.com"), "감사에 결과 값이 남았다: $history")
    }

    /** 오류 원문은 STEWARD/ADMIN에게만 — 일반 사용자에게는 분류 코드까지만(§6). */
    @Test
    @Order(6)
    fun `감사 - 오류 원문은 권한자에게만 보인다`() {
        val mine = client.getListAs("/api/queries/$queryId/executions", "u1")
            .body!!.filterIsInstance<Map<*, *>>()
        assertTrue(mine.all { it["errorDetail"] == null }, "일반 사용자에게 원문이 노출됐다: $mine")

        val steward = client.getListAs("/api/queries/$queryId/executions", "ap1")
            .body!!.filterIsInstance<Map<*, *>>()
        assertTrue(
            steward.any { it["outcome"] == "BLOCKED" && it["errorDetail"] != null },
            "STEWARD에게 원문이 보이지 않는다: $steward",
        )
    }

    /**
     * spec 008 §7 미리보기 — 실행 없이 재작성만 보여준다. **게이트는 실행과 같아야 한다**:
     * 응답에 적용될 강제식 원문이 담기므로, 게이트가 느슨하면 미리보기가 "어떤 컬럼이 MASK이고 마스크 식이
     * 무엇인지"를 캐는 창구가 된다.
     */
    @Test
    @Order(7)
    fun `미리보기 - 실행 없이 재작성 SQL을 보여준다`() {
        val response = postAs("/api/preview-rewrite", "u1", mapOf(
            "sql" to "SELECT email FROM users", "requestId" to requestId, "dialect" to "MYSQL"))
        assertEquals(HttpStatus.OK, response.statusCode, "미리보기 실패: ${response.body}")
        val body = response.body!!

        assertTrue(body["rewrittenSql"].toString().contains("mask_email"), "${body["rewrittenSql"]}")
        assertTrue(body["rewrittenSql"].toString().contains("demo_users"), "${body["rewrittenSql"]}")
        assertTrue(body["applied"].toString().contains("MASK"), "${body["applied"]}")
        // 실행이 아니므로 결과 행이 없다
        assertTrue(!body.containsKey("rows"), "미리보기에 결과 행이 있다: $body")
        // 통과했어도 안내(WARN)는 그 자리에서 보여야 한다
        assertTrue(body["lintReport"].toString().contains("자동으로 마스킹"), "${body["lintReport"]}")
    }

    @Test
    @Order(8)
    fun `미리보기 게이트 - 요청 없음·남의 요청·룰 위반은 막힌다`() {
        // requestId 없으면 purposeCode를 주입할 수 없다 → purpose별 FILTER 자가 면제를 막는다 (spec 005 C1)
        assertEquals(
            HttpStatus.FORBIDDEN,
            postAs("/api/preview-rewrite", "u1", mapOf("sql" to "SELECT email FROM users")).statusCode,
        )
        // 남의 승인 요청으로는 미리 볼 수 없다
        assertEquals(
            HttpStatus.FORBIDDEN,
            postAs("/api/preview-rewrite", "u2", mapOf(
                "sql" to "SELECT email FROM users", "requestId" to requestId)).statusCode,
        )
        // 룰 위반(BLOCK 컬럼)은 422 — 미리보기라고 통과시키지 않는다
        val blocked = postAs("/api/preview-rewrite", "u1", mapOf(
            "sql" to "SELECT ssn FROM users", "requestId" to requestId))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.statusCode, "${blocked.body}")
        assertTrue(blocked.body!!["violations"].toString().contains("no-blocked-column"), "${blocked.body}")

        // 승인 범위 밖 테이블도 막힌다(요청은 users만 커버한다)
        assertEquals(
            HttpStatus.FORBIDDEN,
            postAs("/api/preview-rewrite", "u1", mapOf(
                "sql" to "SELECT id FROM marketing_consents", "requestId" to requestId)).statusCode,
        )
    }
}
