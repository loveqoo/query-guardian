package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.Severity
import java.time.Instant

// ---- lint ----

/** purposeCode는 클라이언트가 보내지 않는다 — 서버가 승인 요청에서 주입 (spec 005 C1). */
data class LintRequest(val dialect: String, val sql: String, val requestId: Long? = null)

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

/** purposeCode 없음 — 승인 요청(requestId)에서 서버가 주입 (spec 005 C1). */
data class SaveQueryRequest(
    val name: String,
    val dialect: String,
    val sql: String,
    val requestId: Long? = null,
)

data class QuerySummaryDto(
    val id: Long,
    val name: String,
    val dialect: String,
    val purposeCode: String?,
    val requestId: Long,
    val reviewStatus: String,
    val reviewer: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class QueryDto(
    val id: Long,
    val name: String,
    val dialect: String,
    val sql: String,
    val purposeCode: String?,
    val requestId: Long,
    val reviewStatus: String,
    val reviewer: String?,
    val reviewedAt: Instant?,
    val reviewNote: String?,
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

/** 실행 결과 (spec 008 §7). 결과 행은 응답에만 담기고 어디에도 저장되지 않는다(§6 불변식). */
data class ExecutionResultDto(
    val columns: List<ExecutionColumnDto>,
    val rows: List<List<String?>>,
    val rowCount: Int,
    val elapsedMs: Long,
    val truncated: Boolean,
    val rewrittenSql: String,
    val applied: List<AppliedRewriteDto>,
)

data class ExecutionColumnDto(val name: String, val type: String)

/** 무엇이 자동 적용됐는지 — 화면에 그대로 보여준다(사용자가 결과를 해석할 수 있어야 한다). */
data class AppliedRewriteDto(val kind: String, val table: String, val column: String?, val detail: String)

/**
 * 실행 이력 항목. [errorDetail]은 **STEWARD/ADMIN에게만** 채워진다 —
 * MySQL 오류 메시지는 데이터 값을 에코하므로(§6) 일반 사용자에게는 분류 코드까지만 준다.
 */
data class ExecutionEventDto(
    val id: Long,
    val actor: String,
    val outcome: String,
    val rowCount: Int?,
    val elapsedMs: Long?,
    val truncated: Boolean,
    val errorCode: String?,
    val errorDetail: String?,
    val rewrittenSql: String?,
    val at: java.time.Instant,
)
