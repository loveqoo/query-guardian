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

/**
 * **저장 쿼리는 자기 소유자를 말한다** (spec 013 C4).
 *
 * 실행은 요청자 본인만 할 수 있다(결정 14 — 대행 실행 불허). 그런데 목록·상세 DTO에 소유자가 없어서
 * 화면이 그것을 알 수 없었다. 모르면 스튜어드 화면에서 남의 쿼리에도 실행 버튼이 활성으로 보이고,
 * 눌러 보면 403이며, 사용자는 자기가 뭘 잘못했는지 모른다.
 *
 * 소유자는 **별도 컬럼이 아니라** 근거 승인 요청의 요청자다 — 두 값이 어긋날 여지를 만들지 않으려고
 * 그렇게 정의돼 있다(`QueryService.ownerOf`). 그 정의가 DTO에서도 지켜지는지 잰다.
 *
 * 새 노출이 아님을 함께 잰다: 목록은 이미 열람 스코프로 걸러지므로, 분석가에게는 자기 것만 보이고
 * 그 소유자는 자기 자신이다. **소유자 필드가 스코프를 넓히지 않는다**는 것이 3의 요지다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class QueryOwnerVisibilityTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var queryId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private val client by lazy { SessionClient(rest) }

    @Test
    @Order(1)
    fun `u1이 쿼리를 저장한다`() {
        client.postAs("/api/catalog/purposes", "adm1", mapOf("code" to "marketing", "description" to "마케팅"))
        client.postAs("/api/catalog/tables", "adm1", mapOf(
            "name" to "owner_probe",
            "columns" to listOf(mapOf("name" to "id", "type" to "BIGINT", "isPii" to false)),
        ))
        val approval = client.postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to "소유자 노출 확인", "purposeCode" to "marketing",
            "tables" to listOf(mapOf("tableName" to "owner_probe")),
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val summary = approval.body?.get("summary") as? Map<*, *>
            ?: error("승인 요청 생성 실패: ${approval.statusCode} ${approval.body}")
        val requestId = (summary["id"] as Number).toLong()
        client.postAs("/api/approvals/$requestId/approve", "ap1", mapOf("note" to "ok"))

        val saved = client.postAs("/api/queries", "u1", mapOf(
            "name" to "owner_probe_query", "dialect" to "MYSQL",
            "sql" to "SELECT id FROM owner_probe LIMIT 10", "requestId" to requestId))
        assertEquals(HttpStatus.CREATED, saved.statusCode, "저장 실패: ${saved.body}")
        queryId = ((saved.body!!["id"]) as Number).toLong()
    }

    @Test
    @Order(2)
    fun `상세와 목록이 같은 소유자를 말한다`() {
        val detail = client.getAs("/api/queries/$queryId", "u1").body!!
        assertEquals("u1", detail["owner"], "상세의 소유자가 요청자가 아니다: $detail")

        val listed = client.getListAs("/api/queries", "u1").body!!.filterIsInstance<Map<*, *>>()
            .first { (it["id"] as Number).toLong() == queryId }
        assertEquals(
            detail["owner"], listed["owner"],
            "목록과 상세의 소유자가 다르다 — 화면이 어느 쪽을 보느냐로 실행 버튼이 달라진다",
        )
    }

    @Test
    @Order(3)
    fun `스튜어드(u4)는 남의 쿼리를 보되 그 소유자가 자기가 아님을 안다`() {
        val listed = client.getListAs("/api/queries", "u4").body!!.filterIsInstance<Map<*, *>>()
            .first { (it["id"] as Number).toLong() == queryId }
        assertEquals(
            "u1", listed["owner"],
            "스튜어드가 보는 소유자가 실제 요청자와 다르다 — 그러면 대행 실행 금지를 화면이 못 지킨다: $listed",
        )
    }

    @Test
    @Order(4)
    fun `분석가에게는 자기 것만 보이고 소유자는 자기 자신이다`() {
        // 소유자 필드가 열람 스코프를 넓히지 않는다는 확인 — u2에게는 u1의 쿼리가 아예 안 보여야 한다.
        val rows = client.getListAs("/api/queries", "u2").body!!.filterIsInstance<Map<*, *>>()
        assertEquals(
            emptyList(), rows.filter { (it["id"] as Number).toLong() == queryId },
            "남의 쿼리가 목록에 보인다 — 소유자 노출이 스코프를 넓혔다: $rows",
        )
        rows.forEach {
            assertNotNull(it["owner"], "소유자가 비어 있다: $it")
            assertEquals("u2", it["owner"], "본인 목록인데 소유자가 다르다: $it")
        }
    }
}
