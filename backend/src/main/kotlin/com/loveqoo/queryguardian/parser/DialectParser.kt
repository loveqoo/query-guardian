package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.Dialect
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR

interface DialectParser {
    val dialect: Dialect

    fun parse(sql: String): ParseResult

    /** 카탈로그의 필수 술어 문자열(`consent_yn = 'Y'`)을 구조 비교용 Predicate로 파싱 (§6.5). 실패 시 null. */
    fun parsePredicate(predicateSql: String): Predicate?
}

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
