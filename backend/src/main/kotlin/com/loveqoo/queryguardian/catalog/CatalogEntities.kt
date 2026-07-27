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

/**
 * **이 종류는 "WHERE에 이 술어가 있어야 한다"는 요건인가** (spec 012 §7-1, spec 013 S1).
 *
 * 정의를 한 곳에 두는 이유: 이 질문에 답하는 자리가 **셋**이다 — 판정
 * (`DbTableCatalog.requiredPredicates`), 등록 검증(`CatalogService.createMapping`의 C2 가드),
 * 그리고 판정 어휘 문서. 세 곳에 `kind == FILTER`를 흩어 놓았던 것이 사고의 원인이었다:
 * 판정만 FILTER를 보고 주입은 FILTER+INTEGRITY를 봐서, **INTEGRITY의 유일한 강제 수단이
 * 실행 시점 주입**이 되어 있었다. 주입은 spec 012가 걷어낼 대상이었으므로 그대로 지우면 fail-open이다.
 *
 * `when`을 망라적으로 두어 **kind를 추가하는 사람이 이 질문에 답하게** 한다 — `else`를 두면
 * 새 종류가 조용히 "요건 아님"으로 분류되고, 그것이 바로 방금 고친 사고의 모양이다.
 */
val DefKind.isRequiredPredicate: Boolean get() = when (this) {
    DefKind.FILTER -> true      // "동의한 사용자만" — purpose 스코프를 가진다
    DefKind.INTEGRITY -> true   // "삭제되지 않은 것만" — 항상 적용(purpose 스코프 없음)
    DefKind.MASK -> false       // 투영을 바꾸는 요건 — must-be-masked가 본다
    DefKind.BLOCK -> false      // 참조 자체를 금지 — no-blocked-column이 본다
    DefKind.PARTITION -> false  // 컬럼에 경계가 있어야 한다는 요건 — require-partition-key가 본다
    DefKind.JOIN -> false       // 조인 등식 요건 — 사용자 규칙 `joins`가 본다
}

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
