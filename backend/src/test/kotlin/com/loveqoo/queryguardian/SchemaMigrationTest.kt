package com.loveqoo.queryguardian

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `schema.sql`의 **이력 마이그레이션**을 고정한다 (적대 검토 #6 후속).
 *
 * `spring.sql.init.mode=always`라 이 스크립트는 매 기동마다 돌지만 `CREATE TABLE IF NOT EXISTS`는
 * **이미 있는 테이블에 컬럼을 더해주지 않는다**. `truncated` 하나를 `row_cap`+`more_rows_exist` 둘로
 * 쪼갠 변경이 그 함정에 정확히 걸렸다 — 기존 DB는 옛 모양으로 남고, 감사 INSERT가 죽고,
 * 감사가 죽으면 **실행이 무기록으로 통과**한다. 그래서 두 가지를 본다:
 * ⑴ 옛 모양 DB가 새 모양으로 올라간다(값 보존 후 옛 컬럼 제거) ⑵ 같은 스크립트를 두 번 돌려도 안전하다
 * ⑶ **nullability도 마이그레이션 대상**이다 — `query_id NOT NULL`이 남으면 미리보기 감사가 전부 죽는다.
 *
 * 픽스처는 반드시 **실제 옛 DDL**(커밋 52ae36a)이어야 한다. 처음 이 테스트는 픽스처를 이미 새 모양으로
 * 써서 결함을 구조적으로 볼 수 없었다 — 마이그레이션 테스트의 픽스처는 상상이 아니라 git 이력에서 온다.
 */
@Testcontainers
class SchemaMigrationTest {

    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4")
    }

    private fun connect(): Connection =
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)

    private fun applySchema(c: Connection) =
        ScriptUtils.executeSqlScript(c, ClassPathResource("schema.sql"))

    private fun columnType(c: Connection, table: String, column: String): String {
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() " +
                    "AND table_name = '$table' AND column_name = '$column'",
            ).use { rs -> return if (rs.next()) rs.getString(1).lowercase() else "(없음)" }
        }
    }

    private fun columns(c: Connection, table: String): Set<String> {
        val found = mutableSetOf<String>()
        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = '$table'",
            ).use { rs -> while (rs.next()) found += rs.getString(1).lowercase() }
        }
        return found
    }

    @Test
    fun `옛 truncated 컬럼을 가진 DB가 새 모양으로 올라간다`() {
        connect().use { c ->
            // 옛 모양을 손으로 만든다 — 실제 개발 DB가 이 상태였다
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS execution_event")
                s.execute(
                    """
                    CREATE TABLE execution_event (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        query_id BIGINT NOT NULL,
                        actor VARCHAR(64) NOT NULL,
                        outcome VARCHAR(16) NOT NULL,
                        original_sql TEXT NOT NULL,
                        rewritten_sql TEXT NULL,
                        applied_json TEXT NULL,
                        row_count INT NULL,
                        elapsed_ms BIGINT NULL,
                        truncated BOOLEAN NOT NULL DEFAULT FALSE,
                        error_code VARCHAR(32) NULL,
                        error_detail TEXT NULL,
                        at DATETIME(6) NOT NULL
                    )
                    """.trimIndent(),
                )
                // 옛 기록 하나 — 값이 옮겨지는지 본다
                s.execute(
                    "INSERT INTO execution_event (query_id, actor, outcome, original_sql, truncated, at) " +
                        "VALUES (1,'u1','SUCCESS','SELECT 1', TRUE, NOW(6))",
                )
            }

            applySchema(c)

            val after = columns(c, "execution_event")
            // 세 값으로 쪼갠 최종 모양: 설정 상한 · 적용 상한 · 초과 행 존재
            assertTrue(
                listOf("effective_limit", "configured_cap", "more_rows_exist").all { it in after },
                "새 컬럼이 안 생겼다: $after",
            )
            assertTrue("truncated" !in after, "옛 컬럼이 남았다: $after")
            // `row_cap`은 하루 만에 이름을 고친 컬럼이다 — 뭉갠 이름은 남겨두지 않는다(D5)
            assertTrue("row_cap" !in after, "뭉갠 이름의 컬럼이 남았다: $after")

            // 값 보존: truncated=true였던 기록은 "초과 행 있었음"으로 남는다
            c.createStatement().use { s ->
                s.executeQuery("SELECT more_rows_exist FROM execution_event ORDER BY id").use { rs ->
                    assertTrue(rs.next(), "옛 기록이 사라졌다")
                    assertEquals(true, rs.getBoolean(1))
                }
            }

            // `query_id`가 nullable로 풀린다 — 미리보기 감사는 저장 쿼리가 없다.
            // 픽스처를 실제 옛 DDL(`NOT NULL`, 커밋 52ae36a)로 두어야 이 결함이 잡힌다:
            // 처음 이 테스트는 픽스처를 이미 `NULL`로 써서 **영원히 통과할 수 없는 검사**였다(적대 검토 D2).
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO execution_event (query_id, actor, outcome, original_sql, at) " +
                        "VALUES (NULL,'u1','PREVIEW','SELECT 1', NOW(6))",
                )
            }

            // 본문 컬럼은 MEDIUMTEXT로 넓혀진다 — 재작성 SQL은 원본보다 커지므로 TEXT는 입력 상한과 너무 가깝다
            for (column in listOf("original_sql", "rewritten_sql", "applied_json", "error_detail")) {
                assertEquals("mediumtext", columnType(c, "execution_event", column), "컬럼: $column")
            }

            // 두 번 돌려도 안전하다 — 매 기동마다 도는 스크립트다
            applySchema(c)
            assertEquals(after, columns(c, "execution_event"))
        }
    }
}
