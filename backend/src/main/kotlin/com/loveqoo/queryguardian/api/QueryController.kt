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
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun save(http: HttpServletRequest, @RequestBody request: SaveQueryRequest): QueryDto {
        validation.validateDialect(request.dialect)
        return queryService.save(auth.currentUser(http).id, request)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, http: HttpServletRequest, @RequestBody request: SaveQueryRequest): QueryDto {
        validation.validateDialect(request.dialect)
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, http: HttpServletRequest) {
        val me = auth.currentUser(http)
        queryService.delete(id, me.id, privileged(me))
    }
}
