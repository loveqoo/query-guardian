package com.loveqoo.queryguardian.catalog

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table

@Table("catalog_table")
data class CatalogTable(
    @Id val id: Long? = null,
    val name: String,
    val description: String? = null,
    @MappedCollection(idColumn = "catalog_table")
    val columns: Set<CatalogColumn> = emptySet(),
    @MappedCollection(idColumn = "catalog_table")
    val constraints: Set<CatalogConstraint> = emptySet(),
)

@Table("catalog_column")
data class CatalogColumn(
    @Id val id: Long? = null,
    val name: String,
    val type: String? = null,
)

enum class ConstraintKind { PARTITION_KEY, REQUIRED_PREDICATE }

@Table("catalog_constraint")
data class CatalogConstraint(
    @Id val id: Long? = null,
    val kind: ConstraintKind,
    val columnName: String? = null,
    val predicateSql: String? = null,
    val purposeCode: String? = null,
)

@Table("catalog_purpose")
data class CatalogPurpose(
    @Id val id: Long? = null,
    val code: String,
    val description: String? = null,
)
