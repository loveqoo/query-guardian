package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.exec.ExecutionEvent
import com.loveqoo.queryguardian.exec.ExecutionEventRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **감사는 DB가 append-only로 강제한다** (spec 014 L7 · 백로그 `D-D`).
 *
 * ## 왜 코드가 아니라 DB인가
 *
 * 리포지토리에서 `delete*`를 없애는 것은 **한 경로만** 막는다. JDBC를 직접 잡으면 그만이고,
 * 이 저장소에는 이미 `JdbcTemplate`을 쓰는 코드가 있다. 감사를 지울 수 있으면 감사가 아니므로
 * **애플리케이션이 무엇을 하든 DB가 거절**해야 한다.
 *
 * 그래서 이 테스트는 리포지토리를 우회해 **생짜 SQL로** 지우고 고쳐 본다 —
 * "우리 코드가 안 부른다"가 아니라 "**부를 수 없다**"를 재는 것이 목적이기 때문이다.
 *
 * ## 트리거는 어디서 오는가 — **배포되는 바로 그 파일**
 *
 * `docker/audit-append-only.sql`을 읽어서 적용한다. 테스트가 자기 DDL을 따로 쓰면
 * **배포되지 않는 것을 검증**하게 된다 — 같은 바이트를 읽어야 갈라지지 않는다.
 *
 * 왜 테스트가 직접 거는가: binlog가 켜진 MySQL에서 트리거 생성은 SUPER를 요구하고
 * 앱 계정에는 없다(줘서도 안 된다 — 감사를 지키려고 앱 권한을 키우는 것은 방향이 거꾸로다).
 * 운영에서는 root가 스크립트로 걸고, 여기서는 컨테이너 root로 건다. **거는 주체가 앱이 아니라는
 * 사실 자체가 설계의 일부**다.
 *
 * ## 축 둘
 *
 * 1. **막는다**(1·2): DELETE·UPDATE가 거절된다.
 * 2. **막지 않는다**(3): INSERT는 된다. 이 축이 없으면 "테이블을 통째로 잠갔다"도 1번을 만족한다 —
 *    그러면 감사 자체가 죽는데 테스트는 초록이다.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditAppendOnlyTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")
    }

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var repository: ExecutionEventRepository

    /** 배포되는 DDL을 **그대로** 읽어 건다. 테스트가 자기 사본을 쓰면 갈라져도 아무도 모른다. */
    @BeforeAll
    fun applyShippedTriggers() {
        val ddl = Path.of("..", "docker", "audit-append-only.sql").toAbsolutePath().normalize()
        check(ddl.exists()) { "배포 DDL을 못 찾았다: $ddl" }
        val statements = ddl.readText()
            .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
        check(statements.size == 4) { "DDL 문장 수가 4가 아니다(${statements.size}) — 파일이 바뀌었나" }

        DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { root ->
            root.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    private fun seed(): Long {
        val saved = repository.save(
            ExecutionEvent(
                queryId = null,
                actor = "append-only-probe",
                outcome = ExecutionOutcome.BLOCKED,
                originalSql = "SELECT 1",
                rewrittenSql = null,
                appliedJson = null,
                rowCount = null,
                elapsedMs = 1,
                effectiveLimit = null,
                configuredCap = null,
                moreRowsExist = null,
                errorCode = AuditCode.RULE_BLOCKED.name,
                errorDetail = null,
                at = Instant.now(),
            )
        )
        return saved.id!!
    }

    @Test
    fun `생짜 SQL로도 감사 행을 지울 수 없다`() {
        val id = seed()
        val e = assertFailsWith<Exception> { jdbc.update("DELETE FROM execution_event WHERE id = ?", id) }

        assertTrue(
            e.message?.contains("append-only") == true,
            "삭제가 거절되긴 했으나 사유가 트리거가 아니다 — 다른 이유로 실패했을 수 있다: ${e.message}",
        )
        assertEquals(
            1, jdbc.queryForObject("SELECT COUNT(*) FROM execution_event WHERE id = ?", Int::class.java, id),
            "거절됐는데 행이 사라졌다",
        )
    }

    @Test
    fun `생짜 SQL로도 감사 행을 고칠 수 없다`() {
        val id = seed()
        val e = assertFailsWith<Exception> {
            jdbc.update("UPDATE execution_event SET actor = 'someone-else' WHERE id = ?", id)
        }

        assertTrue(
            e.message?.contains("append-only") == true,
            "수정이 거절되긴 했으나 사유가 트리거가 아니다: ${e.message}",
        )
        assertEquals(
            "append-only-probe",
            jdbc.queryForObject("SELECT actor FROM execution_event WHERE id = ?", String::class.java, id),
            "거절됐는데 값이 바뀌었다",
        )
    }

    @Test
    fun `막지 않는다 - 새 기록은 계속 쌓인다`() {
        val before = repository.count()
        seed()
        assertEquals(
            before + 1, repository.count(),
            "append-only 강제가 INSERT까지 막았다 — 그러면 감사 자체가 죽는다",
        )
    }

    @Test
    fun `리포지토리가 삭제 능력을 노출하지 않는다`() {
        // DB 트리거와 **겹치는** 방어. 이쪽은 "실수로 부르는 것"을 컴파일 시점에 막는다.
        val exposed = ExecutionEventRepository::class.java.methods
            .map { it.name }
            .filter { it.startsWith("delete") || it.startsWith("remove") }

        assertTrue(
            exposed.isEmpty(),
            "감사 리포지토리가 삭제 메서드를 노출한다: $exposed — 상속으로 딸려 온 능력은 " +
                "아무도 의도하지 않은 능력이다",
        )
    }
}
