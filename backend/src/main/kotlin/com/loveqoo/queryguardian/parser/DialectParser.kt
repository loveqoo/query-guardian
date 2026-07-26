package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.Dialect
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR

interface DialectParser {
    val dialect: Dialect

    fun parse(sql: String): ParseResult

    /** 카탈로그의 필수 술어 문자열(`consent_yn = 'Y'`)을 구조 비교용 Predicate로 파싱 (§6.5). */
    fun parsePredicate(predicateSql: String): PredicateParse

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
 * **합 타입인 이유**(spec 010 A4): 예전에는 `parse: ParseResult` 옆에 `statement: ParsedStatement?`가
 * 나란히 놓인 곱 타입이었고, "성공이면 핸들이 있다"는 상관관계가 **KDoc 산문**으로만 적혀 있었다.
 * 그래서 호출부에 `inspected.statement!!`가 생겼고 — 50줄 위의 `when` 분기를 근거로 삼는 `!!`였다 —
 * `parse=Success ∧ statement=null`이라는 성립하지 않는 조합이 여전히 표현 가능했다.
 *
 * 이제 그 조합은 **만들 수 없다**. 재작성(M1)은 [Parsed.statement]로 **판정에 쓰인 그 AST**를 고친다 —
 * 재파싱하면 판정 대상과 실행 대상이 갈라진다(spec 008 §2.5-1).
 *
 * 접수 위반은 두 갈래에 **모두** 있다: 주석·문형 검사는 어휘 층이라 파싱 성공 여부와 무관하게 나온다.
 */
sealed interface InspectResult {
    val intakeViolations: List<IntakeViolation>

    /** 파싱 성공 — IR과 그 파싱의 핸들이 **함께** 있다. 둘 중 하나만 있는 상태는 없다. */
    data class Parsed(
        val ir: QueryIR,
        val statement: ParsedStatement,
        override val intakeViolations: List<IntakeViolation>,
    ) : InspectResult

    /** 파싱 실패 — 고칠 AST가 없으므로 핸들도 없다. */
    data class Unparsed(
        val failure: ParseResult.Failure,
        override val intakeViolations: List<IntakeViolation>,
    ) : InspectResult
}

/**
 * [DialectParser.parsePredicate] 결과 (spec 010 P3 C4).
 *
 * **합 타입인 이유**: 예전 반환형은 `Predicate?`였고 `null`은 오직 `catch (e: Exception)`에서만 나왔다
 * (`toPredicate`는 non-null을 낸다 — 실측). 즉 `null`의 뜻은 정확히 **"예외가 났고 그 이유를 버렸다"** 였다.
 * 이유를 버린 대가가 호출부에 남아 있었다:
 *
 * - 강제식 **등록** 거부가 "파싱할 수 없습니다: <식>"까지만 말했다 — 어디가 틀렸는지는 없다
 * - 강제식 **매핑** 거부는 단정 하나(`predicate != null && requiredForm(predicate) != null`)가 서로 다른
 *   두 실패를 덮고 **그중 하나의 이름만** 댔다. 파싱이 안 된 경우에도 "판정 미지원 형태"라고 답해서
 *   등록자가 엉뚱한 곳을 고치러 갔다
 * - 카탈로그 로드는 `?: Predicate.Raw(sql)`로 **구조 검증을 텍스트 비교로 격하**시키면서 조용했다
 *
 * `null` 하나가 다섯 호출부에서 다섯 정책을 뜻했다. 이제 실패는 이유를 갖는 값이고, 정책은 각자
 * `when`으로 적는다 — 이유가 정책에 영향을 주지 않는 자리만 [predicateOrNull]을 쓴다.
 */
sealed interface PredicateParse {
    data class Parsed(val predicate: Predicate) : PredicateParse

    /** [reason]은 파서가 준 문장이다. 대상은 카탈로그에 등록된 **우리 강제식**이므로 사용자 데이터가 아니다. */
    data class Unparsed(val reason: String) : PredicateParse

    /**
     * 폴백이 이미 정해져 있어 **이유가 정책을 바꾸지 않는** 호출부용.
     * 이유를 쓸 곳이 있으면 `when`으로 갈라라 — 그것이 이 타입을 만든 목적이다.
     */
    val predicateOrNull: Predicate? get() = (this as? Parsed)?.predicate
}

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
