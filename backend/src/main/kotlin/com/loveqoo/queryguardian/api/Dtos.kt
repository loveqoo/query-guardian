package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.audit.AuditCode

import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.Fix
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.query.ReviewStatus
import java.time.Instant

// ---- lint ----

/** purposeCode는 클라이언트가 보내지 않는다 — 서버가 승인 요청에서 주입 (spec 005 C1). */
data class LintRequest(val dialect: String, val sql: String, val requestId: Long? = null)

/**
 * 고칠 방법 한 조각 — 화면이 클릭 한 번에 적용한다 (spec 012 §7-3).
 *
 * 와이어에서는 `kind`로 갈라 놓는다. 서버 안에서는 [Fix]가 sealed로 비대칭을 들고 있지만
 * (`ADD_PREDICATE`엔 바꿀 원본이 없다), JSON에는 그 타입 정보가 없으므로 **읽는 쪽이 갈라 볼
 * 근거**를 명시적으로 준다. 다형 역직렬화를 켜지 않는 이유: 이 DTO는 서버→화면 단방향이고,
 * Jackson의 타입 정보 주입은 와이어 표현을 클래스 이름에 묶어 버린다(spec 010 I13의 교훈).
 *
 * [from]은 `REPLACE_PROJECTION`에만 있다. `ADD_PREDICATE`에서 null인 것은 "못 찾았다"가 아니라
 * **"바꾸는 게 아니라 더하는 것"**이라는 뜻이다.
 */
data class FixDto(
    val kind: String,
    val table: String,
    val column: String,
    val from: String?,
    val to: String,
) {
    companion object {
        fun from(fix: Fix): FixDto = when (fix) {
            is Fix.ReplaceProjection ->
                FixDto("REPLACE_PROJECTION", fix.table, fix.column, from = fix.from, to = fix.to)
            is Fix.AddPredicate ->
                FixDto("ADD_PREDICATE", fix.table, fix.column, from = null, to = fix.predicate)
        }
    }
}

data class ViolationDto(
    val ruleId: String,
    val severity: Severity,
    val message: String,
    val fix: FixDto? = null,
)

data class LintReportDto(
    val violations: List<ViolationDto>,
    val blocked: Boolean,
    /**
     * 이 판정으로 차단됐을 때의 분류 코드. `GateStop.Violated`가 채우므로 **저장·실행 게이트 모두**
     * 값을 싣는다 — 출처는 언제나 `GateStop.code` 하나다(spec 010 A2).
     *
     * null인 경우는 차단이 아닌 판정 결과다(lint 조회, 저장 성공 시 `lint_report_json`).
     * (P1-C3 시점 주석은 "저장 게이트는 감사를 남기지 않으므로 null"이라 적었는데, C4가 저장 게이트를
     * 같은 단계 단위로 옮기면서 사실이 아니게 됐다 — 적대 검토가 실측으로 잡았다.)
     */
    val code: AuditCode? = null,
) {
    companion object {
        fun from(report: LintReport) = LintReportDto(
            violations = report.violations.map { ViolationDto(it.ruleId, it.severity, it.message, it.fix?.let(FixDto::from)) },
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
    val reviewStatus: ReviewStatus,
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
    val reviewStatus: ReviewStatus,
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
    /**
     * [effectiveLimit] = 실제 적용된 상한, [configuredCap] = 거버넌스 설정 상한.
     * 둘이 같을 때만 "상한 때문에 잘렸다"이고, 다르면 사용자가 스스로 좁힌 것이다(D5).
     * [moreRowsExist]가 null이면 **확인하지 않음**(상한 0이면 초과 탐지용 1행조차 조회하지 않는다).
     */
    val effectiveLimit: Long?,
    val configuredCap: Long?,
    val moreRowsExist: Boolean?,
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
    /** enum을 그대로 싣는다 — Jackson이 이름으로 직렬화하므로 **JSON은 바뀌지 않는다**(spec 010 I13). */
    val outcome: ExecutionOutcome,
    val rowCount: Int?,
    val elapsedMs: Long?,
    val effectiveLimit: Long?,
    val configuredCap: Long?,
    val moreRowsExist: Boolean?,
    val errorCode: String?,
    val errorDetail: String?,
    val rewrittenSql: String?,
    val at: java.time.Instant,
)

/** 재작성 미리보기 요청 (spec 008 §7). [requestId]는 **필수** — purposeCode를 서버가 그 요청에서 주입한다. */
data class PreviewRewriteRequest(val sql: String, val requestId: Long?, val dialect: String? = null)

/**
 * 미리보기 응답 — 실행 결과가 없다. "무엇이 자동 적용되는지"만 보여준다.
 * [lintReport]를 함께 주는 이유: 통과했더라도 WARN(예: "실행 시 자동 마스킹됩니다")을 그 자리에서 봐야 한다.
 */
data class PreviewRewriteDto(
    val rewrittenSql: String,
    val applied: List<AppliedRewriteDto>,
    val lintReport: LintReportDto,
)
