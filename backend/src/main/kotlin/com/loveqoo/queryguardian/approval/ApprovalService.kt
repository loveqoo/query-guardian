package com.loveqoo.queryguardian.approval

import com.loveqoo.queryguardian.api.ApprovalDetailDto
import com.loveqoo.queryguardian.api.ApprovalEventDto
import com.loveqoo.queryguardian.api.ApprovalSummaryDto
import com.loveqoo.queryguardian.api.ApproverDto
import com.loveqoo.queryguardian.api.ConflictException
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.RuleSnapshotDto
import com.loveqoo.queryguardian.api.SaveApprovalRequest
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.auth.AppUserRepository
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.catalog.CatalogPurposeRepository
import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import com.loveqoo.queryguardian.rules.RuleRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ApprovalService(
    private val requests: ApprovalRequestRepository,
    private val events: ApprovalEventRepository,
    private val purposes: CatalogPurposeRepository,
    private val tables: CatalogTableRepository,
    private val rules: RuleRepository,
    private val appUsers: AppUserRepository,
    private val access: AccessControl,
) {
    private val MAX_STEPS = 10

    // ---- 조회 ----

    fun list(status: String?, requester: String?): List<ApprovalSummaryDto> =
        requests.findAll()
            .filter { status == null || it.status.name == status.uppercase() }
            .filter { requester == null || it.requester == requester }
            .map { toSummary(it) }

    fun get(id: Long): ApprovalDetailDto {
        val r = load(id)
        val eventDtos = events.findAll().filter { it.requestId == id }
            .sortedBy { it.at }
            .map { ApprovalEventDto(it.step, it.actor, it.action.name, it.note, it.at) }
        val ruleDtos = r.rules.map { snap ->
            val current = rules.findById(snap.ruleId).orElse(null)
            RuleSnapshotDto(
                ruleId = snap.ruleId, ruleName = snap.ruleName,
                severitySummary = snap.severitySummary, forced = snap.forced,
                // 삭제되었거나 트리가 달라졌으면 변경됨 (H2 배지)
                changedSinceApproval = current == null || current.treeJson != snap.treeJsonSnapshot,
            )
        }
        return ApprovalDetailDto(toSummary(r), ruleDtos, eventDtos)
    }

    /** 에디터 요청 선택용 — 승인된(사용 가능한) 요청 (§7 usable). */
    fun usable(requester: String): List<ApprovalSummaryDto> =
        requests.findAll().filter { it.status == RequestStatus.APPROVED && it.requester == requester }.map { toSummary(it) }

    /** 저장 게이트가 쓰는 원본 조회. */
    fun findEntity(id: Long): ApprovalRequest? = requests.findById(id).orElse(null)

    // ---- 생성 ----

    fun create(actor: String, request: SaveApprovalRequest): ApprovalDetailDto {
        requireNotNull(appUsers.findById(actor).orElse(null)) { "등록되지 않은 사용자: $actor" }
        require(request.purposeTitle.isNotBlank() && request.purposeTitle.length <= 200) { "목적 제목은 1~200자여야 합니다" }
        require(purposes.findByCode(request.purposeCode) != null) { "등록되지 않은 purpose: ${request.purposeCode}" }

        // 대상 테이블: 카탈로그에서 선택만 (자유 입력 금지, C2)
        require(request.tables.isNotEmpty()) { "대상 테이블을 1개 이상 선택하세요" }
        request.tables.forEach {
            require(tables.findByNameIgnoreCase(it.tableName) != null) { "카탈로그에 없는 테이블: ${it.tableName}" }
            // 권한 없는 테이블은 요청에 담을 수 없다 (spec 007 §6.1)
            require(access.isTableAllowed(actor, it.tableName)) { "권한이 없는 테이블은 요청에 담을 수 없습니다: ${it.tableName}" }
        }
        request.businessReqs.forEach { require(Directory.hasBusinessReq(it)) { "등록되지 않은 비즈니스 요건: $it" } }

        // 승인 라인 무결성 (C3·C4)
        val approvers = request.approvers.sortedBy { it.step }
        require(approvers.isNotEmpty()) { "승인자를 1명 이상 지정하세요" }
        require(approvers.size <= MAX_STEPS) { "승인 단계는 최대 ${MAX_STEPS}단계입니다" }
        require(approvers.map { it.step } == (1..approvers.size).toList()) { "승인 단계는 1부터 연속이어야 합니다" }
        require(approvers.map { it.approverId }.distinct().size == approvers.size) { "같은 승인자를 여러 단계에 지정할 수 없습니다" }
        // 자가 승인 금지 (spec 007 C1) — 풀 통합으로 열리는 구멍이라 불변식으로 막는다
        require(approvers.none { it.approverId == actor }) { "REQUESTER_IS_APPROVER: 본인을 자신의 승인 라인에 지정할 수 없습니다" }
        val resolved = approvers.map { input ->
            val person = appUsers.findById(input.approverId).orElse(null)
                ?: throw IllegalArgumentException("등록되지 않은 승인자: ${input.approverId}")
            // 승인자는 STEWARD/ADMIN만 (spec 007 §5)
            require(person.role == Role.STEWARD || person.role == Role.ADMIN) {
                "승인자는 STEWARD 또는 ADMIN이어야 합니다: ${input.approverId}(${person.role})"
            }
            // role 컬럼에는 직책(title)을 넣는다 — 감사 문자열 보존 (H4-a)
            RequestApprover(approverId = person.id, name = person.displayName, role = person.title)
        }

        // 규칙 내용 스냅샷 (H2) — 선택 규칙 + 항상 강제되는 규칙
        val selected = request.ruleIds.toSet()
        val snapshots = rules.findAll().filter { it.enabled }.mapNotNull { rule ->
            val id = rule.id ?: return@mapNotNull null
            val isSelected = selected.contains(id)
            // 강제(forced) = 요청자가 선택하지 않았어도 판정에 항상 적용됨 (§6)
            if (!isSelected && !rule.enabled) null
            else RequestRule(
                ruleId = id, ruleName = rule.name,
                severitySummary = if (rule.enabled) "ACTIVE" else "DISABLED",
                treeJsonSnapshot = rule.treeJson, forced = !isSelected,
            )
        }

        val now = Instant.now()
        val saved = requests.save(ApprovalRequest(
            purposeTitle = request.purposeTitle, purposeCode = request.purposeCode,
            requester = actor, status = RequestStatus.PENDING, currentStep = 1, submittedAt = now,
            tables = request.tables.map { RequestTable(db = it.db, tableName = it.tableName) },
            rules = snapshots,
            businessReqs = request.businessReqs.map { RequestBusinessReq(code = it) },
            approvers = resolved,
        ))
        logEvent(saved.id!!, null, actor, ApprovalAction.SUBMIT, null)
        return get(saved.id)
    }

    // ---- 전이 (원자적, C4) ----

    fun approve(id: Long, actor: String, note: String?): ApprovalDetailDto = transition(id) { r ->
        val step = r.currentStep
        val approver = r.approvers.firstOrNull { indexStep(r, it) == step }
            ?: throw ConflictException("승인 단계 정보가 없습니다")
        if (approver.approverId != actor) throw ConflictException("현재 승인 단계(${step})의 승인자가 아닙니다: $actor")
        if (approver.decision != ApproverDecision.PENDING) throw ConflictException("이미 결정된 단계입니다")

        val now = Instant.now()
        val updatedApprovers = r.approvers.map {
            if (indexStep(r, it) == step) it.copy(decision = ApproverDecision.APPROVED, decidedAt = now) else it
        }
        val isLast = step >= r.approvers.size
        logEvent(id, step, actor, ApprovalAction.APPROVE, note)
        r.copy(
            approvers = updatedApprovers,
            currentStep = if (isLast) step else step + 1,
            status = if (isLast) RequestStatus.APPROVED else RequestStatus.PENDING,
            decidedAt = if (isLast) now else null,
        )
    }

    fun reject(id: Long, actor: String, note: String?): ApprovalDetailDto = transition(id) { r ->
        val step = r.currentStep
        val approver = r.approvers.firstOrNull { indexStep(r, it) == step }
            ?: throw ConflictException("승인 단계 정보가 없습니다")
        if (approver.approverId != actor) throw ConflictException("현재 승인 단계(${step})의 승인자가 아닙니다: $actor")
        if (approver.decision != ApproverDecision.PENDING) throw ConflictException("이미 결정된 단계입니다")

        val now = Instant.now()
        logEvent(id, step, actor, ApprovalAction.REJECT, note)
        r.copy(
            approvers = r.approvers.map {
                if (indexStep(r, it) == step) it.copy(decision = ApproverDecision.REJECTED, decidedAt = now) else it
            },
            status = RequestStatus.REJECTED, decidedAt = now,
        )
    }

    fun cancel(id: Long, actor: String): ApprovalDetailDto = transition(id) { r ->
        if (r.requester != actor) throw ConflictException("요청자만 취소할 수 있습니다")
        logEvent(id, r.currentStep, actor, ApprovalAction.CANCEL, null)
        r.copy(status = RequestStatus.CANCELLED, decidedAt = Instant.now())
    }

    /**
     * PENDING 상태에서만 전이하며, @Version 낙관적 잠금으로 동시 전이를 차단한다 (C4).
     * 경합 시 OptimisticLockingFailureException → 409.
     */
    private fun transition(id: Long, mutate: (ApprovalRequest) -> ApprovalRequest): ApprovalDetailDto {
        val current = load(id)
        if (current.status != RequestStatus.PENDING) {
            throw ConflictException("이미 ${current.status} 상태인 요청입니다")
        }
        try {
            requests.save(mutate(current))
        } catch (e: OptimisticLockingFailureException) {
            throw ConflictException("다른 사용자가 먼저 처리했습니다. 새로고침 후 다시 시도하세요.")
        }
        return get(id)
    }

    private fun load(id: Long): ApprovalRequest =
        requests.findById(id).orElseThrow { NotFoundException("승인 요청 $id 없음") }

    /** MappedCollection keyColumn=step은 0-based 인덱스로 저장되므로 1-based 단계로 환산. */
    private fun indexStep(r: ApprovalRequest, approver: RequestApprover): Int = r.approvers.indexOf(approver) + 1

    private fun logEvent(requestId: Long, step: Int?, actor: String, action: ApprovalAction, note: String?) {
        events.save(ApprovalEvent(requestId = requestId, step = step, actor = actor, action = action, note = note, at = Instant.now()))
    }

    private fun toSummary(r: ApprovalRequest) = ApprovalSummaryDto(
        id = r.id!!, purposeTitle = r.purposeTitle, purposeCode = r.purposeCode, requester = r.requester,
        status = r.status.name, currentStep = r.currentStep,
        tables = r.tables.map { it.tableName },
        businessReqs = r.businessReqs.map { it.code },
        approvers = r.approvers.mapIndexed { idx, a -> ApproverDto(idx + 1, a.approverId, a.name, a.role, a.decision.name, a.decidedAt) },
        submittedAt = r.submittedAt, decidedAt = r.decidedAt,
    )
}
