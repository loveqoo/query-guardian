package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.audit.AuditCode
import java.time.Instant

// ---- 요청 ----

data class SaveApprovalRequest(
    val purposeTitle: String,
    val purposeCode: String,
    val tables: List<TableRefDto> = emptyList(),
    val ruleIds: List<Long> = emptyList(),
    val businessReqs: List<String> = emptyList(),
    val approvers: List<ApproverInput> = emptyList(),
)

data class TableRefDto(val db: String? = null, val tableName: String)
/** approverId는 디렉터리 id(ASCII) — 이름·역할은 서버가 해석한다. */
data class ApproverInput(val step: Int, val approverId: String)
data class DecisionRequest(val note: String? = null)

// ---- 응답 ----

data class ApprovalSummaryDto(
    val id: Long,
    val purposeTitle: String,
    val purposeCode: String,
    val requester: String,
    val status: String,
    val currentStep: Int,
    val tables: List<String>,
    val businessReqs: List<String>,
    val approvers: List<ApproverDto>,
    val submittedAt: Instant,
    val decidedAt: Instant?,
)

data class ApproverDto(
    val step: Int,
    val approverId: String,
    val name: String,
    val role: String,
    val decision: String,
    val decidedAt: Instant?,
)

data class RuleSnapshotDto(
    val ruleId: Long,
    val ruleName: String,
    val severitySummary: String,
    val forced: Boolean,
    /** 승인 당시 스냅샷과 현재 규칙이 다른가 (H2 배지). 규칙 삭제 시에도 true. */
    val changedSinceApproval: Boolean,
)

data class ApprovalDetailDto(
    val summary: ApprovalSummaryDto,
    val rules: List<RuleSnapshotDto>,
    val events: List<ApprovalEventDto>,
)

data class ApprovalEventDto(
    val step: Int?,
    val actor: String,
    val action: String,
    val note: String?,
    val at: Instant,
)

/**
 * **"이 사람이 할 수 없다"의 공통 계약** — 데이터 권한 차단과 승인 차단이 공유하는 부분.
 *
 * 둘은 응답 바디가 다르다(거부된 테이블 목록 vs 요청 상태·미커버 테이블). 그래서 **바디는 각자 유지**하고,
 * 게이트가 공통으로 쓰는 `code`·`message`만 여기로 올린다. 이것이 없던 동안 `GateStop`에는 네 멤버 구현이
 * 문자 그대로 같은 쌍둥이 두 개가 있었다 — 같은 개념에 두 개의 모양을 주면 나중에 한쪽만 고쳐진다.
 */
sealed interface BlockedDetail {
    val code: AuditCode
    val message: String
}

/** 승인 차단 응답 (spec 005 §7 — 룰 차단 422와 구분되는 403). */
data class ApprovalBlockedDto(
    /**
     * 실제로 나올 수 있는 값은 넷: `NO_REQUEST` · `NOT_APPROVED` · `REQUESTER_MISMATCH` · `TABLES_NOT_COVERED`.
     * 타입은 [AuditCode](21종)이지만 **이 DTO의 정의역은 그 부분집합**이다 — 프론트가 좁게 분기하는 근거이므로
     * 목록을 남긴다. JSON에서는 Jackson이 이름 문자열로 직렬화한다 — **경계에서만 문자열**(spec 010 I13).
     */
    override val code: AuditCode,
    override val message: String,
    val requestId: Long? = null,
    val requestStatus: String? = null,
    val uncoveredTables: List<String> = emptyList(),
) : BlockedDetail

// ---- 검토 ----

data class ReviewRequest(val decision: String, val note: String? = null)

/** id는 actor 헤더·approverId에 쓰이는 ASCII 식별자 — 반드시 함께 내려야 클라이언트가 추측하지 않는다. */
data class DirectoryPersonDto(val id: String, val name: String, val role: String)
data class BusinessReqDto(val code: String, val label: String, val description: String)

// ---- 인증·권한 (spec 007) ----

/** 데이터 권한 차단 — 역할 부족(ErrorResponse)과 구분되는 코드 포함 403. */
data class AccessBlockedDto(
    /**
     * 실제로 나올 수 있는 값은 셋: `TABLES_NOT_PERMITTED` · `TABLES_UNKNOWN` · `REQUESTER_MISMATCH`.
     * 타입은 [AuditCode](21종)이지만 **이 DTO의 정의역은 그 부분집합**이다 — 프론트가 좁게 분기하는 근거다.
     */
    override val code: AuditCode,
    override val message: String,
    val deniedTables: List<String> = emptyList(),
) : BlockedDetail

data class LoginRequest(val userId: String, val password: String)

/** password_hash는 어떤 DTO에도 노출하지 않는다 (spec 007 H9). */
data class MeDto(val id: String, val displayName: String, val title: String, val role: String)
