package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.audit.ExecutionOutcome
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

    @Autowired
    lateinit var executor: com.loveqoo.queryguardian.exec.QueryExecutor

    @Autowired
    lateinit var executionEvents: com.loveqoo.queryguardian.exec.ExecutionEventRepository

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
        // 상한이 걸렸다는 사실과 상한을 넘는 행이 있다는 사실은 **다른 사실**이다(적대 검토 #7)
        assertEquals(5, (body["effectiveLimit"] as Number).toInt())
        assertEquals(5, (body["configuredCap"] as Number).toInt())
        assertEquals(true, body["moreRowsExist"])

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
        assertEquals(5, (success["effectiveLimit"] as Number).toInt())
        assertEquals(5, (success["configuredCap"] as Number).toInt())
        assertEquals(true, success["moreRowsExist"])
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

    /**
     * 타사 검토가 실측한 감사 누락(§6 "모든 시도를 기록한다" 위반)의 회귀.
     * 403이면서 감사 0건이던 두 경로 — 승인 범위 밖 테이블, 남의 승인 요청 — 이제 기록되어야 한다.
     */
    @Test
    @Order(9)
    fun `감사 - 미리보기 차단도 빠짐없이 기록된다`() {
        fun previewEvents(actor: String) = client.getListAs("/api/queries/$queryId/executions", actor)
            .body!!.filterIsInstance<Map<*, *>>()

        val before = previewEvents("ap1").size

        // 승인 범위 밖 테이블 (요청은 users만 커버)
        val denied = postAs("/api/preview-rewrite", "u1", mapOf(
            "sql" to "SELECT id FROM marketing_consents", "requestId" to requestId))
        assertEquals(HttpStatus.FORBIDDEN, denied.statusCode)
        // **바디에 자기 필드가 살아 있는가.** `AccessDenied`·`ApprovalDenied` 두 변종을
        // `Blocked(BlockedDetail)` 하나로 합치면서, 응답이 공통 인터페이스로 직렬화되어
        // `deniedTables` 같은 자기 필드가 사라질 수 있었다 — 프론트가 그 목록으로 분기한다.
        assertTrue(
            (denied.body?.get("deniedTables") as? List<*>).orEmpty().isNotEmpty(),
            "권한 차단 바디에서 거부 테이블 목록이 사라졌다: ${denied.body}",
        )
        // 남의 승인 요청
        assertEquals(HttpStatus.FORBIDDEN, postAs("/api/preview-rewrite", "u2", mapOf(
            "sql" to "SELECT id FROM users", "requestId" to requestId)).statusCode)

        // 미리보기는 query_id가 없으므로 쿼리별 이력에는 안 잡힌다 — 대신 전체 기록이 늘었는지 본다.
        // (쿼리별 이력 API만으로는 확인할 수 없으므로 감사 저장소를 직접 본다.)
        val recorded = executionEvents.findAll().filter {
            it.outcome == ExecutionOutcome.BLOCKED && it.queryId == null
        }
        // 이 카탈로그에는 marketing_consents가 없으므로 **권한 검사**(승인 커버보다 앞)에서 먼저 막힌다 —
        // 미등록 테이블은 fail-closed로 TABLES_UNKNOWN이다(spec 007: 오타와 권한 부족을 구분).
        // 어느 게이트에서 막혔든 **기록은 남아야 한다**는 것이 이 회귀의 요점이다.
        assertTrue(
            recorded.any { it.errorCode == "TABLES_UNKNOWN" },
            "승인 범위 밖 시도가 기록되지 않았다: ${recorded.map { it.errorCode }}",
        )
        assertTrue(
            recorded.any { it.errorCode == "REQUESTER_MISMATCH" },
            "남의 요청 사용 시도가 기록되지 않았다: ${recorded.map { it.errorCode }}",
        )
        assertTrue(before >= 0)
    }

    /** 실행 시 열람 권한에서 막힌 시도도 기록된다 — 남의 쿼리 id로 실행을 시도한 것 자체가 감사 대상이다. */
    @Test
    @Order(10)
    fun `감사 - 남의 쿼리 실행 시도가 기록된다`() {
        assertEquals(HttpStatus.FORBIDDEN, postAs("/api/queries/$queryId/execute", "u2").statusCode)
        val forbidden = executionEvents.findAll().filter { it.errorCode == "FORBIDDEN_READ" }
        assertTrue(forbidden.any { it.actor == "u2" && it.queryId == queryId }, "열거 시도가 기록되지 않았다")
        // 본문은 기록하지 않는다 — 열람 권한이 없는 사람의 요청으로 SQL을 감사에 복사할 이유가 없다
        assertTrue(forbidden.none { it.originalSql.contains("SELECT") }, "권한 없는 시도에 SQL 본문이 남았다")
    }

    /**
     * PUT 탈취 (적대 검토 #1). `update`가 소유권을 확인하지 않던 동안 남의 쿼리를 덮어쓸 수 있었고,
     * 소유권이 `request_id`로 정의되므로 **requestId 교체는 소유권 이전**이었다 — 둘 다 막혔음을 본다.
     */
    @Test
    @Order(11)
    fun `게이트 - 남의 쿼리는 수정할 수 없고 근거 승인도 바꿀 수 없다`() {
        val before = client.getAs("/api/queries/$queryId", "u1").body!!
        val body = mapOf(
            "name" to "탈취", "dialect" to "MYSQL", "purposeCode" to "marketing",
            "sql" to "SELECT id FROM users", "requestId" to requestId,
        )
        val hijack = client.putAs("/api/queries/$queryId", "u2", body)
        assertEquals(HttpStatus.FORBIDDEN, hijack.statusCode, "본문: " + hijack.body)

        // 본인이라도 근거 승인 요청은 교체할 수 없다 — 그 컬럼이 소유자를 정의한다
        val otherCreated = postAs("/api/approvals", "u2", mapOf(
            "purposeTitle" to "타인 요청", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "users")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val otherRequest =
            (((otherCreated.body ?: error("요청 생성 실패: " + otherCreated.body))["summary"] as Map<*, *>)["id"] as Number).toLong()
        assertEquals(
            HttpStatus.FORBIDDEN,
            client.putAs("/api/queries/$queryId", "u1", body + mapOf("requestId" to otherRequest)).statusCode,
        )

        // 행이 실제로 안 바뀌었다 — 403만 보고 만족하면 "거절했지만 이미 저장됨"을 놓친다
        val after = client.getAs("/api/queries/$queryId", "u1").body!!
        assertEquals(before["name"], after["name"])
        assertEquals(before["sql"], after["sql"])
        assertEquals(before["requestId"], after["requestId"])
    }

    /**
     * SQL 길이 (적대 검토 #3). 파서 한계(65536B)가 감사 TEXT 컬럼 한계(65535B)보다 커서,
     * 그 사이 크기의 SQL은 REQUIRES_NEW 감사 INSERT에서 죽어 **500 + 감사 0건**이 됐다 — 유일한 미기록 경로였다.
     */
    @Test
    @Order(12)
    fun `입력 한계 - 과대 SQL은 감사 이전에 거부된다`() {
        val huge = "SELECT id FROM users WHERE id IN (" + (1..12_000).joinToString(",") + ")"
        assertTrue(huge.toByteArray().size > 60_000, "표본이 한계보다 작다: ${huge.length}")
        val before = executionEvents.findAll().count()
        val res = client.postAs("/api/queries", "u1", mapOf(
            "name" to "과대", "dialect" to "MYSQL", "purposeCode" to "marketing",
            "sql" to huge, "requestId" to requestId,
        ))
        assertTrue(res.statusCode.is4xxClientError, "500이 아니라 4xx여야 한다: " + res.statusCode + " " + res.body)
        // **길이 때문에** 막혔는지 확인한다 — dialect 오타로 400이 나도 통과하던 단정이었다(실측)
        assertTrue(
            res.body.toString().contains("길이") || res.body.toString().contains("깁니다"),
            "다른 이유로 막혔다: " + res.body,
        )
        assertEquals(before, executionEvents.findAll().count(), "거부된 입력이 감사를 남겼다")
    }

    /**
     * 감사 도달성 (적대 검토 #2). 유일한 읽기 경로가 저장 쿼리를 지나던 동안, 쿼리를 지우면 그 실행 기록이
     * 404가 되어 **행위자가 스스로 감사를 은닉**할 수 있었고 PREVIEW 기록은 어떤 API로도 볼 수 없었다.
     */
    @Test
    @Order(13)
    fun `감사 - 쿼리를 지워도 기록은 남고 미리보기도 조회된다`() {
        // 지울 쿼리를 하나 만들어 실행까지 한 뒤 삭제한다
        val created = client.postAs("/api/queries", "u1", mapOf(
            "name" to "지울 쿼리", "dialect" to "MYSQL", "purposeCode" to "marketing",
            "sql" to "SELECT email FROM users", "requestId" to requestId,
        ))
        val disposable = (created.body?.get("id") as? Number)?.toLong()
            ?: error("쿼리 생성 실패: " + created.statusCode + " " + created.body)
        assertEquals(
            HttpStatus.OK,
            postAs("/api/queries/$disposable/review", "ap1", mapOf("decision" to "APPROVED")).statusCode,
        )
        assertEquals(HttpStatus.OK, postAs("/api/queries/$disposable/execute", "u1").statusCode)
        assertEquals(HttpStatus.NO_CONTENT, client.deleteAs("/api/queries/$disposable", "u1").statusCode)

        // 쿼리별 경로는 이제 대상이 없다 — 그런데 감사는 살아 있어야 한다
        assertEquals(HttpStatus.NOT_FOUND, client.getAs("/api/queries/$disposable/executions", "u1").statusCode)
        val all = client.getListAs("/api/executions", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(all.isNotEmpty(), "감사 전건 조회가 비었다")
        assertTrue(all.any { it["outcome"] == "PREVIEW" }, "미리보기 기록이 어디에도 안 보인다: $all")
        assertTrue(
            executionEvents.findAll().any { it.queryId == disposable },
            "삭제된 쿼리의 실행 기록이 사라졌다",
        )
        // 결과 값은 여기에도 없다(§6 불변식)
        assertTrue(!all.toString().contains("@naver.com"), "감사 전건에 결과 값이 있다")
        // 거버넌스 역할 전용
        // 거부 응답은 리스트가 아니라 오류 객체이므로 Map으로 받는다
        assertEquals(HttpStatus.FORBIDDEN, client.getAs("/api/executions", "u1").statusCode)
        // 행위자 좁히기
        val mine = client.getListAs("/api/executions?actor=u2", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(mine.all { it["actor"] == "u2" }, "actor 필터가 새어 나갔다: $mine")

        // **결말 좁히기** — 이 필터는 테스트가 0건이었다(P3 착수 시 실측). 지금 붙이는 이유는
        // `outcome`이 String에서 enum이 되면서 **알 수 없는 값의 처리가 정책이 됐기** 때문이다.
        val blocked = client.getListAs("/api/executions?outcome=blocked", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(blocked.isNotEmpty(), "BLOCKED 기록이 없다 — 이 단정 자체가 공허해진다")
        assertTrue(blocked.all { it["outcome"] == "BLOCKED" }, "outcome 필터가 새어 나갔다: $blocked")

        // **오타는 전체를 열지 않는다.** 필터를 조용히 버리면 `outcome=BLOKED` 하나로 감사 전건이 나간다 —
        // 필터를 건 사람은 좁혀 봤다고 믿는데 실제로는 넓게 본다. 빈 결과가 fail-closed다.
        val typo = client.getListAs("/api/executions?outcome=BLOKED", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertEquals(emptyList(), typo, "알 수 없는 outcome이 필터를 버리고 전체를 열었다")
    }

    /**
     * 승인 요청 읽기 스코프 (적대 검토 #6). 요청에는 목적·대상 테이블·승인 라인이 들어 있는데
     * `requester` 파라미터를 비우면 전건이 보였다.
     */
    @Test
    @Order(14)
    fun `스코프 - 남의 승인 요청은 목록에도 상세에도 없다`() {
        assertEquals(HttpStatus.NOT_FOUND, client.getAs("/api/approvals/$requestId", "u2").statusCode)
        assertEquals(HttpStatus.OK, client.getAs("/api/approvals/$requestId", "u1").statusCode)
        // 승인선에 편성된 사람은 심사해야 하므로 보인다
        assertEquals(HttpStatus.OK, client.getAs("/api/approvals/$requestId", "ap1").statusCode)

        val u2List = client.getListAs("/api/approvals", "u2").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(
            u2List.none { (it["id"] as Number).toLong() == requestId },
            "남의 요청이 목록에 있다: $u2List",
        )
        val stewardList = client.getListAs("/api/approvals", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(stewardList.any { (it["id"] as Number).toLong() == requestId }, "심사자에게 안 보인다")
    }

    /**
     * 실행 커넥션 `sql_mode` 계약 (적대 검토 #4). 전체를 치환해서 서버 기본값의 `ONLY_FULL_GROUP_BY`가
     * 사라졌고, 검토자가 승인한 SQL이 실행 시점에 **다른 의미**로 돌았다(그룹당 임의 행 선택).
     * `NO_BACKSLASH_ESCAPES`는 M0 어휘 스캐너의 전제이므로 반대로 **없어야** 한다.
     */
    @Test
    @Order(15)
    fun `실행 커넥션 - sql_mode는 합집합이고 스캐너 전제를 지킨다`() {
        ensureDemoSchema()
        val mode = executor.execute(ProbeOrder("SELECT @@SESSION.sql_mode")).rows.single().single()!!
        assertTrue(mode.contains("ONLY_FULL_GROUP_BY"), "서버 기본 모드를 갈아써 버렸다: $mode")
        assertTrue(mode.contains("STRICT_TRANS_TABLES"), "고정 모드가 빠졌다: $mode")
        assertTrue(!mode.contains("NO_BACKSLASH_ESCAPES"), "접수 스캐너의 전제가 깨진다: $mode")
    }

    /**
     * 상한 경계 (적대 검토 D5·테스트 공백 4). 사용자가 스스로 좁힌 것과 거버넌스가 자른 것은 **다른 사실**이다 —
     * 한 값으로 뭉치면 감사가 "상한 2가 걸려 잘렸다"는 거짓을 남긴다.
     */
    @Test
    @Order(16)
    fun `상한 - 사용자 LIMIT과 설정 상한을 구분해 기록한다`() {
        ensureDemoSchema()
        fun run(sql: String): Map<*, *> {
            val id = (client.postAs("/api/queries", "u1", mapOf(
                "name" to "상한 $sql", "dialect" to "MYSQL", "requestId" to requestId, "sql" to sql,
            )).body!!["id"] as Number).toLong()
            postAs("/api/queries/$id/review", "ap1", mapOf("decision" to "APPROVED"))
            val res = postAs("/api/queries/$id/execute", "u1")
            assertEquals(HttpStatus.OK, res.statusCode, "실행 실패: " + res.body)
            return res.body!!
        }

        // 사용자가 2행만 요청 — 설정 상한(5)은 발동하지 않았다
        val narrow = run("SELECT email FROM users LIMIT 2")
        assertEquals(2, narrow["rowCount"])
        assertEquals(2, (narrow["effectiveLimit"] as Number).toInt())
        assertEquals(5, (narrow["configuredCap"] as Number).toInt())
        assertTrue(
            (narrow["effectiveLimit"] as Number).toLong() != (narrow["configuredCap"] as Number).toLong(),
            "사용자 LIMIT과 설정 상한이 구분되지 않는다: $narrow",
        )

        // LIMIT 0 — 초과 행을 볼 기회가 없으므로 "없다"고 단정하지 않는다
        val zero = run("SELECT email FROM users LIMIT 0")
        assertEquals(0, zero["rowCount"])
        assertEquals(null, zero["moreRowsExist"], "확인하지 않은 것을 false로 단정했다: $zero")
    }

    /**
     * 감사 밀어내기 은닉 (적대 검토 D3). "최근 200건"만 주면 새 기록을 쌓아 옛 기록을 조회 범위 밖으로
     * 밀어낼 수 있다 — 저장은 남지만 아무도 볼 수 없으니 삭제 은닉과 결말이 같다.
     */
    @Test
    @Order(17)
    fun `감사 - 새 기록을 쌓아도 옛 기록에 도달할 수 있다`() {
        val oldest = executionEvents.findAll().minByOrNull { it.id!! } ?: error("감사가 비었다")

        // 미리보기 차단은 요청당 감사 1행을 만든다(무권한·무비용) — 은닉 시나리오의 재료였다
        repeat(210) { client.postAs("/api/preview-rewrite", "u2", mapOf("sql" to "SELECT 1")) }

        val firstPage = client.getListAs("/api/executions", "ap1").body!!.filterIsInstance<Map<*, *>>()
        assertTrue(firstPage.size <= 200, "상한이 없다: ${firstPage.size}")
        assertTrue(
            firstPage.none { (it["id"] as Number).toLong() == oldest.id },
            "밀려나지 않았다면 이 테스트가 은닉을 재현하지 못한다",
        )

        // 커서로 거슬러 올라가면 반드시 도달한다
        var cursor = firstPage.minOf { (it["id"] as Number).toLong() }
        var found = false
        repeat(20) {
            if (found) return@repeat
            val page = client.getListAs("/api/executions?before=$cursor", "ap1")
                .body!!.filterIsInstance<Map<*, *>>()
            if (page.isEmpty()) return@repeat
            if (page.any { (it["id"] as Number).toLong() == oldest.id }) found = true
            cursor = page.minOf { (it["id"] as Number).toLong() }
        }
        assertTrue(found, "커서 페이징으로도 옛 기록(id=${oldest.id})에 도달할 수 없다 — 감사가 은닉됐다")
    }

    /** 대행 수정 불허 (적대 검토 D7) — 결정 14가 대행 실행을 막는데 대행 수정을 열어둘 이유가 없다. */
    @Test
    @Order(18)
    fun `게이트 - STEWARD도 남의 쿼리를 수정할 수 없다`() {
        val before = client.getAs("/api/queries/$queryId", "u1").body!!
        val res = client.putAs("/api/queries/$queryId", "ap1", mapOf(
            "name" to "심사자 수정", "dialect" to "MYSQL", "purposeCode" to "marketing",
            "sql" to "SELECT id FROM users", "requestId" to requestId,
        ))
        assertEquals(HttpStatus.FORBIDDEN, res.statusCode, "본문: " + res.body)
        assertEquals(before["name"], client.getAs("/api/queries/$queryId", "u1").body!!["name"])
    }

    /**
     * 존재·상태 오라클 (적대 검토 D6). `GET /api/approvals/{id}`가 404로 숨기는 요청의 존재·상태를
     * 다른 경로가 확정해 주면 은닉은 무의미하다.
     */
    @Test
    @Order(19)
    fun `스코프 - 승인 요청의 존재와 상태가 다른 경로로 새지 않는다`() {
        // 취소는 역할 제약이 없다 — 상태를 먼저 보던 동안 아무나 존재+상태를 얻었다
        val cancel = postAs("/api/approvals/$requestId/cancel", "u2")
        assertEquals(HttpStatus.NOT_FOUND, cancel.statusCode, "본문: " + cancel.body)
        assertTrue(
            !cancel.body.toString().contains("APPROVED"),
            "상태가 메시지로 새어 나갔다: " + cancel.body,
        )

        // 남의 requestId로 저장을 시도하면 403이지만, 그 본문에 상태를 담아서는 안 된다
        val save = client.postAs("/api/queries", "u2", mapOf(
            "name" to "정찰", "dialect" to "MYSQL", "requestId" to requestId, "sql" to "SELECT id FROM users",
        ))
        assertEquals(HttpStatus.FORBIDDEN, save.statusCode)
        assertEquals(null, save.body?.get("requestStatus"), "요청 상태가 403 본문으로 새어 나갔다: " + save.body)
        assertEquals(null, save.body?.get("requestId"), "요청 id가 403 본문으로 새어 나갔다: " + save.body)
    }

    /**
     * 재작성 증폭 (적대 검토 테스트 공백 5). 입력을 통과한 SQL은 **재작성 후에도** 감사에 온전히 저장돼야
     * 한다 — 감사 INSERT가 죽으면 그 실행은 무기록으로 통과한다. `applied_json`은 마스킹 컬럼 수에
     * 비례해 커지므로 원본보다 훨씬 빨리 TEXT 한계(65,535 B)를 넘는다.
     */
    @Test
    @Order(20)
    fun `감사 - 재작성으로 커진 기록도 잘리지 않고 저장된다`() {
        ensureDemoSchema()
        val projections = (1..2_000).joinToString(", ") { "email AS e$it" }
        val sql = "SELECT $projections FROM users"
        val id = (client.postAs("/api/queries", "u1", mapOf(
            "name" to "증폭", "dialect" to "MYSQL", "requestId" to requestId, "sql" to sql,
        )).body!!["id"] as Number).toLong()
        postAs("/api/queries/$id/review", "ap1", mapOf("decision" to "APPROVED"))
        val res = postAs("/api/queries/$id/execute", "u1")
        assertEquals(HttpStatus.OK, res.statusCode, "실행 실패: " + res.body)

        val event = executionEvents.findAll().filter { it.queryId == id }
            .maxByOrNull { it.id!! } ?: error("감사 기록이 없다")
        val applied = event.appliedJson ?: error("적용 목록이 저장되지 않았다")
        // 표본이 실제로 TEXT 한계를 넘어야 MEDIUMTEXT 전환이 검증된다
        assertTrue(applied.toByteArray().size > 65_535, "한계를 넘지 않는 표본이다: ${applied.length}")
        assertTrue(applied.trimEnd().endsWith("]"), "적용 목록이 잘렸다(끝: ...${applied.takeLast(40)})")
        assertEquals(sql, event.originalSql, "원본 SQL이 잘렸다")
        assertTrue(event.rewrittenSql!!.contains("mask_email"), "재작성 SQL이 잘렸다")
    }

    /**
     * 증폭 절벽 (실측 발견). 입력 상한(60,000 B)을 통과한 SQL도 마스킹 치환으로 부풀어 **파서 상한(64 KiB)**
     * 을 넘을 수 있다 — 재작성 검증이 다시 파싱하므로 그 지점에서 걸린다. 데이터는 나가지 않아야 하고
     * (fail-closed), 감사에 남아야 하고, 사용자는 **무엇을 고쳐야 할지** 알아야 한다.
     */
    @Test
    @Order(21)
    fun `증폭 절벽 - 재작성이 파서 상한을 넘으면 차단하고 이유를 알려준다`() {
        ensureDemoSchema()
        val sql = "SELECT " + (1..3_000).joinToString(", ") { "email AS e$it" } + " FROM users"
        assertTrue(sql.toByteArray().size < 60_000, "입력 상한을 넘는 표본이면 다른 게이트가 잡는다")
        val id = (client.postAs("/api/queries", "u1", mapOf(
            "name" to "절벽", "dialect" to "MYSQL", "requestId" to requestId, "sql" to sql,
        )).body!!["id"] as Number).toLong()
        postAs("/api/queries/$id/review", "ap1", mapOf("decision" to "APPROVED"))

        val res = postAs("/api/queries/$id/execute", "u1")
        assertTrue(res.statusCode.is4xxClientError, "500이 아니라 4xx여야 한다: ${res.statusCode}")
        assertTrue(
            res.body.toString().contains("컬럼 수를 줄여"),
            "무엇을 고쳐야 할지 알려주지 않는다: " + res.body,
        )
        // 차단도 감사 대상이다(§6)
        assertTrue(
            executionEvents.findAll().any { it.queryId == id && it.outcome == ExecutionOutcome.BLOCKED },
            "차단이 기록되지 않았다",
        )
    }
}
