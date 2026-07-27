package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.Op
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.ResolvedColumn
import com.loveqoo.queryguardian.ir.ScopeKind
import com.loveqoo.queryguardian.ir.MaskUsage
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.maskFindings
import com.loveqoo.queryguardian.ir.SelectItem

/** select-item `*`/`t.*` 금지. COUNT(*)는 Star가 아니며, EXISTS 스코프는 관용구로 면제 (§6.7). */
class NoSelectStarRule : Rule {
    override val id = "no-select-star"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> {
        if (scope.kind == ScopeKind.EXISTS) return emptyList()
        return scope.selectItems.filterIsInstance<SelectItem.Star>().map { star ->
            val target = star.qualifier?.let { "$it.*" } ?: "*"
            Violation(id, severity, "SELECT $target 는 허용되지 않습니다. 필요한 컬럼을 명시하세요.")
        }
    }
}

/** 루트 SELECT에 LIMIT 권고 + 상한 검사 (spec 002 B13). 알려진 한계: LIMIT 0은 통과한다. */
class RequireLimitRule(private val maxLimit: Long = 1000) : Rule {
    override val id = "require-limit"
    override val severity = Severity.WARN

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> {
        if (scope.kind != ScopeKind.ROOT) return emptyList()
        val limit = scope.limit
            ?: return listOf(Violation(id, severity, "LIMIT이 없습니다. 결과 크기를 제한하는 것을 권장합니다."))
        return if (limit > maxLimit) {
            listOf(Violation(id, severity, "LIMIT $limit — 권장 최대 $maxLimit 이내로 제한하세요."))
        } else emptyList()
    }
}

/**
 * BLOCK 매핑 컬럼 참조 차단 (spec 002 §5.2 신규). 스코프의 columnRefs 전체를 검사 —
 * select·WHERE·GROUP BY·HAVING·ORDER BY·JOIN ON·함수 인자 포함. 귀속 불가 참조는 fail-closed.
 * `SELECT *` 경유 노출은 no-select-star(BLOCK)가 담당(§3.2 의존 — 그 룰의 severity 강등 금지).
 */
class NoBlockedColumnRule : Rule {
    override val id = "no-blocked-column"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        scope.columnRefs.mapNotNull { ref ->
            val table = ref.table
            if (table != null) {
                if (table.physical && catalog.blockedColumns(table.name).contains(ref.column.lowercase())) {
                    Violation(id, severity, "컬럼 ${table.name}.${ref.column}은(는) 조회가 차단된 컬럼입니다.")
                } else null
            } else {
                // 귀속 불가: 스코프 내 어떤 물리 테이블이라도 동명 BLOCK 컬럼이 있으면 차단 (fail-closed, §6.4)
                val candidate = scope.tables.firstOrNull {
                    it.physical && catalog.blockedColumns(it.name).contains(ref.column.lowercase())
                }
                candidate?.let {
                    Violation(id, severity, "컬럼 ${ref.column}은(는) ${it.name}의 차단 컬럼일 수 있어 조회할 수 없습니다(테이블 귀속 불명).")
                }
            }
        }
}

/**
 * MASK 매핑 컬럼의 사용 위치 판정 (spec 008 §3.1 — spec 004에서 미판정으로 남긴 must_be_masked).
 *
 * 투영으로만 쓰였으면 실행 시 자동 마스킹되므로 **WARN**(안내), 투영 아닌 위치에 쓰였으면 재작성으로
 * 표현할 수 없으므로 **BLOCK**이다. 저장 시점에 BLOCK으로 막는 이유: 실행 단계에서 거부될 쿼리를
 * 승인까지 통과시키면 사용자는 승인 후에야 거부를 알게 된다(§2.8-1과 같은 근거).
 *
 * 표현 가능성 판단은 `maskUsageOf` **한 함수**를 재작성 계획 수립기와 공유한다 — 기준이 갈라지면
 * "저장은 통과했는데 실행은 마스킹 없이 통과"가 생길 수 있다.
 */
class MustBeMaskedRule : Rule {
    override val id = "must-be-masked"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        maskFindings(
            scope,
            { table -> catalog.maskedColumns(table) },
            { table, instanceKey, column -> catalog.maskForms(table, instanceKey, column) },
        ).mapNotNull { finding ->
            when (finding.usage) {
                MaskUsage.ABSENT -> null
                // 사용자가 등록된 형태 그대로 가려서 썼다 — 통과이고 재작성 대상도 아니다 (spec 012 P0)
                MaskUsage.ALREADY_MASKED -> null
                MaskUsage.PROJECTION_ONLY -> Violation(
                    id, Severity.WARN,
                    "컬럼 ${finding.logicalTable}.${finding.column}은(는) 실행 시 자동으로 마스킹됩니다.",
                )
                MaskUsage.NOT_EXPRESSIBLE -> Violation(
                    id, Severity.BLOCK,
                    "컬럼 ${finding.logicalTable}.${finding.column}은(는) 마스킹 대상인데 치환으로 표현할 수 없는 " +
                        "형태입니다(투영 아닌 위치·`*`·DISTINCT·그룹/정렬 기준·테이블 귀속 불명) — " +
                        "컬럼을 한정해 select 목록에 그대로 두고 조건에서 빼 주세요.",
                )
            }
        }
}

/**
 * 파티션 키 등록 테이블은 §6.1 위치 + 리터럴 경계로만 충족.
 *
 * **모양이 아니라 사실을 묻는다** (spec 011 §5.1). 예전에는 조건 **하나**가 `=`·`IN`·`BETWEEN`
 * 모양인지 봤고, 그래서 `>= a AND < b`가 막혔다 — `BETWEEN a AND b`와 같은 뜻이고 오히려 더 좁은데도.
 * 통과하는 `BETWEEN`이 1년이고 막히는 부등호 쌍이 1개월이면 **안전 방향이 거꾸로**다.
 *
 * 이제 최상위 conjunct 전체에서 컬럼별 **사실**을 모아 "고정되었는가"를 묻는다:
 * 등호 · 열거 · (하한 ∧ 상한) 중 하나면 충족. 하한만 있으면(`>= a`) 끝이 없으므로 **미충족**(현행 유지).
 */
class RequirePartitionKeyRule : Rule {
    override val id = "require-partition-key"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        scope.tables.flatMap { table ->
            if (!table.physical) return@flatMap emptyList()
            // 복합 파티션: 각 키는 독립 요건 — 전부 충족해야 한다 (spec 002 C4)
            catalog.partitionKeys(table.name).mapNotNull { key ->
                // 인스턴스 키로 판정 — 셀프 조인에서 alias 하나의 조건이 다른 인스턴스를 면제하지 못한다 (§6.4)
                if (isPinned(scope.whereConjuncts, table.instanceKey, key)) null
                else Violation(id, severity, "테이블 ${table.name}(${table.instanceKey})은(는) 파티션 키 `$key`를 " +
                    "고정하는 조건이 WHERE에 필요합니다 (= / IN / BETWEEN / 상한·하한 쌍, 함수 래핑 불가).")
            }
        }

    /**
     * 컬럼이 **고정**되었는가 — 최상위 AND conjunct들이 함께 만드는 사실로 판단한다.
     *
     * 조각을 합치는 것은 **하나의 AND 묶음 안에서만** 한다(spec 011 I2) — 서로 다른 `OR` 분기의
     * 조각을 합치면 `OR 1=1`류 무력화가 되살아난다.
     *
     * **`OR`는 모든 분기가 각각 고정할 때만 충족**이다(spec 011 I3, 결정 §5.2). 한 분기라도 고정하지
     * 않으면 그 분기로 들어오는 행은 제약이 없다. 분기마다 **값이 달라도 된다** —
     * `d='X' OR d='Y'`는 `IN ('X','Y')`와 같고, 파티션이 묻는 것은 "얼마나 읽는가"이기 때문이다.
     * (필수 술어는 다르다 — [satisfiesRequiredForm] 참조.)
     */
    private fun isPinned(conjuncts: List<Predicate>, table: String, key: String): Boolean {
        var hasLower = false
        var hasUpper = false
        for (p in conjuncts) {
            when {
                p is Predicate.Comparison && p.value != null && columnMatches(p.column, table, key) ->
                    when (p.op) {
                        Op.EQ -> return true
                        Op.GT, Op.GTE -> hasLower = true
                        Op.LT, Op.LTE -> hasUpper = true
                        // NEQ·LIKE는 범위를 좁히지 않는다 — 스캔은 그대로다
                        Op.NEQ, Op.LIKE -> Unit
                    }
                p is Predicate.InList && p.values != null && columnMatches(p.column, table, key) -> return true
                p is Predicate.Between && p.low != null && p.high != null && columnMatches(p.column, table, key) ->
                    return true
                p is Predicate.Or && p.branches.isNotEmpty() &&
                    p.branches.all { isPinned(conjunctsOf(it), table, key) } -> return true
            }
        }
        // 한쪽만 있으면 끝이 없는 범위다 — 사실상 전체 스캔이므로 막는 것이 옳다
        return hasLower && hasUpper
    }
}

/** 필수 술어 룰: §6.1 위치 + §6.5 닫힌 동치 목록(= 순서 무시, 케이스 정규화, IN 단일값 ≡ =)으로만 충족. */
class RequirePredicateRule : Rule {
    override val id = "require-predicate"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        scope.tables.filter { it.physical }.flatMap { table ->
            catalog.requiredPredicates(table.name, context.purposeCode).mapNotNull { required ->
                val normalized = requiredForm(required.predicate)
                if (normalized == null) {
                    // 카탈로그 등록 술어가 지원 형태(= 리터럴 / IN 단일 리터럴)가 아니면 검증 불가 → fail-closed
                    Violation(id, severity, "테이블 ${table.name}의 필수 술어(${required.label})를 검증할 수 없습니다. 카탈로그 등록 형태를 확인하세요.")
                } else {
                    val satisfied = scope.whereConjuncts.any { satisfiesRequiredForm(it, table.instanceKey, normalized) }
                    if (satisfied) null
                    else Violation(id, severity, "테이블 ${table.name}(${table.instanceKey})은(는) 필수 조건 `${required.label}` 이 WHERE에 필요합니다.")
                }
            }
        }
}

/** 카탈로그 미등록 물리 테이블 경고 — 자동완성·의미 룰이 적용되지 않음을 사용자에게 알린다. CTE/파생 alias는 제외. */
class UnknownTableRule : Rule {
    override val id = "unknown-table"
    override val severity = Severity.WARN

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        scope.tables
            .filter { it.physical && !catalog.exists(it.name) }
            .map { Violation(id, severity, "테이블 ${it.name}은(는) 카탈로그에 등록되어 있지 않습니다. 자동완성과 의미 룰(파티션 키·필수 조건)이 적용되지 않습니다.") }
}

/** 필수 술어로 등록 가능한 정규형: 컬럼 EQ 리터럴값 (§6.5). 카탈로그 등록 검증에서도 사용한다. */
data class RequiredForm(val column: String, val value: String)

fun requiredForm(p: Predicate): RequiredForm? = when {
    p is Predicate.Comparison && p.op == Op.EQ && p.value != null -> RequiredForm(p.column.column.lowercase(), p.value)
    p is Predicate.InList && p.values?.size == 1 -> RequiredForm(p.column.column.lowercase(), p.values.single())
    else -> null
}

/** 컬럼이 해당 테이블에 양의 귀속되고 이름이 일치하는가. 귀속 불가(table=null)는 항상 false (§6.4). */
internal fun columnMatches(column: ResolvedColumn, table: String, name: String): Boolean =
    column.table != null &&
        column.table.equals(table, ignoreCase = true) &&
        column.column.equals(name, ignoreCase = true)

/** 최상위 AND conjunct가 요구 술어(EQ 리터럴/IN 단일)를 충족하는가 (§6.1·§6.5). 사용자 규칙 requires도 재사용. */
/**
 * 하나의 AND 묶음을 conjunct 목록으로 편다. `OR` 분기 안이 `AND`면 그 안의 조각들이 함께 성립한다.
 * 서로 다른 `OR` 분기를 가로질러 합치지는 않는다(spec 011 I2).
 */
internal fun conjunctsOf(p: Predicate): List<Predicate> =
    if (p is Predicate.And) p.conjuncts.flatMap { conjunctsOf(it) } else listOf(p)

/**
 * 필수 술어 충족 판정 — **시스템 룰과 사용자 규칙이 공유한다**
 * (`RequirePredicateRule`, `UserRuleEvaluator.satisfiesRequires`). 기준이 갈라지면 같은 쿼리가
 * 두 계층에서 다르게 판정된다.
 *
 * **`OR`는 모든 분기가 각각 충족할 때만**(spec 011 I3, 결정 §5.2). 파티션과 갈리는 지점이 여기다:
 * 파티션은 "어떤 값으로든 고정"이면 되지만 필수 술어는 **바로 그 값**이어야 한다.
 * `동의='Y' OR 동의='N'`은 파티션이라면 `IN`과 같아 충족이지만, 여기서는 'N' 분기의 행이
 * **동의하지 않은 사람**이므로 충족이 아니다.
 */
internal fun satisfiesRequiredForm(conjunct: Predicate, tableInstanceKey: String, required: RequiredForm): Boolean =
    when (conjunct) {
        is Predicate.Comparison ->
            conjunct.op == Op.EQ && conjunct.value == required.value &&
                columnMatches(conjunct.column, tableInstanceKey, required.column)
        is Predicate.InList ->
            conjunct.values?.singleOrNull() == required.value &&
                columnMatches(conjunct.column, tableInstanceKey, required.column)
        // AND 묶음: 조각 하나라도 술어면 그 묶음은 술어를 함의한다
        is Predicate.And ->
            conjunctsOf(conjunct).any { satisfiesRequiredForm(it, tableInstanceKey, required) }
        is Predicate.Or ->
            conjunct.branches.isNotEmpty() &&
                conjunct.branches.all { satisfiesRequiredForm(it, tableInstanceKey, required) }
        else -> false // Not/Raw는 충족 불가 (§6.1, §6.3)
    }
