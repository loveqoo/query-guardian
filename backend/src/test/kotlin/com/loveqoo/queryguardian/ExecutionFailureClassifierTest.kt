package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.exec.ExecutionOrder
import com.loveqoo.queryguardian.exec.QueryExecutor
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **실행 실패를 실제로 분류하는가** (spec 014 L13 · 백로그 "실제 실행 실패 분류 경로가 무검증").
 *
 * ## 왜 이 파일이 생겼나
 *
 * `AuditCodeCoverageTest`가 감사 코드 전수를 재지만, `TIMEOUT`·`SQL_ERROR`·`CONNECTION`은
 * **스파이가 `ExecutionFailure`를 직접 던져서** 도달한다. 즉 재는 것은 "그 실패가 그 감사 코드로
 * 기록되는가"이고, **"어떤 예외가 그 실패가 되는가"는 아무도 안 쟀다.**
 * 그 파일 자신이 그렇게 적어 두었다 — *"실제 분류 경로의 발화 검증은 spec 010 P1(A7)로 넘긴다"*.
 *
 * 그런데 **A7은 파서 재귀 유계로 정의되어 P1.5에서 소진됐다.** 넘긴 곳이 사라진 것이다.
 * 위임처가 없어진 미검증은 조용하다 — 두 문서 다 "저쪽이 한다"고 적혀 있으면 아무도 안 한다.
 *
 * ## 무엇을 재는가
 *
 * `QueryExecutor.execute`의 catch 사슬이 **진짜 JDBC 예외**를 옳게 가르는지. 스파이가 아니라
 * 실제 드라이버가 던지게 한다 — 그래야 `SQLTimeoutException`이 `SQLException`의 하위 타입이라
 * **catch 순서가 뒤집히면 타임아웃이 SQL 오류로 기록되는** 사고를 잡을 수 있다.
 *
 * `CONNECTION` 갈래는 `SQLState`가 `08`로 시작하는지로 갈린다 — 그 규약이 벤더에서 실제로
 * 성립하는지도 여기서만 확인된다.
 */
@Testcontainers
class ExecutionFailureClassifierTest {

    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")
    }

    private fun order(sql: String) = object : ExecutionOrder {
        override val sql = sql
        override val maxRows = 10L
        override val governanceCap = 10L
    }

    /** 컨테이너를 그대로 겨눈 실행기. 권한은 이 테스트의 축이 아니다 — 분류만 본다. */
    private fun executor(url: String = mysql.jdbcUrl, timeoutMs: Long = 5_000) = QueryExecutor(
        url = url,
        username = mysql.username,
        password = mysql.password,
        poolSize = 2,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `타임아웃은 TIMEOUT으로 분류된다`() {
        // `queryTimeout`은 초 단위로 내려가고 최소 1초다. 그보다 확실히 긴 질의를 준다.
        val e = assertFailsWith<ExecutionFailure> {
            executor(timeoutMs = 1_000).execute(order("SELECT SLEEP(5)"))
        }
        assertEquals(
            ExecutionFailure.Kind.TIMEOUT, e.kind,
            "타임아웃이 다른 종류로 분류됐다 — `SQLTimeoutException`은 `SQLException`의 하위 타입이므로 " +
                "catch 순서가 뒤집히면 조용히 SQL_ERROR가 된다. 실제 값: ${e.kind} / ${e.detail}",
        )
    }

    @Test
    fun `문법·객체 오류는 SQL_ERROR로 분류된다`() {
        val e = assertFailsWith<ExecutionFailure> {
            executor().execute(order("SELECT * FROM no_such_table_here"))
        }
        assertEquals(ExecutionFailure.Kind.SQL_ERROR, e.kind, "실제 값: ${e.kind} / ${e.detail}")
        assertTrue(e.detail.contains("SQLState="), "감사 원문에 SQLState가 있어야 한다: ${e.detail}")
    }

    @Test
    fun `연결 실패는 CONNECTION으로 분류된다`() {
        // 아무도 듣지 않는 포트. 드라이버가 SQLState 08xxx를 낸다 — 그 규약이 이 벤더에서
        // 실제로 성립하는지는 **여기서만** 확인된다(코드의 `startsWith("08")`가 그것에 기댄다).
        val dead = "jdbc:mysql://127.0.0.1:1/nothing"
        val e = assertFailsWith<ExecutionFailure> {
            executor(url = dead, timeoutMs = 2_000).execute(order("SELECT 1"))
        }
        assertEquals(
            ExecutionFailure.Kind.CONNECTION, e.kind,
            "연결 실패가 SQL 오류로 분류되면 사용자는 자기 쿼리를 고치러 간다. 실제 값: ${e.kind} / ${e.detail}",
        )
    }

    @Test
    fun `분류 종류마다 감사 코드가 이름 규약이 아니라 필드로 붙어 있다`() {
        // `kind.name`이 우연히 감사 코드와 같은 것에 기대지 않는다는 계약을 고정한다.
        ExecutionFailure.Kind.entries.forEach {
            assertTrue(
                it.userMessage.isNotBlank(),
                "${it.name}에 사용자 안내 문구가 없다 — 응답이 빈 메시지가 된다",
            )
        }
        assertEquals(
            ExecutionFailure.Kind.entries.size,
            ExecutionFailure.Kind.entries.map { it.auditCode }.toSet().size,
            "두 종류가 같은 감사 코드를 쓰면 감사에서 구분이 사라진다",
        )
    }
}
