package com.loveqoo.queryguardian.catalog

import com.loveqoo.queryguardian.api.ColumnDto
import com.loveqoo.queryguardian.api.ConstraintDto
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.PurposeDto
import com.loveqoo.queryguardian.api.SaveConstraintRequest
import com.loveqoo.queryguardian.api.SaveTableRequest
import com.loveqoo.queryguardian.api.TableDto
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.rules.requiredForm
import org.springframework.stereotype.Service

@Service
class CatalogService(
    private val tables: CatalogTableRepository,
    private val purposes: CatalogPurposeRepository,
    private val parser: DialectParser,
) {
    // ---- tables ----

    fun listTables(): List<TableDto> = tables.findAll().map(::toDto)

    fun createTable(request: SaveTableRequest): TableDto {
        require(request.name.isNotBlank()) { "테이블 이름은 필수입니다" }
        require(tables.findByNameIgnoreCase(request.name) == null) { "이미 등록된 테이블: ${request.name}" }
        val saved = tables.save(
            CatalogTable(
                name = request.name,
                description = request.description,
                columns = request.columns.map { CatalogColumn(name = it.name, type = it.type) }.toSet(),
            )
        )
        return toDto(saved)
    }

    fun updateTable(id: Long, request: SaveTableRequest): TableDto {
        val existing = tables.findById(id).orElseThrow { NotFoundException("테이블 $id 없음") }
        val saved = tables.save(
            existing.copy(
                name = request.name,
                description = request.description,
                columns = request.columns.map { CatalogColumn(id = it.id, name = it.name, type = it.type) }.toSet(),
            )
        )
        return toDto(saved)
    }

    fun deleteTable(id: Long) {
        if (!tables.existsById(id)) throw NotFoundException("테이블 $id 없음")
        tables.deleteById(id)
    }

    // ---- constraints ----

    /** 등록 시점 검증: 파싱 불가·미지원 형태의 필수 술어는 아예 등록을 거부한다 (§5.4). */
    fun addConstraint(tableId: Long, request: SaveConstraintRequest): TableDto {
        val table = tables.findById(tableId).orElseThrow { NotFoundException("테이블 $tableId 없음") }
        val kind = ConstraintKind.entries.firstOrNull { it.name == request.kind }
            ?: throw IllegalArgumentException("지원하지 않는 제약 종류: ${request.kind}")

        when (kind) {
            ConstraintKind.PARTITION_KEY -> {
                val column = request.columnName
                require(!column.isNullOrBlank()) { "PARTITION_KEY 제약은 columnName이 필요합니다" }
                require(table.columns.any { it.name.equals(column, ignoreCase = true) }) {
                    "테이블 ${table.name}에 없는 컬럼: $column"
                }
            }
            ConstraintKind.REQUIRED_PREDICATE -> {
                val sql = request.predicateSql
                require(!sql.isNullOrBlank()) { "REQUIRED_PREDICATE 제약은 predicateSql이 필요합니다" }
                val parsed = parser.parsePredicate(sql)
                    ?: throw IllegalArgumentException("술어를 파싱할 수 없습니다: $sql")
                require(requiredForm(parsed) != null) {
                    "지원하지 않는 술어 형태입니다 (컬럼 = 리터럴 또는 컬럼 IN (단일값)만 가능): $sql"
                }
                request.purposeCode?.let {
                    require(purposes.findByCode(it) != null) { "등록되지 않은 purpose: $it" }
                }
            }
        }

        val saved = tables.save(
            table.copy(
                constraints = table.constraints + CatalogConstraint(
                    kind = kind,
                    columnName = request.columnName,
                    predicateSql = request.predicateSql,
                    purposeCode = request.purposeCode,
                )
            )
        )
        return toDto(saved)
    }

    fun deleteConstraint(constraintId: Long) {
        val owner = tables.findAll().firstOrNull { t -> t.constraints.any { it.id == constraintId } }
            ?: throw NotFoundException("제약 $constraintId 없음")
        tables.save(owner.copy(constraints = owner.constraints.filterNot { it.id == constraintId }.toSet()))
    }

    // ---- purposes ----

    fun listPurposes(): List<PurposeDto> = purposes.findAll().map { PurposeDto(it.id, it.code, it.description) }

    fun createPurpose(request: PurposeDto): PurposeDto {
        require(request.code.isNotBlank()) { "purpose code는 필수입니다" }
        require(purposes.findByCode(request.code) == null) { "이미 등록된 purpose: ${request.code}" }
        val saved = purposes.save(CatalogPurpose(code = request.code, description = request.description))
        return PurposeDto(saved.id, saved.code, saved.description)
    }

    fun deletePurpose(id: Long) {
        if (!purposes.existsById(id)) throw NotFoundException("purpose $id 없음")
        purposes.deleteById(id)
    }

    // ---- schema (자동완성 사전) ----

    fun schema(): Map<String, List<String>> =
        tables.findAll().associate { t -> t.name to t.columns.map { it.name } }

    private fun toDto(table: CatalogTable) = TableDto(
        id = table.id,
        name = table.name,
        description = table.description,
        columns = table.columns.map { ColumnDto(it.id, it.name, it.type) },
        constraints = table.constraints.map {
            ConstraintDto(it.id, it.kind.name, it.columnName, it.predicateSql, it.purposeCode)
        },
    )
}
