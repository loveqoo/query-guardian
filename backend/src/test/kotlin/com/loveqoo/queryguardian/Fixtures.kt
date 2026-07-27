package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.toAsciiTree
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DruidMySqlParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.parser.PredicateParse
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.RequiredPredicate
import com.loveqoo.queryguardian.rules.RuleEngine
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 공용 픽스처 — spec 001 대표 시나리오:
 * user_events: 파티션 키 event_date, purpose=marketing일 때 consent_yn='Y' 필수.
 */
object Fixtures {
    val parser = DruidMySqlParser()

    val catalog = InMemoryTableCatalog(
        partitionKeys = mapOf("user_events" to listOf("event_date")),
        required = listOf(
            InMemoryTableCatalog.Entry(
                table = "user_events",
                purposeCode = "marketing",
                // 픽스처가 파싱에 실패하면 그 자리에서 터져야 한다 — 조용히 Raw로 흐르면
                // 구조 비교 테스트가 텍스트 비교로 바뀐 채 초록이 된다.
                predicate = RequiredPredicate("consent_yn = 'Y'", parsedFixture("consent_yn = 'Y'")),
            ),
        ),
        // spec 002: users.ssn은 BLOCK 매핑 (디자인 표본 — 조회 전면 차단)
        blocked = mapOf("users" to setOf("ssn")),
        // spec 008: users.email은 MASK 매핑 (실행 시 자동 마스킹, 표현 불가 위치는 BLOCK)
        masked = mapOf("users" to setOf("email")),
        // 실제 카탈로그에 등록된 강제식과 같은 값 — 사용자가 직접 `mask_email(email)`로 써도
        // 정답으로 인정되려면 픽스처도 그 근거를 갖고 있어야 한다 (spec 012 P0).
        maskTemplates = mapOf("users" to mapOf("email" to "mask_email({col})")),
        tables = setOf("user_events", "users"),
    )

    val service = LintService(parser, RuleEngine.withDefaultRules(), catalog)

    fun lint(sql: String, purpose: String? = null): LintReport = service.lint(sql, purpose)

    /**
     * 파싱해서 IR을 준다. 디버깅 중에 구조를 보고 싶으면 `println(Fixtures.ir(sql).toAsciiTree())`.
     * 파싱이 실패하면 그 사실을 예외로 알린다 — null을 돌려주면 호출부가 조용히 넘어간다.
     */
    fun ir(sql: String): QueryIR = when (val r = parser.parse(sql)) {
        is ParseResult.Success -> r.ir
        is ParseResult.Failure -> error("파싱 실패 [${r.kind}] ${r.message}\n  sql: $sql")
    }

    /** 술어 픽스처. [ir]과 같은 모양 — 실패는 예외로 알린다(이제 이유까지 나온다). */
    fun parsedFixture(predicateSql: String): Predicate = when (val r = parser.parsePredicate(predicateSql)) {
        is PredicateParse.Parsed -> r.predicate
        is PredicateParse.Unparsed -> error("술어 파싱 실패: ${r.reason}\n  sql: $predicateSql")
    }

    /**
     * SQL의 IR 트리 문자열. **단정 메시지에 붙이는 용도**다 —
     * `println` 디버깅은 통과할 때도 쏟아지고 CI 로그에서 사라진다.
     *
     * 파싱 실패도 문자열로 돌려준다: 실패한 이유 자체가 디버깅 정보이고,
     * 단정 메시지를 만드는 도중에 예외가 나면 원래 실패가 가려진다.
     */
    fun irTree(sql: String): String = when (val r = parser.parse(sql)) {
        is ParseResult.Success -> r.ir.toAsciiTree()
        is ParseResult.Failure -> "파싱 실패 [${r.kind}] ${r.message}"
    }

    fun assertBlockedBy(sql: String, ruleIdPrefix: String, purpose: String? = null) {
        val report = lint(sql, purpose)
        assertTrue(report.blocked, "차단되어야 하는데 통과함: $sql\n$report\n${irTree(sql)}")
        assertTrue(
            report.violations.any { it.ruleId.startsWith(ruleIdPrefix) },
            "룰 [$ruleIdPrefix] 위반이 있어야 함: $sql\n$report\n${irTree(sql)}",
        )
    }

    fun assertNotBlocked(sql: String, purpose: String? = null) {
        val report = lint(sql, purpose)
        assertFalse(report.blocked, "통과해야 하는데 차단됨: $sql\n$report\n${irTree(sql)}")
    }
}

/**
 * 게이트를 거치지 않는 **진단용 실행 지시** — 세션 계약(`sql_mode`) 확인처럼 결과 행이 아니라
 * 접속의 성질을 보는 프로브에만 쓴다.
 *
 * 프로덕션에는 이런 구현체가 없다(`Executable`이 유일하고 발급자가 하나다). 테스트가 만들 수 있는 것은
 * `ExecutionOrder`를 봉인하지 않았기 때문이고, 봉인하면 `exec → query` 순환이 생긴다 —
 * 그 대신 `ArchGateAccessTest.onlyTheGateMayReachTheExecutor`가 **실행기에 닿을 수 있는 클래스 자체**를 묶는다.
 */
data class ProbeOrder(
    override val sql: String,
    override val maxRows: Long = 1,
    override val governanceCap: Long = 1,
) : com.loveqoo.queryguardian.exec.ExecutionOrder
