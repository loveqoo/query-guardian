package com.loveqoo.queryguardian.approval

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

enum class RequestStatus { PENDING, APPROVED, REJECTED, CANCELLED }
enum class ApproverDecision { PENDING, APPROVED, REJECTED }
enum class ApprovalAction { SUBMIT, APPROVE, REJECT, CANCEL }

/**
 * 승인 요청 (spec 005 §3.1). purposeCode는 관리형 목록 참조 — lint/save의 purposeCode를 서버가 여기서 주입한다(C1).
 * APPROVED 요청은 불변(철회·삭제 없음) — 저장 쿼리가 참조하므로 dangling 차단.
 */
@Table("approval_request")
data class ApprovalRequest(
    @Id val id: Long? = null,
    val purposeTitle: String,
    val purposeCode: String,
    val requester: String,
    val status: RequestStatus = RequestStatus.PENDING,
    val currentStep: Int = 1,
    val submittedAt: Instant,
    val decidedAt: Instant? = null,
    @Version val version: Long? = null, // 낙관적 잠금 — 동시 승인 경합 차단 (C4)
    @MappedCollection(idColumn = "request_id", keyColumn = "table_idx")
    val tables: List<RequestTable> = emptyList(),
    @MappedCollection(idColumn = "request_id", keyColumn = "rule_idx")
    val rules: List<RequestRule> = emptyList(),
    @MappedCollection(idColumn = "request_id", keyColumn = "req_idx")
    val businessReqs: List<RequestBusinessReq> = emptyList(),
    @MappedCollection(idColumn = "request_id", keyColumn = "step")
    val approvers: List<RequestApprover> = emptyList(),
)

@Table("request_table")
data class RequestTable(@Id val id: Long? = null, val db: String? = null, val tableName: String)

/** 규칙 내용 스냅샷 (H2) — 승인 후 규칙이 바뀌어도 당시 기록은 불변. */
@Table("request_rule")
data class RequestRule(
    @Id val id: Long? = null,
    val ruleId: Long,
    val ruleName: String,
    val severitySummary: String,
    val treeJsonSnapshot: String,
    val forced: Boolean,
)

@Table("request_business_req")
data class RequestBusinessReq(@Id val id: Long? = null, val code: String)

/** name/role은 디렉터리 FK 없이 비정규화 저장 — 인사 변동에도 감사 기록 불변(의도, L3). */
@Table("request_approver")
data class RequestApprover(
    @Id val id: Long? = null,
    val approverId: String,
    val name: String,
    val role: String,
    val decision: ApproverDecision = ApproverDecision.PENDING,
    val decidedAt: Instant? = null,
)

/** append-only 감사 로그 (H6) — UPDATE·DELETE 금지. */
@Table("approval_event")
data class ApprovalEvent(
    @Id val id: Long? = null,
    val requestId: Long,
    val step: Int?,
    val actor: String,
    val action: ApprovalAction,
    val note: String? = null,
    val at: Instant,
)

@Table("query_review_event")
data class QueryReviewEvent(
    @Id val id: Long? = null,
    val queryId: Long,
    val actor: String,
    val decision: String,
    val note: String? = null,
    val sqlHash: String,
    val lintSnapshotJson: String,
    val at: Instant,
)
