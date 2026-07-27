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
 * **제약 정의를 *수정*할 때도 등록과 같은 검사를 지난다** (spec 014 L5 · 백로그 `D-H`).
 *
 * ## 왜 이 파일이 생겼나
 *
 * C2 가드(판정 미지원 형태의 요건 술어 거부)가 **`createMapping`에만** 있었다.
 * `updateDef`는 정의 자체만 보고 **기존 매핑을 다시 보지 않았다.** 구멍이 둘이다:
 *
 * - 강제식을 판정 미지원 형태로 바꾸면 그 정의를 쓰는 매핑이 전부 판정 불가가 되고,
 *   해당 테이블을 조회하는 쿼리가 전부 "검증할 수 없습니다"로 막힌다(**과차단**).
 * - **kind를 요건(INTEGRITY·FILTER)에서 MASK로 바꾸면 요건이 조용히 사라진다**(**유출**).
 *   이쪽이 더 나쁘다 — 아무 오류도 안 나고 그냥 안 막게 된다.
 *
 * **검사가 한 경로에만 있으면 다른 경로가 그 검사를 무효로 만든다.** 등록에서 막은 것을
 * 수정으로 우회할 수 있으면 막은 것이 아니다.
 *
 * ## 축 둘 — 둘 다 없으면 안 된다
 *
 * 1. **막는다**(2·3): 매핑이 있는 정의의 kind 변경, 강제식의 판정 불가 형태 변경.
 * 2. **과차단하지 않는다**(4·5): 매핑이 없으면 kind를 바꿀 수 있고, 매핑이 있어도
 *    무해한 변경(이름·설명)은 통과한다. 이 축이 없으면 "전부 거절"도 1번을 만족한다 —
 *    감시자가 무엇을 **통과시켜야 하는지**까지 재야 가드가 가드다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DefUpdateGuardTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var mappedDefId = 0L
        var freeDefId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    private val client by lazy { SessionClient(rest) }
    private fun post(path: String, body: Any) = client.postAs(path, "adm1", body)
    private fun put(path: String, body: Any) = client.putAs(path, "adm1", body)

    private fun defBody(kind: String, name: String, expression: String?, cls: String = "STRING") =
        buildMap {
            put("cls", cls); put("kind", kind); put("name", name)
            if (expression != null) put("expression", expression)
        }

    @Test
    @Order(1)
    fun `준비 - 요건 제약 하나를 만들어 매핑한다`() {
        val table = post("/api/catalog/tables", mapOf(
            "name" to "guard_docs",
            "columns" to listOf(
                mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
                mapOf("name" to "deleted_yn", "type" to "CHAR(1)", "isPii" to false),
            ),
        )).body!!
        @Suppress("UNCHECKED_CAST")
        val columns = table["columns"] as List<Map<String, Any>>
        val deletedCol = (columns.first { it["name"] == "deleted_yn" }["id"] as Number).toLong()

        mappedDefId = (post("/api/catalog/defs",
            defBody("INTEGRITY", "삭제되지 않은 것만", "{col} = 'N'")).body!!["id"] as Number).toLong()
        freeDefId = (post("/api/catalog/defs",
            defBody("INTEGRITY", "매핑 없는 요건", "{col} = 'N'")).body!!["id"] as Number).toLong()

        val mapped = post("/api/catalog/mappings", mapOf("columnId" to deletedCol, "defId" to mappedDefId))
        assertEquals(HttpStatus.CREATED, mapped.statusCode, "매핑 생성 실패: ${mapped.body}")
    }

    @Test
    @Order(2)
    fun `막는다 - 매핑이 있는 요건의 kind를 MASK로 바꿀 수 없다 (유출 방향)`() {
        val res = put("/api/catalog/defs/$mappedDefId",
            defBody("MASK", "삭제되지 않은 것만", "mask_name({col})"))

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode,
            "요건(INTEGRITY)을 MASK로 바꿨는데 통과했다 — 요건이 조용히 사라진다: ${res.body}")
        assertTrue(res.body.toString().contains("강제 방식"),
            "거절 사유가 kind 변경임을 말해야 한다: ${res.body}")
    }

    @Test
    @Order(3)
    fun `막는다 - 매핑이 있는 요건의 강제식을 판정 불가 형태로 바꿀 수 없다 (과차단 방향)`() {
        // `LIKE`는 요건 정규형(컬럼 = 리터럴 / IN 단일값)이 아니다.
        val res = put("/api/catalog/defs/$mappedDefId",
            defBody("INTEGRITY", "삭제되지 않은 것만", "{col} LIKE 'N%'"))

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode,
            "판정 불가 형태로 바꿨는데 통과했다 — 이 테이블 전 쿼리가 '검증할 수 없습니다'가 된다: ${res.body}")
    }

    @Test
    @Order(4)
    fun `과차단하지 않는다 - 매핑이 없으면 kind를 바꿀 수 있다`() {
        val res = put("/api/catalog/defs/$freeDefId",
            defBody("MASK", "매핑 없는 요건", "mask_name({col})"))

        assertEquals(HttpStatus.OK, res.statusCode,
            "매핑이 없는데도 막혔다 — 가드가 필요 이상으로 넓다: ${res.body}")
    }

    @Test
    @Order(5)
    fun `과차단하지 않는다 - 매핑이 있어도 같은 형태의 강제식 변경은 통과한다`() {
        val res = put("/api/catalog/defs/$mappedDefId",
            defBody("INTEGRITY", "삭제되지 않은 것만(수정)", "{col} = 'X'"))

        assertEquals(HttpStatus.OK, res.statusCode,
            "판정 가능한 형태인데 막혔다 — 스튜어드가 오타 하나 못 고친다: ${res.body}")
    }
}
