package com.loveqoo.queryguardian.query

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

/** 쿼리 검토 상태 (spec 005 §3.2). 이번 스펙에서는 표시·감사용 — 실행 차단은 실행 스펙에서. */
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
    val reviewStatus: String = ReviewStatus.PENDING_REVIEW.name,
    val reviewer: String? = null,
    val reviewedAt: Instant? = null,
    val reviewNote: String? = null,
    val lintReportJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SavedQueryRepository : CrudRepository<SavedQuery, Long>
