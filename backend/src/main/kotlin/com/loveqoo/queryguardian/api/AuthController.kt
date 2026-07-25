package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/login", consumes = ["application/json"])
    fun login(request: HttpServletRequest, @RequestBody body: LoginRequest): MeDto =
        authService.login(request, body.userId, body.password).let { MeDto(it.id, it.displayName, it.title, it.role.name) }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): Map<String, String> {
        authService.logout(request)
        return mapOf("message" to "로그아웃되었습니다")
    }

    @GetMapping("/me")
    fun me(request: HttpServletRequest): MeDto =
        authService.currentUser(request).let { MeDto(it.id, it.displayName, it.title, it.role.name) }
}
