package com.loveqoo.queryguardian.ir

/**
 * **방언 중립 재작성 계획** (spec 008 §3.5 M1-2).
 *
 * `exec/RewritePlanner`가 IR+카탈로그로 만들고 `parser/SqlRewriter`가 AST에 적용한다.
 * 이 어휘가 `ir`에 사는 이유: 계획을 `exec`에 두면 `parser`가 `exec`를 의존해야 해서 계층이 역전된다.
 * 계획 자체는 Druid·카탈로그·권한을 **알지 못한다** — 무엇을 어디에 적용할지만 담는다.
 *
 * 모든 항목은 [SelectScope.scopeId]로 대상 스코프를 지목하므로, 계획은 그 id를 발급한 파싱의 핸들과
 * **짝으로만** 유효하다(spec 008 결정 13).
 */
data class RewritePlan(
    /** MASK 매핑 컬럼의 투영을 강제식으로 치환 (§3.0.1). */
    val maskProjections: List<MaskProjection> = emptyList(),
    /** FILTER·INTEGRITY 술어를 최상위 AND conjunct로 주입 (§3.0-1). */
    val injections: List<PredicateInjection> = emptyList(),
    /** 행 상한 — 루트(또는 UNION 노드)에 적용 (§3.0-2). */
    val limitCap: LimitCap? = null,
    /**
     * 논리명(소문자) → 물리 데모 테이블명. **재작성의 마지막 단계에서만** 적용한다 —
     * 물리명으로 카탈로그를 조회하면 제약이 0건 매칭돼 마스킹·필터가 조용히 사라진다(§3 원칙).
     */
    val tableMap: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = maskProjections.isEmpty() && injections.isEmpty() && limitCap == null && tableMap.isEmpty()

    /** 계획이 참조하는 모든 스코프 id — 핸들과의 짝 검증에 쓴다. */
    val referencedScopeIds: Set<String>
        get() = (maskProjections.map { it.scopeId } + injections.map { it.scopeId } +
            listOfNotNull(limitCap?.scopeId)).toSet()
}

/**
 * [scopeId] 스코프에서 [instanceKey] 인스턴스의 [column] **투영**을 [expressionTemplate]로 치환한다.
 *
 * [expressionTemplate]는 `{col}` 자리표시자를 정확히 하나 포함한다(`mask_email({col})`). 재작성기는
 * 그 자리에 **원본 컬럼 표현식을 그대로** 넣는다 — `u.email`처럼 한정된 참조의 한정자가 보존되어야 하기 때문.
 * 치환 결과는 문자열로 이어붙이지 않고 **다시 파싱해 단일 표현식 노드로** 삽입한다(§3.0-3).
 *
 * [outputName]은 재작성 후에도 유지할 출력 이름 — 원 별칭이 있으면 그 별칭, 없으면 원 컬럼명.
 * 강제로 `AS {컬럼명}`을 붙이면 `email AS mail`의 이름이 바뀌어 외부 참조가 깨진다(§3.0.1).
 */
data class MaskProjection(
    val scopeId: String,
    val instanceKey: String,
    val column: String,
    val expressionTemplate: String,
    val outputName: String,
)

/**
 * [scopeId] 스코프의 WHERE에 [predicateSql]를 최상위 AND conjunct로 주입한다.
 *
 * [predicateSql]는 **이미 인스턴스로 한정된 최종 술어**다(`mc.consent_yn = 'Y'`) — 스코프에 같은 테이블이
 * 여러 인스턴스로 있을 수 있으므로 한정은 계획 수립자의 책임이다. 재작성기는 파싱해 결합만 한다.
 *
 * [alreadySatisfied]가 true면 동일 술어가 이미 최상위 conjunct로 있어 주입을 생략했다는 기록(감사용).
 */
data class PredicateInjection(
    val scopeId: String,
    val instanceKey: String,
    val predicateSql: String,
    val reason: String,
    val alreadySatisfied: Boolean = false,
)

/**
 * 유효 상한 [maxRows] = `min(사용자 LIMIT ?: ∞, 설정 상한)`. 재작성기는 `LIMIT maxRows + 1`을 넣고
 * 실행기가 `maxRows + 1`번째 행을 보면 `truncated = true`로 확정한 뒤 그 행을 버린다(§3.0-2).
 * `setMaxRows` 병용은 금지 — 상한 장치는 하나여야 어긋나지 않는다.
 */
data class LimitCap(val scopeId: String, val maxRows: Long)

/** 재작성 거부 사유 — 전부 fail-closed다(부분 적용 없음, spec 008 결정 6). */
enum class RewriteRefusal {
    /** MASK 대상 컬럼이 투영이 아닌 위치(함수 인자·CASE·WHERE 등)에 있어 치환으로 표현할 수 없다 (§3.0.1). */
    MASK_NOT_EXPRESSIBLE,

    /** OUTER JOIN의 null 생성 쪽 테이블에 FILTER 대상이 있다 — WHERE 주입이 LEFT JOIN을 INNER로 바꾼다 (§3.0.2). */
    OUTER_JOIN_FILTER,

    /** 강제식·술어를 표현식으로 파싱할 수 없거나 서브쿼리를 포함한다 (§3.0-3). */
    EXPRESSION_NOT_USABLE,

    /** 계획이 지목한 스코프를 핸들에서 찾을 수 없다 — 다른 파싱의 계획을 적용하려 한 것이다. */
    SCOPE_NOT_FOUND,

    /** 재작성 결과 자체 검증 실패 (§3.0.3) — 주입이 최상위 conjunct가 아니거나 MASK 컬럼이 남아 있는 등. */
    VERIFY_FAILED,
}

/** 재작성 결과. 거부는 예외가 아니라 값이다 — 게이트가 사유를 사용자에게 그대로 전달해야 한다. */
sealed interface RewriteOutcome {
    data class Rewritten(val sql: String, val applied: List<AppliedRewrite>) : RewriteOutcome
    data class Refused(val refusal: RewriteRefusal, val message: String) : RewriteOutcome
}

/** 무엇이 자동 적용됐는지 — 화면 표시와 감사(`applied_json`)에 쓴다. 강제식 원문을 남긴다(§6). */
data class AppliedRewrite(
    val kind: RewriteKind,
    val table: String,
    val column: String?,
    val detail: String,
)

enum class RewriteKind { MASK, FILTER, INTEGRITY, LIMIT, TABLE_MAP }
