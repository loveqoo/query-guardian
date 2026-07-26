package com.loveqoo.queryguardian.approval

import com.loveqoo.queryguardian.api.ApprovalBlockedDto
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.SelectScope
import org.springframework.stereotype.Component

/** 승인 차단 (spec 005 §7) — 룰 차단(422)과 구분되는 403. */
class ApprovalBlockedException(val detail: ApprovalBlockedDto) : RuntimeException(detail.message)

/**
 * 저장 게이트의 승인 검사 (spec 005 §4.2~4.4). 룰 게이트(422)가 **선행**한 뒤 호출된다 (H4).
 * fail-closed: 요청 없음·미승인·범위 초과는 전부 차단.
 */
@Component
class ApprovalGate(private val approvals: ApprovalService) {

    /** 조회 전용 — purposeCode 주입(C1)에 사용. 없으면 null(게이트가 이후 403 처리). */
    fun findRequest(id: Long): ApprovalRequest? = approvals.findEntity(id)

    /**
     * **존재 + 요청자 본인** 확인 — 신원 게이트용.
     *
     * [check]가 하는 네 검사 중 앞의 둘만 떼어낸 것이다. 실행 게이트가 이것을 **판정보다 먼저** 부른다:
     * 대행 실행 불허(spec 008 결정 14)는 신원 문제이고, 신원 검사가 판정 뒤에 오면 STEWARD가 남의 쿼리
     * id를 넣어 보는 것만으로 그 쿼리의 판정 결과(어떤 컬럼이 PII·BLOCK인지)를 받아 간다.
     *
     * 그 뒤 [check]가 **같은 둘을 다시 본다.** 중복이 아니라 **완전 재판정**이다(spec 008 §5) —
     * 앞선 검사와 실제 사용 사이에 상태가 뒤집힐 수 있고, 예전에 소유권 탈취로 `request_id`가 바뀔 수
     * 있었던 적이 있다. 판정 로직은 여기 한 벌만 두되, 검사 시점은 둘로 남긴다.
     */
    fun requireOwned(requestId: Long?, actor: String): ApprovalRequest {
        val request = requestId?.let { approvals.findEntity(it) }
            ?: throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.NO_REQUEST, "근거 승인 요청을 찾을 수 없습니다.", requestId))
        if (request.requester != actor) {
            // 열람 자격이 없으면 요청의 존재·상태를 확정해 주지 않는다([check]와 같은 기준).
            throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.REQUESTER_MISMATCH, "본인이 요청한 승인만 사용할 수 있습니다."))
        }
        return request
    }

    fun check(requestId: Long?, actor: String, ir: QueryIR): ApprovalRequest {
        if (requestId == null) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.NO_REQUEST, "승인된 요청을 선택해야 쿼리를 저장할 수 있습니다."))
        }
        val request = approvals.findEntity(requestId)
            ?: throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.NO_REQUEST, "승인 요청 $requestId 를 찾을 수 없습니다.", requestId))

        // 차단 응답에 **요청의 상태를 담을지**는 열람 자격에 따른다. 예전에는 무조건 담아서,
        // `GET /api/approvals/{id}`가 404로 숨기는 요청의 존재·상태·단계를 이 403 본문이 확정해 줬다
        // (적대 검토 D6). 남의 requestId를 넣어보는 것만으로 조직 내부 상태를 열거할 수 있었다.
        val mayKnow = request.requester == actor || request.approvers.any { it.approverId == actor }
        val knownId = if (mayKnow) requestId else null
        val knownStatus = if (mayKnow) request.status.name else null

        if (request.status != RequestStatus.APPROVED) {
            val detail = if (mayKnow) " (현재 ${request.status})" else ""
            throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.NOT_APPROVED, "승인되지 않은 요청입니다$detail.", knownId, knownStatus))
        }
        // 신원 검사 — 스텁 identity이므로 접근 통제가 아님 (§5)
        if (request.requester != actor) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.REQUESTER_MISMATCH, "본인이 요청한 승인만 사용할 수 있습니다.", knownId, knownStatus))
        }

        val approved = request.tables.map { it.tableName.lowercase() }.toSet()
        val used = physicalTables(ir)
        val uncovered = (used - approved).sorted()
        if (uncovered.isNotEmpty()) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                AuditCode.TABLES_NOT_COVERED,
                "승인 범위에 없는 테이블을 참조했습니다: ${uncovered.joinToString(", ")}",
                requestId, request.status.name, uncovered))
        }
        return request
    }

    /**
     * 루트 + 모든 자손 스코프의 **물리** 테이블 합집합 (spec 005 §4.1, C2).
     * CTE/파생 alias(physical=false)는 제외 — 그 본문의 물리 테이블은 자식 스코프에서 잡힌다.
     * 이 재귀가 없으면 `WITH x AS (SELECT ... FROM users) SELECT ... FROM x` 한 줄로 승인 범위를 우회할 수 있다.
     */
    fun physicalTables(ir: QueryIR): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(scope: SelectScope) {
            scope.tables.filter { it.physical }.forEach { out += it.name.lowercase() }
            scope.children.forEach(::walk)
        }
        walk(ir.root)
        return out
    }
}
