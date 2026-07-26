package com.loveqoo.queryguardian.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.loveqoo.queryguardian.api.ConflictException
import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.QueryDto
import com.loveqoo.queryguardian.api.QuerySummaryDto
import com.loveqoo.queryguardian.api.ReviewRequest
import com.loveqoo.queryguardian.api.SaveQueryRequest
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.approval.Directory
import com.loveqoo.queryguardian.approval.QueryReviewEvent
import com.loveqoo.queryguardian.approval.QueryReviewEventRepository
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.rules.RuleService
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

@Service
class QueryService(
    private val lintService: LintService,
    private val repository: SavedQueryRepository,
    private val objectMapper: ObjectMapper,
    private val ruleService: RuleService,
    private val approvalGate: ApprovalGate,
    private val steps: GateSteps,
    private val reviewEvents: QueryReviewEventRepository,
) {
    /** 저장 게이트: 룰(422) 선행 → 승인 검사(403) (spec 005 §4, H4). */
    fun save(actor: String, request: SaveQueryRequest): QueryDto {
        validateName(request)
        val storable = gate(actor, request)
        val approval = storable.approval
        val now = Instant.now()
        val saved = repository.save(
            SavedQuery(
                name = request.name, dialect = request.dialect, sqlText = request.sql,
                purposeCode = approval.purposeCode,           // 클라이언트 입력 무시, 요청에서 주입 (C1)
                requestId = approval.id!!,
                reviewStatus = ReviewStatus.PENDING_REVIEW.name,
                lintReportJson = objectMapper.writeValueAsString(storable.report),
                createdAt = now, updatedAt = now,
            )
        )
        return toDto(saved)
    }

    /**
     * 수정: **소유권 검사** + 게이트 재실행 + 검토 상태 리셋(C5 — 검토 도장을 단 채 본문이 바뀌는 구멍 차단).
     *
     * 소유권 검사가 없던 동안 **남의 쿼리를 탈취**할 수 있었다(적대 검토 CRITICAL, 실측):
     * 공격자가 자기 승인 요청 id로 PUT하면 게이트는 *공격자의* 승인을 검증해 통과시키고, 저장 시 `request_id`가
     * 덮여 소유권이 넘어갔다. 그러면 피해자는 자기 쿼리를 잃고(403), 공격자는 피해자의 재작성 SQL·조건 상수·
     * 행위자 이름을 실행 이력으로 읽고 삭제까지 했다. M2-1이 조회·삭제만 닫고 **수정을 빠뜨린 것**이 원인이다.
     *
     * 그래서 두 겹으로 막는다: ⑴ 소유자만 수정할 수 있다 ⑵ **`request_id`는 바꿀 수 없다** —
     * 소유자 정의가 그 컬럼에 걸려 있으므로 갱신을 허용하는 것 자체가 소유권 이전이다.
     *
     * **`privileged`를 받지 않는다.** 처음엔 조회와 대칭으로 STEWARD/ADMIN에게 열었는데, 실제로 탈취가
     * 막힌 이유는 게이트가 `REQUESTER_MISMATCH`에 걸리는 **우연**이었다 — 나중에 게이트가 특권 역할을
     * 면제하면 탈취가 되살아난다(적대 검토 D7). 결정 14가 대행 *실행*을 불허하는데 대행 *수정*을 허용할
     * 이유는 없다. 검토는 읽기로 하고, 고치는 것은 소유자가 한다.
     */
    fun update(id: Long, actor: String, request: SaveQueryRequest): QueryDto {
        val existing = visible(id, actor, privileged = false)
        if (request.requestId != null && request.requestId != existing.requestId) {
            throw ForbiddenException(
                "저장된 쿼리의 근거 승인 요청은 바꿀 수 없습니다 — 다른 요청으로 저장하려면 새 쿼리로 저장하세요",
            )
        }
        validateName(request)
        // 게이트는 **원래 요청 id**로 재실행한다(클라이언트가 보낸 것을 신뢰하지 않는다)
        val storable = gate(actor, request.copy(requestId = existing.requestId))
        val approval = storable.approval
        val saved = repository.save(
            existing.copy(
                name = request.name, dialect = request.dialect, sqlText = request.sql,
                purposeCode = approval.purposeCode, requestId = approval.id!!,
                reviewStatus = ReviewStatus.PENDING_REVIEW.name,   // 항상 재검토로 리셋
                reviewer = null, reviewedAt = null, reviewNote = null,
                lintReportJson = objectMapper.writeValueAsString(storable.report),
                updatedAt = Instant.now(),
            )
        )
        return toDto(saved)
    }

    /**
     * 검토 결정 (spec 005 §3.2). 결정 직전 **재-lint**(C6): 현재 규칙 기준 BLOCK이면 승인 불가(409).
     * 자가 검토(요청자 == 검토 actor)는 409 — 스텁 identity라 우회 가능하나 정책을 코드로 표현(M3).
     */
    fun review(id: Long, actor: String, request: ReviewRequest): QueryDto {
        requireNotNull(Directory.findAnyone(actor)) { "등록되지 않은 행위자: $actor" }
        val existing = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        val decision = ReviewStatus.entries.firstOrNull { it.name == request.decision.uppercase() && it != ReviewStatus.PENDING_REVIEW }
            ?: throw IllegalArgumentException("decision은 APPROVED 또는 REJECTED여야 합니다")

        val approval = approvalGate.findRequest(existing.requestId)
        if (approval != null && approval.requester == actor) {
            throw ConflictException("본인이 요청한 쿼리는 스스로 검토할 수 없습니다")
        }

        // 재-lint: 저장 시점 스냅샷이 아니라 현재 규칙·카탈로그로 판정
        val current = LintReportDto.from(lintService.lint(existing.sqlText, existing.purposeCode))
        if (decision == ReviewStatus.APPROVED && current.blocked) {
            throw ConflictException("현재 규칙 기준으로 위반이 있어 검토 승인할 수 없습니다: " +
                current.violations.filter { it.severity.name == "BLOCK" }.joinToString("; ") { it.message })
        }

        val now = Instant.now()
        val saved = repository.save(existing.copy(
            reviewStatus = decision.name, reviewer = actor, reviewedAt = now, reviewNote = request.note,
            lintReportJson = objectMapper.writeValueAsString(current), // 재-lint 결과로 갱신
        ))
        reviewEvents.save(QueryReviewEvent(
            queryId = id, actor = actor, decision = decision.name, note = request.note,
            sqlHash = sha256(existing.sqlText),
            lintSnapshotJson = objectMapper.writeValueAsString(current), at = now,
        ))
        return toDto(saved)
    }

    /**
     * 목록은 **본인 것만** (STEWARD/ADMIN은 전체 — 검토가 그들의 일이다).
     *
     * 이 스코프가 없으면 로그인한 아무나 남의 쿼리 본문을 읽는다. SQL 본문에는 "누가 무엇을 조사하는지"와
     * 조건에 쓴 상수가 담기므로 **열람 자체가 유출**이고, 실행을 막아도 그것은 막히지 않는다
     * (spec 005 §5가 M2 선행 조건으로 예고한 지점, 결정 15).
     */
    fun list(actor: String, privileged: Boolean): List<QuerySummaryDto> =
        repository.findAll()
            .filter { privileged || ownerOf(it) == actor }
            .map {
                QuerySummaryDto(it.id!!, it.name, it.dialect, it.purposeCode, it.requestId, it.reviewStatus,
                    it.reviewer, it.createdAt, it.updatedAt)
            }

    fun get(id: Long, actor: String, privileged: Boolean): QueryDto = toDto(visible(id, actor, privileged))

    fun delete(id: Long, actor: String, privileged: Boolean) {
        visible(id, actor, privileged) // 남의 쿼리를 지울 수도 없다
        repository.deleteById(id)
    }

    /**
     * 쿼리의 소유자 = **근거 승인 요청의 요청자**. 저장 게이트가 `requester == actor`를 강제하므로
     * (ApprovalGate `REQUESTER_MISMATCH`) 이 정의가 저장 시점 행위자와 일치한다.
     * 소유자를 별도 컬럼으로 복제하지 않는 이유: 두 값이 어긋날 여지를 만들지 않는다.
     */
    private fun ownerOf(query: SavedQuery): String? = approvalGate.findRequest(query.requestId)?.requester

    /** 없으면 404, 남의 것이면 403. 존재 여부를 숨기지 않는다 — id는 순번이므로 숨겨도 의미가 없다. */
    internal fun visible(id: Long, actor: String, privileged: Boolean): SavedQuery {
        val query = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        if (!privileged && ownerOf(query) != actor) {
            throw ForbiddenException("본인이 저장한 쿼리만 조회할 수 있습니다")
        }
        return query
    }

    // ---- 게이트 (spec 005 §4 — 실행 순서: 룰 422 → 승인 403) ----

    /**
     * 저장 게이트 — **실행 게이트와 같은 단계 단위**([GateSteps])를 쓰되 **순서는 저장의 것**이다.
     *
     * 순서가 다른 것은 정책이다: 저장은 룰 422가 승인 403보다 앞서고(spec 005 H4), 실행은 신원 검사가
     * 판정보다 앞선다(남의 쿼리 판정 결과를 흘리지 않기 위해). 그래서 [GateSteps]는 단계만 주고
     * 조립은 각 게이트가 한다.
     *
     * 예전에는 이 절차가 손으로 한 번 더 적혀 있었고 **이미 갈라져 있었다** — 여기서만 파싱을 두 번 했다
     * (`parser.parse()` 한 번, `lintService.lint(sql)` 안에서 또 한 번). 그래서 "판정과 재작성이 같은
     * AST를 쓴다"(spec 008 결정 13)가 실행 게이트에서만 성립했다.
     */
    private fun gate(actor: String, request: SaveQueryRequest): Storable {
        // purposeCode는 클라이언트 입력이 아니라 승인 요청에서 주입한다 (C1). 요청이 없으면 null로 lint 후 403.
        val purposeCode = request.requestId?.let { approvalGate.findRequest(it)?.purposeCode }
        val ctx = GateRequest(
            queryId = null, requestId = request.requestId,
            purposeCode = purposeCode, sql = request.sql, actor = actor,
        )

        // 데이터 권한이 룰보다 **앞**이다 (spec 007 §6.0) — 권한 없는 사용자에게 위반 메시지를 주지 않는다.
        val judged = steps.parseOnce(ctx)
            .then(steps::checkAccess)
            .then(steps::judgeRules)

        // 룰 hit 통계는 **차단된 쿼리도 포함**한다 — "무엇이 자주 걸리는가"가 통계의 목적이므로
        // 걸린 것을 빼면 목적이 뒤집힌다. 그래서 통과·차단 양쪽에서 보고서를 꺼내 기록한다.
        judged.judgedReport()?.let(::recordRuleHits)

        // 승인 검사(요청 존재·승인·요청자·테이블 커버)가 **판정 뒤**에 오는 것이 저장의 정책이다.
        return judged.then(steps::requireApproval).orThrowWithoutAudit()
    }

    /**
     * 이름 길이는 **요청 검증**이지 거버넌스 판정이 아니다 — 그래서 게이트 줄기 밖에 있다.
     *
     * 예전에는 [gate] 첫 줄이었고, 그 결과 20줄 안에 실패 문법이 셋 나란히 있었다
     * (`require` 400 · `GateStop` · 생짜 `approvalGate.check` 403). 무엇이 게이트 검사이고 무엇이
     * 입력 검증인지 읽어서는 갈리지 않았다. `GateStop`으로 바꾸지 않은 이유: 그러면 새 `AuditCode`가
     * 생기고 A0 전수 검증이 따라붙는다 — 없앨 수 있는 것에 감사 어휘를 늘리지 않는다.
     *
     * 호출 위치는 예전과 같다(저장은 게이트 직전, 수정은 소유권 검사 **뒤**) — 순서가 바뀌면
     * 남의 쿼리에 잘못된 이름으로 PUT했을 때 403이 400으로 바뀐다.
     */
    private fun validateName(request: SaveQueryRequest) {
        require(request.name.isNotBlank() && request.name.length <= 100) { "이름은 1~100자여야 합니다" }
    }

    /**
     * **감사 없이** 경계로 내보낸다 — 저장 게이트 전용이라 `private`이다.
     *
     * 저장은 실행이 아니므로 `execution_event`를 남기지 않는다(감사의 대상은 실행 시도다).
     * 공개 확장으로 두었더니 실행 게이트에서 `orRaise(request)` 대신 이것을 쓸 수 있었고, 그러면
     * **응답은 같은데 감사만 조용히 사라진다** — 이 저장소에서 실제로 일어났던 사고("403인데 감사 0건")의
     * 정확한 형태다. 게다가 이름이 짧고 인자가 없어 더 싸 보였다. 이름과 가시성으로 그 길을 닫는다.
     */
    private fun <T> GateOutcome<T>.orThrowWithoutAudit(): T = when (this) {
        is GateOutcome.Cleared -> value
        is GateOutcome.Stopped -> stop.raise()
    }

    /** 규칙 hit 통계 (spec 004 §7 개정 — 권한 통과 후에만 기록). */
    private fun recordRuleHits(report: LintReportDto) {
        val ruleIds = report.violations
            .filter { it.ruleId.startsWith("rule/") }
            .mapNotNull { it.ruleId.removePrefix("rule/").toLongOrNull() }
            .toSet()
        if (ruleIds.isNotEmpty()) ruleService.recordHits(ruleIds)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun toDto(saved: SavedQuery): QueryDto = QueryDto(
        id = saved.id!!, name = saved.name, dialect = saved.dialect, sql = saved.sqlText,
        purposeCode = saved.purposeCode, requestId = saved.requestId,
        reviewStatus = saved.reviewStatus, reviewer = saved.reviewer, reviewedAt = saved.reviewedAt,
        reviewNote = saved.reviewNote,
        lintReport = objectMapper.readValue(saved.lintReportJson),
        createdAt = saved.createdAt, updatedAt = saved.updatedAt,
    )
}
