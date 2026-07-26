package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.Dialect
import com.loveqoo.queryguardian.ir.SelectScope
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
     * 접수 검사 (spec 008 §2.6) — 원문·AST에서만 볼 수 있는 형태 위반 목록. 위반 없으면 빈 목록.
     *
     * [parse]와 별개인 이유: IR은 lossy해서 주석·변수·스키마 한정자·문형이 IR에 남지 않는다.
     * **검사 불가(파싱 실패·비-SELECT)는 빈 목록이 아니라 [IntakeCode.UNVERIFIABLE]** — 빈 목록은
     * "위반 없음"으로 읽혀 단독 호출 경로를 fail-open시킨다.
     */
    fun checkIntake(sql: String): List<IntakeViolation>

    /**
     * 파싱 **1회**로 IR과 접수 위반을 함께 얻는다. 게이트는 이 경로를 쓴다 — 두 번 파싱하면
     * 판정 대상과 검사 대상이 갈라질 수 있다(타임아웃 레이스로 실측됨).
     */
    fun inspect(sql: String): InspectResult
}

/**
 * [DialectParser.inspect] 결과 — 같은 파싱에서 나온 판정 입력과 형태 검사 결과, 그리고 그 파싱의 핸들.
 *
 * [statement]는 파싱 성공 시에만 있다. 재작성(M1)은 이 핸들을 통해 **판정에 쓰인 그 AST**를 고친다 —
 * 재파싱하면 판정 대상과 실행 대상이 갈라진다(spec 008 §2.5-1).
 */
data class InspectResult(
    val parse: ParseResult,
    val intakeViolations: List<IntakeViolation>,
    val statement: ParsedStatement? = null,
)

/**
 * 파싱 1회의 **불투명 핸들** (spec 008 결정 13). 방언 AST 타입을 밖으로 노출하지 않으면서
 * 재작성이 그 AST를 지목할 수 있게 한다 — 구현은 방언별 파서 내부에 있다.
 *
 * [SelectScope.scopeId]는 이 핸들과 **짝으로만** 유효하다. 다른 파싱의 id를 넘기면 재작성은 대상을 찾지 못한다.
 */
interface ParsedStatement {
    val dialect: Dialect

    /** 이 파싱에서 발급된 스코프 수 — 계획이 참조하는 id가 이 핸들 것인지 확인하는 용도. */
    val scopeIds: Set<String>
}

sealed interface ParseResult {
    data class Success(val ir: QueryIR) : ParseResult
    data class Failure(val kind: FailureKind, val message: String) : ParseResult
}

enum class FailureKind {
    SYNTAX_ERROR,

    /**
     * 재귀가 감당 범위를 넘었다 — 문법 오류가 아니다.
     *
     * 예전에는 `StackOverflowError`가 `SYNTAX_ERROR`로 기록됐다. 감사에서 **오타와 공격을 구분할 수 없다**는
     * 뜻이고, 폭주 입력을 던지는 사람과 괄호를 잘못 닫은 사람이 같은 줄로 남는다.
     */
    TOO_COMPLEX,
    MULTI_STATEMENT,
    NOT_SELECT,
    INPUT_TOO_LARGE,
    TIMEOUT,

    /** 파싱 풀이 포화됐다 — 입력의 문제가 아니라 서버 부하다. 재시도가 의미 있는 유일한 실패다. */
    OVERLOADED,
}
