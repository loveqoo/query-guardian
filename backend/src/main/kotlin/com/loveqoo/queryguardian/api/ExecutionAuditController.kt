package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.exec.ExecutionAudit
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 실행 감사 조회 (STEWARD/ADMIN 전용) — **저장 쿼리와 분리된** 경로다.
 *
 * 쿼리별 이력(`GET /api/queries/{id}/executions`)만 있던 동안 두 가지가 깨져 있었다:
 * 쿼리를 지우면 그 실행 기록이 404가 되어 행위자가 감사를 은닉할 수 있었고, 미리보기 기록은
 * `query_id`가 null이라 어떤 API로도 볼 수 없었다(적대 검토 HIGH). 감사는 대상 행의 생사와 무관해야 한다.
 */
@RestController
@RequestMapping("/api/executions")
class ExecutionAuditController(
    private val audit: ExecutionAudit,
    private val auth: AuthService,
) {
    /**
     * @param before 커서 — 이 id보다 **앞선**(오래된) 기록. 200건 상한만으로는 새 기록을 쌓아
     *   옛 기록을 조회 범위 밖으로 밀어낼 수 있다(적대 검토 D3). 응답 헤더 `X-QG-Audit-Total`에 전체 건수.
     */
    @GetMapping
    fun recent(
        http: HttpServletRequest,
        response: jakarta.servlet.http.HttpServletResponse,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) outcome: String?,
        @RequestParam(required = false) before: Long?,
    ): List<ExecutionEventDto> {
        auth.requireRole(http, Role.STEWARD, Role.ADMIN)
        response.setHeader("X-QG-Audit-Total", audit.total().toString())
        return audit.recent(actor, outcome?.uppercase(), before).map {
            ExecutionEventDto(
                id = it.id!!, actor = it.actor, outcome = it.outcome,
                rowCount = it.rowCount, elapsedMs = it.elapsedMs, effectiveLimit = it.effectiveLimit, configuredCap = it.configuredCap, moreRowsExist = it.moreRowsExist,
                errorCode = it.errorCode,
                // STEWARD/ADMIN 전용 경로이므로 원문을 준다 (§6)
                errorDetail = it.errorDetail,
                rewrittenSql = it.rewrittenSql, at = it.at,
            )
        }
    }
}
