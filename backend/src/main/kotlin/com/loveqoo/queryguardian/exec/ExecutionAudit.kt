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

/**
 * 실행 시도의 결말. **차단·오류도 기록한다** — 시도 자체가 감사 대상이다(spec 008 §6).
 * [PREVIEW]는 실행 없이 재작성만 보여준 경우다 — 데이터는 나가지 않지만 **어떤 강제식이 적용되는지**가
 * 노출되므로(카탈로그 오라클) 누가 무엇을 미리 봤는지는 남긴다.
 */
enum class ExecutionOutcome { SUCCESS, BLOCKED, ERROR, PREVIEW }

@Table("execution_event")
data class ExecutionEvent(
    @Id val id: Long? = null,
    /** 미리보기는 저장된 쿼리가 없어 null이다. */
    val queryId: Long? = null,
    val actor: String,
    val outcome: String,
    val originalSql: String,
    val rewrittenSql: String? = null,
    val appliedJson: String? = null,
    val rowCount: Int? = null,
    val elapsedMs: Long? = null,
    val effectiveLimit: Long? = null,
    val configuredCap: Long? = null,
    val moreRowsExist: Boolean? = null,
    /** 사용자에게 보여줄 분류 코드(TIMEOUT·SQL_ERROR·게이트 코드). */
    val errorCode: String? = null,
    /** 원문 — STEWARD/ADMIN 전용. MySQL 오류는 데이터 값을 에코하므로 일반 사용자에게 주지 않는다. */
    val errorDetail: String? = null,
    val at: Instant,
)

interface ExecutionEventRepository : CrudRepository<ExecutionEvent, Long> {
    fun findByQueryIdOrderByIdDesc(queryId: Long): List<ExecutionEvent>

    // 커서 페이징(`id < before`) — 상한만 있고 커서가 없으면 새 기록을 쌓아 **옛 기록을 조회 범위 밖으로
    // 밀어낼 수 있다**(적대 검토 D3). 그러면 삭제로 은닉하던 것과 같은 결말에 다른 문으로 도달한다.
    fun findTop200ByIdLessThanOrderByIdDesc(before: Long): List<ExecutionEvent>
    fun findTop200ByActorAndIdLessThanOrderByIdDesc(actor: String, before: Long): List<ExecutionEvent>
    fun findTop200ByOutcomeAndIdLessThanOrderByIdDesc(outcome: String, before: Long): List<ExecutionEvent>
    fun findTop200ByActorAndOutcomeAndIdLessThanOrderByIdDesc(
        actor: String,
        outcome: String,
        before: Long,
    ): List<ExecutionEvent>
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
        queryId: Long?,
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
            effectiveLimit = result?.effectiveLimit,
            configuredCap = result?.configuredCap,
            moreRowsExist = result?.moreRowsExist,
            errorCode = errorCode,
            errorDetail = errorDetail,
            at = Instant.now(),
        )
    )

    fun historyOf(queryId: Long): List<ExecutionEvent> = repository.findByQueryIdOrderByIdDesc(queryId)

    /**
     * **저장 쿼리와 무관한** 감사 조회 (적대 검토 HIGH).
     *
     * 유일한 읽기 경로가 `queries.visible(queryId)`를 지나던 동안 두 가지가 깨졌다:
     * ⑴ 쿼리를 지우면 그 실행 기록이 404가 되어 **행위자 스스로 감사를 은닉**할 수 있었다
     * ⑵ PREVIEW 기록은 `query_id`가 null이라 **어떤 API로도 볼 수 없었다**(write-only 감사).
     * append-only 감사가 대상 행의 생사에 매달려 있으면 통제 수단이 아니다.
     *
     * **커서 페이징이 필수다.** "최근 200건"만 주던 동안, 미리보기를 200번 호출해(요청당 감사 1행)
     * 옛 SUCCESS 기록을 조회 범위 밖으로 밀어낼 수 있었다 — 저장은 남지만 아무도 볼 수 없으니
     * 삭제 은닉과 결말이 같다(적대 검토 D3).
     */
    fun recent(actor: String?, outcome: String?, before: Long?): List<ExecutionEvent> {
        // 커서는 "이 id보다 앞"이다. 없으면 맨 앞부터 — Long.MAX_VALUE로 같은 질의를 쓴다.
        val cursor = before ?: Long.MAX_VALUE
        return when {
            actor != null && outcome != null ->
                repository.findTop200ByActorAndOutcomeAndIdLessThanOrderByIdDesc(actor, outcome, cursor)
            actor != null -> repository.findTop200ByActorAndIdLessThanOrderByIdDesc(actor, cursor)
            outcome != null -> repository.findTop200ByOutcomeAndIdLessThanOrderByIdDesc(outcome, cursor)
            else -> repository.findTop200ByIdLessThanOrderByIdDesc(cursor)
        }
    }

    /** 전체 건수 — 목록이 상한에 걸렸는지(더 있는지) 화면·감사자가 알 수 있어야 한다. */
    fun total(): Long = repository.count()
}
