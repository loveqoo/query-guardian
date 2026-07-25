package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.approval.ApprovalService
import com.loveqoo.queryguardian.approval.Directory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 승인 요청 API (spec 005 §7).
 * actor는 헤더 `X-QG-Actor` — **인증되지 않은 스텁 identity이며 접근 통제가 아니다**(§5).
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalController(private val approvals: ApprovalService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) requester: String?,
    ): List<ApprovalSummaryDto> = approvals.list(status, requester)

    @GetMapping("/usable")
    fun usable(@RequestHeader(ACTOR) actor: String): List<ApprovalSummaryDto> = approvals.usable(actor)

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApprovalDetailDto = approvals.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader(ACTOR) actor: String, @RequestBody request: SaveApprovalRequest): ApprovalDetailDto =
        approvals.create(actor, request)

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long, @RequestHeader(ACTOR) actor: String, @RequestBody(required = false) body: DecisionRequest?): ApprovalDetailDto =
        approvals.approve(id, actor, body?.note)

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long, @RequestHeader(ACTOR) actor: String, @RequestBody(required = false) body: DecisionRequest?): ApprovalDetailDto =
        approvals.reject(id, actor, body?.note)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @RequestHeader(ACTOR) actor: String): ApprovalDetailDto =
        approvals.cancel(id, actor)

    companion object { const val ACTOR = "X-QG-Actor" }
}

/** 관리형 디렉터리 (spec 005 §7) — 화이트리스트 검증의 근거이자 화면 select 소스. */
@RestController
@RequestMapping("/api/directory")
class DirectoryController {

    @GetMapping("/users")
    fun users(): List<DirectoryPersonDto> = Directory.users.map { DirectoryPersonDto(it.id, it.name, it.role) }

    @GetMapping("/approvers")
    fun approvers(): List<DirectoryPersonDto> = Directory.approvers.map { DirectoryPersonDto(it.id, it.name, it.role) }

    @GetMapping("/business-reqs")
    fun businessReqs(): List<BusinessReqDto> =
        Directory.businessReqs.map { BusinessReqDto(it.code, it.label, it.desc) }
}
