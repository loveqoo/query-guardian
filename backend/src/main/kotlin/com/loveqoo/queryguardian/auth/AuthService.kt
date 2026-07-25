package com.loveqoo.queryguardian.auth

import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.UnauthenticatedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

/**
 * 세션 인증 (spec 007 §4). 주체는 세션 principal뿐 — `X-QG-Actor` 헤더는 인터셉터가 400으로 거부한다.
 * 매 요청 principal의 enabled·role을 **DB에서 재조회**한다(세션에 role 캐싱 금지) → 비활성화·역할 변경 즉시 반영.
 */
@Service
class AuthService(private val users: AppUserRepository) {

    private val encoder = BCryptPasswordEncoder()

    companion object {
        const val SESSION_KEY = "qg.userId"
    }

    /** 로그인. 실패 사유(사용자 없음/비밀번호 불일치/비활성)를 구분하지 않고 동일 401. */
    fun login(request: HttpServletRequest, userId: String, rawPassword: String): AppUser {
        val user = users.findById(userId).orElse(null)
        val ok = user != null && user.enabled && encoder.matches(rawPassword, user.passwordHash)
        if (!ok) throw UnauthenticatedException("아이디 또는 비밀번호가 올바르지 않습니다")
        // 세션 고정 공격 방지 (H8): 기존 세션이 있을 때만 id를 회전한다.
        // changeSessionId()는 세션이 없으면 IllegalStateException을 던지므로 첫 로그인에서 500이 된다.
        if (request.getSession(false) != null) request.changeSessionId()
        request.session.setAttribute(SESSION_KEY, user!!.id)
        return user
    }

    fun logout(request: HttpServletRequest) {
        request.getSession(false)?.invalidate()
    }

    /** 현재 사용자. 미인증이면 401. 매 호출 DB 재조회이므로 enabled·role 변경이 즉시 반영된다. */
    fun currentUser(request: HttpServletRequest): AppUser {
        val userId = request.getSession(false)?.getAttribute(SESSION_KEY) as? String
            ?: throw UnauthenticatedException("로그인이 필요합니다")
        val user = users.findById(userId).orElse(null)
            ?: throw UnauthenticatedException("로그인이 필요합니다")
        if (!user.enabled) throw UnauthenticatedException("로그인이 필요합니다")
        return user
    }

    fun requireRole(request: HttpServletRequest, vararg allowed: Role): AppUser {
        val user = currentUser(request)
        if (user.role !in allowed) {
            throw ForbiddenException("이 작업에는 ${allowed.joinToString("/") { it.name }} 권한이 필요합니다 (현재 ${user.role})")
        }
        return user
    }

    fun isSteward(user: AppUser) = user.role == Role.STEWARD || user.role == Role.ADMIN

    fun hash(rawPassword: String): String = encoder.encode(rawPassword)
}
