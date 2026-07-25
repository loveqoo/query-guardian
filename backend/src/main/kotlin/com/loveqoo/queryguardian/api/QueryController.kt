package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.query.QueryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queries")
class QueryController(
    private val queryService: QueryService,
    private val validation: RequestValidation,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun save(
        @RequestHeader(ApprovalController.ACTOR) actor: String,
        @RequestBody request: SaveQueryRequest,
    ): QueryDto {
        validation.validateDialect(request.dialect)
        return queryService.save(actor, request)
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestHeader(ApprovalController.ACTOR) actor: String,
        @RequestBody request: SaveQueryRequest,
    ): QueryDto {
        validation.validateDialect(request.dialect)
        return queryService.update(id, actor, request)
    }

    /** 쿼리 검토 (spec 005 §3.2) — 결정 직전 재-lint, 현재 BLOCK이면 409. */
    @PostMapping("/{id}/review")
    fun review(
        @PathVariable id: Long,
        @RequestHeader(ApprovalController.ACTOR) actor: String,
        @RequestBody request: ReviewRequest,
    ): QueryDto = queryService.review(id, actor, request)

    @GetMapping
    fun list(): List<QuerySummaryDto> = queryService.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): QueryDto = queryService.get(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = queryService.delete(id)
}
