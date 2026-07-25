package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.query.QueryExecutionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 재작성 미리보기 (spec 008 §7) — 저장 전에 "무엇이 자동 적용되는지"를 보여준다. **실행하지 않는다.**
 *
 * 실행 게이트와 같은 검사를 통과해야 한다. 그러지 않으면 미리보기가 게이트를 우회해 카탈로그를 캐는 창구가 된다 —
 * 응답에 적용될 강제식 원문이 담기므로 "어떤 컬럼이 MASK이고 마스크 식이 무엇인지"를 알아낼 수 있다.
 * 그래서 스펙은 이 API를 **권한 게이트가 붙는 마일스톤에서만** 노출하라고 못 박았다(M2).
 */
@RestController
@RequestMapping("/api/preview-rewrite")
class PreviewController(
    private val executionService: QueryExecutionService,
    private val auth: AuthService,
    private val validation: RequestValidation,
) {
    @PostMapping
    fun preview(http: HttpServletRequest, @RequestBody request: PreviewRewriteRequest): PreviewRewriteDto {
        request.dialect?.let { validation.validateDialect(it) }
        val me = auth.currentUser(http)
        val previewed = executionService.previewRewrite(request.sql, request.requestId, me.id)
        return PreviewRewriteDto(
            rewrittenSql = previewed.rewrittenSql,
            applied = previewed.applied.map { AppliedRewriteDto(it.kind.name, it.table, it.column, it.detail) },
            lintReport = previewed.report,
        )
    }
}
