package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.catalog.CatalogService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
class CatalogController(
    private val catalogService: CatalogService,
    private val auth: AuthService,
    private val access: AccessControl,
) {
    private fun steward(http: HttpServletRequest) = auth.requireRole(http, Role.STEWARD, Role.ADMIN)

    // ---- tables ----

    @GetMapping("/tables")
    fun listTables(http: HttpServletRequest): List<TableDto> { steward(http); return catalogService.listTables() }

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTable(http: HttpServletRequest, @RequestBody request: SaveTableRequest): TableDto { steward(http); return catalogService.createTable(request) }

    @PutMapping("/tables/{id}")
    fun updateTable(@PathVariable id: Long, http: HttpServletRequest, @RequestBody request: SaveTableRequest): TableDto {
        steward(http); return catalogService.updateTable(id, request)
    }

    @DeleteMapping("/tables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTable(@PathVariable id: Long, http: HttpServletRequest) { steward(http); catalogService.deleteTable(id) }

    // ---- constraint defs (spec 002 §5.3) ----

    @GetMapping("/defs")
    fun listDefs(http: HttpServletRequest): List<DefDto> { steward(http); return catalogService.listDefs() }

    @PostMapping("/defs")
    @ResponseStatus(HttpStatus.CREATED)
    fun createDef(http: HttpServletRequest, @RequestBody request: SaveDefRequest): DefDto { steward(http); return catalogService.createDef(request) }

    @PutMapping("/defs/{id}")
    fun updateDef(@PathVariable id: Long, http: HttpServletRequest, @RequestBody request: SaveDefRequest): DefDto {
        steward(http); return catalogService.updateDef(id, request)
    }

    @DeleteMapping("/defs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDef(@PathVariable id: Long, http: HttpServletRequest) { steward(http); catalogService.deleteDef(id) }

    // ---- mappings ----

    @GetMapping("/mappings")
    fun listMappings(
        http: HttpServletRequest,
        @RequestParam(required = false) tableId: Long?,
        @RequestParam(required = false) columnId: Long?,
        @RequestParam(required = false) defId: Long?,
    ): List<MappingDto> { steward(http); return catalogService.listMappings(tableId, columnId, defId) }

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMapping(http: HttpServletRequest, @RequestBody request: SaveMappingRequest): MappingDto { steward(http); return catalogService.createMapping(request) }

    @DeleteMapping("/mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMapping(@PathVariable id: Long, http: HttpServletRequest) { steward(http); catalogService.deleteMapping(id) }

    // ---- purposes ----

    @GetMapping("/purposes")
    fun listPurposes(): List<PurposeDto> = catalogService.listPurposes()

    @PostMapping("/purposes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPurpose(http: HttpServletRequest, @RequestBody request: PurposeDto): PurposeDto { steward(http); return catalogService.createPurpose(request) }

    @DeleteMapping("/purposes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePurpose(@PathVariable id: Long, http: HttpServletRequest) { steward(http); catalogService.deletePurpose(id) }

    // ---- schema ----

    /** 자동완성 사전 — **허용 테이블만** (spec 007 §6.3). 판정 카탈로그는 이 필터와 무관하다(§6.4). */
    @GetMapping("/schema")
    fun schema(http: HttpServletRequest): Map<String, List<String>> {
        val me = auth.currentUser(http)
        return catalogService.schema().filterKeys { access.isTableAllowed(me.id, it) }
    }
}
