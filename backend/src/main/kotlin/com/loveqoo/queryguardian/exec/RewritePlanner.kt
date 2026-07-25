package com.loveqoo.queryguardian.exec

import com.loveqoo.queryguardian.catalog.Expressions
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.MaskProjection
import com.loveqoo.queryguardian.ir.PredicateInjection
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.ir.SelectItem
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.TableRename
import com.loveqoo.queryguardian.ir.TableRef

/** 계획 수립 결과. 거부는 예외가 아니라 값이다 — 게이트가 사유를 그대로 사용자에게 전달한다. */
sealed interface PlanOutcome {
    data class Planned(val plan: RewritePlan) : PlanOutcome
    data class Refused(val refusal: RewriteRefusal, val message: String) : PlanOutcome
}

/**
 * IR + 카탈로그 → **방언 중립 재작성 계획** (spec 008 §3.5 M1-3).
 *
 * 설계 제약 세 가지:
 * 1. **논리명으로만** 카탈로그를 조회한다 — 물리명으로 조회하면 0건이라 마스킹·필터가 조용히 사라진다(§3 원칙).
 *    물리명 치환은 계획의 [RewritePlan.tableMap]에만 담고 재작성의 마지막 단계에서 적용된다.
 * 2. **권한을 모른다** — 재작성이 권한에 따라 달라지면 "권한 없는 사용자가 마스킹을 덜 받는" 역전이 생긴다
 *    (ArchUnit `execKnowsNothingAboutAuth`로 고정).
 * 3. **Druid를 모른다** — AST 조작은 `parser/SqlRewriter`의 일이다.
 */
class RewritePlanner(
    private val catalog: RewriteCatalog,
    private val maxRows: Long,
) {

    fun plan(ir: QueryIR, purposeCode: String?, tableMap: Map<String, String>): PlanOutcome {
        val masks = mutableListOf<MaskProjection>()
        val injections = mutableListOf<PredicateInjection>()
        val renames = mutableListOf<TableRename>()

        for (scope in allScopes(ir.root)) {
            for (instance in scope.tables.filter { it.physical }) {
                planMasks(scope, instance, masks)?.let { return it }
                planInjections(scope, instance, purposeCode, injections)?.let { return it }
                // 물리명 치환은 **물리 테이블 인스턴스에만** 계획한다 — CTE·파생 alias가 논리명과 겹쳐도
                // 그것들은 physical=false라 여기 오지 않는다(전역 치환이면 그 참조까지 깨뜨린다).
                tableMap[instance.name.lowercase()]?.let { physical ->
                    renames += TableRename(scope.scopeId, instance.instanceKey, instance.name, physical)
                }
            }
        }

        return PlanOutcome.Planned(
            RewritePlan(
                maskProjections = masks,
                injections = injections,
                limitCap = LimitCap(ir.root.scopeId, effectiveCap(ir.root.limit)),
                tableRenames = renames,
            )
        )
    }

    /** 유효 상한 = `min(사용자 LIMIT ?: ∞, 설정 상한)` (§3.0-2). 사용자가 더 작게 걸었으면 그것을 존중한다. */
    private fun effectiveCap(userLimit: Long?): Long =
        if (userLimit != null && userLimit in 1..maxRows) userLimit else maxRows

    /**
     * MASK 매핑 컬럼 처리 (§3.0.1).
     *
     * **투영으로만** 표현할 수 있다. 그 컬럼이 참조된 횟수가 최상위 bare 투영 횟수보다 많으면 함수 인자·CASE·
     * WHERE·GROUP BY 같은 위치에도 쓰인 것이므로 치환으로 표현할 수 없다 → 거부(spec 001 §6.3의 직계 적용:
     * 표현할 수 없는 것을 표현한 척하지 않는다). 이때 저장 시 `must_be_masked`는 BLOCK이 된다.
     */
    private fun planMasks(
        scope: SelectScope,
        instance: TableRef,
        into: MutableList<MaskProjection>,
    ): PlanOutcome.Refused? {
        for (masked in catalog.maskExpressions(instance.name)) {
            val column = masked.column.lowercase()
            val projections = scope.selectItems.count { item ->
                item is SelectItem.Column &&
                    item.column.table == instance.instanceKey &&
                    item.column.column.lowercase() == column
            }
            val references = scope.columnRefs.count { ref ->
                ref.table?.instanceKey == instance.instanceKey && ref.column.lowercase() == column
            }
            // star 투영은 어떤 컬럼이 나갈지 IR이 알 수 없다 — no-select-star 룰이 이미 BLOCK이지만
            // 재작성 경로에서도 독립적으로 거부한다(룰이 꺼져도 평문이 나가지 않도록).
            val hasStar = scope.selectItems.any { it is SelectItem.Star }

            if (projections == 0 && references == 0 && !hasStar) continue

            if (masked.template == null) {
                return PlanOutcome.Refused(
                    RewriteRefusal.EXPRESSION_NOT_USABLE,
                    "마스킹 강제식을 쓸 수 없습니다: ${instance.name}.${masked.column} (${masked.label}) — " +
                        "강제식이 없거나 {col} 자리표시자·파라미터가 온전하지 않습니다",
                )
            }
            if (hasStar || references > projections) {
                return PlanOutcome.Refused(
                    RewriteRefusal.MASK_NOT_EXPRESSIBLE,
                    "마스킹 대상 컬럼을 투영 이외의 위치에서 사용했습니다: ${instance.name}.${masked.column} — " +
                        "함수 인자·CASE·WHERE·GROUP BY·`*` 투영은 치환으로 표현할 수 없습니다. " +
                        "해당 컬럼을 select 목록에 그대로 두고 조건에서 빼 주세요",
                )
            }
            if (projections > 0) {
                into += MaskProjection(
                    scopeId = scope.scopeId,
                    instanceKey = instance.instanceKey,
                    column = masked.column,
                    expressionTemplate = masked.template,
                    outputName = masked.column,
                )
            }
        }
        return null
    }

    /**
     * FILTER·INTEGRITY 술어 주입 (§3.0-1, §3.0.2).
     *
     * OUTER JOIN의 null 생성 쪽 인스턴스는 **거부**한다 — WHERE 주입이 LEFT JOIN을 사실상 INNER로 바꿔
     * 결과 의미가 변한다. 조용히 의미를 바꾸는 것보다 거부가 안전하다(fail-closed).
     */
    private fun planInjections(
        scope: SelectScope,
        instance: TableRef,
        purposeCode: String?,
        into: MutableList<PredicateInjection>,
    ): PlanOutcome.Refused? {
        val filters = catalog.filterExpressions(instance.name, purposeCode).map { it to "FILTER" }
        val integrity = catalog.integrityExpressions(instance.name).map { it to "INTEGRITY" }

        for ((expression, kind) in filters + integrity) {
            if (instance.instanceKey in scope.nullProducingInstances) {
                return PlanOutcome.Refused(
                    RewriteRefusal.OUTER_JOIN_FILTER,
                    "OUTER JOIN으로 null이 생성되는 쪽(${instance.instanceKey})에 필수 조건" +
                        "(${expression.label})을 주입하면 조인 의미가 INNER로 바뀝니다 — " +
                        "해당 테이블을 INNER JOIN으로 바꾸거나 조건을 직접 작성해 주세요",
                )
            }
            if (expression.template == null) {
                return PlanOutcome.Refused(
                    RewriteRefusal.EXPRESSION_NOT_USABLE,
                    "$kind 강제식을 쓸 수 없습니다: ${instance.name}.${expression.column} (${expression.label})",
                )
            }
            // 인스턴스 한정은 **계획 수립자의 책임**이다 — 같은 테이블이 여러 인스턴스로 있으면
            // 재작성기가 추측하면 셀프 조인에서 엉뚱한 쪽에 붙는다.
            val qualified = expression.template.replace(
                com.loveqoo.queryguardian.catalog.Expressions.COL,
                "${instance.instanceKey}.${expression.column}",
            )
            into += PredicateInjection(
                scopeId = scope.scopeId,
                instanceKey = instance.instanceKey,
                predicateSql = qualified,
                reason = "${expression.label} (${instance.name}.${expression.column})",
            )
        }
        return null
    }

    private fun allScopes(scope: SelectScope): List<SelectScope> =
        listOf(scope) + scope.children.flatMap { allScopes(it) }
}
