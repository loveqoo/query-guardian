package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.SelectScope

enum class Severity { BLOCK, WARN }

/** BLOCK을 가장 심각한 것으로 취급 (enum ordinal은 반대라 maxOf 사용 금지). */
internal fun worstSeverity(severities: Collection<Severity>): Severity =
    if (severities.any { it == Severity.BLOCK }) Severity.BLOCK else Severity.WARN

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

    /**
     * 사용자 규칙 requires 조건의 술어 해석 (spec 004 §4.2). defId+컬럼(+mappingId)의 FILTER 정의를
     * 치환·파싱해 판정 가능 정규형으로 반환. 매핑 사라짐(dangling)·판정 불가 형태면 null → 평가기가 fail-closed 처리.
     */
    fun resolveConditionPredicate(defId: Long, mappingId: Long?, columnName: String): RequiredForm?
}

/** [predicate]는 카탈로그의 predicate_sql을 DialectParser.parsePredicate로 파싱해 둔 것 (§6.5 구조 비교). */
data class RequiredPredicate(val label: String, val predicate: Predicate)

class InMemoryTableCatalog(
    private val partitionKeys: Map<String, List<String>> = emptyMap(),
    /** (테이블명 소문자, purposeCode?) → 필수 술어 목록 */
    private val required: List<Entry> = emptyList(),
    private val blocked: Map<String, Set<String>> = emptyMap(),
    tables: Set<String> = emptySet(),
    /** defId → requires 판정용 정규형 (테스트 시드). */
    private val conditionPredicates: Map<Long, RequiredForm> = emptyMap(),
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

    override fun resolveConditionPredicate(defId: Long, mappingId: Long?, columnName: String): RequiredForm? =
        conditionPredicates[defId]
}
