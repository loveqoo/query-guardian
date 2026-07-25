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
        // 대행 수정 불허 — privileged를 넘기지 않는다(결정 14의 대칭, 적대 검토 D7)
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
    fun list(http: HttpServletRequest): List<QuerySummaryDto> {
        val me = auth.currentUser(http)
        return queryService.list(me.id, privileged(me))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long, http: HttpServletRequest): QueryDto {
        val me = auth.currentUser(http)
        return queryService.get(id, me.id, privileged(me))
    }

    /** STEWARD·ADMIN은 검토가 업무이므로 전체를 본다. 그 외는 본인 것만 (spec 008 결정 15). */
    private fun privileged(user: com.loveqoo.queryguardian.auth.AppUser): Boolean =
        user.role == Role.STEWARD || user.role == Role.ADMIN

    /**
     * 저장·검토 승인된 쿼리 실행 (spec 008 §7). **요청자 본인만**(결정 14 — 대행 실행 불허).
     * 실행 직전에 권한·접수·룰을 현재 기준으로 다시 판정하고, 차단·오류도 감사에 남는다.
     */
    @PostMapping("/{id}/execute")
    fun execute(@PathVariable id: Long, http: HttpServletRequest): ExecutionResultDto {
        val me = auth.currentUser(http)
        val executed = executionService.execute(id, me.id, privileged(me))
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
        val me = auth.currentUser(http)
        val canSeeRawErrors = privileged(me)
        return executionService.history(id, me.id, canSeeRawErrors).map {
            ExecutionEventDto(
                id = it.id!!, actor = it.actor, outcome = it.outcome,
                rowCount = it.rowCount, elapsedMs = it.elapsedMs, effectiveLimit = it.effectiveLimit, configuredCap = it.configuredCap, moreRowsExist = it.moreRowsExist,
                errorCode = it.errorCode,
                errorDetail = if (canSeeRawErrors) it.errorDetail else null,
                rewrittenSql = it.rewrittenSql, at = it.at,
            )
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, http: HttpServletRequest) {
        val me = auth.currentUser(http)
        queryService.delete(id, me.id, privileged(me))
    }
}
