package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.Severity
import java.time.Instant

// ---- lint ----

data class LintRequest(val dialect: String, val sql: String, val purposeCode: String? = null)

data class ViolationDto(val ruleId: String, val severity: Severity, val message: String)

data class LintReportDto(val violations: List<ViolationDto>, val blocked: Boolean) {
    companion object {
        fun from(report: LintReport) = LintReportDto(
            violations = report.violations.map { ViolationDto(it.ruleId, it.severity, it.message) },
            blocked = report.blocked,
        )
    }
}

// ---- queries ----

data class SaveQueryRequest(
    val name: String,
    val dialect: String,
    val sql: String,
    val purposeCode: String? = null,
)

data class QuerySummaryDto(
    val id: Long,
    val name: String,
    val dialect: String,
    val purposeCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class QueryDto(
    val id: Long,
    val name: String,
    val dialect: String,
    val sql: String,
    val purposeCode: String?,
    val lintReport: LintReportDto,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// ---- catalog (spec 002 §5.3) ----

data class ColumnDto(
    val id: Long? = null,
    val name: String,
    val type: String? = null,
    val isPii: Boolean = false,
    val cls: String? = null, // 요청 시 생략하면 자동 판별, 지정 시 override
)

data class TableDto(
    val id: Long? = null,
    val name: String,
    val description: String? = null,
    val columns: List<ColumnDto> = emptyList(),
)

data class SaveTableRequest(
    val name: String,
    val description: String? = null,
    val columns: List<ColumnDto> = emptyList(),
)

data class DefDto(
    val id: Long? = null,
    val cls: String,
    val kind: String,
    val name: String,
    val description: String? = null,
    val expression: String? = null,
    val mappingCount: Long = 0,
)

data class SaveDefRequest(
    val cls: String,
    val kind: String,
    val name: String,
    val description: String? = null,
    val expression: String? = null,
)

data class MappingDto(
    val id: Long,
    val tableId: Long,
    val tableName: String,
    val columnId: Long,
    val columnName: String,
    val defId: Long,
    val defName: String,
    val defKind: String,
    val purposeCode: String? = null,
    val paramsJson: String? = null,
    val clsMismatch: Boolean = false,
)

data class SaveMappingRequest(
    val columnId: Long,
    val defId: Long,
    val purposeCode: String? = null,
    val paramsJson: String? = null,
)

data class PurposeDto(val id: Long? = null, val code: String, val description: String? = null)
