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

/** 의미 룰이 참조하는 테이블 메타데이터. M2에서 설정 DB 구현으로 대체, M1은 인메모리. */
interface TableCatalog {
    /** 파티션 키(또는 필수 인덱스 컬럼). 미등록 테이블은 null. */
    fun partitionKey(tableName: String): String?

    /** 적용 대상 필수 술어: 항상 적용(purpose=null 등록분) + 현재 purpose에 등록된 것. */
    fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate>

    /** 카탈로그에 등록된 테이블인가 — unknown-table 경고 룰이 사용. */
    fun exists(tableName: String): Boolean
}

/** [predicate]는 카탈로그의 predicate_sql을 DialectParser.parsePredicate로 파싱해 둔 것 (§6.5 구조 비교). */
data class RequiredPredicate(val label: String, val predicate: Predicate)

class InMemoryTableCatalog(
    private val partitionKeys: Map<String, String> = emptyMap(),
    /** (테이블명 소문자, purposeCode?) → 필수 술어 목록 */
    private val required: List<Entry> = emptyList(),
    tables: Set<String> = emptySet(),
) : TableCatalog {
    data class Entry(val table: String, val purposeCode: String?, val predicate: RequiredPredicate)

    private val known: Set<String> =
        (tables + partitionKeys.keys + required.map { it.table }).map { it.lowercase() }.toSet()

    override fun partitionKey(tableName: String): String? =
        partitionKeys.entries.firstOrNull { it.key.equals(tableName, ignoreCase = true) }?.value

    override fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate> =
        required.filter {
            it.table.equals(tableName, ignoreCase = true) &&
                (it.purposeCode == null || it.purposeCode == purposeCode)
        }.map { it.predicate }

    override fun exists(tableName: String): Boolean = known.contains(tableName.lowercase())
}
