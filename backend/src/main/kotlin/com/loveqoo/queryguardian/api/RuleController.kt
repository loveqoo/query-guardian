package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.rules.RuleService
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
@RequestMapping("/api/rules")
class RuleController(
    private val ruleService: RuleService,
    private val auth: AuthService,
) {
    private fun steward(http: HttpServletRequest) = auth.requireRole(http, Role.STEWARD, Role.ADMIN)

    @GetMapping
    fun list(): List<RuleDto> = ruleService.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long, http: HttpServletRequest): RuleDetailDto { steward(http); return ruleService.get(id) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(http: HttpServletRequest, @RequestBody request: SaveRuleRequest): RuleDetailDto { steward(http); return ruleService.create(request) }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, http: HttpServletRequest, @RequestBody request: SaveRuleRequest): RuleDetailDto {
        steward(http); return ruleService.update(id, request)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long, http: HttpServletRequest) { steward(http); ruleService.delete(id) }

    @PostMapping("/{id}/test")
    fun test(@PathVariable id: Long, http: HttpServletRequest): Map<String, String> {
        steward(http)
        return mapOf("message" to "테스트 실행은 후속 스펙에서 구현됩니다")
    }
}
