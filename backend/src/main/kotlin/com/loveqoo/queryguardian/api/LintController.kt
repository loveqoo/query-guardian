package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.approval.ApprovalGate
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
    private val approvalGate: ApprovalGate,
) {
    /**
     * purposeCode는 클라이언트가 아니라 **승인 요청에서 주입**한다 (spec 005 C1).
     * 에디터 디바운스 lint와 저장 게이트가 같은 purposeCode를 써야 "lint 통과 → 저장 422"가 안 생긴다.
     */
    @PostMapping
    fun lint(@RequestBody request: LintRequest): LintReportDto {
        validation.validateDialect(request.dialect)
        val purposeCode = request.requestId?.let { approvalGate.findRequest(it)?.purposeCode }
        return LintReportDto.from(lintService.lint(request.sql, purposeCode))
    }
}
