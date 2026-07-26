package com.loveqoo.queryguardian

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.exec.ExecutionAudit
import com.loveqoo.queryguardian.exec.ExecutionEvent
import com.loveqoo.queryguardian.exec.ExecutionEventRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.doThrow
import org.mockito.kotlin.any
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 P1-C2 · 수용 기준 A5 — **감사 저장이 실패할 때 무엇이 이기는가**.
 *
 * 두 등급은 반대 방향이며, 그 방향이 뒤집히면 각각 다른 사고가 된다:
 *
 * | 종결 | 반출 | 기록 실패 시 | 뒤집히면 |
 * |---|---|---|---|
 * | SUCCESS · PREVIEW | 있음 | **내보내지 않는다** | 누가 봤는지 모르는 채 PII·강제식이 나간다 |
 * | BLOCKED · ERROR | 없음 | **원래 사유가 이긴다** + 경보 | 403이 500이 되어 무엇이 막혔는지 잃는다 |
 *
 * PREVIEW가 SUCCESS와 같은 등급인 이유: 미리보기 응답은 **적용될 강제식 원문**을 담는 카탈로그
 * 오라클이다. 데이터 행이 없다고 반출이 아닌 것이 아니다. (spec 010 v2가 여기를 best-effort로
 * 내렸다가 검토에서 회귀로 지적받았다 — retrospect 013 반성 1.)
 *
 * ## 왜 주입이 두 종류인가
 *
 * 감사 쓰기는 두 곳에서 깨질 수 있다: **리포지토리 저장**과 **`applied_json` 직렬화**.
 * 뒤엣것은 `applied`가 있는 종결에서만 일어나므로 BLOCKED에는 해당 사례가 없다 — 그래서 이 파일의
 * 사례는 8이 아니라 **7**이다. 없는 것을 있는 척 세지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditFailureContractTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

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
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var executionEvents: ExecutionEventRepository

    /** 저장 실패 주입용. 스터빙하지 않으면 실물에 위임한다. */
    @MockitoSpyBean lateinit var repository: ExecutionEventRepository

    /** `applied_json` 직렬화 실패 주입용. */
    @MockitoSpyBean lateinit var objectMapper: ObjectMapper

    private val client by lazy { SessionClient(rest) }
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val auditLogger = LoggerFactory.getLogger(ExecutionAudit::class.java) as Logger

    private var fixtures: Fixture? = null
    private data class Fixture(val requestId: Long, val approvedQueryId: Long, val pendingQueryId: Long)

    @BeforeEach
    fun captureLogs() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        auditLogger.addAppender(appender)
    }

    @AfterEach
    fun releaseLogs() {
        auditLogger.detachAppender(appender)
    }

    // ---- 반출이 있는 종결: 기록이 선행 조건 -------------------------------

    @Test
    fun `SUCCESS - 감사 저장이 실패하면 결과 행을 내보내지 않는다`() {
        val f = fixtures()
        failRepository()

        val response = client.postAs("/api/queries/${f.approvedQueryId}/execute", "u1")

        assertTrue(response.statusCode.is5xxServerError, "기록에 실패했는데 정상 응답이 나갔다: ${response.statusCode}")
        assertTrue(
            response.body?.get("rows") == null && !response.body.toString().contains("@"),
            "감사 없이 결과가 반출됐다: ${response.body}",
        )
    }

    @Test
    fun `PREVIEW - 감사 저장이 실패하면 강제식을 내보내지 않는다`() {
        val f = fixtures()
        failRepository()

        val response = preview("SELECT email FROM users", f.requestId)

        assertTrue(response.statusCode.is5xxServerError, "기록에 실패했는데 정상 응답이 나갔다: ${response.statusCode}")
        assertTrue(
            !response.body.toString().contains("mask_email"),
            "감사 없이 강제식 원문이 반출됐다(카탈로그 오라클): ${response.body}",
        )
    }

    @Test
    fun `SUCCESS - applied_json 직렬화가 실패해도 내보내지 않는다`() {
        val f = fixtures()
        failSerialization()

        val response = client.postAs("/api/queries/${f.approvedQueryId}/execute", "u1")

        assertTrue(response.statusCode.is5xxServerError, "직렬화 실패인데 정상 응답이 나갔다: ${response.statusCode}")
        assertTrue(!response.body.toString().contains("@naver.com"), "결과가 반출됐다: ${response.body}")
    }

    @Test
    fun `PREVIEW - applied_json 직렬화가 실패해도 내보내지 않는다`() {
        val f = fixtures()
        failSerialization()

        val response = preview("SELECT email FROM users", f.requestId)

        assertTrue(response.statusCode.is5xxServerError, "직렬화 실패인데 정상 응답이 나갔다: ${response.statusCode}")
        assertTrue(!response.body.toString().contains("mask_email"), "강제식이 반출됐다: ${response.body}")
    }

    // ---- 반출이 없는 종결: 원래 사유가 이긴다 -----------------------------

    @Test
    fun `BLOCKED - 감사 저장이 실패해도 원래 차단 사유가 응답을 이긴다`() {
        val f = fixtures()
        failRepository()

        val response = client.postAs("/api/queries/${f.pendingQueryId}/execute", "u1")

        assertEquals(
            HttpStatus.FORBIDDEN, response.statusCode,
            "감사 예외가 차단을 덮었다 — 무엇이 막혔는지 잃는다: ${response.body}",
        )
        assertAlerted()
    }

    @Test
    fun `ERROR - 감사 저장이 실패해도 원래 실행 오류가 응답을 이긴다`() {
        val f = fixtures()
        failRepository()

        // 카탈로그에는 있으나 물리 테이블에 없는 컬럼 → 실제 MySQL 오류
        val response = client.postAs("/api/queries/${f.sqlErrorQueryId()}/execute", "u1")

        assertEquals(
            HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode,
            "감사 예외가 실행 오류를 덮었다: ${response.body}",
        )
        assertEquals("SQL_ERROR", response.body?.get("code"), "원래 분류 코드가 바뀌었다: ${response.body}")
        assertAlerted()
    }

    @Test
    fun `ERROR - applied_json 직렬화가 실패해도 원래 실행 오류가 이긴다`() {
        val f = fixtures()
        failSerialization()

        val response = client.postAs("/api/queries/${f.sqlErrorQueryId()}/execute", "u1")

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode, "${response.body}")
        assertEquals("SQL_ERROR", response.body?.get("code"), "${response.body}")
        assertAlerted()
    }

    // ---- 대조군: 주입이 엉뚱한 것을 깨고 있지 않은가 ----------------------

    /**
     * **이 테스트가 없으면 위의 직렬화 사례 3건은 공허하다.**
     *
     * `objectMapper.writeValueAsString`을 막았을 때 HTTP 응답 직렬화까지 깨진다면, 위 사례들의 "5xx가
     * 나왔다"는 단정은 감사 계약이 아니라 **주입 부작용**을 본 것이 된다. 차단 경로는 `applied`가 없어
     * 감사 직렬화를 타지 않으므로, 여기서 403이 그대로 나오면 주입이 감사 경로에만 닿았다는 뜻이다.
     *
     * (retrospect 012에서 "테스트가 통과하는 **이유**를 봐라"를 배웠다 — 세 번 다른 이유로 초록이었다.)
     */
    @Test
    fun `대조군 - 직렬화 주입은 감사 경로에만 닿는다`() {
        val f = fixtures()
        failSerialization()

        val response = client.postAs("/api/queries/${f.pendingQueryId}/execute", "u1")

        assertEquals(
            HttpStatus.FORBIDDEN, response.statusCode,
            "직렬화 주입이 응답 직렬화까지 깨뜨렸다 — 위의 직렬화 사례 3건은 아무것도 증명하지 못한다",
        )
    }

    /**
     * **유실은 조용히 지나가면 안 된다** (spec 010 I5). best-effort 등급이 원래 사유를 살리는 대가로
     * "기록이 없다"는 사실이 조용해지는데, 조용해지면 "감사가 있다"는 전제가 무너진다.
     */
    private fun assertAlerted() {
        val alerts = appender.list.filter { it.level == Level.ERROR && it.formattedMessage.contains("AUDIT_WRITE_FAILED") }
        assertTrue(alerts.isNotEmpty(), "감사 유실이 경보 없이 지나갔다: ${appender.list.map { it.formattedMessage }}")
    }

    // ---- 주입 -------------------------------------------------------------

    private fun failRepository() {
        doThrow(IllegalStateException("주입된 감사 저장 실패")).`when`(repository).save(any<ExecutionEvent>())
    }

    private fun failSerialization() {
        doThrow(IllegalStateException("주입된 직렬화 실패")).`when`(objectMapper).writeValueAsString(any())
    }

    // ---- 준비물 -----------------------------------------------------------

    private fun preview(sql: String, requestId: Long?) =
        client.postAs("/api/preview-rewrite", "u1", mapOf("sql" to sql, "requestId" to requestId))

    private fun Fixture.sqlErrorQueryId(): Long = sqlErrorId

    private var sqlErrorId: Long = 0

    private fun fixtures(): Fixture = fixtures ?: build().also { fixtures = it }

    private fun build(): Fixture {
        val script = Path.of("..", "docker", "initdb", "01-exec-isolation.sql")
        val result = mysql.execInContainer("mysql", "-uroot", "-p${mysql.password}", "-e", Files.readString(script))
        check(result.exitCode == 0) { "데모 스키마 초기화 실패: ${result.stderr}" }

        fun post(path: String, body: Map<String, Any?>) = client.postAs(path, "adm1", body)
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅"))
        val users = post("/api/catalog/tables", mapOf("name" to "users", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "email", "type" to "VARCHAR(255)", "isPii" to true),
            mapOf("name" to "nickname", "type" to "VARCHAR(50)", "isPii" to false)))).body!!
        val maskDef = (post("/api/catalog/defs", mapOf(
            "cls" to "PII", "kind" to "MASK", "name" to "이메일 마스킹", "expression" to "mask_email({col})"))
            .body!!["id"] as Number).toLong()
        val emailId = ((users["columns"] as List<*>)
            .first { (it as Map<*, *>)["name"] == "email" } as Map<*, *>).let { (it["id"] as Number).toLong() }
        post("/api/catalog/mappings", mapOf("columnId" to emailId, "defId" to maskDef))

        val created = client.postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "감사 실패 계약", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val requestId = ((created.body!!["summary"] as Map<*, *>)["id"] as Number).toLong()
        client.postAs("/api/approvals/$requestId/approve", "ap1")

        fun saved(name: String, sql: String, review: Boolean): Long {
            val res = client.postAs("/api/queries", "u1", mapOf(
                "name" to name, "dialect" to "MYSQL", "requestId" to requestId, "sql" to sql))
            check(res.statusCode == HttpStatus.CREATED) { "저장 실패($name): ${res.statusCode} ${res.body}" }
            val id = (res.body!!["id"] as Number).toLong()
            if (review) client.postAs("/api/queries/$id/review", "ap1", mapOf("decision" to "APPROVED"))
            return id
        }

        val approved = saved("이메일 조회", "SELECT email FROM users", review = true)
        val pending = saved("미검토", "SELECT id FROM users", review = false)
        sqlErrorId = saved("없는 컬럼", "SELECT nickname FROM users", review = true)
        // 준비 자체가 감사를 남기지 않았는지는 상관없다 — 각 테스트가 주입 전에 이 함수를 부른다.
        executionEvents.count()
        return Fixture(requestId, approved, pending)
    }
}
