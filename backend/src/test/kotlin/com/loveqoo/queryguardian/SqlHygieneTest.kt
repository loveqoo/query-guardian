package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.parser.HygieneCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * spec 008 §2.6 위생 게이트 프로브 스위트.
 *
 * 적대 검토가 druid 하네스로 **실측한** 우회 형태들을 그대로 입력으로 쓴다(learning 003: 파서 "표현" 결함은
 * 계약 테스트로 안 잡힌다 — 실제 파서에 실제 문자열을 넣어야 한다). 오탐 축(리터럴 안의 `--`)도 같이 고정한다.
 */
class SqlHygieneTest {

    private fun codes(sql: String): Set<HygieneCode> =
        Fixtures.parser.checkHygiene(sql).map { it.code }.toSet()

    private fun assertHygiene(sql: String, expected: HygieneCode) {
        assertTrue(expected in codes(sql), "[$expected] 위생 위반이어야 함: $sql → ${codes(sql)}")
    }

    // ---- 주석 (CRITICAL 1·2) ----

    /**
     * 실행 주석은 IR에서 **보이지 않는데** MySQL은 실행한다 — 위생 게이트가 없으면 평문 ssn이 반환된다.
     * 이 테스트는 "차단됨"뿐 아니라 **BLOCK 룰이 발화하지 못한다는 사실**까지 고정한다(위생 게이트의 존재 이유).
     */
    @Test
    fun `MySQL 실행 주석에 숨긴 UNION은 위생 게이트만이 잡는다`() {
        val sql = "SELECT email FROM users /*!50000 UNION SELECT ssn FROM users */ LIMIT 10"
        assertHygiene(sql, HygieneCode.COMMENT_NOT_ALLOWED)

        val report = Fixtures.lint(sql)
        assertTrue(report.blocked, "차단되어야 함: $report")
        assertTrue(
            report.violations.any { it.ruleId == "hygiene/comment-not-allowed" },
            "위생 위반으로 차단되어야 함: $report",
        )
        // 근본 사실: 룰 층은 주석 안의 ssn을 못 본다. 이 단정이 깨지는 날(파서가 주석을 파싱)엔 게이트를 재설계한다.
        assertFalse(
            report.violations.any { it.ruleId.startsWith("no-blocked-column") },
            "IR은 주석 속 ssn을 볼 수 없다는 전제가 바뀌었다 — spec 008 §2.5 재검토 필요: $report",
        )
    }

    @Test
    fun `후행 주석은 주입될 LIMIT을 삼키므로 거부한다`() {
        assertHygiene("SELECT id FROM user_events WHERE event_date = '2026-01-01' -- 코멘트", HygieneCode.COMMENT_NOT_ALLOWED)
        assertHygiene("SELECT id FROM user_events WHERE event_date = '2026-01-01' # 코멘트", HygieneCode.COMMENT_NOT_ALLOWED)
        assertHygiene("SELECT id /* 블록 */ FROM user_events", HygieneCode.COMMENT_NOT_ALLOWED)
    }

    // ---- 오탐 금지: 리터럴 안의 주석 문자 ----

    @Test
    fun `리터럴 안의 주석 문자는 주석이 아니다`() {
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = 'a--b'"))
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = 'a#b'"))
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = 'a/*b*/c'"))
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = \"a--b\""))
        // 백틱 식별자·이스케이프된 인용부호를 지나서도 상태가 유지되어야 한다
        assertEquals(emptySet(), codes("SELECT `id` FROM `users` WHERE name = 'it\\'s -- ok'"))
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = 'it''s -- ok'"))
    }

    @Test
    fun `정상 쿼리는 위생 위반이 없다`() {
        assertEquals(emptySet(), codes("SELECT COUNT(*) FROM user_events WHERE event_date = '2026-01-01' LIMIT 10"))
        assertEquals(
            emptySet(),
            codes("WITH recent AS (SELECT id FROM user_events WHERE event_date = '2026-01-01') SELECT id FROM recent"),
        )
    }

    /**
     * MySQL에서 `--`는 **뒤에 공백류나 문장 끝**이 와야 주석이다. 이 규칙을 안 맞추면 두 가지가 깨진다:
     * ⑴ `5--1`(뺄셈) 오차단 ⑵ Druid 프린터가 `- -1`을 `--1`로 출력하므로 재작성 결과가 자기 게이트에 걸려
     * §3.0.3 "재작성 결과 자체 검증"이 저장/실행 분기를 만든다(적대 검토 결함 6).
     */
    @Test
    fun `공백 없는 이중 하이픈은 주석이 아니라 뺄셈이다`() {
        assertEquals(emptySet(), codes("SELECT id, 5--1 AS n FROM users"))
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE id > --1"))
        // 실제 주석은 공백이 오므로 그대로 잡힌다
        assertHygiene("SELECT id FROM users -- AND consent_yn = 'Y'", HygieneCode.COMMENT_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users WHERE id = 1 --", HygieneCode.COMMENT_NOT_ALLOWED)
    }

    // ---- 스키마 한정자 (CRITICAL 5) ----

    @Test
    fun `스키마 한정자는 판정과 실행 대상을 분기시키므로 거부한다`() {
        assertHygiene("SELECT ssn FROM otherdb.users LIMIT 10", HygieneCode.SCHEMA_QUALIFIER)
        // 논리 `users` 권한으로 통과하던 형태 — 위생 게이트 없이는 카탈로그가 이 테이블을 users로 본다
        assertHygiene("SELECT u.id FROM queryguardian.users u LIMIT 10", HygieneCode.SCHEMA_QUALIFIER)
    }

    // ---- 변수·0-테이블·금지 함수 (CRITICAL 5) ----

    @Test
    fun `변수 참조는 2단 유출 경로이므로 거부한다`() {
        assertHygiene("SELECT id FROM users WHERE id = @v LIMIT 10", HygieneCode.VARIABLE_NOT_ALLOWED)
        assertHygiene("SELECT @@version FROM users LIMIT 10", HygieneCode.VARIABLE_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users WHERE id = ? LIMIT 10", HygieneCode.VARIABLE_NOT_ALLOWED)
    }

    @Test
    fun `물리 테이블 0개 쿼리는 모든 테이블 게이트를 통과하므로 거부한다`() {
        assertHygiene("SELECT @@version", HygieneCode.NO_PHYSICAL_TABLE)
        assertHygiene("SELECT LOAD_FILE('/etc/passwd')", HygieneCode.NO_PHYSICAL_TABLE)
        assertHygiene("WITH x AS (SELECT 1 AS a) SELECT a FROM x", HygieneCode.NO_PHYSICAL_TABLE)
        assertHygiene("SELECT 1 FROM (SELECT 1 AS a) d", HygieneCode.NO_PHYSICAL_TABLE)
    }

    /**
     * 적대 검토 결함 4(실측): `dual`은 테이블처럼 쓰이지만 데이터가 없다 —
     * `FROM DUAL` 네 글자로 §2.6이 막으려던 정찰(`@@version`·`SLEEP`)이 그대로 통과했다.
     */
    @Test
    fun `FROM DUAL은 물리 테이블이 아니다`() {
        assertHygiene("SELECT VERSION(), DATABASE(), CURRENT_USER() FROM DUAL", HygieneCode.NO_PHYSICAL_TABLE)
        assertHygiene("SELECT 1 FROM `dual`", HygieneCode.NO_PHYSICAL_TABLE)
        // 반대로 서브쿼리가 물리 테이블을 읽으면 테이블 기반 게이트가 헛돌지 않으므로 위생 위반이 아니다
        // (승인 커버·권한은 전 스코프 합집합을 보므로 users를 본다 — spec 005 C2)
        assertEquals(emptySet(), codes("SELECT (SELECT MAX(id) FROM users) FROM DUAL"))
    }

    /**
     * 적대 검토 결함 3(실측): "집계 CTE를 원본 테이블 이름으로 명명"은 분석가 표준 관용구인데,
     * CTE 이름을 전역 수집해 물리 테이블까지 지우면 정상 쿼리가 `NO_PHYSICAL_TABLE`로 오차단됐다.
     * 물리 테이블 판정은 스코프를 정확히 아는 **IR 기준**이어야 한다.
     */
    @Test
    fun `동명 CTE는 물리 테이블 참조를 지우지 않는다`() {
        assertEquals(
            emptySet(),
            codes(
                "WITH user_events AS (SELECT event_date, COUNT(*) c FROM user_events GROUP BY event_date) " +
                    "SELECT event_date, c FROM user_events LIMIT 10",
            ),
        )
        assertEquals(
            emptySet(),
            codes("SELECT u.id FROM users u JOIN (WITH users AS (SELECT 1 AS id) SELECT id FROM users) d ON u.id = d.id"),
        )
    }

    @Test
    fun `금지 함수는 문형에서 막는다`() {
        assertHygiene("SELECT LOAD_FILE('/etc/passwd')", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT id FROM users WHERE SLEEP(5) LIMIT 10", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT BENCHMARK(1000000, MD5('x')) FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT GET_LOCK('x', 10) FROM users", HygieneCode.BANNED_FUNCTION)
        // 서브쿼리 안에 숨겨도 잡힌다
        assertHygiene("SELECT id FROM users WHERE id IN (SELECT SLEEP(5)) LIMIT 10", HygieneCode.BANNED_FUNCTION)
    }

    /**
     * 적대 검토 결함 1(CRITICAL, 실측): Druid는 백틱을 함수명에 그대로 남기므로 `` `SLEEP`(5) ``가
     * 목록 비교를 통째로 우회했고 MySQL 8.4는 그것을 빌트인으로 **실제 실행**했다(3초 지연 확인).
     * 식별자는 예외 없이 `norm()`을 통과해야 한다(spec 001 §6.5).
     */
    @Test
    fun `백틱으로 감싼 금지 함수명도 잡는다`() {
        assertHygiene("SELECT `sleep`(5) FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT `load_file`('/etc/passwd') AS f FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT `get_lock`('x', 3600) AS g FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT `benchmark`(1000000, MD5('x')) FROM users", HygieneCode.BANNED_FUNCTION)
        // 대소문자·공백·한정자 변형
        assertHygiene("SELECT LoAd_FiLe('/etc/passwd') FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT SLEEP (1) FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT mysql.SLEEP(1) FROM users", HygieneCode.BANNED_FUNCTION)
        // 잠금·행수 캐시 계열도 목록에 있다
        assertHygiene("SELECT RELEASE_LOCK('x') FROM users", HygieneCode.BANNED_FUNCTION)
        assertHygiene("SELECT FOUND_ROWS() FROM users", HygieneCode.BANNED_FUNCTION)
        // 오탐 금지: 컬럼명이 sleep인 것은 함수가 아니다
        assertEquals(emptySet(), codes("SELECT sleep FROM users WHERE sleep > 1"))
    }

    // ---- 문형 허용목록 ----

    @Test
    fun `잠금 문형은 읽기 전용 실행과 모순이므로 거부한다`() {
        assertHygiene("SELECT id FROM users FOR UPDATE", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users LOCK IN SHARE MODE", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users FOR UPDATE SKIP LOCKED", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        assertHygiene("SELECT SQL_CALC_FOUND_ROWS id FROM users LIMIT 10", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
    }

    /**
     * 적대 검토 결함 2(실측): MySQL 8이 `LOCK IN SHARE MODE`를 대체한 현행 문법 `FOR SHARE`는
     * Druid의 어떤 플래그에도 담기지 않으면서 출력에는 보존된다. 게다가 `START TRANSACTION READ ONLY`에서도
     * **실행되므로**(FOR UPDATE는 거부됨) DB 권한이 막아주지 않는 유일한 잠금 형태였다.
     */
    @Test
    fun `FOR SHARE는 어휘로 잡는다`() {
        assertHygiene("SELECT id FROM users FOR SHARE", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users WHERE id IN (SELECT id FROM users FOR SHARE)", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users UNION (SELECT id FROM users FOR SHARE)", HygieneCode.STATEMENT_FORM_NOT_ALLOWED)
        // 오탐 금지: 리터럴 안의 같은 문구는 문형이 아니다
        assertEquals(emptySet(), codes("SELECT id FROM users WHERE name = 'for share'"))
    }

    /**
     * 검사 불가를 "위반 없음"으로 보고하면 위생을 독립 단계로 호출하는 경로(spec 008 §5)가 fail-open한다.
     * `INTO DUMPFILE`·`PROCEDURE ANALYSE`·`TABLE users`는 Druid가 파싱을 거부하는데, 이때 빈 목록을 돌려주면
     * 그 문형들이 위생 게이트를 통과한 것으로 읽힌다(적대 검토 결함 5).
     */
    @Test
    fun `검사 불가는 위반 없음이 아니다`() {
        for (sql in listOf(
            "SELECT id FROM users INTO DUMPFILE '/tmp/x'",
            "SELECT id FROM users PROCEDURE ANALYSE()",
            "TABLE users",
            "SELECT 1; SELECT 2",
            "DELETE FROM users",
            "SELECT FROM WHERE ((",
        )) {
            assertTrue(
                HygieneCode.UNVERIFIABLE in codes(sql),
                "검사 불가는 UNVERIFIABLE로 보고되어야 함: $sql → ${codes(sql)}",
            )
        }
    }

    /**
     * `INTO OUTFILE`은 파일 반출 경로다. Druid가 이를 SELECT 문으로 파싱하면 위생 위반으로,
     * 별도 문 타입으로 파싱하면 parse/not-select로 막힌다 — **어느 쪽이든 lint가 차단**하는 것이 계약이다.
     */
    @Test
    fun `INTO 반출은 어느 경로로든 차단된다`() {
        for (sql in listOf(
            "SELECT email INTO OUTFILE '/tmp/x' FROM users",
            "SELECT email INTO @v FROM users LIMIT 1",
        )) {
            val report = Fixtures.lint(sql)
            assertTrue(report.blocked, "차단되어야 함: $sql\n$report")
            assertTrue(
                report.violations.any {
                    it.ruleId == "hygiene/statement-form-not-allowed" ||
                        it.ruleId == "hygiene/variable-not-allowed" ||
                        it.ruleId.startsWith("parse/")
                },
                "위생 또는 파스 위반으로 차단되어야 함: $sql\n$report",
            )
        }
    }

    /**
     * spec 008 결정 12 (사용자): OFFSET 금지. `LIMIT 1000,1000`을 반복하면 행 상한이 무의미해지고,
     * 감사에서 "이 쿼리로 총 몇 행이 나갔나"를 셀 수 없다. 페이지네이션은 §2에서 이미 비범위.
     */
    @Test
    fun `LIMIT OFFSET은 행 상한을 무한 우회하므로 거부한다`() {
        assertHygiene("SELECT id FROM users LIMIT 1000, 1000", HygieneCode.LIMIT_OFFSET_NOT_ALLOWED)
        assertHygiene("SELECT id FROM users LIMIT 1000 OFFSET 1000", HygieneCode.LIMIT_OFFSET_NOT_ALLOWED)
        // 서브쿼리·UNION 안에 숨겨도 잡힌다
        assertHygiene("SELECT d.id FROM (SELECT id FROM users LIMIT 10 OFFSET 5) d", HygieneCode.LIMIT_OFFSET_NOT_ALLOWED)
        assertHygiene(
            "SELECT id FROM users UNION ALL SELECT id FROM users LIMIT 5 OFFSET 5",
            HygieneCode.LIMIT_OFFSET_NOT_ALLOWED,
        )
        // 오탐 금지: OFFSET 없는 LIMIT은 정상
        assertEquals(emptySet(), codes("SELECT id FROM users LIMIT 100"))
    }
}
