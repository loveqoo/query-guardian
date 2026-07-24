package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.catalog.CatalogService
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
class CatalogController(private val catalogService: CatalogService) {

    // ---- tables ----

    @GetMapping("/tables")
    fun listTables(): List<TableDto> = catalogService.listTables()

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTable(@RequestBody request: SaveTableRequest): TableDto = catalogService.createTable(request)

    @PutMapping("/tables/{id}")
    fun updateTable(@PathVariable id: Long, @RequestBody request: SaveTableRequest): TableDto =
        catalogService.updateTable(id, request)

    @DeleteMapping("/tables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTable(@PathVariable id: Long) = catalogService.deleteTable(id)

    // ---- constraint defs (spec 002 §5.3) ----

    @GetMapping("/defs")
    fun listDefs(): List<DefDto> = catalogService.listDefs()

    @PostMapping("/defs")
    @ResponseStatus(HttpStatus.CREATED)
    fun createDef(@RequestBody request: SaveDefRequest): DefDto = catalogService.createDef(request)

    @PutMapping("/defs/{id}")
    fun updateDef(@PathVariable id: Long, @RequestBody request: SaveDefRequest): DefDto =
        catalogService.updateDef(id, request)

    @DeleteMapping("/defs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDef(@PathVariable id: Long) = catalogService.deleteDef(id)

    // ---- mappings ----

    @GetMapping("/mappings")
    fun listMappings(
        @RequestParam(required = false) tableId: Long?,
        @RequestParam(required = false) columnId: Long?,
        @RequestParam(required = false) defId: Long?,
    ): List<MappingDto> = catalogService.listMappings(tableId, columnId, defId)

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMapping(@RequestBody request: SaveMappingRequest): MappingDto = catalogService.createMapping(request)

    @DeleteMapping("/mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMapping(@PathVariable id: Long) = catalogService.deleteMapping(id)

    // ---- purposes ----

    @GetMapping("/purposes")
    fun listPurposes(): List<PurposeDto> = catalogService.listPurposes()

    @PostMapping("/purposes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPurpose(@RequestBody request: PurposeDto): PurposeDto = catalogService.createPurpose(request)

    @DeleteMapping("/purposes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePurpose(@PathVariable id: Long) = catalogService.deletePurpose(id)

    // ---- schema ----

    @GetMapping("/schema")
    fun schema(): Map<String, List<String>> = catalogService.schema()
}
