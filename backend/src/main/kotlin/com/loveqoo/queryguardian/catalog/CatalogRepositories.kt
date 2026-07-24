package com.loveqoo.queryguardian.catalog

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface CatalogTableRepository : CrudRepository<CatalogTable, Long> {
    @Query("SELECT * FROM catalog_table WHERE LOWER(name) = LOWER(:name)")
    fun findByNameIgnoreCase(@Param("name") name: String): CatalogTable?
}

interface CatalogPurposeRepository : CrudRepository<CatalogPurpose, Long> {
    @Query("SELECT * FROM catalog_purpose WHERE code = :code")
    fun findByCode(@Param("code") code: String): CatalogPurpose?
}

interface ConstraintDefRepository : CrudRepository<ConstraintDef, Long>

interface ConstraintMappingRepository : CrudRepository<ConstraintMapping, Long> {
    fun findByColumnId(columnId: Long): List<ConstraintMapping>
    fun findByDefId(defId: Long): List<ConstraintMapping>
    fun countByDefId(defId: Long): Long
    fun findByColumnIdIn(columnIds: Collection<Long>): List<ConstraintMapping>
    fun findByPurposeCode(purposeCode: String): List<ConstraintMapping>
    fun deleteByColumnIdIn(columnIds: Collection<Long>)
}
