package com.loveqoo.queryguardian.exec

import com.loveqoo.queryguardian.audit.AuditCode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
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
    /** 실제로 적용된 상한(= min(사용자 LIMIT, 설정 상한)). */
    val effectiveLimit: Long,
    /** 거버넌스 설정 상한. [effectiveLimit]과 같을 때만 "상한 때문에 잘렸다"고 읽어야 한다(D5). */
    val configuredCap: Long,
    /**
     * 상한을 넘는 행이 더 있었는지. **null = 알 수 없음** — 상한이 0이면 초과 탐지용 1행조차 조회하지 않는다.
     * `false`("상한 안에 다 들어왔다")와 `null`("확인하지 않았다")을 구분하는 것이 이 필드의 존재 이유다.
     */
    val moreRowsExist: Boolean?,
)

data class ColumnMeta(val name: String, val type: String)

/** 실행 실패 — 사용자에게는 **분류 코드**만 주고 원문은 감사에만 남긴다(§6: MySQL 오류는 데이터 값을 에코한다). */
class ExecutionFailure(
    val kind: Kind,
    /** 감사용 원문(SQLState·vendor code 포함). 사용자 응답에 넣지 않는다. */
    val detail: String,
) : RuntimeException(kind.userMessage) {
    /** 감사 코드를 **이름 규약이 아니라 필드로** 짝지운다 — `kind.name`이 우연히 맞는 것에 기대지 않는다. */
    enum class Kind(val userMessage: String, val auditCode: AuditCode) {
        TIMEOUT("쿼리가 제한 시간 안에 끝나지 않았습니다", AuditCode.TIMEOUT),
        SQL_ERROR("쿼리 실행 중 오류가 발생했습니다", AuditCode.SQL_ERROR),
        CONNECTION("실행 대상 데이터베이스에 연결할 수 없습니다", AuditCode.CONNECTION),
    }
}

/**
 * 실행 세션에서 **반드시 제거**하는 모드 — 파서·스캐너와 서버의 문법 해석을 갈라놓는 것들이다.
 * 판정과 실행이 같은 문장을 다르게 읽으면 이 제품의 전제("승인한 것이 실행된다")가 무너진다.
 */
private val FORBIDDEN_SQL_MODES = setOf(
    "NO_BACKSLASH_ESCAPES",  // 리터럴 경계가 달라진다(M0 어휘 스캐너의 전제 — §2.8)
    "ANSI_QUOTES",           // `"x"`가 리터럴 → **컬럼 참조**가 된다(마스킹·BLOCK 우회 — D1)
    "PIPES_AS_CONCAT",       // `||`가 OR → CONCAT이 된다(WHERE 의미 변화)
    "IGNORE_SPACE",          // `count (x)`가 함수 호출이 되어 금지 함수 검사와 갈라진다
    "HIGH_NOT_PRECEDENCE",   // `NOT a BETWEEN b AND c`의 결합이 달라진다(주입 필터 극성)
)

/** 반대로 항상 켜 두는 모드 — 조용한 데이터 변형을 막는다. */
private val REQUIRED_SQL_MODES = listOf("STRICT_TRANS_TABLES", "NO_ENGINE_SUBSTITUTION")

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
/**
 * **실행 지시** — 무엇을 몇 행까지 실행할지가 **한 값**으로 묶인다 (spec 010 I9).
 *
 * 예전에는 [QueryExecutor.execute]가 `(sql, maxRows, configuredCap)` 세 인자를 받았다. 그러면 A 쿼리의
 * SQL에 B 쿼리의 상한을 붙여 부르는 것이 **문법적으로 가능**했다 — 게이트를 다 지나고도 마지막 한 줄에서
 * 짝이 어긋날 수 있는 자리였다. 한 값으로 묶으면 그 자리가 사라진다.
 *
 * 이 인터페이스를 **밖에서 구현할 수 없게 막지는 않았다**(막으면 `exec → query` 순환이 생긴다).
 * "게이트를 거치지 않은 실행"은 대신 `ArchGateAccessTest.onlyTheGateMayReachTheExecutor`가 막는다 —
 * 실행기를 **의존할 수 있는 클래스 자체를 하나로** 묶는 규칙이다.
 */
interface ExecutionOrder {
    val sql: String

    /** 유효 상한. 재작성기가 SQL에 `LIMIT maxRows + 1`을 넣어 두었다. */
    val maxRows: Long

    /** 설정된 거버넌스 상한 — 사용자가 요청한 LIMIT과 구분해 응답에 그대로 보인다(retrospect 012). */
    val governanceCap: Long
}

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

    private fun buildUrl(): String = url

    /**
     * [ExecutionOrder.maxRows]가 유효 상한이다. 재작성기가 IR을 통해 SQL에 `LIMIT maxRows + 1`을
     * 넣어 두었으므로, 여기서는 **`maxRows + 1`번째 행이 오는지**만 보고 `truncated`를 확정한 뒤 그 행을 버린다.
     *
     * **`setMaxRows`는 쓰지 않는다** — 상한 장치가 둘이면 서로 어긋난다(§3.0-2 단일 장치).
     *
     * spec 012 P1에서 잠시 `setMaxRows`로 옮겼다가 되돌렸다. 그때의 전제("서버가 사용자 SQL을 전혀
     * 바꾸지 않는다")가 틀렸기 때문이다 — 바꾸지 않는 것은 **값과 조건**(마스킹·필터)이고,
     * **행 상한은 IR을 통해 벤더별로 붙인다**(사용자 확정 안전장치 4종). 그 사이 실측으로 남은 사실:
     *
     * - `setMaxRows`는 JDBC 표준이지만 **구현이 드라이버마다 다르다.** MySQL(connector-j 9.7)에서는
     *   서버를 멈추고 사용자의 `LIMIT`도 이기지만, 그 보장은 **벤더마다 다시 재야 하는 보장**이다.
     *   결과를 다 받아 놓고 앞의 n개만 주는 드라이버라면 감사에는 "10행"인데 전부 건너온 것이 된다.
     * - 반대로 커넥션에 `SQL_SELECT_LIMIT`을 박는 방식은 **쓸 수 없다** — 사용자가 명시한 `LIMIT`이
     *   세션 상한을 이긴다(실측: 세션 3에 `LIMIT 9` → 9행).
     *
     * IR을 통한 주입이 벤더 무관하게 서버를 멈추는 유일한 방법이고, 그래서 그것이 본체다.
     */
    fun execute(order: ExecutionOrder): ExecutionResult {
        val started = System.nanoTime()
        try {
            connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = (timeoutMs / 1000).toInt().coerceAtLeast(1)
                    statement.executeQuery(order.sql).use { rs ->
                        return read(rs, order.maxRows, order.governanceCap, started)
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

    /**
     * 실행 커넥션을 초기화한다. 실패하면 **얻은 커넥션을 반드시 닫는다** — `apply {}` 안에서 던지면
     * 그 커넥션은 `use{}`에 도달하지 못해 풀(최대 3)에 영구히 붙잡힌다. 세 번 실패하면 실행 기능 전체가
     * 죽는다(적대 검토 D4).
     */
    private fun connection(): Connection {
        val connection = try {
            pool.connection
        } catch (e: SQLException) {
            throw ExecutionFailure(ExecutionFailure.Kind.CONNECTION, describe(e))
        } catch (e: HikariPool.PoolInitializationException) {
            // **풀은 지연 생성이라 첫 실행에서 초기화된다.** 그때 DB가 안 떠 있으면 Hikari는
            // `SQLException`이 아니라 이 예외를 던진다(`RuntimeException` 계열) — 위 catch를 그냥
            // 지나쳐 분류되지 않은 채 게이트 밖으로 나갔다. 결과: 사용자는 정체불명 오류를 받고
            // **감사에는 CONNECTION이 남지 않는다.** 실측으로 걸렸다(spec 014 L13).
            //
            // 원인을 벗겨서 담는다 — 껍데기 메시지("Failed to initialize pool")만 남기면
            // 감사 원문이 SQLState를 잃는다.
            val cause = generateSequence(e.cause) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            throw ExecutionFailure(
                ExecutionFailure.Kind.CONNECTION,
                cause?.let { describe(it) } ?: "풀 초기화 실패: ${e.message}",
            )
        }
        try {
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                // 계정 권한·커넥션 플래그에 더해 트랜잭션 수준에서도 쓰기를 막는다(삼중 방어)
                statement.execute("SET SESSION TRANSACTION READ ONLY")
                fixSqlMode(statement)
            }
            return connection
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw ExecutionFailure(ExecutionFailure.Kind.CONNECTION, describe(e))
        } catch (e: RuntimeException) {
            runCatching { connection.close() }
            throw e
        }
    }

    /**
     * `sql_mode`에서 **SQL의 의미를 바꾸는 모드**를 걷어낸다.
     *
     * 두 번 틀렸던 자리다. 처음엔 전체를 갈아써서(`sessionVariables=sql_mode='STRICT_...'`) 서버 기본값의
     * `ONLY_FULL_GROUP_BY`가 사라졌고 — 검토자가 승인한 SQL이 실행 시점에 그룹당 임의 행을 고르는
     * **다른 의미**로 돌았다. 그래서 합집합으로 바꿨더니 이번엔 `ANSI_QUOTES`가 **상속**됐다:
     * 판정기(어휘 스캐너·Druid 렉서)는 `"ssn"`을 문자열 리터럴로 보는데 실행 세션은 **컬럼 참조**로 읽어,
     * BLOCK 등급 컬럼이 마스킹·차단을 통과해 원문으로 나갈 수 있었다(적대 검토 D1 실측).
     *
     * 결론: 화이트리스트도 합집합도 아니라 **금지 목록**이다. 판정과 실행의 문법 해석이 갈라지게 만드는
     * 모드만 정확히 뺀다. 그리고 뺀 결과를 **다시 읽어 확인**한다 — 못 지웠으면 실행하지 않는다(fail-closed).
     */
    private fun fixSqlMode(statement: java.sql.Statement) {
        val current = statement.executeQuery("SELECT @@SESSION.sql_mode").use { rs ->
            rs.next()
            rs.getString(1) ?: ""
        }
        val kept = current.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && it.uppercase() !in FORBIDDEN_SQL_MODES }
        val target = (kept + REQUIRED_SQL_MODES).distinct().joinToString(",")
        statement.execute("SET SESSION sql_mode = '$target'")

        val applied = statement.executeQuery("SELECT @@SESSION.sql_mode").use { rs ->
            rs.next()
            (rs.getString(1) ?: "").split(',').map { it.trim().uppercase() }.toSet()
        }
        // 조합 모드(ANSI 등)는 서버가 구성 모드로 펼치므로, 펼쳐진 결과를 보고 판단해야 한다
        val leftover = applied.intersect(FORBIDDEN_SQL_MODES)
        if (leftover.isNotEmpty()) {
            throw ExecutionFailure(
                ExecutionFailure.Kind.CONNECTION,
                "실행 세션 sql_mode에서 금지 모드를 제거하지 못했습니다: $leftover",
            )
        }
    }

    private fun read(rs: ResultSet, maxRows: Long, configuredCap: Long, startedNanos: Long): ExecutionResult {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { ColumnMeta(meta.getColumnLabel(it), meta.getColumnTypeName(it)) }
        val rows = mutableListOf<List<String?>>()
        var moreRows = false
        while (rs.next()) {
            if (rows.size >= maxRows) {
                // 재작성이 넣은 `maxRows + 1`번째 행이다 — 더 있다는 사실만 남기고 값은 버린다
                moreRows = true
                break
            }
            rows += (1..meta.columnCount).map { rs.getString(it) }
        }
        return ExecutionResult(
            columns = columns,
            rows = rows,
            rowCount = rows.size,
            elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000,
            effectiveLimit = maxRows,
            configuredCap = configuredCap,
            // 상한이 0이면 재작성이 `LIMIT 0`을 넣으므로 초과 행을 볼 기회가 없다 → 단정하지 않는다
            moreRowsExist = if (maxRows == 0L) null else moreRows,
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
