package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.ColumnEquality
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.MaskUsage
import com.loveqoo.queryguardian.ir.forcedExpressionForm
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.maskUsageOf

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
            RuleGroup.Combinator.ALL -> {
                val violated = results.filterIsInstance<Result.Violated>()
                if (violated.isEmpty()) Result.Satisfied
                else Result.Violated(violated.flatMap { it.violations }, worstSeverity(violated.map { it.maxSeverity }))
            }
            RuleGroup.Combinator.ANY -> {
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
        if (!cond.judged) return Result.Neutral // must_be_within — 트리·severity에서 제외 (C3)
        val satisfied = when (cond.op) {
            RuleOp.REQUIRES -> satisfiesRequires(cond, scope, catalog)
            RuleOp.BLOCKS -> !isColumnReferenced(cond, scope) // blocks: 참조되면 미충족(위반)
            RuleOp.JOINS -> satisfiesJoins(cond, scope)
            // 컬럼을 조회하지 않는 스코프는 이 조건과 무관하다 — 중립이어야 AND 그룹을 헛되게 깨지 않는다
            RuleOp.MUST_BE_MASKED -> when (maskUsage(cond, scope, catalog)) {
                MaskUsage.ABSENT -> return Result.Neutral
                // spec 012 P2: 맨몸 투영은 위반이다 — 서버가 대신 가려주지 않는다
                MaskUsage.PROJECTION_ONLY -> false
                // 사용자가 이미 등록된 형태로 가렸다 (spec 012 P0)
                MaskUsage.ALREADY_MASKED -> true
                MaskUsage.NOT_EXPRESSIBLE -> false
            }
            else -> return Result.Neutral
        }
        return if (satisfied) Result.Satisfied
        else Result.Violated(
            listOf(Violation("rule/${rule.id}", cond.severity, conditionMessage(rule, cond), conditionFix(cond, scope, catalog))),
            cond.severity,
        )
    }

    /**
     * **고칠 방법 한 조각** (spec 013 S3). 시스템 룰과 같은 어휘를 쓴다 — 화면이 규칙의 출처에 따라
     * 다르게 그리지 않도록.
     *
     * `blocks`·`joins`에는 조각이 없다: 차단된 컬럼은 **빼는** 것이지 무엇으로 바꾸는 것이 아니고,
     * 조인 요건은 FROM 절 구조를 바꾸는 일이라 한 조각으로 표현되지 않는다. 없는 것을 지어내지 않는다.
     */
    private fun conditionFix(cond: RuleCondition, scope: SelectScope, catalog: TableCatalog): Fix? =
        if (!scope.fixable()) null else when (cond.op) {
        RuleOp.REQUIRES -> {
            val table = cond.targetTable
            val column = cond.targetColumn
            val defId = cond.defId
            if (table == null || column == null || defId == null) {
                null
            } else {
                val resolved = catalog.resolveConditionPredicate(defId, cond.mappingId, column)
                // 판정이 본 그 인스턴스에 맞춰 한정한다 — 없으면(테이블이 이 스코프에 없으면) 한정 없이.
                val instanceKey = scope.tables
                    .firstOrNull { it.physical && it.name.equals(table, ignoreCase = true) }?.instanceKey
                resolved?.let { forcedExpressionForm(it.template, instanceKey, column) }
                    ?.let { Fix.AddPredicate(table, column, it) }
            }
        }
        RuleOp.MUST_BE_MASKED -> {
            val table = cond.targetTable
            val column = cond.targetColumn
            if (table == null || column == null) null
            else {
                val instanceKey = scope.tables
                    .firstOrNull { it.physical && it.name.equals(table, ignoreCase = true) }?.instanceKey ?: table
                catalog.maskForms(table, instanceKey, column).minByOrNull { it.length }
                    ?.let { Fix.ReplaceProjection(table, column, from = column, to = it) }
            }
        }
        else -> null
    }

    // ---- op별 판정 (fail-closed) ----

    private fun satisfiesRequires(cond: RuleCondition, scope: SelectScope, catalog: TableCatalog): Boolean {
        val table = cond.targetTable ?: return false
        val defId = cond.defId ?: return false
        val required = catalog.resolveConditionPredicate(defId, cond.mappingId, cond.targetColumn ?: return false)
            ?: return false // dangling·판정 불가 → fail-closed 미충족 (C4)
        // 대상 테이블 인스턴스에 귀속된 최상위 AND conjunct가 술어를 충족해야 함
        return scope.tables.filter { it.physical && it.name.equals(table, ignoreCase = true) }.any { t ->
            scope.whereConjuncts.any { satisfiesRequiredForm(it, t.instanceKey, required.form) }
        }
    }

    /**
     * must_be_masked 판정 (spec 008 §3.1). 표현 가능성 판단은 재작성 계획 수립기와 **같은 함수**를 쓴다 —
     * 기준이 갈라지면 "저장은 통과, 실행은 마스킹 없이 통과"가 생긴다.
     * 대상 테이블·컬럼이 불명이면 fail-closed로 표현 불가 취급(=위반).
     */
    private fun maskUsage(cond: RuleCondition, scope: SelectScope, catalog: TableCatalog): MaskUsage {
        val table = cond.targetTable ?: return MaskUsage.NOT_EXPRESSIBLE
        val column = cond.targetColumn ?: return MaskUsage.NOT_EXPRESSIBLE
        val instances = scope.tables.filter { it.physical && it.name.equals(table, ignoreCase = true) }
        if (instances.isEmpty()) return MaskUsage.ABSENT
        // 사용자가 직접 가려 쓴 형태도 인정한다 — 시스템 룰과 **같은 근거**(등록된 강제식)를 본다.
        // 여기만 빠뜨리면 같은 쿼리가 시스템 룰은 통과하고 사용자 규칙은 위반이 된다 (spec 012 P0).
        val usages = instances.map {
            maskUsageOf(scope, it.instanceKey, column, catalog.maskForms(table, it.instanceKey, column))
        }
        return when {
            // 한 인스턴스라도 표현 불가면 위반 — 셀프 조인에서 한쪽만 안전한 것은 안전이 아니다
            usages.any { it == MaskUsage.NOT_EXPRESSIBLE } -> MaskUsage.NOT_EXPRESSIBLE
            usages.any { it == MaskUsage.PROJECTION_ONLY } -> MaskUsage.PROJECTION_ONLY
            usages.any { it == MaskUsage.ALREADY_MASKED } -> MaskUsage.ALREADY_MASKED
            else -> MaskUsage.ABSENT
        }
    }

    private fun isColumnReferenced(cond: RuleCondition, scope: SelectScope): Boolean {
        val table = cond.targetTable ?: return true // 불명 → fail-closed(참조된 것으로 간주 → blocks 위반)
        val column = cond.targetColumn ?: return true
        return scope.columnRefs.any { ref ->
            ref.column.equals(column, ignoreCase = true) &&
                (ref.table?.name?.equals(table, ignoreCase = true) == true ||
                    // 귀속 불가 참조가 대상 테이블이 스코프에 있을 때 동명이면 fail-closed 참조로 간주 (§6.4)
                    (ref.table == null && scope.tables.any { it.physical && it.name.equals(table, ignoreCase = true) }))
        }
    }

    /** joins: joinEqualities에 {table.column ↔ refTable.refColumn}이 양변 물리 귀속되어 존재해야 충족 (§4.2, 방향 무관). */
    private fun satisfiesJoins(cond: RuleCondition, scope: SelectScope): Boolean {
        val table = cond.targetTable ?: return false
        val column = cond.targetColumn ?: return false
        val refTable = cond.refTable?.takeIf { it.isNotBlank() } ?: return false
        val refColumn = cond.refColumn?.takeIf { it.isNotBlank() } ?: return false
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
        RuleOp.REQUIRES -> "규칙 '${rule.name}': ${cond.table}.${cond.column} 필수 조건이 WHERE에 없습니다."
        RuleOp.BLOCKS -> "규칙 '${rule.name}': ${cond.table}.${cond.column}은(는) 조회가 차단된 컬럼입니다."
        RuleOp.JOINS -> "규칙 '${rule.name}': ${cond.table}.${cond.column} ↔ ${cond.refTable}.${cond.refColumn} 필수 조인이 없습니다."
        else -> "규칙 '${rule.name}' 위반."
    }
}
