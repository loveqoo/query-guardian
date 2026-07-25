package com.loveqoo.queryguardian.rules

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** 규칙 범위 (spec 004 §3.2). */
enum class RuleScope { SINGLE, MULTI, GLOBAL }

/**
 * 조건 연산자. requires/blocks/joins/must_be_masked는 판정, must_be_within은 등록·표시만(judged=false).
 *
 * `must_be_masked`는 spec 008 M1에서 **미판정 → 판정으로 전환**됐다(재작성이 생겨 "마스킹 안 됨"을 정의할 수
 * 있게 됐다). 전환은 소급 효과가 있다: 그 조건만 가진 기존 규칙은 `enforced=false`(미강제)에서 강제로 바뀐다.
 */
enum class RuleOp { requires, blocks, joins, must_be_within, must_be_masked }

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
    enum class Combinator { all, any }
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
    /** 이번 스펙에서 실제 판정하는 op인가 (§4.2). must_be_*는 false → 트리·severity 집계에서 제외. */
    /**
     * 대상 테이블·컬럼 — **공백 문자열은 미지정으로 본다**.
     *
     * `""`를 값으로 취급하면 "그 컬럼은 어디에도 없다"가 되어 조건이 조용히 중립·충족으로 떨어진다(fail-open).
     * 손상된 조건은 판정 불가로 다뤄야 한다 (spec 001 §6 fail-closed).
     */
    val targetTable: String? get() = table?.takeIf { it.isNotBlank() }
    val targetColumn: String? get() = column?.takeIf { it.isNotBlank() }

    val judged: Boolean get() =
        op == RuleOp.requires || op == RuleOp.blocks || op == RuleOp.joins || op == RuleOp.must_be_masked
}
