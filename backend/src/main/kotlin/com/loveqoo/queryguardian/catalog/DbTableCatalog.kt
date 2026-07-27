package com.loveqoo.queryguardian.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.forcedExpressionForms
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.PredicateParse
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

    private val log = org.slf4j.LoggerFactory.getLogger(DbTableCatalog::class.java)

    private fun boundFor(tableName: String): List<ConstraintBinding> = bindings.forTable(tableName)

    override fun partitionKeys(tableName: String): List<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.PARTITION }.map { it.column.name }

    /**
     * 사용자가 직접 써도 되는 가려진 형태 (spec 012 P0) — 등록된 MASK 강제식이 근거다.
     * 목록을 따로 만들지 않는다: 스튜어드가 이미 등록한 그 값이 곧 허용 목록이다.
     */
    override fun maskForms(tableName: String, instanceKey: String, column: String): Set<String> =
        boundFor(tableName)
            .filter { it.def.kind == DefKind.MASK && it.column.name.equals(column, ignoreCase = true) }
            .flatMap { bound ->
                val template = bound.def.expression ?: return@flatMap emptyList()
                val params = Expressions.parseParams(objectMapper, bound.mapping.paramsJson) ?: return@flatMap emptyList()
                val withParams = Expressions.substituteParams(template, params) ?: return@flatMap emptyList()
                forcedExpressionForms(withParams, instanceKey, bound.column.name)
            }
            .toSet()

    override fun maskedColumns(tableName: String): Set<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.MASK }.map { it.column.name.lowercase() }.toSet()

    override fun blockedColumns(tableName: String): Set<String> =
        boundFor(tableName).filter { it.def.kind == DefKind.BLOCK }.map { it.column.name.lowercase() }.toSet()

    /**
     * `FILTER`뿐 아니라 **`INTEGRITY`도** 요구한다 (spec 012 §7-1). 예전에는 FILTER만 걸렀고,
     * 그 결과 무결성 조건의 유일한 강제 수단이 **실행 시점 술어 주입**이었다 — 걷어낼 예정인 것이.
     *
     * purpose 스코프는 **FILTER에만** 적용한다. 등록 검증이 `purposeCode`를 FILTER에만 허용하므로
     * 다른 종류의 매핑은 그 값이 없어야 하는데, "없을 것"에 판정을 맡기지 않는다 — 어쩌다 값이 있으면
     * 이 필터가 요건을 **좁혀** 조용히 fail-open이 된다. 좁히지 않는 쪽을 택한다.
     */
    override fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate> =
        boundFor(tableName)
            .filter { it.def.kind.isRequiredPredicate }
            .filter {
                it.def.kind != DefKind.FILTER ||
                    it.mapping.purposeCode == null || it.mapping.purposeCode == purposeCode
            }
            .map { bound ->
                val expression = bound.def.expression ?: return@map unverifiable(bound)
                val params = Expressions.parseParams(objectMapper, bound.mapping.paramsJson) ?: return@map unverifiable(bound)
                val sql = Expressions.substitute(expression, bound.column.name, params) ?: return@map unverifiable(bound)
                RequiredPredicate("${bound.def.name} ($sql)", predicateOrRaw(bound, sql))
            }

    /**
     * 파싱 실패 시 `Raw`로 격하한다 — 그러면 `RewriteVerifier`가 **구조 비교 대신 텍스트 비교**로
     * 확인한다(`matches`의 `expected == null` 분기와 같은 자리). 정책 자체는 유지하되 **조용하지 않게** 한다.
     *
     * **도달 경로를 구체적으로 제시하지는 못했다** — `Expressions.substitute`가 값의 `'`를 `''`로
     * 이스케이프하고 비숫자 값을 인용부호로 감싸므로 파라미터로 문법을 깨는 뻔한 길은 막혀 있다.
     * 그래도 등록 시 파싱이 여기서의 파싱을 **보장하지는 않는다**: 등록 검증은 모든 파라미터를 `"1"`로
     * 치환한 표본(숫자, 인용부호 없음)으로 확인하고 여기서는 실제 값(대개 인용부호 있음)으로 치환하므로
     * **같은 문자열이 아니다**. 보장하지 못하는 것을 보장한다고 적는 대신, 벌어지면 소리가 나게 해 둔다.
     */
    private fun predicateOrRaw(bound: ConstraintBinding, sql: String): Predicate =
        when (val parsed = parser.parsePredicate(sql)) {
            is PredicateParse.Parsed -> parsed.predicate
            is PredicateParse.Unparsed -> {
                log.warn(
                    "FORCED_PREDICATE_UNPARSED def={} sql={} reason={} — 구조 검증이 텍스트 비교로 격하된다",
                    bound.def.name, sql, parsed.reason,
                )
                Predicate.Raw(sql)
            }
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
        // 여기서는 이유가 정책을 바꾸지 않는다 — 판정 불가면 평가기가 fail-closed로 차단한다(§4.2).
        val predicate = parser.parsePredicate(sql).predicateOrNull ?: return null
        return requiredForm(predicate)
    }
}
