package com.loveqoo.queryguardian.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.loveqoo.queryguardian.api.BlockedException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.QueryDto
import com.loveqoo.queryguardian.api.QuerySummaryDto
import com.loveqoo.queryguardian.api.SaveQueryRequest
import com.loveqoo.queryguardian.lint.LintService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QueryService(
    private val lintService: LintService,
    private val repository: SavedQueryRepository,
    private val objectMapper: ObjectMapper,
    private val ruleService: com.loveqoo.queryguardian.rules.RuleService,
) {
    /** 저장 게이트: BLOCK이면 BlockedException(→422). WARN은 저장하되 리포트에 남긴다 (spec §13.3). */
    fun save(request: SaveQueryRequest): QueryDto {
        val report = gate(request)
        val now = Instant.now()
        val saved = repository.save(
            SavedQuery(
                name = request.name,
                dialect = request.dialect,
                sqlText = request.sql,
                purposeCode = request.purposeCode,
                lintReportJson = objectMapper.writeValueAsString(report),
                createdAt = now,
                updatedAt = now,
            )
        )
        return toDto(saved)
    }

    fun update(id: Long, request: SaveQueryRequest): QueryDto {
        val existing = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        val report = gate(request)
        val saved = repository.save(
            existing.copy(
                name = request.name,
                dialect = request.dialect,
                sqlText = request.sql,
                purposeCode = request.purposeCode,
                lintReportJson = objectMapper.writeValueAsString(report),
                updatedAt = Instant.now(),
            )
        )
        return toDto(saved)
    }

    fun list(): List<QuerySummaryDto> = repository.findAll().map {
        QuerySummaryDto(it.id!!, it.name, it.dialect, it.purposeCode, it.createdAt, it.updatedAt)
    }

    fun get(id: Long): QueryDto =
        toDto(repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") })

    fun delete(id: Long) {
        if (!repository.existsById(id)) throw NotFoundException("쿼리 $id 없음")
        repository.deleteById(id)
    }

    private fun gate(request: SaveQueryRequest): LintReportDto {
        require(request.name.isNotBlank() && request.name.length <= 100) { "이름은 1~100자여야 합니다" }
        val report = LintReportDto.from(lintService.lint(request.sql, request.purposeCode))
        // 위반 통계 (spec 004 §7): 저장 시도에서 위반한 사용자 규칙의 hit 증가 (BLOCK/WARN 무관, 저장 성공/실패 무관)
        val ruleIds = report.violations.mapNotNull { it.ruleId.removePrefix("rule/").toLongOrNull().takeIf { _ -> it.ruleId.startsWith("rule/") } }.toSet()
        if (ruleIds.isNotEmpty()) ruleService.recordHits(ruleIds)
        if (report.blocked) throw BlockedException(report)
        return report
    }

    private fun toDto(saved: SavedQuery): QueryDto = QueryDto(
        id = saved.id!!,
        name = saved.name,
        dialect = saved.dialect,
        sql = saved.sqlText,
        purposeCode = saved.purposeCode,
        lintReport = objectMapper.readValue(saved.lintReportJson),
        createdAt = saved.createdAt,
        updatedAt = saved.updatedAt,
    )
}
