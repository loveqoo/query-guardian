package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.api.AccessBlockedDto
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import jakarta.servlet.http.HttpServletRequest
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
    private val auth: AuthService,
    private val access: AccessControl,
    private val parser: DialectParser,
) {
    /**
     * purposeCode는 클라이언트가 아니라 **승인 요청에서 주입**한다 (spec 005 C1).
     * 에디터 디바운스 lint와 저장 게이트가 같은 purposeCode를 써야 "lint 통과 → 저장 422"가 안 생긴다.
     */
    @PostMapping
    fun lint(http: HttpServletRequest, @RequestBody request: LintRequest): LintReportDto {
        validation.validateDialect(request.dialect)
        val user = auth.currentUser(http)

        // 게이트 순서 (spec 007 §6.0): 인증 → 데이터 권한 → 룰. 권한이 없으면 룰 결과를 주지 않는다.
        when (val parsed = parser.parse(request.sql)) {
            is ParseResult.Success -> access.checkTables(user.id, approvalGate.physicalTables(parsed.ir))
            is ParseResult.Failure -> { /* 파싱 실패는 룰 게이트가 위반으로 보고 */ }
        }

        // requestId는 세션 principal이 요청자인 것만 허용 (H2) — 남의 승인으로 purpose를 훔쳐 정찰하지 못하게
        val approval = request.requestId?.let { approvalGate.findRequest(it) }
        if (approval != null && approval.requester != user.id) {
            throw AccessBlockedException(AccessBlockedDto(
                "REQUESTER_MISMATCH", "본인이 요청한 승인만 사용할 수 있습니다."))
        }
        return LintReportDto.from(lintService.lint(request.sql, approval?.purposeCode))
    }
}
