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
)

/** 컬럼 클래스 — 제약 정의와 매핑의 어휘를 묶는 축 (spec 002 §3.1). */
enum class ColumnClass { PII, BOOLEAN, DATETIME, NUMERIC, KEY, STRING }

@Table("catalog_column")
data class CatalogColumn(
    @Id val id: Long? = null,
    val name: String,
    val type: String? = null,
    val isPii: Boolean = false,
    /** 판별값 저장(수동 override 반영). 변경돼도 기존 매핑은 자동 삭제하지 않는다 (§3.1/H1). */
    val cls: ColumnClass = ColumnClass.STRING,
)

/** kind 6종 — 디자인 5종 + PARTITION(결정 §9-3). */
enum class DefKind { MASK, FILTER, BLOCK, JOIN, INTEGRITY, PARTITION }

/** 제약 정의 사전 — 컬럼 클래스별 재사용 가능한 제약 (spec 002 §3). */
@Table("constraint_def")
data class ConstraintDef(
    @Id val id: Long? = null,
    val cls: ColumnClass,
    val kind: DefKind,
    val name: String,
    val description: String? = null,
    /** `{col}`/`:param` 강제식. BLOCK/PARTITION은 null. */
    val expression: String? = null,
)

/** 컬럼 ↔ 제약 정의 매핑. UNIQUE(column_id, def_id, purpose_code)는 schema.sql에서 강제 (H5). */
@Table("constraint_mapping")
data class ConstraintMapping(
    @Id val id: Long? = null,
    val columnId: Long,
    val defId: Long,
    val purposeCode: String? = null,
    val paramsJson: String? = null,
)

@Table("catalog_purpose")
data class CatalogPurpose(
    @Id val id: Long? = null,
    val code: String,
    val description: String? = null,
)

/** 클래스 자동 판별 (spec 002 §3.1 — 우선순위 결정적). */
object ColumnClassifier {
    fun classify(type: String?, isPii: Boolean, columnName: String): ColumnClass {
        if (isPii) return ColumnClass.PII
        val t = (type ?: "").uppercase()
        return when {
            t.contains("BOOL") -> ColumnClass.BOOLEAN
            t.contains("DATE") || t.contains("TIME") -> ColumnClass.DATETIME
            NUMERIC_TYPES.any { t.startsWith(it) } ->
                if (columnName.equals("id", true) || columnName.endsWith("_id", true)) ColumnClass.KEY
                else ColumnClass.NUMERIC
            else -> ColumnClass.STRING // 알려진 한계: 문자 타입 id(uuid 등)는 KEY가 아니다 (M2)
        }
    }

    private val NUMERIC_TYPES = listOf("INT", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE")
}
