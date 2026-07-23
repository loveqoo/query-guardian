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

// ---- catalog ----

data class ColumnDto(val id: Long? = null, val name: String, val type: String? = null)

data class ConstraintDto(
    val id: Long? = null,
    val kind: String,
    val columnName: String? = null,
    val predicateSql: String? = null,
    val purposeCode: String? = null,
)

data class TableDto(
    val id: Long? = null,
    val name: String,
    val description: String? = null,
    val columns: List<ColumnDto> = emptyList(),
    val constraints: List<ConstraintDto> = emptyList(),
)

data class SaveTableRequest(
    val name: String,
    val description: String? = null,
    val columns: List<ColumnDto> = emptyList(),
)

data class SaveConstraintRequest(
    val kind: String,
    val columnName: String? = null,
    val predicateSql: String? = null,
    val purposeCode: String? = null,
)

data class PurposeDto(val id: Long? = null, val code: String, val description: String? = null)
