package com.loveqoo.queryguardian.auth

import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 사용자 부트스트랩 (spec 007 §3.2). 스펙의 data.sql 리터럴 해시 대신 **기동 시 idempotent 시딩**으로 구현한다
 * — 해시를 소스에 박지 않아도 되고(H9 "운영 반입 금지" 위험 감소) 테스트·데모가 같은 경로를 쓴다.
 * 데모 공통 비밀번호는 아래 상수 하나뿐이며 **운영에 반입 금지**.
 */
@Configuration
class UserBootstrap {

    @Bean
    fun seedUsers(
        users: AppUserRepository,
        auth: AuthService,
        jdbc: org.springframework.jdbc.core.JdbcTemplate,
    ): ApplicationRunner = ApplicationRunner {
        if (users.count() > 0L) return@ApplicationRunner
        val hash = auth.hash(DEMO_PASSWORD)
        listOf(
            AppUser("u1", "김도현", "데이터 분석가", Role.ANALYST, hash),
            AppUser("u2", "이서연", "데이터 분석가", Role.ANALYST, hash),
            AppUser("u3", "박민준", "데이터 엔지니어", Role.ANALYST, hash),
            AppUser("u4", "정하윤", "데이터 거버넌스", Role.STEWARD, hash),
            AppUser("ap1", "최지훈", "마케팅본부장", Role.STEWARD, hash),
            AppUser("ap2", "한도윤", "데이터플랫폼장", Role.STEWARD, hash),
            AppUser("ap3", "서준호", "정보보호책임자(CISO)", Role.STEWARD, hash),
            AppUser("ap4", "김영은", "최고데이터책임자(CDO)", Role.STEWARD, hash),
            AppUser("adm1", "시스템 관리자", "플랫폼 관리자", Role.ADMIN, hash),
        ).forEach { u ->
            // @Id가 String이라 save()는 UPDATE로 시도된다 → 명시 INSERT
            jdbc.update("INSERT INTO app_user (id, display_name, title, role, password_hash, enabled) VALUES (?,?,?,?,?,TRUE)",
                u.id, u.displayName, u.title, u.role.name, u.passwordHash)
        }
    }

    companion object {
        /** 데모 전용 — 운영 반입 금지. */
        const val DEMO_PASSWORD = "qg-demo"
    }
}
