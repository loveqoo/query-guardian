package com.loveqoo.queryguardian.config

import com.loveqoo.queryguardian.api.UnauthenticatedException
import com.loveqoo.queryguardian.auth.AuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 인증 인터셉터 (spec 007 §4).
 * - 공개 경로는 `POST /api/auth/login` 단 하나. 그 외 api 경로는 전부 미인증 401.
 * - `X-QG-Actor` 헤더가 오면 400 — spec 005의 스텁 identity를 전역에서 차단(우회 경로 없음).
 */
@Configuration
class WebSecurityConfig(private val authService: AuthService) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (request.getHeader("X-QG-Actor") != null) {
                    response.status = HttpServletResponse.SC_BAD_REQUEST
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write("""{"message":"X-QG-Actor 헤더는 더 이상 지원되지 않습니다. 세션 로그인을 사용하세요."}""")
                    return false
                }
                val path = request.requestURI
                val isLogin = path == "/api/auth/login" && request.method == "POST"
                if (isLogin) return true
                authService.currentUser(request) // 미인증이면 UnauthenticatedException → 401
                return true
            }
        }).addPathPatterns("/api/**")
    }
}
