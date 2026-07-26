package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 P1.5 · 수용 기준 A7 — **파서는 어떤 입력에도 값을 돌려준다.**
 *
 * 게이트의 실패는 값이다(I7). 파서가 `StackOverflowError`를 던지면 그 계약이 파서 앞에서 이미 깨진다 —
 * 게이트 본문은 값을 기대하므로 잡을 자리가 없고, 응답은 무기록 500이 된다.
 *
 * ## 실측이 이 파일을 만들었다
 *
 * 폭주하는 스택의 반복 프레임을 세어 재귀 고리 **셋**을 특정했다:
 *
 * | 고리 | 반복 프레임 | 조치 |
 * |---|---|---|
 * | 괄호·AND 중첩 | `primary`↔`expr`↔`primaryLParen` | `MySqlExprParser.primary()` 어댑터 |
 * | 서브쿼리 중첩 | `query`↔`select`↔`parseFrom` | `MySqlSelectParser.query()` 어댑터 |
 * | 산술 좌결합 | `additiveRest` 자기 재귀 (`final`) | 파싱 전 텍스트 검사 |
 *
 * 그리고 **네 번째가 우리 코드에 있었다** — 평면 `OR` 체인은 Druid가 반복으로 잘 파싱하는데
 * `buildFromSelect`가 호출 스레드에서 터졌다. 그쪽은 재귀를 스택에서 내려 없앤다.
 *
 * ## 이 테스트가 지키는 것
 *
 * **"던지지 않는다"가 핵심 단정이다.** 어떤 분류로 실패하는지도 보지만, 그보다 먼저
 * `inspect()`가 예외 없이 반환하는지를 본다 — 새 폭주 형태가 발견되면 여기 한 줄을 추가한다.
 */
class ParserResourceBoundTest {

    private val parser = Fixtures.parser

    /** 상한을 낮춘 파서 — 상한이 **실제로 발화하는지** 보려면 64KB 안에서 넘길 수 있어야 한다. */
    private val tight = DruidMySqlParser(maxParseDepth = 20, maxOperatorChain = 50)

    // ---- 던지지 않는다 -----------------------------------------------------

    /**
     * **바이트 상한을 열고 잰다.** 기본 64KB에서는 평면 체인이 상한에 먼저 걸려 재귀를 타지도 못한다 —
     * 그 상태로 초록이면 "던지지 않는다"를 증명한 게 아니라 **바이트 상한을 증명한 것**이다.
     * (retrospect 012: 테스트가 통과하는 *이유*를 봐라.)
     */
    @Test
    fun `알려진 폭주 형태가 전부 값으로 돌아온다`() {
        val open = DruidMySqlParser(maxSqlBytes = 8 * 1024 * 1024)
        val shapes = mapOf(
            "괄호 중첩" to "SELECT " + "(".repeat(3000) + "1" + ")".repeat(3000) + " FROM t",
            "AND 괄호 중첩" to "SELECT id FROM t WHERE " + "(".repeat(2000) + "id=1" + " AND id=1)".repeat(2000),
            "서브쿼리 중첩" to "SELECT id FROM " + "(SELECT id FROM ".repeat(2000) + "t" + ") x".repeat(2000),
            "평면 OR 체인" to "SELECT id FROM t WHERE " + (1..8000).joinToString(" OR ") { "id=$it" },
            "평면 AND 체인" to "SELECT id FROM t WHERE " + (1..8000).joinToString(" AND ") { "id=$it" },
            "산술 체인" to "SELECT " + (1..8000).joinToString(" + ") { "$it" } + " FROM t",
            "NOT 체인" to "SELECT id FROM t WHERE " + "NOT ".repeat(4000) + "id=1",
        )
        for ((label, sql) in shapes) {
            val result = runCatching { open.inspect(sql) }
            assertTrue(
                result.isSuccess,
                "[$label] 파서가 값이 아니라 ${result.exceptionOrNull()?.let { it::class.simpleName }}를 던졌다 — " +
                    "게이트는 잡을 자리가 없어 무기록 500이 된다",
            )
            val parsed = result.getOrThrow().parse
            assertTrue(
                parsed !is ParseResult.Failure || parsed.kind != FailureKind.INPUT_TOO_LARGE,
                "[$label] 바이트 상한에 먼저 걸려 재귀를 타지 않았다 — 이 사례는 아무것도 증명하지 못한다",
            )
        }
    }

    // ---- 상한이 발화한다 ---------------------------------------------------

    @Test
    fun `괄호 중첩 상한은 제 이름으로 거부한다`() {
        val failure = failureOf(tight, "SELECT " + "(".repeat(60) + "1" + ")".repeat(60) + " FROM t")
        assertEquals(FailureKind.TOO_COMPLEX, failure.kind, "문법 오류로 기록되면 오타와 공격을 못 가른다")
    }

    @Test
    fun `서브쿼리 중첩 상한은 제 이름으로 거부한다`() {
        val sql = "SELECT id FROM " + "(SELECT id FROM ".repeat(40) + "t" + ") x".repeat(40)
        assertEquals(FailureKind.TOO_COMPLEX, failureOf(tight, sql).kind)
    }

    /**
     * 어댑터의 **깊이 계수기 밖**에 있는 축 — 평면 체인은 Druid가 반복으로 파싱해(깊이 2) 계수기를
     * 지나가지만, 만들어진 AST는 좌편향 n단이라 그 위를 걷는 코드가 n단 재귀가 된다.
     */
    @Test
    fun `이진 연쇄 상한은 파싱 전에 거부한다`() {
        for ((label, sql) in mapOf(
            "산술" to "SELECT " + (1..80).joinToString(" + ") { "$it" } + " FROM t",
            "OR" to "SELECT id FROM t WHERE " + (1..80).joinToString(" OR ") { "a=1" },
            "AND" to "SELECT id FROM t WHERE " + (1..80).joinToString(" AND ") { "a=1" },
        )) {
            val failure = failureOf(tight, sql)
            assertEquals(FailureKind.TOO_COMPLEX, failure.kind, "[$label]")
            assertTrue(failure.message.contains("이진 연산자"), "[$label] 어느 상한인지 알 수 있어야 한다: ${failure.message}")
        }
    }

    /** 리터럴 안의 연산자·키워드는 세지 않는다 — 세면 정상 쿼리가 오차단된다. */
    @Test
    fun `리터럴 안의 연산자는 상한에 세지 않는다`() {
        val sql = "SELECT id FROM t WHERE note = '" + "+".repeat(200) + " AND ".repeat(200) + "'"
        val result = tight.inspect(sql)
        assertTrue(result.parse is ParseResult.Success, "리터럴을 세어 오차단했다: ${result.parse}")
    }

    // ---- 오차단이 없다 -----------------------------------------------------

    @Test
    fun `정상 쿼리는 기본 상한에 걸리지 않는다`() {
        val queries = listOf(
            "SELECT u.id, u.email FROM users u WHERE u.id > 1 LIMIT 10",
            "SELECT u.id FROM users u LEFT JOIN marketing_consents c ON c.user_id = u.id WHERE c.consent_yn = 'Y'",
            "WITH recent AS (SELECT id FROM users WHERE id > 1) SELECT r.id FROM recent r LIMIT 5",
            "SELECT id FROM users WHERE id IN (SELECT user_id FROM marketing_consents WHERE consent_yn = 'Y')",
            "SELECT SUM(a + b + c) FROM t WHERE d = 1 GROUP BY e HAVING SUM(a) > 0 ORDER BY 1 LIMIT 10",
        )
        for (sql in queries) {
            val result = parser.inspect(sql)
            assertTrue(result.parse is ParseResult.Success, "정상 쿼리가 막혔다: $sql\n  ${result.parse}")
        }
    }

    /**
     * **상한은 스택 용량에서 멀리 떨어져야 한다.**
     *
     * 상한이 스택 한계에 가까우면 계수기가 세기 전에 스택이 먼저 터진다 — 실측으로 상한 1,000은 뚫렸고
     * 200은 잡았다. 기본값이 그 실수를 반복하지 않는지 고정한다(정상 쿼리의 실제 깊이는 2다).
     */
    @Test
    fun `기본 깊이 상한은 스택 한계보다 훨씬 낮다`() {
        val deep = "SELECT " + "(".repeat(500) + "1" + ")".repeat(500) + " FROM t"
        val failure = failureOf(parser, deep)
        assertEquals(
            FailureKind.TOO_COMPLEX, failure.kind,
            "기본 상한이 스택 한계에 너무 가까워 계수기보다 스택이 먼저 터졌다",
        )
    }

    // ---- 잔존 작업 ---------------------------------------------------------

    /**
     * **공격 뒤에 파서가 회복하는가** (spec 010 A7).
     *
     * 호출이 시간 안에 반환하는 것만으로는 이 축을 못 본다 — 취소 불가한 작업이 worker를 영구 점유하면
     * 응답은 정상인데 **이후 정상 요청이 계속 거부된다**. 예전 구조가 정확히 그 모양이었다:
     * 무제한 캐시드 풀 + `cancel(true)`가 순수 CPU 파싱을 멈추지 못함.
     *
     * 지금은 입력이 유계라(어댑터·연쇄 상한) 작업이 반드시 끝나고, 풀에도 천장이 있다.
     */
    @Test
    fun `폭주 입력을 반복해도 파서가 회복한다`() {
        val attacks = listOf(
            "SELECT " + "(".repeat(3000) + "1" + ")".repeat(3000) + " FROM t",
            "SELECT id FROM " + "(SELECT id FROM ".repeat(2000) + "t" + ") x".repeat(2000),
            "SELECT id FROM t WHERE " + (1..5000).joinToString(" OR ") { "a=1" },
        )
        repeat(20) { round ->
            for (sql in attacks) {
                val result = runCatching { parser.inspect(sql) }
                assertTrue(result.isSuccess, "${round}회차에서 던졌다: ${result.exceptionOrNull()}")
            }
        }
        // 공격 **직후** 정상 요청이 통과해야 한다 — worker가 물려 있으면 여기서 드러난다.
        val normal = parser.inspect("SELECT u.id FROM users u WHERE u.id > 1 LIMIT 10")
        assertTrue(normal.parse is ParseResult.Success, "공격 뒤 정상 쿼리가 막혔다: ${normal.parse}")
    }

    private fun failureOf(p: DialectParser, sql: String): ParseResult.Failure {
        val parsed = p.inspect(sql).parse
        assertTrue(parsed is ParseResult.Failure, "실패해야 하는데 통과했다: ${sql.take(60)}…")
        return parsed
    }
}
