package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.auth.UserTablePermission
import com.loveqoo.queryguardian.auth.UserTablePermissionRepository
import com.loveqoo.queryguardian.catalog.ColumnClass
import com.loveqoo.queryguardian.catalog.ConstraintDef
import com.loveqoo.queryguardian.catalog.ConstraintDefRepository
import com.loveqoo.queryguardian.catalog.ConstraintMapping
import com.loveqoo.queryguardian.catalog.ConstraintMappingRepository
import com.loveqoo.queryguardian.catalog.DefKind
import com.loveqoo.queryguardian.exec.DemoTableMapRepository
import com.loveqoo.queryguardian.exec.DemoTableMapping
import com.loveqoo.queryguardian.exec.ExecutionEvent
import com.loveqoo.queryguardian.exec.ExecutionEventRepository
import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.exec.PlanOutcome
import com.loveqoo.queryguardian.exec.QueryExecutor
import com.loveqoo.queryguardian.exec.RewritePlanner
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.parser.SqlRewriter
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
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
 * spec 010 P0 · 수용 기준 A0 — **감사 코드 전수 검증**.
 *
 * 왜 이 테스트가 재설계보다 먼저인가: 착수 시점의 감사 코드 21종 중 테스트가 단정하던 것은 **3종**이었다
 * (`TABLES_UNKNOWN`·`REQUESTER_MISMATCH`·`FORBIDDEN_READ`). 나머지 18종은 "코드에 적혀 있다"는 것 외에
 * 아무 근거가 없었다 — 발생하는지, 감사에 남는지, 어떤 상태로 응답하는지 누구도 확인한 적이 없다.
 * 그 상태에서 게이트를 재작성하면 **사라진 코드를 아무도 눈치채지 못한다.** 안전망이 먼저다.
 *
 * ## 굿하트 방지 (spec 010 §3 A0)
 *
 * "감사 코드마다 테스트가 존재한다"는 값싼 대리 변수다 — 빈 단정으로도 만족된다. 대신 요구한다:
 * ⑴ [AuditCode] **전 값**을 `@EnumSource`로 돌린다. 값을 추가하면 이 테스트가 즉시 실패한다.
 * ⑵ 시나리오는 전부 **실제 HTTP 요청**이다 — 감사 API를 직접 부르지 않는다.
 * ⑶ 요청 뒤 새로 생긴 `execution_event` 행이 **정확히 하나**이고, 그 행의 `error_code`·`outcome`·
 *    `query_id` 유무, HTTP 상태, **응답 본문의 분류 코드**가 전부 기대와 같은지 본다. 하나라도 어긋나면
 *    실패한다. 특히 본문 코드를 계약에 넣은 이유: 감사에는 남는데 응답에는 안 실리는 경로가 **9개**이고
 *    (`null`로 적혀 있다), 그것이 리뷰 R3가 "스타일이 아니라 계약 결함"이라 지목한 지점이다.
 *
 * ## 축은 "코드별"이 아니라 **"코드 × 진입점"** 이다
 *
 * 첫 판(코드당 시나리오 하나)은 적대 검토에서 뚫렸다. `TABLES_UNKNOWN`·`NOT_APPROVED` 같은 코드는
 * `execute`와 `previewRewrite` **양쪽**에서 나는데 미리보기 쪽만 고정하면, `execute`의 감사 래퍼
 * (`try { … } catch (e: AccessBlockedException) { audit.record(…); throw }`)를 통째로 지워도 테스트가
 * 그린이다. 그리고 그 결함은 **이 저장소에서 실제로 한 번 일어났다** — `QueryExecutionService`의
 * "예전엔 여기서 예외가 그대로 빠져나가 403인데 감사 0건이었다"는 주석이 그 자리다.
 * 그래서 두 진입점을 각각 고정한다.
 *
 * ## 협력자 주입(6종)에 대하여
 *
 * `REWRITE_MASK_NOT_EXPRESSIBLE`·`REWRITE_SCOPE_NOT_FOUND`·`REWRITE_VERIFY_FAILED`·`REWRITE_NO_LIMIT`·
 * `TIMEOUT`·`CONNECTION`은 **정상 입력으로는 도달할 수 없는 방어선**이다(도달한다면 그것 자체가 상류
 * 버그다). 이 사실 자체가 A0이 처음 밝혀낸 것이다 — 특히 `REWRITE_MASK_NOT_EXPRESSIBLE`은 판정의
 * mask 룰이 **같은 기준으로 먼저 BLOCK**하므로 판정과 재작성이 갈라질 때만 발화한다. 이 6종만 협력자
 * 빈(3개)을 spy로 강제하되, **요청은 여전히 HTTP를 통과하고 게이트 본문은 실제로 실행된다** — 검증
 * 대상인 "게이트가 그 실패를 어떤 감사 코드로 번역하는가"는 진짜 경로에서 확인된다.
 *
 * ## 이 테스트가 기록하는 현행 계약의 어긋남
 *
 * 재작성 실패 6종은 **403**으로 응답한다. 권한 실패가 아닌데도 그렇다. P0은 이것을 고치지 않고
 * **있는 그대로 고정**한다 — P1(A2)이 응답 코드를 `GateStop` 하나로 모을 때 이 단정들이 "무엇이
 * 바뀌었는지"를 정확히 짚어 준다. 안전망은 현실을 재야 안전망이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditCodeCoverageTest {

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

    /**
     * 픽스처는 **인스턴스 필드**다. companion object에 두면 Spring 컨텍스트·컨테이너 수명과 끊어져,
     * 컨텍스트가 재생성됐는데 옛 DB id가 남으면 엉뚱한 행을 대상으로 시나리오가 돈다(codex 검토 #4).
     * `PER_CLASS`가 인스턴스를 클래스당 하나로 묶어 주므로 전역 상태 없이 한 번만 만든다.
     */
    private var fixtures: Fixtures? = null

    /** 준비 실패는 **한 번만** 진단된다 — 재시도하면 근인이 20개의 무관한 실패에 묻힌다. */
    private var fixtureFailure: Throwable? = null

    data class Fixtures(
        val approvedRequestId: Long,
        val pendingRequestId: Long,
        val narrowRequestId: Long,
        val approvedQueryId: Long,
        val pendingReviewQueryId: Long,
        val sqlErrorQueryId: Long,
        /** 저장·검토 뒤에 **권한이 회수된** 쿼리 — execute 경로의 권한 감사 래퍼용 */
        val revokedQueryId: Long,
        /** 저장·검토 뒤에 **승인이 취소된** 쿼리 — execute 경로의 승인 감사 래퍼용 */
        val unapprovedQueryId: Long,
        /** 저장·검토 뒤에 **BLOCK 제약이 생긴** 쿼리 — execute 경로의 완전 재판정용 */
        val relintQueryId: Long,
    )

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var executionEvents: ExecutionEventRepository
    @Autowired lateinit var demoTableMap: DemoTableMapRepository
    @Autowired lateinit var defs: ConstraintDefRepository
    @Autowired lateinit var mappings: ConstraintMappingRepository
    @Autowired lateinit var tablePermissions: UserTablePermissionRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @MockitoSpyBean lateinit var executor: QueryExecutor
    @MockitoSpyBean lateinit var rewriter: SqlRewriter
    @MockitoSpyBean lateinit var planner: RewritePlanner

    private val client by lazy { SessionClient(rest) }

    // ---- 시나리오 ---------------------------------------------------------

    /**
     * 한 감사 코드를 한 진입점에서 발생시키는 방법.
     * [status]는 **현행 동작**이며 P1이 바꿀 대상이다 — 지금 고정해 두어야 그 변경이 보인다.
     */
    private data class Scenario(
        val code: AuditCode,
        val entry: String,
        val status: HttpStatus,
        val outcome: ExecutionOutcome,
        val expectQueryId: Boolean,
        /**
         * 응답 **본문**의 분류 코드. `null`은 "이 경로는 응답에 코드를 싣지 않는다"는 **현행 사실**이다 —
         * 감사에는 남는 코드가 사용자에게는 전달되지 않는 지점이고(리뷰 R3), P1(A2)이 닫을 대상이다.
         * 여기에 적어 두어야 P1의 변경이 "무엇이 달라졌는지"로 드러난다.
         */
        val bodyCode: String? = null,
        /** 요청을 보낸 사람. 감사 행이 **그 요청의 것인지** 확인하는 연결 키다(codex 검토 #1). */
        val actor: String = "u1",
        val run: (Fixtures) -> ResponseEntity<*>,
    )

    private fun execScenario(
        code: AuditCode,
        status: HttpStatus,
        outcome: ExecutionOutcome = ExecutionOutcome.BLOCKED,
        bodyCode: String? = null,
        actor: String = "u1",
        run: (Fixtures) -> ResponseEntity<*>,
    ) = Scenario(code, "execute", status, outcome, expectQueryId = true, bodyCode = bodyCode,
        actor = actor, run = run)

    private fun previewScenario(
        code: AuditCode,
        status: HttpStatus,
        bodyCode: String? = null,
        run: (Fixtures) -> ResponseEntity<*>,
    ) = Scenario(code, "preview", status, ExecutionOutcome.BLOCKED, expectQueryId = false,
        bodyCode = bodyCode, run = run)

    // 남이 보낸 요청은 actor를 명시한다 — 감사가 **시도한 사람**을 남기는지가 이 코드들의 존재 이유다.

    private fun scenarios(): List<Scenario> = listOf(
        // ── 열람 ──
        execScenario(AuditCode.FORBIDDEN_READ, HttpStatus.FORBIDDEN, actor = "u2") { f ->
            // 남의 저장 쿼리 id로 실행 시도 — 열거 시도 자체가 감사 대상이다
            client.postAs("/api/queries/${f.approvedQueryId}/execute", "u2")
        },

        // ── 승인 ──
        previewScenario(AuditCode.NO_REQUEST, HttpStatus.FORBIDDEN) {
            preview("SELECT email FROM users", requestId = null)
        },
        previewScenario(AuditCode.NOT_APPROVED, HttpStatus.FORBIDDEN, bodyCode = "NOT_APPROVED") { f ->
            preview("SELECT email FROM users", f.pendingRequestId)
        },
        // execute 경로의 **승인 감사 래퍼**를 고정한다 — 승인은 저장 뒤에도 뒤집힐 수 있고(§5 완전 재판정),
        // 그때 감사가 남지 않으면 "403인데 기록 0건"이 되돌아온다.
        execScenario(AuditCode.NOT_APPROVED, HttpStatus.FORBIDDEN, bodyCode = "NOT_APPROVED") { f ->
            client.postAs("/api/queries/${f.unapprovedQueryId}/execute", "u1")
        },
        execScenario(AuditCode.REQUESTER_MISMATCH, HttpStatus.FORBIDDEN, actor = "ap1") { f ->
            // STEWARD는 열람은 되지만 **대행 실행은 불가**(spec 008 결정 14)
            client.postAs("/api/queries/${f.approvedQueryId}/execute", "ap1")
        },
        previewScenario(AuditCode.TABLES_NOT_COVERED, HttpStatus.FORBIDDEN, bodyCode = "TABLES_NOT_COVERED") { f ->
            preview("SELECT id FROM marketing_consents", f.narrowRequestId)
        },
        execScenario(AuditCode.NOT_REVIEWED, HttpStatus.FORBIDDEN) { f ->
            client.postAs("/api/queries/${f.pendingReviewQueryId}/execute", "u1")
        },

        // ── 데이터 권한 ──
        previewScenario(AuditCode.TABLES_UNKNOWN, HttpStatus.FORBIDDEN, bodyCode = "TABLES_UNKNOWN") { f ->
            preview("SELECT id FROM ghost_table", f.approvedRequestId)
        },
        previewScenario(AuditCode.TABLES_NOT_PERMITTED, HttpStatus.FORBIDDEN, bodyCode = "TABLES_NOT_PERMITTED") { f ->
            preview("SELECT id FROM denied_table", f.approvedRequestId)
        },
        // execute 경로의 **권한 감사 래퍼**. 권한은 저장 후 회수될 수 있다(spec 007 — 비소급).
        execScenario(AuditCode.TABLES_NOT_PERMITTED, HttpStatus.FORBIDDEN, bodyCode = "TABLES_NOT_PERMITTED") { f ->
            client.postAs("/api/queries/${f.revokedQueryId}/execute", "u1")
        },

        // ── 접수·판정 ──
        previewScenario(AuditCode.PARSE_FAILED, HttpStatus.UNPROCESSABLE_ENTITY) { f ->
            preview("SELEC email FROM users", f.approvedRequestId)
        },
        previewScenario(AuditCode.RULE_BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY) { f ->
            preview("SELECT ssn FROM users", f.approvedRequestId)
        },
        // execute 경로의 **완전 재판정**: 저장·검토를 통과한 쿼리라도 지금 카탈로그로 다시 본다.
        execScenario(AuditCode.RULE_BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY) { f ->
            client.postAs("/api/queries/${f.relintQueryId}/execute", "u1")
        },

        // ── 실행 대상 매핑 ──
        previewScenario(AuditCode.NO_DEMO_MAPPING, HttpStatus.FORBIDDEN) { f ->
            preview("SELECT id FROM orphan_table", f.approvedRequestId)
        },
        previewScenario(AuditCode.INVALID_PHYSICAL_NAME, HttpStatus.FORBIDDEN) { f ->
            preview("SELECT id FROM bad_map_table", f.approvedRequestId)
        },

        // ── 재작성 ──
        previewScenario(AuditCode.REWRITE_MASK_NOT_EXPRESSIBLE, HttpStatus.FORBIDDEN) { f ->
            // 실측: `SELECT UPPER(email) FROM users`는 여기 오지 못한다 — 판정의 mask 룰이 같은
            // 기준(MaskUsage)으로 **먼저 BLOCK**한다(RuleImpls: "실행 단계에서 거부될 쿼리를 저장
            // 시점에 막는다"). 즉 이 코드는 판정과 재작성이 갈라질 때만 발화하는 2선 방어다.
            forcePlanRefusal(RewriteRefusal.MASK_NOT_EXPRESSIBLE)
            preview("SELECT email FROM users", f.approvedRequestId)
        },
        previewScenario(AuditCode.REWRITE_OUTER_JOIN_FILTER, HttpStatus.FORBIDDEN) { f ->
            // 주입이 LEFT JOIN을 INNER로 바꾸므로 fail-closed. 필수 술어를 **직접 써서** 판정을 통과시킨
            // 뒤에야 이 거부가 드러난다 — WHERE가 없으면 require-predicate(BLOCK)가 먼저 잡는다.
            preview(
                "SELECT u.id FROM users u LEFT JOIN marketing_consents c ON c.user_id = u.id " +
                    "WHERE c.consent_yn = 'Y'",
                f.approvedRequestId,
            )
        },
        previewScenario(AuditCode.REWRITE_EXPRESSION_NOT_USABLE, HttpStatus.FORBIDDEN) { f ->
            // 강제식이 파싱되지 않는다 — 등록 검증을 우회해 들어온 행(마이그레이션·시드)을 가정한다
            preview("SELECT note FROM broken_mask_table", f.approvedRequestId)
        },
        previewScenario(AuditCode.REWRITE_SCOPE_NOT_FOUND, HttpStatus.FORBIDDEN) { f ->
            forceRewriteRefusal(RewriteRefusal.SCOPE_NOT_FOUND)
            preview("SELECT email FROM users", f.approvedRequestId)
        },
        previewScenario(AuditCode.REWRITE_VERIFY_FAILED, HttpStatus.FORBIDDEN) { f ->
            forceRewriteRefusal(RewriteRefusal.VERIFY_FAILED)
            preview("SELECT email FROM users", f.approvedRequestId)
        },
        execScenario(AuditCode.REWRITE_NO_LIMIT, HttpStatus.FORBIDDEN) { f ->
            // 계획에 상한이 없으면 실행하지 않는다 — 상한 없는 반출을 허용하지 않는 fail-closed
            forcePlanWithoutLimit()
            client.postAs("/api/queries/${f.approvedQueryId}/execute", "u1")
        },

        // ── 실행 인프라 ──
        execScenario(AuditCode.SQL_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, ExecutionOutcome.ERROR,
            bodyCode = "SQL_ERROR") { f ->
            // 카탈로그에는 있으나 물리 테이블에 없는 컬럼 — 실제 MySQL 오류다(주입 아님)
            client.postAs("/api/queries/${f.sqlErrorQueryId}/execute", "u1")
        },
        execScenario(AuditCode.TIMEOUT, HttpStatus.UNPROCESSABLE_ENTITY, ExecutionOutcome.ERROR,
            bodyCode = "TIMEOUT") { f ->
            forceExecutionFailure(ExecutionFailure.Kind.TIMEOUT)
            client.postAs("/api/queries/${f.approvedQueryId}/execute", "u1")
        },
        execScenario(AuditCode.CONNECTION, HttpStatus.UNPROCESSABLE_ENTITY, ExecutionOutcome.ERROR,
            bodyCode = "CONNECTION") { f ->
            forceExecutionFailure(ExecutionFailure.Kind.CONNECTION)
            client.postAs("/api/queries/${f.approvedQueryId}/execute", "u1")
        },
    )

    // ---- 검증 -------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(AuditCode::class)
    fun `모든 감사 코드는 실제 요청에서 발생하고 감사에 기록된다`(code: AuditCode) {
        val f = ensureFixtures()
        val matching = scenarios().filter { it.code == code }
        assertTrue(
            matching.isNotEmpty(),
            "감사 코드 $code 에 대응하는 시나리오가 없다. 코드를 추가했다면 '그 코드가 실제로 발생하고 " +
                "기록되는가'를 여기서 증명해야 한다 — 증명할 수 없는 코드는 감사 어휘가 아니라 죽은 문자열이다.",
        )

        for (scenario in matching) {
            // 한 코드에 시나리오가 여럿이면 앞 시나리오의 스텁이 뒤로 샌다 — 매번 실물로 되돌린다.
            Mockito.reset(planner, rewriter, executor)

            val before = latestEventId()
            val response = scenario.run(f)
            val fresh = executionEvents.findAll().filter { (it.id ?: 0) > before }
            val where = "$code (${scenario.entry})"

            assertEquals(1, fresh.size, "$where: 감사 행이 정확히 1건이 아니다 — ${describe(fresh)}")
            val event = fresh.single()
            assertEquals(code.name, event.errorCode, "$where: 감사 코드가 다르다 — ${describe(fresh)}")
            assertEquals(scenario.outcome.name, event.outcome, "$where: outcome이 다르다")
            assertEquals(
                scenario.expectQueryId, event.queryId != null,
                "$where: query_id 유무가 다르다 — 없으면 `/api/queries/{id}/executions` 이력에서 사라진다",
            )
            assertEquals(
                scenario.actor, event.actor,
                "$where: 감사에 남은 actor가 요청자와 다르다 — 다른 요청의 행을 보고 통과했을 수 있다",
            )
            assertEquals(scenario.status, response.statusCode, "$where: 응답 상태가 달라졌다")
            assertEquals(
                scenario.bodyCode, (response.body as? Map<*, *>)?.get("code"),
                "$where: 응답 본문의 분류 코드가 달라졌다 — 감사와 응답이 갈라지는 자리다",
            )
        }
    }

    /** `error_code VARCHAR(32)` — 넘치면 조용히 잘려 사후 분류가 어긋난다. */
    @Test
    fun `감사 코드 이름은 저장 폭 안에 있다`() {
        val tooLong = AuditCode.entries.filter { it.name.length > AuditCode.MAX_LENGTH }
        assertTrue(tooLong.isEmpty(), "error_code VARCHAR(32)를 넘는다: $tooLong")
    }

    /**
     * 재작성 거부 사유와 감사 코드가 **이름으로** 1:1인지. 개수만 세면 한쪽에 값을 넣고 다른 쪽에서
     * 빼는 변경이 통과한다 — 짝을 검사해야 짝이 지켜진다.
     */
    @Test
    fun `재작성 거부 사유는 모두 감사 코드를 갖는다`() {
        val fromRefusals = RewriteRefusal.entries.map { "REWRITE_${it.name}" }.toSet()
        // REWRITE_NO_LIMIT은 RewriteRefusal이 아니라 게이트 자체의 거부다(계획에 상한이 없음).
        val declared = AuditCode.entries.map { it.name }.filter { it.startsWith("REWRITE_") }.toSet() -
            AuditCode.REWRITE_NO_LIMIT.name
        assertEquals(fromRefusals, declared, "RewriteRefusal과 REWRITE_* 감사 코드가 어긋난다")
    }

    /**
     * 실행 실패 3종이 감사 코드와 짝지어져 있는지. spy가 실제 JDBC 분류 경로(`SQLTimeoutException` →
     * `TIMEOUT` 등)를 건너뛰므로(codex 검토 #3), 최소한 **짝 자체**는 구조로 고정한다.
     * 실제 분류 경로의 발화 검증은 spec 010 P1(A7)로 넘긴다 — 여기서 할 수 있는 척하지 않는다.
     */
    @Test
    fun `실행 실패 종류는 모두 감사 코드를 갖는다`() {
        assertEquals(
            setOf(AuditCode.TIMEOUT, AuditCode.SQL_ERROR, AuditCode.CONNECTION),
            ExecutionFailure.Kind.entries.map { it.auditCode }.toSet(),
            "ExecutionFailure.Kind와 감사 코드의 짝이 어긋난다",
        )
    }

    /** 두 진입점 모두를 지나는 코드는 **양쪽 다** 고정돼야 한다 — 한쪽만 있으면 다른 쪽 감사가 무보호다. */
    @Test
    fun `execute 경로의 감사 래퍼가 시나리오로 고정돼 있다`() {
        val byEntry = scenarios().groupBy { it.entry }.mapValues { (_, v) -> v.map { it.code }.toSet() }
        val executeCodes = byEntry["execute"].orEmpty()
        // 이 셋은 execute의 try/catch 감사 래퍼와 완전 재판정을 각각 대표한다.
        for (required in listOf(AuditCode.TABLES_NOT_PERMITTED, AuditCode.NOT_APPROVED, AuditCode.RULE_BLOCKED)) {
            assertTrue(required in executeCodes, "$required 의 execute 경로 시나리오가 사라졌다")
        }
    }

    // ---- 협력자 강제 ------------------------------------------------------

    private fun forceRewriteRefusal(refusal: RewriteRefusal) {
        doAnswer { RewriteOutcome.Refused(refusal, "주입된 거부($refusal)") }
            .`when`(rewriter).rewrite(any(), any(), any(), any())
    }

    private fun forcePlanRefusal(refusal: RewriteRefusal) {
        doAnswer { PlanOutcome.Refused(refusal, "주입된 거부($refusal)") }
            .`when`(planner).plan(any(), anyOrNull(), any())
    }

    private fun forcePlanWithoutLimit() {
        doAnswer { invocation ->
            when (val real = invocation.callRealMethod()) {
                is PlanOutcome.Planned -> PlanOutcome.Planned(real.plan.copy(limitCap = null))
                else -> real
            }
        }.`when`(planner).plan(any(), anyOrNull(), any())
    }

    private fun forceExecutionFailure(kind: ExecutionFailure.Kind) {
        doThrow(ExecutionFailure(kind, "주입된 실패($kind)"))
            .`when`(executor).execute(any(), any(), any())
    }

    // ---- 준비물 -----------------------------------------------------------

    private fun describe(events: List<ExecutionEvent>) =
        events.map { "${it.outcome}/${it.errorCode}/query=${it.queryId}" }

    private fun latestEventId(): Long = executionEvents.findAll().mapNotNull { it.id }.maxOrNull() ?: 0L

    private fun preview(sql: String, requestId: Long?) = client.postAs(
        "/api/preview-rewrite", "u1", mapOf("sql" to sql, "requestId" to requestId))

    private fun post(path: String, body: Map<String, Any?>) = client.postAs(path, "adm1", body)

    private fun ensureFixtures(): Fixtures {
        fixtureFailure?.let { throw IllegalStateException("준비물 생성이 이미 실패했다(근인은 첫 실패를 보라)", it) }
        fixtures?.let { return it }
        return try {
            buildFixtures().also { fixtures = it }
        } catch (e: Throwable) {
            // 재시도하면 중복 등록(409·UNIQUE 위반)이 근인을 덮어쓴다 — 첫 실패를 보존한다.
            fixtureFailure = e
            throw e
        }
    }

    private fun buildFixtures(): Fixtures {
        ensureDemoSchema()
        post("/api/catalog/purposes", mapOf("code" to "marketing", "description" to "마케팅"))

        // users — email은 MASK, ssn은 BLOCK, nickname은 **물리 테이블에 없다**(SQL_ERROR용)
        val users = post("/api/catalog/tables", mapOf("name" to "users", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "email", "type" to "VARCHAR(255)", "isPii" to true),
            mapOf("name" to "ssn", "type" to "CHAR(13)", "isPii" to true),
            mapOf("name" to "nickname", "type" to "VARCHAR(50)", "isPii" to false)))).body!!
        val consents = post("/api/catalog/tables", mapOf("name" to "marketing_consents", "columns" to listOf(
            mapOf("name" to "id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "user_id", "type" to "BIGINT", "isPii" to false),
            mapOf("name" to "consent_yn", "type" to "CHAR(1)", "isPii" to false)))).body!!
        // 매핑 없음 → NO_DEMO_MAPPING
        simpleTable("orphan_table", "id", "BIGINT", mapTo = null)
        // 매핑은 있으나 물리명이 식별자 규칙 위반 → INVALID_PHYSICAL_NAME
        simpleTable("bad_map_table", "id", "BIGINT", mapTo = "demo bad!")
        // 권한 회수 대상 2종 — 미리보기용/실행용을 나눈다(회수 시점이 다르다)
        simpleTable("denied_table", "id", "BIGINT", mapTo = "demo_users")
        simpleTable("revoked_table", "id", "BIGINT", mapTo = "demo_users")
        // 저장 후 BLOCK 제약이 생기는 테이블 → execute 경로의 완전 재판정
        val relint = simpleTable("relint_table", "secret", "VARCHAR(50)", mapTo = "demo_users")
        // 파싱 불가 강제식 → REWRITE_EXPRESSION_NOT_USABLE
        val broken = simpleTable("broken_mask_table", "note", "VARCHAR(50)", mapTo = "demo_users")

        val maskDef = defId("PII", "MASK", "이메일 마스킹", "mask_email({col})")
        val blockPiiDef = defId("PII", "BLOCK", "조회 차단", null)
        val filterDef = defId("STRING", "FILTER", "동의자만", "{col} = 'Y'")
        post("/api/catalog/mappings", mapOf("columnId" to columnId(users, "email"), "defId" to maskDef))
        post("/api/catalog/mappings", mapOf("columnId" to columnId(users, "ssn"), "defId" to blockPiiDef))
        post("/api/catalog/mappings", mapOf(
            "columnId" to columnId(consents, "consent_yn"), "defId" to filterDef, "purposeCode" to "marketing"))

        // 등록 API는 파싱 가능성을 검증하므로 **저장소로 직접** 넣는다 — 시드·마이그레이션으로 들어온
        // 행을 재현하는 것이고, 그 경우를 위해 계획 수립기에 방어선이 있다.
        val brokenDef = defs.save(ConstraintDef(
            cls = ColumnClass.STRING, kind = DefKind.MASK, name = "깨진 마스크", expression = "broken_fn({col}"))
        mappings.save(ConstraintMapping(columnId = columnId(broken, "note"), defId = brokenDef.id!!))

        val approvedRequestId = approval("전수 검증", listOf(
            "users", "marketing_consents", "orphan_table", "bad_map_table",
            "denied_table", "revoked_table", "relint_table", "broken_mask_table"), approve = true)
        val pendingRequestId = approval("미승인", listOf("users"), approve = false)
        val narrowRequestId = approval("좁은 범위", listOf("users"), approve = true)
        val revokableRequestId = approval("승인 취소 대상", listOf("users"), approve = true)

        val approvedQueryId = savedQuery("이메일 조회", "SELECT email FROM users", approvedRequestId, review = true)
        val pendingReviewQueryId = savedQuery("미검토", "SELECT id FROM users", approvedRequestId, review = false)
        val sqlErrorQueryId = savedQuery("없는 컬럼", "SELECT nickname FROM users", approvedRequestId, review = true)
        val revokedQueryId = savedQuery("권한 회수 예정", "SELECT id FROM revoked_table", approvedRequestId, review = true)
        val unapprovedQueryId = savedQuery("승인 취소 예정", "SELECT id FROM users", revokableRequestId, review = true)
        val relintQueryId = savedQuery("재판정 대상", "SELECT secret FROM relint_table", approvedRequestId, review = true)

        // ── 여기서부터가 "저장 이후에 세상이 바뀐다" 부분이다. 순서가 중요하다 —
        //    저장·검토가 먼저 끝나야 게이트가 **실행 시점에** 처음 이 변화를 만난다.
        tablePermissions.save(UserTablePermission(userId = "u1", tableName = "denied_table", allowed = false))
        tablePermissions.save(UserTablePermission(userId = "u1", tableName = "revoked_table", allowed = false))
        // 승인 취소: APPROVED는 API로 되돌릴 수 없으므로(PENDING 전이만 허용) 직접 바꾼다 —
        // DB 조작·수동 개입으로 상태가 뒤집히는 상황이 게이트가 방어해야 할 바로 그 상황이다.
        jdbc.update("UPDATE approval_request SET status = 'CANCELLED' WHERE id = ?", revokableRequestId)
        // 저장·검토를 통과한 뒤 BLOCK 제약이 생긴다
        val blockStringDef = defId("STRING", "BLOCK", "문자열 조회 차단", null)
        post("/api/catalog/mappings", mapOf("columnId" to columnId(relint, "secret"), "defId" to blockStringDef))

        return Fixtures(
            approvedRequestId, pendingRequestId, narrowRequestId,
            approvedQueryId, pendingReviewQueryId, sqlErrorQueryId,
            revokedQueryId, unapprovedQueryId, relintQueryId,
        )
    }

    private fun simpleTable(name: String, column: String, type: String, mapTo: String?): Map<*, *> {
        val created = post("/api/catalog/tables", mapOf("name" to name, "columns" to listOf(
            mapOf("name" to column, "type" to type, "isPii" to false)))).body!!
        if (mapTo != null) demoTableMap.save(DemoTableMapping(logicalName = name, physicalName = mapTo))
        return created
    }

    private fun columnId(table: Map<*, *>, name: String): Long = ((table["columns"] as List<*>)
        .first { (it as Map<*, *>)["name"] == name } as Map<*, *>).let { (it["id"] as Number).toLong() }

    private fun defId(cls: String, kind: String, name: String, expression: String?): Long {
        val body = mutableMapOf<String, Any?>("cls" to cls, "kind" to kind, "name" to name)
        if (expression != null) body["expression"] = expression
        return (post("/api/catalog/defs", body).body!!["id"] as Number).toLong()
    }

    private fun approval(title: String, tables: List<String>, approve: Boolean): Long {
        val created = client.postAs("/api/approvals", "u1", mapOf(
            "purposeTitle" to title, "purposeCode" to "marketing",
            "tables" to tables.map { mapOf("tableName" to it) },
            "ruleIds" to emptyList<Long>(), "businessReqs" to emptyList<String>(),
            "approvers" to listOf(mapOf("step" to 1, "approverId" to "ap1"))))
        val summary = created.body?.get("summary") as? Map<*, *>
            ?: error("승인 요청 생성 실패($title): ${created.statusCode} ${created.body}")
        val id = (summary["id"] as Number).toLong()
        if (approve) client.postAs("/api/approvals/$id/approve", "ap1")
        return id
    }

    private fun savedQuery(name: String, sql: String, requestId: Long, review: Boolean): Long {
        val saved = client.postAs("/api/queries", "u1", mapOf(
            "name" to name, "dialect" to "MYSQL", "requestId" to requestId, "sql" to sql))
        check(saved.statusCode == HttpStatus.CREATED) { "쿼리 저장 실패($name): ${saved.statusCode} ${saved.body}" }
        val id = (saved.body!!["id"] as Number).toLong()
        if (review) {
            val reviewed = client.postAs("/api/queries/$id/review", "ap1", mapOf("decision" to "APPROVED"))
            check(reviewed.statusCode == HttpStatus.OK) { "검토 승인 실패($name): ${reviewed.statusCode} ${reviewed.body}" }
        }
        return id
    }

    private fun ensureDemoSchema() {
        val script = Path.of("..", "docker", "initdb", "01-exec-isolation.sql")
        val result = mysql.execInContainer("mysql", "-uroot", "-p${mysql.password}", "-e", Files.readString(script))
        check(result.exitCode == 0) { "데모 스키마 초기화 실패: ${result.stderr}" }
    }
}
