package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.Dialect
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR

interface DialectParser {
    val dialect: Dialect

    fun parse(sql: String): ParseResult

    /** 카탈로그의 필수 술어 문자열(`consent_yn = 'Y'`)을 구조 비교용 Predicate로 파싱 (§6.5). 실패 시 null. */
    fun parsePredicate(predicateSql: String): Predicate?

    /** 강제식 등록 검증용 — 술어 표현식에 서브쿼리가 포함되면 true (spec 002 §3.3: 등록 거부 대상). */
    fun predicateContainsSubquery(predicateSql: String): Boolean

    /**
     * 위생 게이트 (spec 008 §2.6) — 원문·AST에서만 볼 수 있는 형태 위반 목록. 위반 없으면 빈 목록.
     *
     * [parse]와 별개인 이유: IR은 lossy해서 주석·변수·스키마 한정자·문형이 IR에 남지 않는다.
     * **검사 불가(파싱 실패·비-SELECT)는 빈 목록이 아니라 [HygieneCode.UNVERIFIABLE]** — 빈 목록은
     * "위반 없음"으로 읽혀 단독 호출 경로를 fail-open시킨다.
     */
    fun checkHygiene(sql: String): List<HygieneViolation>

    /**
     * 파싱 **1회**로 IR과 위생 위반을 함께 얻는다. 게이트는 이 경로를 쓴다 — 두 번 파싱하면
     * 판정 대상과 검사 대상이 갈라질 수 있다(타임아웃 레이스로 실측됨).
     */
    fun inspect(sql: String): InspectResult
}

/** [DialectParser.inspect] 결과 — 같은 파싱에서 나온 판정 입력과 형태 검사 결과. */
data class InspectResult(val parse: ParseResult, val hygiene: List<HygieneViolation>)

sealed interface ParseResult {
    data class Success(val ir: QueryIR) : ParseResult
    data class Failure(val kind: FailureKind, val message: String) : ParseResult
}

enum class FailureKind {
    SYNTAX_ERROR,
    MULTI_STATEMENT,
    NOT_SELECT,
    INPUT_TOO_LARGE,
    TIMEOUT,
}
