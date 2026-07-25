package com.loveqoo.queryguardian.exec

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import jakarta.annotation.PreDestroy

/** 실행 결과 — 결과 행은 **응답에만** 담고 어디에도 저장하지 않는다(spec 008 §6 불변식). */
data class ExecutionResult(
    val columns: List<ColumnMeta>,
    val rows: List<List<String?>>,
    val rowCount: Int,
    val elapsedMs: Long,
    val truncated: Boolean,
)

data class ColumnMeta(val name: String, val type: String)

/** 실행 실패 — 사용자에게는 **분류 코드**만 주고 원문은 감사에만 남긴다(§6: MySQL 오류는 데이터 값을 에코한다). */
class ExecutionFailure(
    val kind: Kind,
    /** 감사용 원문(SQLState·vendor code 포함). 사용자 응답에 넣지 않는다. */
    val detail: String,
) : RuntimeException(kind.userMessage) {
    enum class Kind(val userMessage: String) {
        TIMEOUT("쿼리가 제한 시간 안에 끝나지 않았습니다"),
        SQL_ERROR("쿼리 실행 중 오류가 발생했습니다"),
        CONNECTION("실행 대상 데이터베이스에 연결할 수 없습니다"),
    }
}

/**
 * 재작성된 SQL을 **실행 전용 접속**으로 실행한다 (spec 008 §4, M2-2·M2-5).
 *
 * 접속 격리(§2.7): 데모 스키마만 보이는 별도 계정(`qg_exec`)이고 설정 스키마에는 권한이 없다.
 * 앱에 버그가 있어도 `app_user.password_hash`·`rule`에 손이 닿지 않는다.
 *
 * **Spring `DataSource` 빈으로 노출하지 않는다.** 두 번째 `DataSource` 빈이 생기면 Boot의 자동 설정이
 * 물러나 주 DataSource(설정 DB)가 사라지고, Testcontainers의 접속 정보 주입도 어긋난다.
 * 그래서 풀을 이 클래스가 직접 소유하고 **처음 실행할 때 지연 생성**한다(실행하지 않는 환경은 접속조차 만들지 않는다).
 */
@Component
class QueryExecutor(
    @Value("\${guardian.exec.url:jdbc:mysql://localhost:3307/queryguardian_demo}") private val url: String,
    @Value("\${guardian.exec.username:qg_exec}") private val username: String,
    @Value("\${guardian.exec.password:qg-exec-demo}") private val password: String,
    @Value("\${guardian.exec.pool-size:3}") private val poolSize: Int,
    @Value("\${guardian.exec.timeout-ms:5000}") private val timeoutMs: Long,
) {

    private val poolDelegate = lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = buildUrl()
                username = this@QueryExecutor.username
                password = this@QueryExecutor.password
                maximumPoolSize = poolSize          // 작은 전용 풀 — 실행이 설정 DB 커넥션을 잠식하지 못하게
                isReadOnly = true                   // 커넥션 수준 읽기 전용 (계정 권한과 이중 방어)
                isAutoCommit = true
                connectionTimeout = timeoutMs
                poolName = "qg-exec"
            }
        )
    }

    private val pool: HikariDataSource get() = poolDelegate.value

    /**
     * `sql_mode`를 **고정**한다. M0의 어휘 스캐너는 `NO_BACKSLASH_ESCAPES`가 **없는** 기본 모드를 전제로
     * 리터럴 경계를 판단한다(§2.8) — 서버 설정이 바뀌면 그 전제가 깨지므로 접속마다 명시한다.
     */
    private fun buildUrl(): String {
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "sessionVariables=sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'"
    }

    /**
     * [maxRows]가 유효 상한이다. 재작성기가 SQL에 `LIMIT maxRows + 1`을 넣어 두었으므로, 여기서는
     * **`maxRows + 1`번째 행이 오는지**만 보고 `truncated`를 확정한 뒤 그 행을 버린다.
     * `setMaxRows`는 쓰지 않는다 — 상한 장치가 둘이면 서로 어긋난다(§3.0-2 단일 장치).
     */
    fun execute(sql: String, maxRows: Long): ExecutionResult {
        val started = System.nanoTime()
        try {
            connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = (timeoutMs / 1000).toInt().coerceAtLeast(1)
                    statement.executeQuery(sql).use { rs ->
                        return read(rs, maxRows, started)
                    }
                }
            }
        } catch (e: SQLTimeoutException) {
            throw ExecutionFailure(ExecutionFailure.Kind.TIMEOUT, describe(e))
        } catch (e: SQLException) {
            // 연결 실패와 질의 오류를 구분한다 — 사용자에게 주는 안내가 달라진다
            val kind = if (e.sqlState?.startsWith("08") == true) ExecutionFailure.Kind.CONNECTION
            else ExecutionFailure.Kind.SQL_ERROR
            throw ExecutionFailure(kind, describe(e))
        }
    }

    private fun connection(): Connection = try {
        pool.connection.apply {
            isReadOnly = true
            // 계정 권한·커넥션 플래그에 더해 트랜잭션 수준에서도 쓰기를 막는다(삼중 방어)
            createStatement().use { it.execute("SET SESSION TRANSACTION READ ONLY") }
        }
    } catch (e: SQLException) {
        throw ExecutionFailure(ExecutionFailure.Kind.CONNECTION, describe(e))
    }

    private fun read(rs: ResultSet, maxRows: Long, startedNanos: Long): ExecutionResult {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { ColumnMeta(meta.getColumnLabel(it), meta.getColumnTypeName(it)) }
        val rows = mutableListOf<List<String?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size >= maxRows) {
                // 재작성이 넣은 `maxRows + 1`번째 행이다 — 잘렸다는 사실만 남기고 값은 버린다
                truncated = true
                break
            }
            rows += (1..meta.columnCount).map { rs.getString(it) }
        }
        return ExecutionResult(
            columns = columns,
            rows = rows,
            rowCount = rows.size,
            elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000,
            truncated = truncated,
        )
    }

    /** 감사용 오류 서술 — SQLState + vendor code + 메시지. 사용자 응답에는 쓰지 않는다. */
    private fun describe(e: SQLException): String = "SQLState=${e.sqlState} vendor=${e.errorCode} ${e.message}"

    @PreDestroy
    fun close() {
        // 지연 생성이므로 한 번도 실행하지 않은 환경에서는 풀 자체가 없다
        if (poolDelegate.isInitialized() && !pool.isClosed) pool.close()
    }
}
