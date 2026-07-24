package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.Op
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.ResolvedColumn
import com.loveqoo.queryguardian.ir.ScopeKind
import com.loveqoo.queryguardian.ir.SelectScope
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

/** 파티션 키 등록 테이블은 §6.1 위치 + §6.6 형태(베어 컬럼 =/IN/BETWEEN, 전부 리터럴)로만 충족. */
class RequirePartitionKeyRule : Rule {
    override val id = "require-partition-key"
    override val severity = Severity.BLOCK

    override fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation> =
        scope.tables.flatMap { table ->
            if (!table.physical) return@flatMap emptyList()
            // 복합 파티션: 각 키는 독립 요건 — 전부 충족해야 한다 (spec 002 C4)
            catalog.partitionKeys(table.name).mapNotNull { key ->
                // 인스턴스 키로 판정 — 셀프 조인에서 alias 하나의 조건이 다른 인스턴스를 면제하지 못한다 (§6.4)
                val satisfied = scope.whereConjuncts.any { satisfiesPartitionKey(it, table.instanceKey, key) }
                if (satisfied) null
                else Violation(id, severity, "테이블 ${table.name}(${table.instanceKey})은(는) 파티션 키 `$key` 조건(=/IN/BETWEEN, 함수 래핑 불가)이 WHERE에 필요합니다.")
            }
        }

    private fun satisfiesPartitionKey(p: Predicate, table: String, key: String): Boolean = when (p) {
        is Predicate.Comparison -> p.op == Op.EQ && p.value != null && columnMatches(p.column, table, key)
        is Predicate.InList -> p.values != null && columnMatches(p.column, table, key)
        is Predicate.Between -> p.low != null && p.high != null && columnMatches(p.column, table, key)
        else -> false // Or/Not/And/Raw는 충족 불가 (§6.1, §6.3)
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
                    val satisfied = scope.whereConjuncts.any { satisfies(it, table.instanceKey, normalized) }
                    if (satisfied) null
                    else Violation(id, severity, "테이블 ${table.name}(${table.instanceKey})은(는) 필수 조건 `${required.label}` 이 WHERE에 필요합니다.")
                }
            }
        }

    private fun satisfies(conjunct: Predicate, table: String, required: RequiredForm): Boolean = when (conjunct) {
        is Predicate.Comparison ->
            conjunct.op == Op.EQ && conjunct.value == required.value &&
                columnMatches(conjunct.column, table, required.column)
        is Predicate.InList ->
            conjunct.values?.singleOrNull() == required.value &&
                columnMatches(conjunct.column, table, required.column)
        else -> false // Or/Not/And/Raw는 충족 불가 (§6.1, §6.3)
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
