package com.loveqoo.queryguardian.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.api.ColumnDto
import com.loveqoo.queryguardian.api.ConflictException
import com.loveqoo.queryguardian.api.DefDto
import com.loveqoo.queryguardian.api.MappingDto
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.PurposeDto
import com.loveqoo.queryguardian.api.SaveDefRequest
import com.loveqoo.queryguardian.api.SaveMappingRequest
import com.loveqoo.queryguardian.api.SaveTableRequest
import com.loveqoo.queryguardian.api.TableDto
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.rules.requiredForm
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.stereotype.Service

@Service
class CatalogService(
    private val tables: CatalogTableRepository,
    private val purposes: CatalogPurposeRepository,
    private val defs: ConstraintDefRepository,
    private val mappings: ConstraintMappingRepository,
    private val parser: DialectParser,
    private val objectMapper: ObjectMapper,
    private val ruleService: com.loveqoo.queryguardian.rules.RuleService,
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
                columns = request.columns.map { toColumn(it, existingId = null) }.toSet(),
            )
        )
        return toDto(saved)
    }

    /** 컬럼은 이름 기준으로 id를 보존한다 — 매핑이 컬럼 id를 참조하므로(H5). 사라진 컬럼의 매핑은 연쇄 삭제. */
    fun updateTable(id: Long, request: SaveTableRequest): TableDto {
        val existing = tables.findById(id).orElseThrow { NotFoundException("테이블 $id 없음") }
        val byName = existing.columns.associateBy { it.name.lowercase() }
        val newColumns = request.columns.map { toColumn(it, existingId = byName[it.name.lowercase()]?.id) }.toSet()
        val removedIds = existing.columns.mapNotNull { it.id } - newColumns.mapNotNull { it.id }.toSet()
        if (removedIds.isNotEmpty()) mappings.deleteByColumnIdIn(removedIds)
        val saved = tables.save(existing.copy(name = request.name, description = request.description, columns = newColumns))
        return toDto(saved)
    }

    fun deleteTable(id: Long) {
        val existing = tables.findById(id).orElseThrow { NotFoundException("테이블 $id 없음") }
        val columnIds = existing.columns.mapNotNull { it.id }
        if (columnIds.isNotEmpty()) mappings.deleteByColumnIdIn(columnIds) // 연쇄 삭제 (H5)
        tables.deleteById(id)
    }

    private fun toColumn(dto: ColumnDto, existingId: Long?): CatalogColumn {
        require(dto.name.isNotBlank()) { "컬럼 이름은 필수입니다" }
        val cls = dto.cls?.let { parseEnum<ColumnClass>(it, "컬럼 클래스") }
            ?: ColumnClassifier.classify(dto.type, dto.isPii, dto.name)
        return CatalogColumn(id = existingId, name = dto.name, type = dto.type, isPii = dto.isPii, cls = cls)
    }

    // ---- constraint defs ----

    fun listDefs(): List<DefDto> = defs.findAll().map { toDto(it) }

    fun createDef(request: SaveDefRequest): DefDto = toDto(defs.save(validatedDef(null, request)))

    fun updateDef(id: Long, request: SaveDefRequest): DefDto {
        if (!defs.existsById(id)) throw NotFoundException("제약 정의 $id 없음")
        return toDto(defs.save(validatedDef(id, request)))
    }

    fun deleteDef(id: Long) {
        if (!defs.existsById(id)) throw NotFoundException("제약 정의 $id 없음")
        if (mappings.countByDefId(id) > 0) throw ConflictException("매핑이 있는 정의는 삭제할 수 없습니다. 먼저 매핑을 해제하세요.")
        defs.deleteById(id)
    }

    /** 강제식 등록 검증 (spec 002 §3.3): 파싱 가능 + 단일 술어(서브쿼리 금지) + {col} 요구 kind 검사. */
    private fun validatedDef(id: Long?, request: SaveDefRequest): ConstraintDef {
        require(request.name.isNotBlank()) { "제약 이름은 필수입니다" }
        val cls = parseEnum<ColumnClass>(request.cls, "컬럼 클래스")
        val kind = parseEnum<DefKind>(request.kind, "강제 방식(kind)")
        val expression = request.expression?.trim()?.takeIf { it.isNotEmpty() }

        when (kind) {
            DefKind.BLOCK, DefKind.PARTITION ->
                require(expression == null) { "${kind.name} 제약은 강제식을 갖지 않습니다" }
            else -> {
                requireNotNull(expression) { "${kind.name} 제약은 강제식이 필수입니다" }
                if (kind != DefKind.JOIN) {
                    require(expression.contains(Expressions.COL)) { "강제식에 {col}이 최소 1회 등장해야 합니다" }
                }
                val sampleParams = Expressions.paramNames(expression).associateWith { "1" }
                val sample = Expressions.substitute(expression, "qg_col_placeholder", sampleParams)
                requireNotNull(sample) { "강제식 파라미터 치환에 실패했습니다" }
                require(!parser.predicateContainsSubquery(sample)) { "강제식에 서브쿼리를 포함할 수 없습니다 (단일 술어 표현식만 허용)" }
                requireNotNull(parser.parsePredicate(sample)) { "강제식을 파싱할 수 없습니다: $expression" }
            }
        }
        return ConstraintDef(id = id, cls = cls, kind = kind, name = request.name,
            description = request.description, expression = expression)
    }

    // ---- mappings ----

    fun listMappings(tableId: Long?, columnId: Long?, defId: Long?): List<MappingDto> {
        val all = tables.findAll()
        val columnOwner: Map<Long, CatalogTable> = buildMap {
            all.forEach { t -> t.columns.forEach { c -> c.id?.let { put(it, t) } } }
        }
        return mappings.findAll()
            .filter { m ->
                (tableId == null || columnOwner[m.columnId]?.id == tableId) &&
                    (columnId == null || m.columnId == columnId) &&
                    (defId == null || m.defId == defId)
            }
            .mapNotNull { m ->
                val owner = columnOwner[m.columnId] ?: return@mapNotNull null
                val column = owner.columns.first { it.id == m.columnId }
                val def = defs.findById(m.defId).orElse(null) ?: return@mapNotNull null
                MappingDto(
                    id = m.id!!, tableId = owner.id!!, tableName = owner.name,
                    columnId = column.id!!, columnName = column.name,
                    defId = def.id!!, defName = def.name, defKind = def.kind.name,
                    purposeCode = m.purposeCode, paramsJson = m.paramsJson,
                    clsMismatch = def.cls != column.cls, // H1: 불일치는 경고 표시, 판정은 지속
                )
            }
    }

    fun createMapping(request: SaveMappingRequest): MappingDto {
        val owner = tables.findAll().firstOrNull { t -> t.columns.any { it.id == request.columnId } }
            ?: throw NotFoundException("컬럼 ${request.columnId} 없음")
        val column = owner.columns.first { it.id == request.columnId }
        val def = defs.findById(request.defId).orElseThrow { NotFoundException("제약 정의 ${request.defId} 없음") }

        require(def.cls == column.cls) { "클래스 불일치: 컬럼은 ${column.cls}, 정의는 ${def.cls} — 같은 클래스의 정의만 매핑할 수 있습니다" }

        val params = Expressions.parseParams(objectMapper, request.paramsJson)
            ?: throw IllegalArgumentException("params_json은 스칼라 값만 가진 JSON 객체여야 합니다")
        val expression = def.expression
        if (expression != null) {
            val needed = Expressions.paramNames(expression)
            require(params.keys.containsAll(needed)) { "누락된 파라미터: ${needed - params.keys}" }
            require(needed.containsAll(params.keys)) { "정의에 없는 파라미터: ${params.keys - needed}" }
        } else {
            require(params.isEmpty()) { "이 정의는 파라미터를 받지 않습니다" }
        }

        if (request.purposeCode != null) {
            require(def.kind == DefKind.FILTER) { "purpose 조건은 FILTER 제약에만 지정할 수 있습니다" }
            require(purposes.findByCode(request.purposeCode) != null) { "등록되지 않은 purpose: ${request.purposeCode}" }
        }

        // C2: 판정 미지원 형태의 FILTER는 매핑 거부 — 매핑하면 spec 003 전까지 해당 테이블 전체가 차단되므로
        if (def.kind == DefKind.FILTER) {
            val substituted = expression?.let { Expressions.substitute(it, column.name, params) }
            val predicate = substituted?.let { parser.parsePredicate(it) }
            require(predicate != null && requiredForm(predicate) != null) {
                "판정 미지원 형태의 FILTER는 아직 매핑할 수 없습니다 (컬럼 = 리터럴 / IN 단일값만 지원, spec 003에서 확장)"
            }
        }

        // MySQL UNIQUE는 NULL purpose를 중복으로 안 잡는다 — 애플리케이션 레벨에서 먼저 검사 (H5)
        val duplicate = mappings.findByColumnId(request.columnId)
            .any { it.defId == request.defId && it.purposeCode == request.purposeCode }
        if (duplicate) throw ConflictException("이미 동일한 매핑이 있습니다")

        val saved = try {
            mappings.save(ConstraintMapping(
                columnId = request.columnId, defId = request.defId,
                purposeCode = request.purposeCode, paramsJson = request.paramsJson,
            ))
        } catch (e: DbActionExecutionException) {
            if (e.cause is DuplicateKeyException) throw ConflictException("이미 동일한 매핑이 있습니다") else throw e
        }
        return listMappings(null, null, null).first { it.id == saved.id }
    }

    fun deleteMapping(id: Long) {
        val mapping = mappings.findById(id).orElseThrow { NotFoundException("매핑 $id 없음") }
        // 역참조 가드 (spec 004 C4): 이 (defId, 컬럼)을 참조하는 규칙 조건이 있으면 삭제 거부
        val column = tables.findAll().flatMap { it.columns }.firstOrNull { it.id == mapping.columnId }
        val table = tables.findAll().firstOrNull { t -> t.columns.any { it.id == mapping.columnId } }
        if (column != null && table != null &&
            ruleService.isReferencedByMapping(mapping.defId, table.name, column.name)) {
            throw ConflictException("이 매핑을 참조하는 규칙 조건이 있어 삭제할 수 없습니다. 먼저 규칙 조건을 제거하세요.")
        }
        mappings.deleteById(id)
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
        val purpose = purposes.findById(id).orElseThrow { NotFoundException("purpose $id 없음") }
        if (mappings.findByPurposeCode(purpose.code).isNotEmpty()) {
            throw ConflictException("이 purpose를 참조하는 매핑이 있어 삭제할 수 없습니다") // H5
        }
        purposes.deleteById(id)
    }

    // ---- schema (자동완성 사전 — 계약 불변, L2) ----

    fun schema(): Map<String, List<String>> =
        tables.findAll().associate { t -> t.name to t.columns.map { it.name } }

    // ---- dto 변환 ----

    private fun toDto(table: CatalogTable) = TableDto(
        id = table.id, name = table.name, description = table.description,
        columns = table.columns.map { ColumnDto(it.id, it.name, it.type, it.isPii, it.cls.name) },
    )

    private fun toDto(def: ConstraintDef) = DefDto(
        id = def.id, cls = def.cls.name, kind = def.kind.name, name = def.name,
        description = def.description, expression = def.expression,
        mappingCount = def.id?.let { mappings.countByDefId(it) } ?: 0,
    )

    private inline fun <reified E : Enum<E>> parseEnum(value: String, label: String): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("지원하지 않는 $label: $value")
}
