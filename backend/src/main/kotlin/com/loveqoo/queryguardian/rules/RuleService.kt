package com.loveqoo.queryguardian.rules

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.RuleDetailDto
import com.loveqoo.queryguardian.api.RuleDto
import com.loveqoo.queryguardian.api.SaveRuleRequest
import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import com.loveqoo.queryguardian.catalog.ConstraintMappingRepository
import org.springframework.stereotype.Service

@Service
class RuleService(
    private val rules: RuleRepository,
    private val tables: CatalogTableRepository,
    private val mappings: ConstraintMappingRepository,
    private val catalog: TableCatalog,
    private val objectMapper: ObjectMapper,
) {
    private val MAX_DEPTH = 8
    private val MAX_NODES = 200

    // ---- 조회 ----

    fun list(): List<RuleDto> = rules.findAll().map { toDto(it) }

    fun get(id: Long): RuleDetailDto {
        val e = rules.findById(id).orElseThrow { NotFoundException("규칙 $id 없음") }
        val tree = runCatching { parseTree(e.treeJson) }.getOrNull()
        return RuleDetailDto(e.id!!, e.name, e.scope, e.server, e.enabled, tree, corrupt = tree == null)
    }

    /** 평가기용: 활성·파싱 가능한 규칙만. 손상 규칙은 격리(제외), 목록에는 corrupt로 남는다 (H6). */
    fun activeUserRules(): List<UserRule> = rules.findAll()
        .filter { it.enabled }
        .mapNotNull { e ->
            val tree = runCatching { parseTree(e.treeJson) }.getOrNull() ?: return@mapNotNull null
            UserRule(e.id!!, e.name, parseScope(e.scope), true, tree)
        }

    // ---- 등록/수정 (검증) ----

    fun create(request: SaveRuleRequest): RuleDetailDto {
        validate(request)
        val saved = rules.save(RuleEntity(
            name = request.name, scope = request.scope.uppercase(), server = request.server,
            enabled = request.enabled, treeJson = objectMapper.writeValueAsString(request.tree),
        ))
        return get(saved.id!!)
    }

    fun update(id: Long, request: SaveRuleRequest): RuleDetailDto {
        val existing = rules.findById(id).orElseThrow { NotFoundException("규칙 $id 없음") }
        validate(request)
        rules.save(existing.copy(
            name = request.name, scope = request.scope.uppercase(), server = request.server,
            enabled = request.enabled, treeJson = objectMapper.writeValueAsString(request.tree),
        ))
        return get(id)
    }

    fun delete(id: Long) {
        if (!rules.existsById(id)) throw NotFoundException("규칙 $id 없음")
        rules.deleteById(id)
    }

    /** 저장 시도에서 위반한 규칙들의 hit 증가 (§7, BLOCK/WARN 무관). */
    fun recordHits(ruleIds: Set<Long>) = ruleIds.forEach { rules.incrementHit(it) }

    /** 매핑 삭제 역참조 가드 (C4): (defId, table.column)을 참조하는 규칙 조건이 있는가. */
    fun isReferencedByMapping(defId: Long, tableName: String, columnName: String): Boolean =
        rules.findAll().any { e ->
            val tree = runCatching { parseTree(e.treeJson) }.getOrNull() ?: return@any false
            conditions(tree).any {
                it.defId == defId &&
                    it.table?.equals(tableName, ignoreCase = true) == true &&
                    it.column?.equals(columnName, ignoreCase = true) == true
            }
        }

    // ---- 검증 ----

    private fun validate(request: SaveRuleRequest) {
        require(request.name.isNotBlank()) { "규칙 이름은 필수입니다" }
        parseScope(request.scope) // enum 검증
        var nodeCount = 0
        fun walk(node: RuleNode, depth: Int) {
            require(depth <= MAX_DEPTH) { "규칙 트리가 너무 깊습니다" }
            require(++nodeCount <= MAX_NODES) { "규칙 조건이 너무 많습니다" }
            when (node) {
                is RuleGroup -> {
                    require(node.children.isNotEmpty()) { "조건이 하나도 없는 그룹이 있습니다" } // 빈 그룹 금지
                    node.children.forEach { walk(it, depth + 1) }
                }
                is RuleCondition -> validateCondition(node)
            }
        }
        walk(request.tree, 0)
    }

    private fun validateCondition(c: RuleCondition) {
        when (c.op) {
            RuleOp.requires -> {
                val table = requireNotNull(c.table) { "requires 조건은 table이 필요합니다" }
                val column = requireNotNull(c.column) { "requires 조건은 column이 필요합니다" }
                val defId = requireNotNull(c.defId) { "requires 조건은 defId가 필요합니다" }
                require(mappingExists(defId, table, column)) { "매핑되지 않은 제약을 참조했습니다 (defId=$defId)" }
                // H3: 판정 가능 형태(EQ 리터럴/IN 단일)인지 재검사 — 아니면 테이블 전면 차단 유발
                require(catalog.resolveConditionPredicate(defId, c.mappingId, column) != null) {
                    "판정할 수 없는 술어 형태입니다 (컬럼 = 리터럴 / IN 단일값만 requires로 사용 가능)"
                }
            }
            RuleOp.blocks -> {
                requireNotNull(c.table) { "blocks 조건은 table이 필요합니다" }
                requireNotNull(c.column) { "blocks 조건은 column이 필요합니다" }
            }
            RuleOp.joins -> {
                requireNotNull(c.table) { "joins 조건은 table이 필요합니다" }
                requireNotNull(c.column) { "joins 조건은 column이 필요합니다" }
                requireNotNull(c.refTable) { "joins 조건은 refTable이 필요합니다" }
                requireNotNull(c.refColumn) { "joins 조건은 refColumn이 필요합니다" }
            }
            RuleOp.must_be_within, RuleOp.must_be_masked -> { /* 등록·표시만 — 판정 미구현 */ }
        }
    }

    private fun mappingExists(defId: Long, tableName: String, columnName: String): Boolean {
        val table = tables.findByNameIgnoreCase(tableName) ?: return false
        val column = table.columns.firstOrNull { it.name.equals(columnName, ignoreCase = true) } ?: return false
        return mappings.findByColumnId(column.id!!).any { it.defId == defId }
    }

    // ---- 변환 ----

    private fun parseTree(json: String): RuleGroup = objectMapper.readValue(json, RuleGroup::class.java)
    private fun parseScope(s: String): RuleScope =
        RuleScope.entries.firstOrNull { it.name == s.uppercase() } ?: throw IllegalArgumentException("지원하지 않는 scope: $s")

    private fun conditions(node: RuleNode): List<RuleCondition> = when (node) {
        is RuleGroup -> node.children.flatMap { conditions(it) }
        is RuleCondition -> listOf(node)
    }

    private fun toDto(e: RuleEntity): RuleDto {
        val tree = runCatching { parseTree(e.treeJson) }.getOrNull()
        val conds = tree?.let { conditions(it) } ?: emptyList()
        val judged = conds.filter { it.judged }
        val severity = if (judged.isEmpty()) "NONE" else worstSeverity(judged.map { it.severity }).name
        return RuleDto(
            id = e.id!!, name = e.name, scope = e.scope, server = e.server,
            severity = severity, hits = e.hitCount, enabled = e.enabled,
            enforced = judged.isNotEmpty(), corrupt = tree == null,
        )
    }
}
