package com.loveqoo.queryguardian.auth

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.Instant

/** 기능 접근 축 (spec 007 §5). 데이터 접근(권한)과는 별도 축 — ADMIN도 데이터 권한을 우회하지 않는다. */
enum class Role { ANALYST, STEWARD, ADMIN }

/**
 * `title`은 직책("마케팅본부장")이고 `role`은 기능 역할(열거형)이다 — 혼동 금지 (spec 007 H4-a).
 * 승인 기록(request_approver.role)에는 title을 넣어 인사 변동에도 감사 문자열이 보존된다.
 */
@Table("app_user")
data class AppUser(
    @Id val id: String,
    val displayName: String,
    val title: String,
    val role: Role,
    val passwordHash: String,
    val enabled: Boolean = true,
)

/** 서버 단위 on/off (디자인의 DB 스위치). 행 부재 = 허용 (default-allow, spec 007 §3.1). */
@Table("user_server_permission")
data class UserServerPermission(
    @Id val id: Long? = null,
    val userId: String,
    val serverKey: String,
    val allowed: Boolean,
)

/** 테이블 단위 허용. 행 부재 = 허용, 명시적 false만 차단. 권한 키는 테이블명 단독 (C3). */
@Table("user_table_permission")
data class UserTablePermission(
    @Id val id: Long? = null,
    val userId: String,
    val tableName: String,
    val allowed: Boolean,
)

/** append-only 권한 변경 감사 (spec 007 M2). */
@Table("permission_change_event")
data class PermissionChangeEvent(
    @Id val id: Long? = null,
    val targetUserId: String,
    val actor: String,
    val scope: String,   // SERVER | TABLE
    val target: String,
    val beforeAllowed: Boolean?,
    val afterAllowed: Boolean,
    val at: Instant,
)

interface AppUserRepository : CrudRepository<AppUser, String>

interface UserServerPermissionRepository : CrudRepository<UserServerPermission, Long> {
    fun findByUserId(userId: String): List<UserServerPermission>
}

interface UserTablePermissionRepository : CrudRepository<UserTablePermission, Long> {
    fun findByUserId(userId: String): List<UserTablePermission>

    @Query("SELECT * FROM user_table_permission WHERE user_id = :userId AND LOWER(table_name) = LOWER(:tableName)")
    fun find(@Param("userId") userId: String, @Param("tableName") tableName: String): UserTablePermission?
}

interface PermissionChangeEventRepository : CrudRepository<PermissionChangeEvent, Long>
