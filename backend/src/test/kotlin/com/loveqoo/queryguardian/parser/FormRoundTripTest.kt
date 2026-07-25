package com.loveqoo.queryguardian.parser

import com.alibaba.druid.DbType
import com.alibaba.druid.sql.SQLUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 008 §3.0.3이 의존하는 **왕복 정합성**:
 *
 *     checkForm(sql) 통과  ⟹  checkForm(Druid가 출력한 sql) 통과
 *
 * M1의 재작성은 AST를 고친 뒤 **다시 프린트해서** 실행한다. 이 성질이 깨지면 저장 시점엔 통과한 쿼리가
 * 실행 시점에 자기 재작성 결과 때문에 거부된다(저장/실행 분기). 실제 반례가 있었다:
 * `WHERE id > - -1`이 `--1`로 출력돼 주석으로 오인됐고, MySQL 규칙(`--` 뒤 공백류/문장 끝)을 따라 고쳤다.
 *
 * 이 테스트는 타사 모델 검토가 160개 입력으로 측정한 카테고리를 축약해 영구 고정한 것이다.
 * **Druid를 직접 쓰므로 parser 테스트 패키지에 둔다** — 방언 타입의 parser 패키지 봉인(ArchUnit)을 지키기 위해.
 */
class FormRoundTripTest {

    private val parser = DruidMySqlParser()

    /** 카테고리별 코퍼스 — 프린터가 공백을 정규화하거나 텍스트를 다시 인용할 수 있는 지점을 노린다. */
    private val corpus = listOf(
        // 산술·단항 부호 (실제 반례가 나온 자리)
        "SELECT id FROM users WHERE id > - -1",
        "SELECT id, 5--1 AS n FROM users",
        "SELECT id, -id AS neg, +id AS pos FROM users",
        "SELECT id FROM users WHERE id = 1 - -1",
        // 문자열 리터럴 — 주석 문자·이스케이프·유니코드
        "SELECT id FROM users WHERE name = 'a--b'",
        "SELECT id FROM users WHERE name = 'a#b'",
        "SELECT id FROM users WHERE name = 'a/*b*/c'",
        "SELECT id FROM users WHERE name = 'it''s ok'",
        "SELECT id FROM users WHERE name = 'it\\'s ok'",
        "SELECT id FROM users WHERE name = '한글 😀 --x'",
        "SELECT id FROM users WHERE name = 'for share'",
        // 캐릭터셋 도입자·16진수·비트 리터럴
        "SELECT _utf8mb4'a--b' AS s FROM users",
        "SELECT N'a#b' AS s FROM users",
        "SELECT X'2d2d' AS h FROM users",
        "SELECT 0x2d2d AS h FROM users",
        "SELECT b'1010' AS b FROM users",
        // 인용이 필요한 식별자
        "SELECT `id` FROM `users`",
        "SELECT id AS `select` FROM users",
        "SELECT id AS `two words` FROM users",
        "SELECT id AS `a#b` FROM users",
        "SELECT id AS `a-b` FROM users",
        // 파생·CTE·UNION 스코프
        "SELECT t.id FROM (SELECT id FROM users) t",
        "WITH x AS (SELECT id FROM users) SELECT id FROM x",
        "WITH users AS (SELECT id FROM users) SELECT id FROM users",
        "SELECT d.c FROM (SELECT id AS c FROM users UNION ALL SELECT id AS c FROM users) d",
        "SELECT id FROM users UNION ALL SELECT id FROM users",
        "SELECT id FROM users UNION DISTINCT SELECT id FROM users",
        // 함수·윈도우·집계
        "SELECT COUNT(DISTINCT id) FROM users",
        "SELECT ROW_NUMBER() OVER (PARTITION BY name ORDER BY id DESC) AS rn, id FROM users",
        "SELECT GROUP_CONCAT(name ORDER BY id SEPARATOR ',') AS g FROM users",
        // 날짜·형변환·COLLATE·JSON
        "SELECT id FROM users WHERE created_at > NOW() - INTERVAL 7 DAY",
        "SELECT CAST(id AS CHAR) AS c FROM users",
        "SELECT CONVERT(name USING utf8mb4) AS c FROM users",
        "SELECT id FROM users WHERE name = 'a' COLLATE utf8mb4_bin",
        "SELECT name -> '$.a' AS j FROM users",
        "SELECT name ->> '$.a' AS j FROM users",
        // 비교·NULL·BETWEEN·IN·정렬
        "SELECT id FROM users WHERE id BETWEEN 1 AND 10 AND name IS NOT NULL",
        "SELECT id FROM users WHERE id IN (1, 2, 3) ORDER BY name COLLATE utf8mb4_bin DESC",
        "SELECT id FROM users WHERE EXISTS (SELECT 1 FROM users u2 WHERE u2.id = users.id)",
        // 일반 분석 쿼리
        "SELECT u.id, u.name FROM users u JOIN users v ON v.id = u.id WHERE u.id > 0 GROUP BY u.id, u.name " +
            "HAVING COUNT(*) > 1 ORDER BY u.id LIMIT 100",
        "SELECT event_date, COUNT(*) AS c FROM user_events GROUP BY event_date ORDER BY c DESC LIMIT 10",
    )

    @Test
    fun `형식 검사 통과 SQL은 프린터 출력도 형식 검사를 통과한다`() {
        val violations = mutableListOf<String>()
        var checked = 0
        for (sql in corpus) {
            val before = parser.checkForm(sql)
            if (before.isNotEmpty()) continue // 원문이 이미 거부면 이 성질의 대상이 아니다
            checked++
            val printed = SQLUtils.toSQLString(
                SQLUtils.parseStatements(sql, DbType.mysql).single(),
                DbType.mysql,
            )
            val after = parser.checkForm(printed)
            if (after.isNotEmpty()) {
                violations += "원문: $sql\n출력: $printed\n출력 판정: ${after.map { it.code }}"
            }
        }
        assertTrue(violations.isEmpty(), "왕복 정합성 반례 ${violations.size}건:\n${violations.joinToString("\n---\n")}")
        // 코퍼스가 조용히 비어버리면(원문이 전부 거부되면) 이 테스트는 아무것도 검증하지 않는다
        assertTrue(checked >= 35, "왕복 검증 대상이 너무 적다: $checked/${corpus.size} — 코퍼스가 과도하게 거부되고 있다")
    }

    /** 코퍼스의 정상 쿼리가 형식 검사에 걸리면 그것 자체가 오차단이다 — 어느 문장이 걸렸는지 드러낸다. */
    @Test
    fun `코퍼스의 정상 쿼리는 형식 위반이 없다`() {
        val rejected = corpus.mapNotNull { sql ->
            parser.checkForm(sql).takeIf { it.isNotEmpty() }?.let { "$sql → ${it.map { v -> v.code }}" }
        }
        assertEquals(emptyList(), rejected, "정상 쿼리가 거부됨:\n${rejected.joinToString("\n")}")
    }
}
