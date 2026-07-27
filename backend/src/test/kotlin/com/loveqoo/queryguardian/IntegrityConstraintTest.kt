package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.catalog.ColumnClass
import com.loveqoo.queryguardian.catalog.ConstraintDef
import com.loveqoo.queryguardian.catalog.ConstraintDefRepository
import com.loveqoo.queryguardian.catalog.ConstraintMapping
import com.loveqoo.queryguardian.catalog.ConstraintMappingRepository
import com.loveqoo.queryguardian.catalog.DefKind
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
 * **INTEGRITY 제약은 판정이 요구한다** (spec 012 §7-1, spec 013 S1).
 *
 * ## 왜 이 파일이 생겼나
 *
 * 실측(2026-07-27): `INTEGRITY` 매핑을 요구하는 룰이 **하나도 없었다**.
 * `DbTableCatalog.requiredPredicates`가 `DefKind.FILTER`만 걸렀으므로, 무결성 조건의 유일한 강제
 * 수단은 **실행 시점의 술어 주입**이었다. 그런데 spec 012는 그 주입을 걷어내기로 정했다
 * (I1 — 서버가 사용자 SQL의 조건을 고치지 않는다).
 *
 * 순서를 뒤집으면 그 사이 구간이 fail-open이다: 스튜어드가 등록한 무결성 조건이 화면엔 있고
 * 아무 일도 하지 않는다. 그래서 **판정이 먼저 덮고**(이 파일), 그 다음에 주입을 지운다.
 *
 * ## 왜 형태 테스트가 아니라 여기인가
 *
 * [ShapeCoverageTest]는 `InMemoryTableCatalog`를 쓰고 그 픽스처는 **제약 종류를 구별하지 않는다**
 * (`required`가 이미 정규형 목록이다). FILTER와 INTEGRITY의 차이는 `DbTableCatalog`에만 있으므로,
 * 그 차이를 재려면 실제 카탈로그 등록 경로를 밟아야 한다.
 *
 * ## 축 둘 — 둘 다 없으면 안 된다
 *
 * 1. **판정이 요구한다**(1~3): 조건이 없으면 막고, 쓰면 통과한다.
 * 2. **등록이 거절한다**(4): 판정할 수 없는 형태의 INTEGRITY는 **매핑 시점에** 막는다.
 *    이것이 없으면 등록은 조용히 성공하고, 그 테이블을 조회하는 **모든** 쿼리가 나중에
 *    "검증할 수 없습니다"로 차단된다 — 등록자는 자기가 무엇을 했는지 모른다.
 *    FILTER에는 이 가드가 이미 있었다(spec 004 C2). 판정 축을 넓히면 가드도 같이 넓어져야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IntegrityConstraintTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")

        var deletedColId = 0L
        var noteColId = 0L
    }

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var defs: ConstraintDefRepository

    @Autowired
    lateinit var mappings: ConstraintMappingRepository

    private val client by lazy { SessionClient(rest) }

    private fun post(path: String, body: Any) = client.postAs(path, "adm1", body)

    private fun lint(sql: String) =
        post("/api/lint", mapOf("dialect" to "MYSQL", "sql" to sql)).body!!

    private fun defId(kind: String, name: String, expression: String): Long =
        (post("/api/catalog/defs", mapOf(
            "cls" to "STRING", "kind" to kind, "name" to name, "expression" to expression,
        )).body!!["id"] as Number).toLong()

    @Test
    @Order(1)
    fun `카탈로그 - 소프트 삭제 테이블에 INTEGRITY 매핑`() {
        val table = post("/api/catalog/tables", mapOf(
            "name" to "archived_docs",
            "columns" to listOf(
                mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
                mapOf("name" to "deleted_yn", "type" to "CHAR(1)", "isPii" to false),
                mapOf("name" to "note", "type" to "VARCHAR(200)", "isPii" to false),
            ),
        )).body!!
        fun colId(name: String) = ((table["columns"] as List<*>)
            .first { (it as Map<*, *>)["name"] == name } as Map<*, *>).let { (it["id"] as Number).toLong() }
        deletedColId = colId("deleted_yn")
        noteColId = colId("note")

        val integrity = defId("INTEGRITY", "삭제되지 않은 것만", "{col} = 'N'")
        assertEquals(
            HttpStatus.CREATED,
            post("/api/catalog/mappings", mapOf("columnId" to deletedColId, "defId" to integrity)).statusCode,
            "판정 가능한 형태의 INTEGRITY 매핑은 등록돼야 한다",
        )
    }

    @Test
    @Order(2)
    fun `무결성 조건을 안 쓰면 막힌다`() {
        val report = lint("SELECT id FROM archived_docs LIMIT 10")
        assertEquals(
            true, report["blocked"],
            "INTEGRITY 제약이 걸린 테이블을 조건 없이 조회했는데 통과했다 — 주입을 걷어내면 이 자리가 " +
                "완전히 열린다(spec 012 §7-1): $report",
        )
        assertTrue(
            report["violations"].toString().contains("require-predicate"),
            "차단은 됐으나 이유가 필수 술어가 아니다 — 다른 룰이 우연히 막은 것이면 이 축은 여전히 비었다: $report",
        )
    }

    @Test
    @Order(3)
    fun `무결성 조건을 쓰면 통과한다`() {
        val report = lint("SELECT id FROM archived_docs WHERE deleted_yn = 'N' LIMIT 10")
        assertEquals(
            false, report["blocked"],
            "사용자가 무결성 조건을 직접 썼는데 막혔다 — 그러면 고칠 방법이 없는 제품이 된다(I5): $report",
        )
    }

    @Test
    @Order(4)
    fun `판정할 수 없는 형태의 INTEGRITY는 매핑을 거절한다`() {
        // `LIKE`는 판정 정규형(= 리터럴 / IN 단일값)이 아니다. FILTER라면 spec 004 C2가 이미 거절한다.
        val unjudgeable = defId("INTEGRITY", "삭제 흔적 없음", "{col} NOT LIKE '%deleted%'")
        val response = post("/api/catalog/mappings", mapOf("columnId" to noteColId, "defId" to unjudgeable))
        assertEquals(
            HttpStatus.BAD_REQUEST, response.statusCode,
            "판정 불가 형태의 INTEGRITY 매핑이 등록됐다 — 이제 archived_docs를 조회하는 모든 쿼리가 " +
                "'검증할 수 없습니다'로 차단되는데 등록자는 그 사실을 모른다: ${response.body}",
        )
    }

    /**
     * **KDoc이 약속한 범위의 경계 밖에 심는다** (learning 018 §8-a).
     *
     * `DbTableCatalog.requiredPredicates`는 purpose 스코프를 FILTER에만 적용한다. 그 근거는
     * "등록 검증이 purposeCode를 FILTER에만 허용한다"인데, **등록 경로만 막혀 있다** — 시드 스크립트·
     * 마이그레이션·리포지토리 직접 호출은 그 검증을 지나가지 않는다. 그래서 서비스가 아니라
     * 리포지토리로 직접 심어, 그 줄이 정말 요건을 좁히지 않는지 잰다.
     *
     * 좁히면 fail-open이다: 무결성 조건이 특정 purpose에서만 요구되고 나머지에서는 사라진다.
     */
    @Test
    @Order(5)
    fun `purposeCode가 붙은 INTEGRITY도 요건을 잃지 않는다`() {
        val def = defs.save(ConstraintDef(
            cls = ColumnClass.STRING, kind = DefKind.INTEGRITY,
            name = "보관 표시 필수", expression = "{col} = 'Y'",
        ))
        // 서비스를 거치지 않는다 — createMapping은 non-FILTER의 purposeCode를 거절하므로 이 상태를 만들 수 없다.
        val mapping = mappings.save(ConstraintMapping(
            columnId = noteColId, defId = def.id!!, purposeCode = "marketing",
        ))
        try {
            // lint는 requestId 없이 부르므로 purposeCode = null이다. purpose 필터가 INTEGRITY에도
            // 걸리면 이 요건은 사라지고 쿼리가 통과한다.
            val report = lint("SELECT id FROM archived_docs WHERE deleted_yn = 'N' LIMIT 10")
            assertEquals(
                true, report["blocked"],
                "purposeCode가 붙은 INTEGRITY 요건이 purpose 불일치로 사라졌다 — 요건을 좁히는 것은 " +
                    "fail-open이다: $report",
            )
        } finally {
            mappings.delete(mapping)
            defs.delete(def)
        }
    }

    @Test
    @Order(6)
    fun `거절된 매핑은 판정을 바꾸지 않는다`() {
        // 4가 거절됐으므로 3의 통과가 그대로여야 한다. 거절이 부분 적용되면 여기서 드러난다.
        val report = lint("SELECT id FROM archived_docs WHERE deleted_yn = 'N' LIMIT 10")
        assertEquals(
            false, report["blocked"],
            "매핑 거절이 부분 적용돼 판정이 달라졌다: $report",
        )
    }
}
