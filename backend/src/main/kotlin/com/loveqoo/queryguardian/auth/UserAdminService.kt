package com.loveqoo.queryguardian.auth

import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import org.springframework.stereotype.Service
import java.time.Instant

data class UserDto(val id: String, val displayName: String, val title: String, val role: String, val enabled: Boolean)
data class TablePermDto(val tableName: String, val allowed: Boolean)
data class PermissionsDto(val userId: String, val serverAllowed: Boolean, val tables: List<TablePermDto>)
data class SavePermissionsRequest(val serverAllowed: Boolean = true, val tables: List<TablePermDto> = emptyList())
data class PermissionEventDto(val targetUserId: String, val actor: String, val scope: String,
                              val target: String, val beforeAllowed: Boolean?, val afterAllowed: Boolean, val at: Instant)

/**
 * 사용자·권한 관리 (spec 007 §4 H3·M2).
 * 쓰기는 ADMIN 전용이며 **자기 자신의 권한 행은 편집할 수 없다**(자기상향 방지). 변경은 append-only 감사에 남는다.
 */
@Service
class UserAdminService(
    private val users: AppUserRepository,
    private val serverPerms: UserServerPermissionRepository,
    private val tablePerms: UserTablePermissionRepository,
    private val events: PermissionChangeEventRepository,
    private val tables: CatalogTableRepository,
) {
    private val DEFAULT_SERVER = "mysql-prod"

    /** 전 인증 사용자 열람 가능 — 승인 라인 편성에 필요(H3 카브아웃). password_hash는 노출하지 않는다. */
    fun list(): List<UserDto> = users.findAll()
        .map { UserDto(it.id, it.displayName, it.title, it.role.name, it.enabled) }
        .sortedBy { it.id }

    fun permissions(userId: String): PermissionsDto {
        users.findById(userId).orElseThrow { NotFoundException("사용자 $userId 없음") }
        val serverAllowed = serverPerms.findByUserId(userId)
            .firstOrNull { it.serverKey == DEFAULT_SERVER }?.allowed ?: true // 행 부재 = 허용
        val explicit = tablePerms.findByUserId(userId).associateBy { it.tableName.lowercase() }
        val rows = tables.findAll().map { t ->
            TablePermDto(t.name, explicit[t.name.lowercase()]?.allowed ?: true)
        }.sortedBy { it.tableName }
        return PermissionsDto(userId, serverAllowed, rows)
    }

    fun updatePermissions(actor: AppUser, userId: String, request: SavePermissionsRequest): PermissionsDto {
        if (actor.role != Role.ADMIN) throw ForbiddenException("권한 관리는 ADMIN만 가능합니다")
        if (actor.id == userId) {
            throw ForbiddenException("CANNOT_EDIT_OWN_PERMISSION: 자기 자신의 권한은 편집할 수 없습니다")
        }
        users.findById(userId).orElseThrow { NotFoundException("사용자 $userId 없음") }
        val before = permissions(userId)

        // 서버 토글
        if (before.serverAllowed != request.serverAllowed) {
            serverPerms.findByUserId(userId).filter { it.serverKey == DEFAULT_SERVER }.forEach { serverPerms.delete(it) }
            serverPerms.save(UserServerPermission(userId = userId, serverKey = DEFAULT_SERVER, allowed = request.serverAllowed))
            audit(userId, actor.id, "SERVER", DEFAULT_SERVER, before.serverAllowed, request.serverAllowed)
        }
        // 테이블 권한: 명시적 차단만 행으로 남긴다(행 부재 = 허용)
        val beforeByTable = before.tables.associate { it.tableName.lowercase() to it.allowed }
        tablePerms.findByUserId(userId).forEach { tablePerms.delete(it) }
        request.tables.forEach { row ->
            if (!row.allowed) tablePerms.save(UserTablePermission(userId = userId, tableName = row.tableName, allowed = false))
            val was = beforeByTable[row.tableName.lowercase()] ?: true
            if (was != row.allowed) audit(userId, actor.id, "TABLE", row.tableName, was, row.allowed)
        }
        return permissions(userId)
    }

    fun history(userId: String): List<PermissionEventDto> = events.findAll()
        .filter { it.targetUserId == userId }
        .sortedByDescending { it.at }
        .map { PermissionEventDto(it.targetUserId, it.actor, it.scope, it.target, it.beforeAllowed, it.afterAllowed, it.at) }

    private fun audit(target: String, actor: String, scope: String, name: String, before: Boolean?, after: Boolean) {
        events.save(PermissionChangeEvent(targetUserId = target, actor = actor, scope = scope,
            target = name, beforeAllowed = before, afterAllowed = after, at = Instant.now()))
    }
}
