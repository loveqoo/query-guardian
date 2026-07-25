package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.PermissionEventDto
import com.loveqoo.queryguardian.auth.PermissionsDto
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.auth.SavePermissionsRequest
import com.loveqoo.queryguardian.auth.UserAdminService
import com.loveqoo.queryguardian.auth.UserDto
import com.loveqoo.queryguardian.catalog.CatalogService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val admin: UserAdminService,
    private val auth: AuthService,
) {
    /** 전 인증 사용자 — 승인 라인 편성 후보 목록 (spec 007 H3 카브아웃). */
    @GetMapping
    fun list(): List<UserDto> = admin.list()

    /** 본인 또는 ADMIN. */
    @GetMapping("/{id}/permissions")
    fun permissions(@PathVariable id: String, http: HttpServletRequest): PermissionsDto {
        val me = auth.currentUser(http)
        if (me.id != id && me.role != Role.ADMIN) {
            throw ForbiddenException("본인 또는 ADMIN만 조회할 수 있습니다")
        }
        return admin.permissions(id)
    }

    @PutMapping("/{id}/permissions", consumes = ["application/json"])
    fun updatePermissions(
        @PathVariable id: String,
        http: HttpServletRequest,
        @RequestBody request: SavePermissionsRequest,
    ): PermissionsDto = admin.updatePermissions(auth.currentUser(http), id, request)

    @GetMapping("/{id}/permissions/history")
    fun history(@PathVariable id: String, http: HttpServletRequest): List<PermissionEventDto> {
        auth.requireRole(http, Role.ADMIN)
        return admin.history(id)
    }
}

/** 탐색기·요청 피커용 — 전 테이블 + accessible 플래그, 비허용은 컬럼 생략 (spec 007 §6.3). */
@RestController
@RequestMapping("/api/my")
class MyTablesController(
    private val catalogService: CatalogService,
    private val access: AccessControl,
    private val auth: AuthService,
) {
    data class MyTableDto(
        val id: Long?, val name: String, val description: String?,
        val accessible: Boolean, val columns: List<ColumnDto>,
    )

    @GetMapping("/tables")
    fun tables(http: HttpServletRequest): List<MyTableDto> {
        val me = auth.currentUser(http)
        return catalogService.listTables().map { t ->
            val ok = access.isTableAllowed(me.id, t.name)
            MyTableDto(t.id, t.name, t.description, ok, if (ok) t.columns else emptyList())
        }
    }
}
