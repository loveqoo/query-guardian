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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val catalogService: CatalogService) {

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

    @PostMapping("/tables/{id}/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    fun addConstraint(@PathVariable id: Long, @RequestBody request: SaveConstraintRequest): TableDto =
        catalogService.addConstraint(id, request)

    @DeleteMapping("/constraints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteConstraint(@PathVariable id: Long) = catalogService.deleteConstraint(id)

    @GetMapping("/purposes")
    fun listPurposes(): List<PurposeDto> = catalogService.listPurposes()

    @PostMapping("/purposes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPurpose(@RequestBody request: PurposeDto): PurposeDto = catalogService.createPurpose(request)

    @DeleteMapping("/purposes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePurpose(@PathVariable id: Long) = catalogService.deletePurpose(id)

    @GetMapping("/schema")
    fun schema(): Map<String, List<String>> = catalogService.schema()
}
