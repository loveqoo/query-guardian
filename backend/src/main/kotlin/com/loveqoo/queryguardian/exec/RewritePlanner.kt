package com.loveqoo.queryguardian.exec

import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.MaskUsage
import com.loveqoo.queryguardian.ir.forcedExpressionForms
import com.loveqoo.queryguardian.ir.maskFindings
import com.loveqoo.queryguardian.ir.MaskProjection
import com.loveqoo.queryguardian.ir.PredicateInjection
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.TableRename
import com.loveqoo.queryguardian.ir.TableRef

/** 계획 수립 결과. 거부는 예외가 아니라 값이다 — 게이트가 사유를 그대로 사용자에게 전달한다. */
sealed interface PlanOutcome {
    data class Planned(val plan: RewritePlan) : PlanOutcome
    data class Refused(val refusal: RewriteRefusal, val message: String) : PlanOutcome {
        val auditCode: AuditCode get() = refusal.auditCode
    }
}

/**
 * 재작성 거부 사유 → 감사 코드. **정의는 여기 한 벌이다.**
 *
 * 예전에는 게이트가 `"REWRITE_" + refusal.name`으로 조립했다. 문자열 조립은 [RewriteRefusal]에 값을
 * 추가한 사람이 **감사 어휘를 확장했다는 사실을 모른 채** 지나가게 한다. `when`을 망라적으로 두면
 * 컴파일러가 그 자리를 막는다.
 *
 * `RewriteRefusal` 자신이 필드로 들지 못하는 이유: 그 enum은 `ir` 패키지에 있고 ArchUnit
 * `irIsTheSharedVocabulary`가 `ir → audit` 의존을 금지한다. `exec`는 그 제약을 받지 않으므로
 * (`ExecutionFailure.Kind`가 이미 `audit`을 쓴다) 짝을 여기 둔다.
 */
val RewriteRefusal.auditCode: AuditCode get() = when (this) {
    RewriteRefusal.MASK_NOT_EXPRESSIBLE -> AuditCode.REWRITE_MASK_NOT_EXPRESSIBLE
    RewriteRefusal.OUTER_JOIN_FILTER -> AuditCode.REWRITE_OUTER_JOIN_FILTER
    RewriteRefusal.EXPRESSION_NOT_USABLE -> AuditCode.REWRITE_EXPRESSION_NOT_USABLE
    RewriteRefusal.SCOPE_NOT_FOUND -> AuditCode.REWRITE_SCOPE_NOT_FOUND
    RewriteRefusal.VERIFY_FAILED -> AuditCode.REWRITE_VERIFY_FAILED
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
            // 마스킹은 **참조된 인스턴스** 축으로 순회한다 (귀속 불가·상관 참조 포함) — scope.tables 축은 샌다.
            // spec 012 P2b: **마스킹을 계획하지 않는다.** 서버가 사용자의 SQL을 고쳐서 가려 주던 것이
            // 이 스펙의 전제였는데 그 전제가 틀렸다(spec 008 §0). 이제 판정이 맨몸 투영을 차단하므로
            // 여기까지 오는 쿼리는 **이미 사용자가 가려서 쓴 것**이거나 마스킹 대상이 없는 것이다.
            // planMasks(scope, masks)?.let { return it }
            for (instance in scope.tables.filter { it.physical }) {
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
                limitCap = LimitCap(ir.root.scopeId, effectiveCap(ir.root.limit), maxRows),
                tableRenames = renames,
            )
        )
    }

    /**
     * 유효 상한 = `min(사용자 LIMIT ?: ∞, 설정 상한)` (§3.0-2). 사용자가 더 작게 걸었으면 그것을 존중한다.
     * **`LIMIT 0`도 존중한다** — 0을 "미지정"으로 취급하면 0행을 요청한 쿼리가 상한만큼 반환된다(적대 검토 HIGH).
     */
    private fun effectiveCap(userLimit: Long?): Long =
        if (userLimit != null && userLimit in 0..maxRows) userLimit else maxRows

    /**
     * MASK 매핑 컬럼 처리 (§3.0.1).
     *
     * **투영으로만** 표현할 수 있다. 그 컬럼이 참조된 횟수가 최상위 bare 투영 횟수보다 많으면 함수 인자·CASE·
     * WHERE·GROUP BY 같은 위치에도 쓰인 것이므로 치환으로 표현할 수 없다 → 거부(spec 001 §6.3의 직계 적용:
     * 표현할 수 없는 것을 표현한 척하지 않는다). 이때 저장 시 `must_be_masked`는 BLOCK이 된다.
     */
    private fun planMasks(scope: SelectScope, into: MutableList<MaskProjection>): PlanOutcome.Refused? {
        // 판정(rules의 must-be-masked)과 **같은 순회 축·같은 기준**을 쓴다 — 갈라지면
        // "저장은 통과, 실행은 마스킹 없이"가 생긴다.
        val findings = maskFindings(
            scope,
            catalog::maskedColumns,
            // 사용자가 등록된 형태로 이미 가렸으면 계획 대상이 아니다 — 또 감싸면 이중 마스킹이다 (spec 012 P0)
            { table, instanceKey, column ->
                catalog.maskExpressions(table)
                    .filter { it.column.equals(column, ignoreCase = true) }
                    .flatMap { forcedExpressionForms(it.template ?: return@flatMap emptyList(), instanceKey, it.column) }
                    .toSet()
            },
        )
        for (finding in findings) {
            if (finding.usage == MaskUsage.ALREADY_MASKED) continue // 사용자가 이미 가렸다
            if (finding.usage == MaskUsage.NOT_EXPRESSIBLE) {
                return PlanOutcome.Refused(
                    RewriteRefusal.MASK_NOT_EXPRESSIBLE,
                    "마스킹 대상 컬럼을 치환할 수 없는 형태로 사용했습니다: " +
                        "${finding.logicalTable}.${finding.column} — 투영 아닌 위치·`*`·DISTINCT·" +
                        "그룹/정렬 기준·테이블 귀속 불명은 표현할 수 없습니다",
                )
            }
            val expression = catalog.maskExpressions(finding.logicalTable)
                .firstOrNull { it.column.equals(finding.column, ignoreCase = true) }
            val template = expression?.template
                ?: return PlanOutcome.Refused(
                    RewriteRefusal.EXPRESSION_NOT_USABLE,
                    "마스킹 강제식을 쓸 수 없습니다: ${finding.logicalTable}.${finding.column} " +
                        "(${expression?.label ?: "정의 없음"}) — 강제식이 없거나 {col}·파라미터가 온전하지 않습니다",
                )
            into += MaskProjection(
                scopeId = scope.scopeId,
                instanceKey = finding.instanceKey,
                column = finding.column,
                expressionTemplate = template,
                outputName = finding.column,
            )
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

        // 주입이 의미를 바꾸는 스코프(부정 문맥·null 생성 경로)에는 넣지 않는다 — 넣으면 필터가 **반전**되거나
        // 조인이 INNER로 바뀐다. 자동 보정만 포기하는 것이고, 필수 술어 자체는 판정 층이 모든 스코프에서
        // 계속 요구한다(분석가가 직접 써야 통과).
        if (!scope.injectable) return null

        for ((expression, kind) in filters + integrity) {
            // **주입 생략 단축 경로를 두지 않는다.** 한때 "그 컬럼에 이미 최상위 조건이 있으면 생략"을 넣어
            // OUTER JOIN 오차단을 완화했는데, 적대 검토가 그것이 fail-open임을 지적했다:
            // `WHERE mc.consent_yn <> 'Y'`도 "이미 제약됨"으로 읽혀 필수 조건이 **아예 주입되지 않는다**.
            // 안전성이 "판정 층이 형태를 검사해 막아줄 것"이라는 가정에 얹히는데, 판정은 다른 카탈로그 축을
            // 읽으므로 두 축의 일치가 보장되지 않는다. 중복 주입(`AND (x='Y') AND (x='Y')`)은 무해하므로
            // 항상 주입하는 쪽이 안전하다. OUTER JOIN 오차단은 알려진 한계로 남긴다(INNER JOIN으로 우회 가능).
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
