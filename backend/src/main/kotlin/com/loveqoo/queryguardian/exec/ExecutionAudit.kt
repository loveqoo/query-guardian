package com.loveqoo.queryguardian.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.ir.AppliedRewrite
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 실행 시도의 결말. **차단·오류도 기록한다** — 시도 자체가 감사 대상이다(spec 008 §6). */
enum class ExecutionOutcome { SUCCESS, BLOCKED, ERROR }

@Table("execution_event")
data class ExecutionEvent(
    @Id val id: Long? = null,
    val queryId: Long,
    val actor: String,
    val outcome: String,
    val originalSql: String,
    val rewrittenSql: String? = null,
    val appliedJson: String? = null,
    val rowCount: Int? = null,
    val elapsedMs: Long? = null,
    val truncated: Boolean = false,
    /** 사용자에게 보여줄 분류 코드(TIMEOUT·SQL_ERROR·게이트 코드). */
    val errorCode: String? = null,
    /** 원문 — STEWARD/ADMIN 전용. MySQL 오류는 데이터 값을 에코하므로 일반 사용자에게 주지 않는다. */
    val errorDetail: String? = null,
    val at: Instant,
)

interface ExecutionEventRepository : CrudRepository<ExecutionEvent, Long> {
    fun findByQueryIdOrderByIdDesc(queryId: Long): List<ExecutionEvent>
}

/**
 * 실행 감사 (spec 008 §6).
 *
 * **`REQUIRES_NEW`로 쓴다.** 실행 커넥션은 읽기 전용이라 감사에 쓸 수 없고, 실행이 타임아웃으로 롤백돼도
 * "시도했다"는 기록은 살아남아야 한다. 그리고 게이트 차단은 예외 핸들러가 아니라 **차단 지점에서 기록 후 throw**다 —
 * 핸들러에 맡기면 어떤 경로가 어디서 막혔는지가 흐려진다.
 */
@Service
class ExecutionAudit(
    private val repository: ExecutionEventRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        queryId: Long,
        actor: String,
        outcome: ExecutionOutcome,
        originalSql: String,
        rewrittenSql: String? = null,
        applied: List<AppliedRewrite>? = null,
        result: ExecutionResult? = null,
        errorCode: String? = null,
        errorDetail: String? = null,
    ): ExecutionEvent = repository.save(
        ExecutionEvent(
            queryId = queryId,
            actor = actor,
            outcome = outcome.name,
            originalSql = originalSql,
            rewrittenSql = rewrittenSql,
            // 적용된 강제식 **원문**을 남긴다 — STEWARD가 마스크 식을 약화시켜도 사후 탐지가 가능해야 한다(§6)
            appliedJson = applied?.let { objectMapper.writeValueAsString(it) },
            rowCount = result?.rowCount,
            elapsedMs = result?.elapsedMs,
            truncated = result?.truncated ?: false,
            errorCode = errorCode,
            errorDetail = errorDetail,
            at = Instant.now(),
        )
    )

    fun historyOf(queryId: Long): List<ExecutionEvent> = repository.findByQueryIdOrderByIdDesc(queryId)
}
