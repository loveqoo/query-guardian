package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.ColumnEquality
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.SelectScope

/** 평가 대상 사용자 규칙 (엔티티에서 분리 — 엔진은 이 형태만 본다). */
data class UserRule(
    val id: Long,
    val name: String,
    val scope: RuleScope,
    val enabled: Boolean,
    val tree: RuleGroup,
)

/**
 * 사용자 정의 규칙 판정 (spec 004 §4). 조건 op requires/blocks/joins만 판정, must_be_*는 중립(judged=false).
 * 판정 조건이 재귀적으로 0개면 규칙은 "미강제"(위반 없음). fail-closed: 확인 불가는 미충족.
 */
class UserRuleEvaluator(private val rules: () -> List<UserRule>) {

    /** 트리 평가 결과. Neutral=판정 대상 없음, Satisfied=충족, Violated=위반(조건별 위반 목록). */
    private sealed interface Result {
        data object Neutral : Result
        data object Satisfied : Result
        data class Violated(val violations: List<Violation>, val maxSeverity: Severity) : Result
    }

    fun evaluate(ir: QueryIR, catalog: TableCatalog): List<Violation> {
        val active = rules().filter { it.enabled && it.scope != RuleScope.GLOBAL } // 전역은 이번 스펙 표시 전용 (§3.2)
        if (active.isEmpty()) return emptyList()
        val out = mutableListOf<Violation>()
        fun walk(scope: SelectScope) {
            for (rule in active) {
                if (!applies(rule, scope)) continue
                when (val r = evalNode(rule.tree, scope, catalog, rule)) {
                    is Result.Violated -> out += r.violations
                    else -> {} // Neutral(미강제)·Satisfied는 위반 없음
                }
            }
            scope.children.forEach(::walk)
        }
        walk(ir.root)
        return out
    }

    /** 규칙 적용 여부: 스코프가 규칙의 대상 테이블(조건 table) 중 하나라도 물리 참조하면 적용 (§3.2). */
    private fun applies(rule: UserRule, scope: SelectScope): Boolean {
        val targets = targetTables(rule.tree)
        if (targets.isEmpty()) return false
        return scope.tables.any { it.physical && targets.contains(it.name.lowercase()) }
    }

    private fun targetTables(node: RuleNode): Set<String> = when (node) {
        is RuleGroup -> node.children.flatMap { targetTables(it) }.toSet()
        is RuleCondition -> node.table?.lowercase()?.let { setOf(it) } ?: emptySet()
    }

    private fun evalNode(node: RuleNode, scope: SelectScope, catalog: TableCatalog, rule: UserRule): Result =
        when (node) {
            is RuleGroup -> evalGroup(node, scope, catalog, rule)
            is RuleCondition -> evalCondition(node, scope, catalog, rule)
        }

    private fun evalGroup(group: RuleGroup, scope: SelectScope, catalog: TableCatalog, rule: UserRule): Result {
        val results = group.children.map { evalNode(it, scope, catalog, rule) }.filter { it != Result.Neutral }
        if (results.isEmpty()) return Result.Neutral // 판정 대상 0개 → 미강제 (C3)
        return when (group.combinator) {
            RuleGroup.Combinator.all -> {
                val violated = results.filterIsInstance<Result.Violated>()
                if (violated.isEmpty()) Result.Satisfied
                else Result.Violated(violated.flatMap { it.violations }, worstSeverity(violated.map { it.maxSeverity }))
            }
            RuleGroup.Combinator.any -> {
                if (results.any { it == Result.Satisfied }) Result.Satisfied
                else { // 전부 미충족 → 그룹 대표 severity로 단일 위반 (§4.1)
                    val violated = results.filterIsInstance<Result.Violated>()
                    val maxSev = worstSeverity(violated.map { it.maxSeverity })
                    Result.Violated(
                        listOf(Violation("rule/${rule.id}", maxSev, "규칙 '${rule.name}': OR 그룹 조건을 하나도 충족하지 못했습니다.")),
                        maxSev,
                    )
                }
            }
        }
    }

    private fun evalCondition(cond: RuleCondition, scope: SelectScope, catalog: TableCatalog, rule: UserRule): Result {
        if (!cond.judged) return Result.Neutral // must_be_* — 트리·severity에서 제외 (C3)
        val satisfied = when (cond.op) {
            RuleOp.requires -> satisfiesRequires(cond, scope, catalog)
            RuleOp.blocks -> !isColumnReferenced(cond, scope) // blocks: 참조되면 미충족(위반)
            RuleOp.joins -> satisfiesJoins(cond, scope)
            else -> return Result.Neutral
        }
        return if (satisfied) Result.Satisfied
        else Result.Violated(
            listOf(Violation("rule/${rule.id}", cond.severity, conditionMessage(rule, cond))),
            cond.severity,
        )
    }

    // ---- op별 판정 (fail-closed) ----

    private fun satisfiesRequires(cond: RuleCondition, scope: SelectScope, catalog: TableCatalog): Boolean {
        val table = cond.table ?: return false
        val defId = cond.defId ?: return false
        val required = catalog.resolveConditionPredicate(defId, cond.mappingId, cond.column ?: return false)
            ?: return false // dangling·판정 불가 → fail-closed 미충족 (C4)
        // 대상 테이블 인스턴스에 귀속된 최상위 AND conjunct가 술어를 충족해야 함
        return scope.tables.filter { it.physical && it.name.equals(table, ignoreCase = true) }.any { t ->
            scope.whereConjuncts.any { satisfiesRequiredForm(it, t.instanceKey, required) }
        }
    }

    private fun isColumnReferenced(cond: RuleCondition, scope: SelectScope): Boolean {
        val table = cond.table ?: return true // 불명 → fail-closed(참조된 것으로 간주 → blocks 위반)
        val column = cond.column ?: return true
        return scope.columnRefs.any { ref ->
            ref.column.equals(column, ignoreCase = true) &&
                (ref.table?.name?.equals(table, ignoreCase = true) == true ||
                    // 귀속 불가 참조가 대상 테이블이 스코프에 있을 때 동명이면 fail-closed 참조로 간주 (§6.4)
                    (ref.table == null && scope.tables.any { it.physical && it.name.equals(table, ignoreCase = true) }))
        }
    }

    /** joins: joinEqualities에 {table.column ↔ refTable.refColumn}이 양변 물리 귀속되어 존재해야 충족 (§4.2, 방향 무관). */
    private fun satisfiesJoins(cond: RuleCondition, scope: SelectScope): Boolean {
        val table = cond.table ?: return false
        val column = cond.column ?: return false
        val refTable = cond.refTable ?: return false
        val refColumn = cond.refColumn ?: return false
        // 대상 테이블·참조 테이블이 모두 스코프에 물리 존재해야 조인 가능 (M3)
        val present = { name: String -> scope.tables.any { it.physical && it.name.equals(name, ignoreCase = true) } }
        if (!present(table) || !present(refTable)) return false
        return scope.joinEqualities.any { eq ->
            endpointMatch(eq, table, column, refTable, refColumn) ||
                endpointMatch(eq, refTable, refColumn, table, column) // 방향 무관
        }
    }

    /** 등식 양변이 {aTable.aCol}과 {bTable.bCol}에 각각 물리 귀속되는가. 한쪽이라도 비귀속이면 false. */
    private fun endpointMatch(
        eq: ColumnEquality,
        aTable: String, aCol: String, bTable: String, bCol: String,
    ): Boolean {
        val l = eq.left; val r = eq.right
        return l.table?.physical == true && r.table?.physical == true &&
            l.table.name.equals(aTable, ignoreCase = true) && l.column.equals(aCol, ignoreCase = true) &&
            r.table.name.equals(bTable, ignoreCase = true) && r.column.equals(bCol, ignoreCase = true)
    }

    private fun conditionMessage(rule: UserRule, cond: RuleCondition): String = when (cond.op) {
        RuleOp.requires -> "규칙 '${rule.name}': ${cond.table}.${cond.column} 필수 조건이 WHERE에 없습니다."
        RuleOp.blocks -> "규칙 '${rule.name}': ${cond.table}.${cond.column}은(는) 조회가 차단된 컬럼입니다."
        RuleOp.joins -> "규칙 '${rule.name}': ${cond.table}.${cond.column} ↔ ${cond.refTable}.${cond.refColumn} 필수 조인이 없습니다."
        else -> "규칙 '${rule.name}' 위반."
    }
}
