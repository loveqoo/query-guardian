package com.loveqoo.queryguardian.parser

/**
 * 접수 검사 (spec 008 §2.6) — 재작성·실행 대상 SQL이 통과해야 하는 형태 제약.
 *
 * **왜 IR이 아니라 원문·AST에서 검사하는가**: IR은 lossy하다. 적대 검토가 실측으로 밝힌 대로
 * MySQL 실행 주석(여는 `slash-star-!`, 예 `!50000 UNION SELECT ssn FROM users`)으로 감싼 UNION은 IR에서
 * **전혀 보이지 않지만** MySQL은 그것을 실행한다. IR로 검사하는 게이트는 이 형태를 원리적으로 볼 수 없다.
 * (KDoc 안에는 여는 주석 기호를 그대로 쓸 수 없어 풀어 적는다 — Kotlin은 블록 주석을 중첩 처리한다.)
 */
enum class IntakeCode {
    /** 모든 주석 금지 — MySQL 실행 주석(여는 `slash-star-!`)은 실제로 실행되고, 후행 `--`·`#`은 주입한 LIMIT을 삼킨다. */
    COMMENT_NOT_ALLOWED,

    /** 문형 허용목록 위반 — `INTO`(OUTFILE/DUMPFILE/변수)·`FOR UPDATE`·`FOR SHARE`·`LOCK IN SHARE MODE`·`PROCEDURE`·힌트·`SQL_CALC_FOUND_ROWS`. */
    CLAUSE_NOT_ALLOWED,

    /** 변수 참조 금지 — `@x`·`@@x`·`?`·`:name`. 2단 유출(`SELECT email INTO @v` → `SELECT @v`) 차단. */
    VARIABLE_NOT_ALLOWED,

    /** 스키마 한정자 금지 — 판정은 `users`, 실행은 `otherdb.users`가 되는 판정-실행 분기 차단. */
    SCHEMA_QUALIFIER,

    /** 물리 테이블 0개 금지 — `SELECT LOAD_FILE(...)`·`SELECT @@version`·`FROM DUAL`은 모든 테이블 기반 게이트를 통과한다. */
    NO_PHYSICAL_TABLE,

    /** 금지 함수 — 파일 읽기·시간 지연·잠금·행수 캐시. */
    BANNED_FUNCTION,

    /**
     * `LIMIT`의 OFFSET 금지 (spec 008 결정 12) — `LIMIT 1000,1000`으로 행 상한을 무한 우회할 수 있고,
     * 상한의 의미를 "이 실행으로 나간 행 수"로 고정해야 감사가 총 반출량을 셀 수 있다.
     */
    LIMIT_OFFSET_NOT_ALLOWED,

    /**
     * 검사 자체가 불가능했다 — 파싱 실패·비-SELECT·AST 순회 예외.
     *
     * 접수 검사가 "볼 수 없었다"를 빈 목록(=위반 없음)으로 보고하면 접수 검사를 **단독으로** 호출하는 경로
     * (spec 008 §5의 독립 단계)가 그대로 fail-open한다. 그래서 검사 불가는 명시적 위반이다.
     */
    UNVERIFIABLE,
}

data class IntakeViolation(val code: IntakeCode, val message: String)

/**
 * 리터럴·백틱 식별자를 인식하는 어휘 스캔.
 *
 * `sql.contains("--")` 같은 단순 검사는 `note = 'a--b'`를 오차단하므로 금지(§2.6). 반대로 Druid AST만 믿으면
 * 주석은 AST에 남지 않거나 출력에 보존되므로 검출되지 않는다 — 그래서 원문 스캔이 유일한 수단이다.
 *
 * **전제(운영 시 고정 필요)**: MySQL 기본 `sql_mode`, 즉 `NO_BACKSLASH_ESCAPES`가 **없는** 상태.
 * 그 모드에서는 `\`가 이스케이프가 아니어서 리터럴 경계가 이 스캐너와 달라진다 → 실행·검사 커넥션의
 * `sql_mode`를 명시적으로 고정해야 한다(적대 검토 결함 8).
 */
object SqlCommentScanner {

    /** 어휘 스캔 결과: 리터럴 밖 첫 주석 위치와, 리터럴을 공백으로 치환한 원문. */
    data class Scan(val commentAt: Int?, val withoutLiterals: String)

    /** 리터럴 밖 주석 시작 위치. 없으면 null. */
    fun findComment(sql: String): Int? = scan(sql).commentAt

    /**
     * 리터럴·백틱 식별자를 같은 길이의 공백으로 치환한 텍스트.
     * 키워드 어휘 검사(`FOR SHARE` 등)를 리터럴 오탐 없이 하기 위한 안전한 기반이다.
     */
    fun withoutLiterals(sql: String): String = scan(sql).withoutLiterals

    fun scan(sql: String): Scan {
        val stripped = StringBuilder(sql.length)
        var commentAt: Int? = null
        var i = 0
        val n = sql.length
        while (i < n) {
            when (val c = sql[i]) {
                '\'', '"', '`' -> {
                    val end = skipQuoted(sql, i, c)
                    repeat(end - i) { stripped.append(' ') }
                    i = end
                }
                // MySQL에서 `--`는 **뒤에 공백류나 문장 끝이 와야** 주석이다. `SELECT 5--1`은 뺄셈이고,
                // Druid 프린터도 `- -1`을 `--1`로 출력하므로(적대 검토 결함 6) 이 규칙을 맞추지 않으면
                // "접수 검사 통과 → 재작성 → 접수 위반"이라는 비고정점이 생겨 §3.0.3 재작성 검증이 성립하지 않는다.
                '-' -> {
                    if (i + 1 < n && sql[i + 1] == '-' && (i + 2 >= n || sql[i + 2].isWhitespace())) {
                        if (commentAt == null) commentAt = i
                    }
                    stripped.append(c); i++
                }
                '#' -> { if (commentAt == null) commentAt = i; stripped.append(c); i++ }
                '/' -> {
                    if (i + 1 < n && sql[i + 1] == '*' && commentAt == null) commentAt = i
                    stripped.append(c); i++
                }
                else -> { stripped.append(c); i++ }
            }
        }
        return Scan(commentAt, stripped.toString())
    }

    /**
     * [open] 위치의 인용부호로 시작하는 리터럴/식별자를 지나 그 다음 인덱스를 반환한다.
     * MySQL 기본 모드: 문자열 안 `\'`는 이스케이프, `''`는 리터럴 인용부호. 백틱 식별자는 `` `` ``만 이스케이프.
     * 닫히지 않은 인용부호는 문자열 끝까지 소비한다(문법 오류로 파서가 별도 차단).
     */
    private fun skipQuoted(sql: String, open: Int, quote: Char): Int {
        var i = open + 1
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            if (c == '\\' && quote != '`') { i += 2; continue }
            if (c == quote) {
                if (i + 1 < n && sql[i + 1] == quote) { i += 2; continue }
                return i + 1
            }
            i++
        }
        return n
    }
}
