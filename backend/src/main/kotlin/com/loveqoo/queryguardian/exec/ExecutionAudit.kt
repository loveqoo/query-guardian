package com.loveqoo.queryguardian.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.ir.AppliedRewrite
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Table("execution_event")
data class ExecutionEvent(
    @Id val id: Long? = null,
    /** 미리보기는 저장된 쿼리가 없어 null이다. */
    val queryId: Long? = null,
    val actor: String,
    val outcome: ExecutionOutcome,
    val originalSql: String,
    val rewrittenSql: String? = null,
    val appliedJson: String? = null,
    val rowCount: Int? = null,
    val elapsedMs: Long? = null,
    val effectiveLimit: Long? = null,
    val configuredCap: Long? = null,
    val moreRowsExist: Boolean? = null,
    /**
     * 사용자에게 보여줄 분류 코드. 값 집합은 [AuditCode]가 닫는다.
     *
     * **[outcome]은 enum인데 이것은 String인 이유**(spec 010 I13의 경계선): 도메인이 **분기하는 값은 enum,
     * 나르기만 하는 값은 문자열**이다. `outcome`은 등급 판단(반출 있음/없음)에 쓰이므로 분기하고,
     * `errorCode`는 응답과 감사로 옮겨질 뿐 어디서도 분기하지 않는다(실측: 프로덕션 분기 0곳).
     *
     * 그리고 감사는 **append-only 이력**이다 — 어휘에서 코드 하나를 지우는 순간 enum이면 그 값을 가진
     * 옛 행을 **읽을 수 없게 된다**. 이력을 읽지 못하게 만드는 통제는 통제가 아니다.
     */
    val errorCode: String? = null,
    /** 원문 — STEWARD/ADMIN 전용. MySQL 오류는 데이터 값을 에코하므로 일반 사용자에게 주지 않는다. */
    val errorDetail: String? = null,
    val at: Instant,
)

/**
 * **감사 이력은 쓰고 읽기만 한다** (spec 014 L7 · 백로그 `D-D`).
 *
 * 예전에는 `CrudRepository`를 상속해 `delete`·`deleteById`·`deleteAll`을 그대로 갖고 있었다.
 * 부르는 곳은 없었지만 **그것은 규약이지 보장이 아니다** — `AuditVocabulary`가 그 한계를 스스로
 * 적어 두었던 자리다. 감사를 지울 수 있으면 감사가 아니다.
 *
 * 그래서 **필요한 것만 선언한다.** 상속으로 딸려 오는 능력은 아무도 의도하지 않은 능력이다.
 * DB 쪽 트리거(`schema.sql`)와 **겹치는 방어**다 — 이쪽만 있으면 JDBC 직접 호출로 도달하고,
 * 저쪽만 있으면 코드가 시도했다가 런타임에 터진다. 둘 다 있어야 "할 수 없다"가 참이 된다.
 */
interface ExecutionEventRepository : Repository<ExecutionEvent, Long> {
    fun save(event: ExecutionEvent): ExecutionEvent
    fun findAll(): List<ExecutionEvent>
    fun count(): Long

    fun findByQueryIdOrderByIdDesc(queryId: Long): List<ExecutionEvent>

    // 커서 페이징(`id < before`) — 상한만 있고 커서가 없으면 새 기록을 쌓아 **옛 기록을 조회 범위 밖으로
    // 밀어낼 수 있다**(적대 검토 D3). 그러면 삭제로 은닉하던 것과 같은 결말에 다른 문으로 도달한다.
    fun findTop200ByIdLessThanOrderByIdDesc(before: Long): List<ExecutionEvent>
    fun findTop200ByActorAndIdLessThanOrderByIdDesc(actor: String, before: Long): List<ExecutionEvent>
    fun findTop200ByOutcomeAndIdLessThanOrderByIdDesc(outcome: ExecutionOutcome, before: Long): List<ExecutionEvent>
    fun findTop200ByActorAndOutcomeAndIdLessThanOrderByIdDesc(
        actor: String,
        outcome: ExecutionOutcome,
        before: Long,
    ): List<ExecutionEvent>
}

/** 감사 유실 경보가 가리켜야 할 대상 — 누가 무엇을 시도하다 기록을 잃었는가. */
interface AuditTarget {
    val actor: String
    val queryId: Long?
    val sqlByteLength: Int
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
    private val log = LoggerFactory.getLogger(ExecutionAudit::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        queryId: Long?,
        actor: String,
        outcome: ExecutionOutcome,
        originalSql: String,
        rewrittenSql: String? = null,
        applied: List<AppliedRewrite>? = null,
        result: ExecutionResult? = null,
        errorCode: AuditCode? = null,
        errorDetail: String? = null,
    ): ExecutionEvent = repository.save(
        ExecutionEvent(
            queryId = queryId,
            actor = actor,
            outcome = outcome,
            originalSql = originalSql,
            rewrittenSql = rewrittenSql,
            // 적용된 강제식 **원문**을 남긴다 — STEWARD가 마스크 식을 약화시켜도 사후 탐지가 가능해야 한다(§6)
            appliedJson = applied?.let { objectMapper.writeValueAsString(it) },
            rowCount = result?.rowCount,
            elapsedMs = result?.elapsedMs,
            effectiveLimit = result?.effectiveLimit,
            configuredCap = result?.configuredCap,
            moreRowsExist = result?.moreRowsExist,
            errorCode = errorCode?.name,
            errorDetail = errorDetail,
            at = Instant.now(),
        )
    )

    /**
     * **감사 기록 실패 자체가 경보 대상이다** (spec 010 I5).
     *
     * 반출이 없는 종결(차단·오류)에서는 기록이 실패해도 **원래 사유가 이긴다** — 감사 예외로 바꿔치면
     * 무엇이 실패했는지 잃기 때문이다. 그러나 그 대가로 "기록이 없다"는 사실이 조용해진다.
     * 조용해지면 "감사가 있다"는 이 제품의 전제가 무너지므로, 유실은 반드시 소리를 내야 한다.
     *
     * 대상 식별자(actor·queryId·SQL 바이트 수)를 함께 싣는다 — 없으면 "언젠가 무언가 유실됐다"만 남아
     * 유실 구간을 사후에 재구성할 수 없다. **SQL 본문은 싣지 않는다**: 로그는 감사 테이블보다 접근
     * 통제가 약하고, 본문을 남기려던 곳이 바로 지금 실패한 감사다.
     *
     * 여기서는 ERROR 로그까지만 한다 — 실제 알림 채널(페이지·슬랙) 연결은 운영의 몫이고,
     * `AUDIT_WRITE_FAILED`가 그 훅으로 쓰라고 남긴 고정 문자열이다.
     *
     * ⚠️ `@Transactional`을 붙이지 않는다. [record]의 `REQUIRES_NEW`는 **프록시 경계를 지날 때만**
     * 열리므로, 이 클래스 안에서 [record]를 자기호출로 감싸면 그 격리가 조용히 사라진다.
     * 등급 판단(기록이 선행 조건인가 best-effort인가)은 **호출자 쪽에** 둔다.
     */
    fun alertRecordFailure(cause: Throwable, outcome: ExecutionOutcome, code: AuditCode?, target: AuditTarget) {
        log.error(
            "AUDIT_WRITE_FAILED outcome={} code={} actor={} queryId={} sqlBytes={} — " +
                "감사 기록이 유실됐다(원래 사유는 응답에 보존됨)",
            outcome, code, target.actor, target.queryId, target.sqlByteLength, cause,
        )
    }

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
    fun recent(actor: String?, outcome: ExecutionOutcome?, before: Long?): List<ExecutionEvent> {
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
