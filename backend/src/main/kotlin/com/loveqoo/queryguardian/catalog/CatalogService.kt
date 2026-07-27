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
import com.loveqoo.queryguardian.parser.PredicateParse
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

    /**
     * **수정도 등록과 같은 검사를 지난다** (spec 014 L5).
     *
     * 예전에는 [validatedDef]만 돌고 **기존 매핑을 다시 보지 않았다.** 구멍이 둘이었다:
     *
     * - 강제식을 판정 미지원 형태로 바꾸면 → 그 정의를 쓰는 **모든 매핑이 판정 불가**가 되고,
     *   해당 테이블을 조회하는 쿼리가 전부 "검증할 수 없습니다"로 막힌다(fail-closed DoS).
     *   등록자는 자기가 방금 한 일과 연결짓지 못한다.
     * - **kind를 요건(INTEGRITY·FILTER)에서 MASK로 바꾸면 요건이 조용히 사라진다**(fail-open).
     *   이쪽이 더 나쁘다 — 아무 오류도 안 나고 그냥 안 막게 된다.
     *
     * kind·클래스 변경은 **매핑이 있으면 거부한다.** [deleteDef]가 이미 같은 규칙을 쓴다
     * (매핑이 있으면 못 지운다). 요건을 없애려면 매핑을 먼저 풀게 해서 **그 행위가 보이게** 만든다.
     */
    fun updateDef(id: Long, request: SaveDefRequest): DefDto {
        val existing = defs.findById(id).orElseThrow { NotFoundException("제약 정의 $id 없음") }
        val updated = validatedDef(id, request)
        val mapped = mappings.findByDefId(id)

        if (mapped.isNotEmpty()) {
            require(updated.kind == existing.kind) {
                "매핑이 ${mapped.size}건 있는 정의의 강제 방식(kind)은 바꿀 수 없습니다 " +
                    "(${existing.kind} → ${updated.kind}). 먼저 매핑을 해제하세요."
            }
            require(updated.cls == existing.cls) {
                "매핑이 ${mapped.size}건 있는 정의의 컬럼 클래스는 바꿀 수 없습니다 " +
                    "(${existing.cls} → ${updated.cls}). 먼저 매핑을 해제하세요."
            }
            // 강제식이 바뀌었을 수 있다 — 기존 매핑을 **전수** 다시 검증한다.
            val columnsById = tables.findAll().flatMap { it.columns }.associateBy { it.id }
            mapped.forEach { m ->
                val col = columnsById[m.columnId]
                    ?: throw ConflictException("매핑 ${m.id}이 없는 컬럼 ${m.columnId}을 가리킵니다")
                val params = Expressions.parseParams(objectMapper, m.paramsJson)
                    ?: throw ConflictException("매핑 ${m.id}의 params_json이 깨졌습니다")
                requireJudgeable(updated, col.name, params)
            }
        }
        return toDto(defs.save(updated))
    }

    /**
     * **C2 — 판정 미지원 형태의 요건 술어를 거부한다.** 생성(매핑)과 수정(정의) **양쪽**이 부른다.
     *
     * 예전에는 이 검사가 `createMapping` 안에만 있었다. 그래서 "등록 시 막은 것"을
     * **수정으로 우회**할 수 있었다 — 검사가 한 경로에만 있으면 다른 경로가 그 검사를 무효로 만든다.
     *
     * 조건이 `kind == FILTER`가 아니라 [isRequiredPredicate]인 이유: 판정이 요구하는 종류가
     * 늘면 이 가드도 같이 늘어야 한다. 안 늘리면 등록은 조용히 성공하고 **그 테이블을 조회하는
     * 모든 쿼리가** 나중에 "검증할 수 없습니다"로 차단된다 — 등록자는 원인을 알 길이 없다.
     *
     * **세 실패를 갈라 말한다.** 예전에는 단정 하나가 치환 실패·파싱 실패·판정 미지원을 전부 덮고
     * **마지막 하나의 이름만** 댔다. 그래서 파싱이 안 된 강제식을 매핑하면 "판정 미지원 형태"라는
     * 답이 돌아왔고, 등록자는 엉뚱한 곳을 고치러 갔다.
     */
    private fun requireJudgeable(def: ConstraintDef, columnName: String, params: Map<String, String>) {
        if (!def.kind.isRequiredPredicate) return
        // 요건 술어는 강제식이 필수다(validatedDef가 등록 시 강제) — 없으면 저장된 정의가 깨진 것이다.
        val forced = requireNotNull(def.expression) { "${def.kind} 제약에 강제식이 없습니다 (정의 ${def.id})" }
        val substituted = Expressions.substitute(forced, columnName, params)
            ?: throw IllegalArgumentException("강제식 파라미터 치환에 실패했습니다: $forced")
        val predicate = when (val parsed = parser.parsePredicate(substituted)) {
            is PredicateParse.Unparsed -> throw IllegalArgumentException(
                "강제식을 파싱할 수 없습니다: $substituted — ${parsed.reason}",
            )
            is PredicateParse.Parsed -> parsed.predicate
        }
        requireNotNull(requiredForm(predicate)) {
            "판정 미지원 형태의 ${def.kind}는 아직 매핑할 수 없습니다 " +
                "(컬럼 = 리터럴 / IN 단일값만 지원, spec 003에서 확장)"
        }
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
                // **파싱을 먼저 본다.** 순서가 반대였을 때 문법이 깨진 강제식은 "서브쿼리를 포함할 수
                // 없습니다"라는 답을 받았다 — `predicateContainsSubquery`가 파싱 실패에 fail-closed로 `true`를
                // 내기 때문이다(그 자체는 옳다). 두 검사 모두 거절이지만 **거절 이유가 등록자의 수정 대상**이고,
                // 그래서 더 구체적인 진단이 앞에 와야 한다. 실측으로 확인했다(그 순서에서는 파싱 검사가
                // 아예 도달 불가였다 — 죽은 코드였다).
                when (val parsed = parser.parsePredicate(sample)) {
                    is PredicateParse.Unparsed -> throw IllegalArgumentException(
                        "강제식을 파싱할 수 없습니다: $expression — ${parsed.reason}",
                    )
                    is PredicateParse.Parsed -> Unit
                }
                require(!parser.predicateContainsSubquery(sample)) { "강제식에 서브쿼리를 포함할 수 없습니다 (단일 술어 표현식만 허용)" }
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

        // C2: 판정 미지원 형태의 **요건 술어**는 매핑 거부 — 매핑하면 해당 테이블 전체가 차단되므로.
        //
        // **세 실패를 갈라 말한다.** 예전에는 단정 하나(`predicate != null && requiredForm(...) != null`)가
        // 치환 실패·파싱 실패·판정 미지원을 전부 덮고 **마지막 하나의 이름만** 댔다. 그래서 파싱이 안 된
        // 강제식을 매핑하면 "판정 미지원 형태"라는 답이 돌아왔고, 등록자는 엉뚱한 곳을 고치러 갔다.
        //
        // 조건이 `kind == FILTER`가 아니라 [isRequiredPredicate]인 이유: 판정이 요구하는 종류가
        // 늘면 이 가드도 같이 늘어야 한다. 안 늘리면 등록은 조용히 성공하고 **그 테이블을 조회하는
        // 모든 쿼리가** 나중에 "검증할 수 없습니다"로 차단된다 — 등록자는 원인을 알 길이 없다.
        requireJudgeable(def, column.name, params)

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
