package com.loveqoo.queryguardian.query

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

/**
 * 쿼리 검토 상태 (spec 005 §3.2). 실행 게이트가 `APPROVED`만 통과시킨다(spec 008 §5).
 *
 * **영속 필드도 이 타입이다**(spec 010 I13). 예전에는 `String`이었고, 그래서 도메인 코드가
 * `reviewStatus != ReviewStatus.APPROVED.name`처럼 **문자열로 비교**했다 — 오타가 컴파일을 통과하는
 * 비교이고, 실행 차단이 그 비교 하나에 걸려 있었다. 집 안에 이미 선례가 있었다(`ApprovalRequest.status`).
 */
enum class ReviewStatus { PENDING_REVIEW, APPROVED, REJECTED }

@Table("saved_query")
data class SavedQuery(
    @Id val id: Long? = null,
    val name: String,
    val dialect: String,
    @Column("sql_text") val sqlText: String,
    val purposeCode: String? = null,
    /** 근거 승인 요청 (APPROVED 필수) — spec 005 §4. */
    val requestId: Long,
    val reviewStatus: ReviewStatus = ReviewStatus.PENDING_REVIEW,
    val reviewer: String? = null,
    val reviewedAt: Instant? = null,
    val reviewNote: String? = null,
    val lintReportJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SavedQueryRepository : CrudRepository<SavedQuery, Long>
