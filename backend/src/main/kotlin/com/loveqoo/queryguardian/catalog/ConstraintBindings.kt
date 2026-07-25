package com.loveqoo.queryguardian.catalog

import org.springframework.stereotype.Component

/** 컬럼–매핑–정의 3튜플. */
data class ConstraintBinding(
    val column: CatalogColumn,
    val mapping: ConstraintMapping,
    val def: ConstraintDef,
)

/**
 * 제약 바인딩 조회의 **단일 경로** — 판정(`DbTableCatalog`)과 재작성(`DbRewriteCatalog`)이 같은 코드를 쓴다.
 *
 * 두 곳에 복제하면 한쪽만 고쳐진 채 남을 수 있는데, 이 조회가 0건을 반환하면 **마스킹·필터가 조용히
 * 적용되지 않는다**(spec 008 §3 원칙). 조용한 무적용은 예외보다 위험하므로 경로를 하나로 유지한다.
 */
@Component
class ConstraintBindingReader(
    private val tables: CatalogTableRepository,
    private val defs: ConstraintDefRepository,
    private val mappings: ConstraintMappingRepository,
) {
    /**
     * **논리** 테이블명으로만 조회한다. 물리 데모 테이블명(`demo_users`)으로 조회하면 카탈로그에 없어
     * 빈 목록이 나오고, 그러면 제약이 하나도 적용되지 않은 SQL이 조용히 실행된다 — 물리명 치환은
     * 재작성의 마지막 단계에서만 일어나야 한다(spec 008 §3 원칙).
     */
    /** 카탈로그에 등록된 논리 테이블인가 — unknown-table 경고 룰이 사용. */
    fun tableExists(logicalTableName: String): Boolean = tables.findByNameIgnoreCase(logicalTableName) != null

    fun forTable(logicalTableName: String): List<ConstraintBinding> {
        val table = tables.findByNameIgnoreCase(logicalTableName) ?: return emptyList()
        val columnsById = table.columns.filter { it.id != null }.associateBy { it.id!! }
        if (columnsById.isEmpty()) return emptyList()
        return mappings.findByColumnIdIn(columnsById.keys).mapNotNull { m ->
            defs.findById(m.defId).orElse(null)?.let { d -> ConstraintBinding(columnsById[m.columnId]!!, m, d) }
        }
    }
}
