package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.QueryExecutor
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 세션 `sql_mode` 계약을 **위험 모드가 켜진 서버**에서 검증한다 (적대 검토 D1).
 *
 * 왜 별도 컨테이너인가: 기본 설정 MySQL의 `sql_mode`에는 `NO_BACKSLASH_ESCAPES`도 `ANSI_QUOTES`도 없다.
 * 그런 서버에서 "제거됐는가"를 단정하면 **아무것도 하지 않아도 통과**한다 — 처음 이 계약 테스트가 정확히
 * 그랬다(적대 검토가 실측으로 지적). 제거 로직을 실제로 태우려면 그 모드를 켠 서버가 필요하다.
 *
 * 왜 중요한가: `ANSI_QUOTES`가 켜지면 `"ssn"`이 **문자열 리터럴에서 컬럼 참조로** 바뀐다. 판정기(어휘
 * 스캐너·Druid 렉서)는 리터럴로 보므로 마스킹·BLOCK 대상이 아니라고 판단하고, 실행 세션은 컬럼으로 읽어
 * 원문을 반환한다. 판정과 실행이 같은 문장을 다르게 읽는 순간 이 제품의 전제가 무너진다.
 */
@Testcontainers
class SqlModeContractTest {

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            // 위험 모드를 **켜 둔** 서버 — 제거 로직이 실제로 동작하는지 보려면 이 상태가 필요하다
            .withCommand(
                "--sql-mode=NO_BACKSLASH_ESCAPES,ANSI_QUOTES,PIPES_AS_CONCAT,IGNORE_SPACE," +
                    "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES",
            )
    }

    private val executor by lazy {
        QueryExecutor(
            url = mysql.jdbcUrl,
            username = mysql.username,
            password = mysql.password,
            poolSize = 2,
            timeoutMs = 5_000,
        )
    }

    @Test
    fun `위험 모드가 켜진 서버에서도 실행 세션은 판정과 같은 문법으로 읽는다`() {
        val mode = executor.execute(ProbeOrder("SELECT @@SESSION.sql_mode")).rows.single().single()!!
        val modes = mode.split(',').map { it.trim().uppercase() }.toSet()

        // ⑴ 판정과 실행을 갈라놓는 모드는 전부 빠졌다
        for (forbidden in listOf(
            "NO_BACKSLASH_ESCAPES", "ANSI_QUOTES", "PIPES_AS_CONCAT", "IGNORE_SPACE", "HIGH_NOT_PRECEDENCE",
        )) {
            assertTrue(forbidden !in modes, "$forbidden 가 남아 있다: $mode")
        }

        // ⑵ 서버가 켜 둔 안전 모드는 **보존**한다 — 예전에는 전체를 갈아써서 ONLY_FULL_GROUP_BY가 사라졌고,
        //    검토자가 승인한 SQL이 그룹당 임의 행을 고르는 다른 의미로 돌았다
        assertTrue("ONLY_FULL_GROUP_BY" in modes, "서버 기본 모드를 갈아써 버렸다: $mode")
        assertTrue("STRICT_TRANS_TABLES" in modes, "고정 모드가 빠졌다: $mode")

        // ⑶ 결정적 증거: `"ssn"`이 컬럼이 아니라 **문자열**로 읽힌다(= 판정기와 같은 해석)
        val quoted = executor.execute(ProbeOrder("""SELECT "ssn" AS probe""")).rows.single().single()
        assertEquals("ssn", quoted, "실행 세션이 큰따옴표를 컬럼 참조로 읽는다 — 마스킹 우회 경로다")

        // ⑷ `||`가 CONCAT이 아니라 OR로 읽힌다(PIPES_AS_CONCAT 제거 확인)
        val pipes = executor.execute(ProbeOrder("SELECT 1 || 0 AS probe")).rows.single().single()
        assertEquals("1", pipes, "`||`가 CONCAT으로 읽힌다 — WHERE 의미가 판정과 달라진다")
    }
}
