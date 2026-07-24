package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.SelectScope

enum class Severity { BLOCK, WARN }

data class Violation(val ruleId: String, val severity: Severity, val message: String)

data class LintContext(val purposeCode: String?)

data class LintReport(val violations: List<Violation>) {
    val blocked: Boolean get() = violations.any { it.severity == Severity.BLOCK }
}

interface Rule {
    val id: String
    val severity: Severity
    fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation>
}

/** 의미 룰이 참조하는 테이블 메타데이터 (spec 002: 제약 정의 사전 + 컬럼 매핑 기반). */
interface TableCatalog {
    /** PARTITION 매핑 컬럼들. 복합 파티션 지원 — 각 키는 독립 요건이다 (spec 002 C4). */
    fun partitionKeys(tableName: String): List<String>

    /** 적용 대상 필수 술어(FILTER 매핑): 항상 적용(purpose=null) + 현재 purpose에 등록된 것. */
    fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate>

    /** BLOCK 매핑 컬럼들(소문자) — no-blocked-column 룰이 사용. */
    fun blockedColumns(tableName: String): Set<String>

    /** 카탈로그에 등록된 테이블인가 — unknown-table 경고 룰이 사용. */
    fun exists(tableName: String): Boolean
}

/** [predicate]는 카탈로그의 predicate_sql을 DialectParser.parsePredicate로 파싱해 둔 것 (§6.5 구조 비교). */
data class RequiredPredicate(val label: String, val predicate: Predicate)

class InMemoryTableCatalog(
    private val partitionKeys: Map<String, List<String>> = emptyMap(),
    /** (테이블명 소문자, purposeCode?) → 필수 술어 목록 */
    private val required: List<Entry> = emptyList(),
    private val blocked: Map<String, Set<String>> = emptyMap(),
    tables: Set<String> = emptySet(),
) : TableCatalog {
    data class Entry(val table: String, val purposeCode: String?, val predicate: RequiredPredicate)

    private val known: Set<String> =
        (tables + partitionKeys.keys + blocked.keys + required.map { it.table }).map { it.lowercase() }.toSet()

    override fun partitionKeys(tableName: String): List<String> =
        partitionKeys.entries.firstOrNull { it.key.equals(tableName, ignoreCase = true) }?.value ?: emptyList()

    override fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate> =
        required.filter {
            it.table.equals(tableName, ignoreCase = true) &&
                (it.purposeCode == null || it.purposeCode == purposeCode)
        }.map { it.predicate }

    override fun blockedColumns(tableName: String): Set<String> =
        blocked.entries.firstOrNull { it.key.equals(tableName, ignoreCase = true) }
            ?.value?.map { it.lowercase() }?.toSet() ?: emptySet()

    override fun exists(tableName: String): Boolean = known.contains(tableName.lowercase())
}
