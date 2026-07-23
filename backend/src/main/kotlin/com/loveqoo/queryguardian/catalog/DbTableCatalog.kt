package com.loveqoo.queryguardian.catalog

import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.rules.RequiredPredicate
import com.loveqoo.queryguardian.rules.TableCatalog

/**
 * 설정 DB 기반 카탈로그. 필수 술어는 등록 시점에 파싱 가능성을 검증하지만(§5.4),
 * 만약 로드 시 파싱이 실패하면 Raw로 격하되어 룰 쪽에서 "검증 불가 → 차단"으로 떨어진다(fail-closed).
 */
class DbTableCatalog(
    private val parser: DialectParser,
    private val repository: CatalogTableRepository,
) : TableCatalog {

    override fun partitionKey(tableName: String): String? =
        repository.findByNameIgnoreCase(tableName)
            ?.constraints
            ?.firstOrNull { it.kind == ConstraintKind.PARTITION_KEY }
            ?.columnName

    override fun requiredPredicates(tableName: String, purposeCode: String?): List<RequiredPredicate> =
        repository.findByNameIgnoreCase(tableName)
            ?.constraints
            ?.filter { it.kind == ConstraintKind.REQUIRED_PREDICATE }
            ?.filter { it.purposeCode == null || it.purposeCode == purposeCode }
            ?.mapNotNull { constraint ->
                val sql = constraint.predicateSql ?: return@mapNotNull null
                val predicate = parser.parsePredicate(sql) ?: Predicate.Raw(sql)
                RequiredPredicate(sql, predicate)
            }
            ?: emptyList()

    override fun exists(tableName: String): Boolean =
        repository.findByNameIgnoreCase(tableName) != null
}
