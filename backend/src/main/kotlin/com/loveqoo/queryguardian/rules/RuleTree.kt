package com.loveqoo.queryguardian.rules

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue

/** 규칙 범위 (spec 004 §3.2). */
enum class RuleScope { SINGLE, MULTI, GLOBAL }

/**
 * 조건 연산자. requires/blocks/joins/must_be_masked는 판정, must_be_within은 등록·표시만(judged=false).
 *
 * **상수 이름과 와이어 표현이 분리되어 있다**(spec 010 I13 · 리뷰 R1). 예전에는 상수 이름 자체가
 * 소문자였다 — 코틀린 관례를 어겼지만, 고칠 때 깨지는 것이 **컴파일러가 잡아 주는 범위 밖**에 있었다:
 * `rule.tree_json`에 소문자 값이 이미 저장되어 있고, 프론트가 그 문자열을 **타입 유니온과 테마 맵의 키**로
 * 쓴다(`theme.ts`). 그래서 답은 "이름을 대문자로"가 아니라 "표현을 계약으로 고정하고 이름을 관례로"다.
 * `RuleWireFormatTest`가 그 계약을 지킨다.
 *
 * `must_be_masked`는 spec 008 M1에서 **미판정 → 판정으로 전환**됐다(재작성이 생겨 "마스킹 안 됨"을 정의할 수
 * 있게 됐다). 전환은 소급 효과가 있다: 그 조건만 가진 기존 규칙은 `enforced=false`(미강제)에서 강제로 바뀐다.
 */
enum class RuleOp(@get:JsonValue val wire: String) {
    REQUIRES("requires"),
    BLOCKS("blocks"),
    JOINS("joins"),
    MUST_BE_WITHIN("must_be_within"),
    MUST_BE_MASKED("must_be_masked"),
    ;

    companion object {
        /**
         * 와이어 표현 → 상수. `@JsonValue`만으로 역직렬화가 되는 버전도 있으나 **되는 줄 알고 넘기지 않는다** —
         * 여기서 명시하면 Jackson 버전에 매달리지 않고, 실패가 `null`이 아니라 예외가 되어 손상으로 잡힌다.
         */
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): RuleOp = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("알 수 없는 규칙 연산자: $value")
    }
}

/** 트리 노드: 그룹 또는 조건. tree_json으로 직렬화. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "node")
@JsonSubTypes(
    JsonSubTypes.Type(value = RuleGroup::class, name = "group"),
    JsonSubTypes.Type(value = RuleCondition::class, name = "cond"),
)
sealed interface RuleNode

/** all=AND, any=OR (§4.1). 빈 children 금지(등록 검증). */
data class RuleGroup(
    val combinator: Combinator,
    val children: List<RuleNode> = emptyList(),
) : RuleNode {
    enum class Combinator(@get:JsonValue val wire: String) {
        ALL("all"),
        ANY("any"),
        ;

        companion object {
            @JsonCreator
            @JvmStatic
            fun fromWire(value: String): Combinator = entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("알 수 없는 결합자: $value")
        }
    }
}

/**
 * 조건. 컬럼 기반(single/multi)은 table·column·defId(+mappingId), joins는 refTable·refColumn 추가,
 * 전역은 subject·value. severity는 조건 단위 (§3.1).
 */
data class RuleCondition(
    val op: RuleOp,
    val severity: Severity,
    val table: String? = null,
    val column: String? = null,
    val defId: Long? = null,
    val mappingId: Long? = null,
    val refTable: String? = null,
    val refColumn: String? = null,
    val subject: String? = null,
    val value: String? = null,
) : RuleNode {
    /**
     * 대상 테이블·컬럼 — **공백 문자열은 미지정으로 본다**.
     *
     * `""`를 값으로 취급하면 "그 컬럼은 어디에도 없다"가 되어 조건이 조용히 중립·충족으로 떨어진다(fail-open).
     * 손상된 조건은 판정 불가로 다뤄야 한다 (spec 001 §6 fail-closed).
     */
    @get:JsonIgnore val targetTable: String? get() = table?.takeIf { it.isNotBlank() }

    @get:JsonIgnore val targetColumn: String? get() = column?.takeIf { it.isNotBlank() }

    /** 실제 판정하는 op인가 (§4.2). `must_be_within`은 등록·표시만이므로 false → 트리·severity 집계에서 제외. */
    @get:JsonIgnore val judged: Boolean get() =
        op == RuleOp.REQUIRES || op == RuleOp.BLOCKS || op == RuleOp.JOINS || op == RuleOp.MUST_BE_MASKED
}
