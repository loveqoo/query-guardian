package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.auth.AuthService
import com.loveqoo.queryguardian.auth.Role
import com.loveqoo.queryguardian.catalog.GovernedServer
import com.loveqoo.queryguardian.catalog.ServerDescriptor
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

    // ---- servers ----

    /**
     * 통제 대상 서버 목록. 오늘은 하나다([GovernedServer]).
     *
     * 화면이 이것을 묻는 이유: 예전에는 규칙 편집 화면이 **디자인 샘플의 서버 셋**을 고르게 했다.
     * 없는 서버를 고를 수 있었고, 호스트·노드 수까지 사실인 양 보여 줬다.
     * 목록을 서버가 주면 **화면이 아는 서버 = 실제로 있는 서버**가 된다.
     */
    @GetMapping("/servers")
    fun listServers(http: HttpServletRequest): List<ServerDescriptor> { steward(http); return GovernedServer.ALL }

    // ---- purposes ----

    /**
     * **전 인증 사용자 열람 가능 — 의도적 카브아웃이다**(spec 014 L10에서 확인).
     *
     * 이 컨트롤러의 다른 조회는 전부 `steward`를 먼저 부르므로 오랫동안 **누락으로 기록돼 있었다**
     * (백로그 `D-I`). 조이려다 실측으로 뒤집혔다: 승인 요청 작성은 **ANALYST의 기능**이고
     * 요청서에는 목적 코드가 필수다(`ApprovalsPage`의 "새 요청 작성"). steward로 조이면
     * 분석가가 요청서를 만들 수 없다 — **통제가 아니라 고장이 된다.**
     *
     * 노출되는 것은 목적의 **이름과 설명**뿐이다. 어느 목적에 어떤 제약이 붙었는지는
     * 이 응답에 없다(그쪽이 `/mappings`이고 그것은 steward 전용이다).
     *
     * 다만 **인증은 요구한다** — 예전에는 `http`를 받지도 않아 그 축조차 없었다.
     * `/api/users`·`/api/directory`의 H3 카브아웃과 같은 성격이다(spec 007).
     */
    @GetMapping("/purposes")
    fun listPurposes(http: HttpServletRequest): List<PurposeDto> { auth.currentUser(http); return catalogService.listPurposes() }

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
