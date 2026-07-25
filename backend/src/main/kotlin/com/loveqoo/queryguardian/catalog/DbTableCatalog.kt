package com.loveqoo.queryguardian.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.rules.RequiredForm
import com.loveqoo.queryguardian.rules.RequiredPredicate
import com.loveqoo.queryguardian.rules.TableCatalog
import com.loveqoo.queryguardian.rules.requiredForm

/**
 * 설정 DB 기반 카탈로그 (spec 002: constraint_def + constraint_mapping).
 * 매핑 시점에 판정 가능성을 검증하지만, 만약 로드 시 치환·파싱이 실패하면 Raw로 격하되어
 * 룰 쪽에서 "검증 불가 → 차단"으로 떨어진다(fail-closed).
 */
class DbTableCatalog(
    private val parser: DialectParser,
    private val bindings: ConstraintBindingReader,
    private val defs: ConstraintDefRepository,
    private val mappings: ConstraintMappingRepository,
    private val objectMapper: ObjectMapper,
) : TableCatalog {

    private fun boundFor(tableName: String): List<ConstraintBinding> = bindings.forTable(tableName)

    override fun partitionKeys(tableName: String): List<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.PARTITION }.map { it.column.name }

    override fun maskedColumns(tableName: String): Set<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.MASK }.map { it.column.name.lowercase() }.toSet()

    override fun blockedColumns(tableName: String): Set<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.BLOCK }.map { it.column.name.lowercase() }.toSet()

    override fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate> =
        boundFor(tableName)
            .filter { it.def.kind == DefKind.FILTER }
            .filter { it.mapping.purposeCode == null || it.mapping.purposeCode == purposeCode }
            .map { bound ->
                val expression = bound.def.expression ?: return@map unverifiable(bound)
                val params = Expressions.parseParams(objectMapper, bound.mapping.paramsJson) ?: return@map unverifiable(bound)
                val sql = Expressions.substitute(expression, bound.column.name, params) ?: return@map unverifiable(bound)
                RequiredPredicate("${bound.def.name} ($sql)", parser.parsePredicate(sql) ?: Predicate.Raw(sql))
            }

    private fun unverifiable(bound: ConstraintBinding) =
        RequiredPredicate("${bound.def.name} (검증 불가)", Predicate.Raw(bound.def.expression ?: ""))

    override fun exists(tableName: String): Boolean = bindings.tableExists(tableName)

    /**
     * 사용자 규칙 requires 조건의 술어 해석 (spec 004 §4.2). def(FILTER)의 강제식을 컬럼·params로 치환·파싱해
     * 판정 정규형(EQ 리터럴/IN 단일)으로 반환. 매핑 사라짐·판정 불가 형태면 null → 평가기 fail-closed.
     */
    override fun resolveConditionPredicate(defId: Long, mappingId: Long?, columnName: String): RequiredForm? {
        val def = defs.findById(defId).orElse(null) ?: return null
        if (def.kind != DefKind.FILTER) return null
        val expression = def.expression ?: return null
        // mappingId가 지정되면 그 매핑, 아니면 이 컬럼-def의 첫 매핑. 매핑이 없으면 dangling → null (C4).
        val mapping = mappings.findByDefId(defId).firstOrNull { m ->
            (mappingId == null || m.id == mappingId)
        } ?: return null
        val params = Expressions.parseParams(objectMapper, mapping.paramsJson) ?: return null
        val sql = Expressions.substitute(expression, columnName, params) ?: return null
        val predicate = parser.parsePredicate(sql) ?: return null
        return requiredForm(predicate)
    }
}
