package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.forcedExpressionForms

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

    /** MASK 매핑 컬럼들(소문자) — must-be-masked 룰이 사용 (spec 008 §3.1). */
    fun maskedColumns(tableName: String): Set<String> = emptySet()

    /**
     * **사용자가 직접 써도 되는 가려진 형태들** (spec 012 P0).
     *
     * 예전에는 강제식 원문이 판정에 필요 없었다 — 서버가 알아서 가렸으니까. 이제는 사용자가 직접
     * 가려서 쓰고, 우리는 그것이 **등록된 그 형태인지** 확인해야 한다. `mask_email(email)`은 통과하고
     * `CONCAT(email,'')`은 막혀야 하는데, 둘을 가르는 유일한 근거가 등록된 강제식이다.
     *
     * 치환을 여기서 하는 이유: `{col}` 자리표시자를 아는 곳을 한 군데로 유지한다(`ir`에 사본을 두지 않는다).
     * 한정자 유무는 **여기서 두 형태를 다 넣어** 다룬다 — 비교하는 쪽에서 한정자를 벗기면
     * `other.email`까지 통과한다.
     *
     * 빈 집합 = 무엇도 "이미 가려짐"으로 인정하지 않음(예전 동작).
     */
    fun maskForms(tableName: String, instanceKey: String, column: String): Set<String> = emptySet()

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
    private val masked: Map<String, Set<String>> = emptyMap(),
    /** (테이블 → 컬럼 → `{col}`을 가진 MASK 강제식) — spec 012 P0의 "사용자가 써도 되는 형태" 근거. */
    private val maskTemplates: Map<String, Map<String, String>> = emptyMap(),
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

    override fun maskedColumns(tableName: String): Set<String> =
        masked[tableName.lowercase()] ?: emptySet()

    override fun maskForms(tableName: String, instanceKey: String, column: String): Set<String> {
        val template = maskTemplates[tableName.lowercase()]
            ?.entries?.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value ?: return emptySet()
        return forcedExpressionForms(template, instanceKey, column)
    }

    override fun blockedColumns(tableName: String): Set<String> =
        blocked.entries.firstOrNull { it.key.equals(tableName, ignoreCase = true) }
            ?.value?.map { it.lowercase() }?.toSet() ?: emptySet()

    override fun exists(tableName: String): Boolean = known.contains(tableName.lowercase())

    override fun resolveConditionPredicate(defId: Long, mappingId: Long?, columnName: String): RequiredForm? =
        conditionPredicates[defId]
}
