package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.query.QueryService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queries")
class QueryController(
    private val queryService: QueryService,
    private val validation: RequestValidation,
    private val auth: AuthService,
    private val executionService: com.loveqoo.queryguardian.query.QueryExecutionService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun save(http: HttpServletRequest, @RequestBody request: SaveQueryRequest): QueryDto {
        validation.validateDialect(request.dialect)
        validation.validateSql(request.sql)
        return queryService.save(auth.currentUser(http).id, request)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, http: HttpServletRequest, @RequestBody request: SaveQueryRequest): QueryDto {
        validation.validateDialect(request.dialect)
        validation.validateSql(request.sql)
        // 대행 수정 불허 — 능력(Viewer)을 넘기지 않는다(결정 14의 대칭, 적대 검토 D7).
        // `update`의 시그니처가 Viewer를 받지 않으므로 여기서 넘길 방법 자체가 없다.
        return queryService.update(id, auth.currentUser(http).id, request)
    }

    /** 쿼리 검토 (spec 005 §3.2) — 결정 직전 재-lint, 현재 BLOCK이면 409. */
    @PostMapping("/{id}/review")
    fun review(
        @PathVariable id: Long,
        http: HttpServletRequest,
        @RequestBody request: ReviewRequest,
    ): QueryDto = queryService.review(id, auth.requireRole(http, Role.STEWARD, Role.ADMIN).id, request)

    @GetMapping
    fun list(http: HttpServletRequest): List<QuerySummaryDto> = queryService.list(auth.currentViewer(http))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long, http: HttpServletRequest): QueryDto =
        queryService.get(id, auth.currentViewer(http))

    /**
     * 저장·검토 승인된 쿼리 실행 (spec 008 §7). **요청자 본인만**(결정 14 — 대행 실행 불허).
     * 실행 직전에 권한·접수·룰을 현재 기준으로 다시 판정하고, 차단·오류도 감사에 남는다.
     */
    @PostMapping("/{id}/execute")
    fun execute(@PathVariable id: Long, http: HttpServletRequest): ExecutionResultDto {
        val executed = executionService.execute(id, auth.currentViewer(http))
        return ExecutionResultDto(
            columns = executed.result.columns.map { ExecutionColumnDto(it.name, it.type) },
            rows = executed.result.rows,
            rowCount = executed.result.rowCount,
            elapsedMs = executed.result.elapsedMs,
            effectiveLimit = executed.result.effectiveLimit,
            configuredCap = executed.result.configuredCap,
            moreRowsExist = executed.result.moreRowsExist,
            rewrittenSql = executed.rewrittenSql,
            applied = executed.applied.map { AppliedRewriteDto(it.kind.name, it.table, it.column, it.detail) },
        )
    }

    /** 실행 이력. 오류 **원문**은 STEWARD/ADMIN에게만 — 일반 사용자에게는 분류 코드까지만(§6). */
    @GetMapping("/{id}/executions")
    fun executions(@PathVariable id: Long, http: HttpServletRequest): List<ExecutionEventDto> {
        // 능력이 **두 질문에 각각** 답한다 — 예전엔 boolean 하나가 두 정책을 겸직했다(행 스코프 + 원문 노출).
        val viewer = auth.currentViewer(http)
        return executionService.history(id, viewer).map {
            ExecutionEventDto(
                id = it.id!!, actor = it.actor, outcome = it.outcome,
                rowCount = it.rowCount, elapsedMs = it.elapsedMs, effectiveLimit = it.effectiveLimit, configuredCap = it.configuredCap, moreRowsExist = it.moreRowsExist,
                errorCode = it.errorCode,
                errorDetail = if (viewer.seesRawErrors) it.errorDetail else null,
                rewrittenSql = it.rewrittenSql, at = it.at,
            )
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, http: HttpServletRequest) {
        queryService.delete(id, auth.currentViewer(http))
    }
}
