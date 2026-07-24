package com.loveqoo.queryguardian.rules

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** 규칙 범위 (spec 004 §3.2). */
enum class RuleScope { SINGLE, MULTI, GLOBAL }

/** 조건 연산자. requires/blocks/joins만 판정, 나머지는 등록·표시만(judged=false) (§4.2). */
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
    val judged: Boolean get() = op == RuleOp.requires || op == RuleOp.blocks || op == RuleOp.joins
}
