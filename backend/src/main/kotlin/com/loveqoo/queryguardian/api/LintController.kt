package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.lint.LintService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/lint")
class LintController(
    private val lintService: LintService,
    private val validation: RequestValidation,
) {
    @PostMapping
    fun lint(@RequestBody request: LintRequest): LintReportDto {
        validation.validateDialect(request.dialect)
        validation.validatePurpose(request.purposeCode)
        return LintReportDto.from(lintService.lint(request.sql, request.purposeCode))
    }
}
