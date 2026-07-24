package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.rules.RuleService
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
class RuleController(private val ruleService: RuleService) {

    @GetMapping
    fun list(): List<RuleDto> = ruleService.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): RuleDetailDto = ruleService.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: SaveRuleRequest): RuleDetailDto = ruleService.create(request)

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: SaveRuleRequest): RuleDetailDto =
        ruleService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = ruleService.delete(id)

    @PostMapping("/{id}/test")
    fun test(@PathVariable id: Long): Map<String, String> =
        mapOf("message" to "테스트 실행은 후속 스펙에서 구현됩니다")
}
