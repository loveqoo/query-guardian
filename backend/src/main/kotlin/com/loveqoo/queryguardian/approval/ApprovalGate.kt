package com.loveqoo.queryguardian.approval

import com.loveqoo.queryguardian.api.ApprovalBlockedDto
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

    fun check(requestId: Long?, actor: String, ir: QueryIR): ApprovalRequest {
        if (requestId == null) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                "NO_REQUEST", "승인된 요청을 선택해야 쿼리를 저장할 수 있습니다."))
        }
        val request = approvals.findEntity(requestId)
            ?: throw ApprovalBlockedException(ApprovalBlockedDto(
                "NO_REQUEST", "승인 요청 $requestId 를 찾을 수 없습니다.", requestId))

        if (request.status != RequestStatus.APPROVED) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                "NOT_APPROVED", "승인되지 않은 요청입니다 (현재 ${request.status}).", requestId, request.status.name))
        }
        // 신원 검사 — 스텁 identity이므로 접근 통제가 아님 (§5)
        if (request.requester != actor) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                "REQUESTER_MISMATCH", "본인이 요청한 승인만 사용할 수 있습니다.", requestId, request.status.name))
        }

        val approved = request.tables.map { it.tableName.lowercase() }.toSet()
        val used = physicalTables(ir)
        val uncovered = (used - approved).sorted()
        if (uncovered.isNotEmpty()) {
            throw ApprovalBlockedException(ApprovalBlockedDto(
                "TABLES_NOT_COVERED",
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
