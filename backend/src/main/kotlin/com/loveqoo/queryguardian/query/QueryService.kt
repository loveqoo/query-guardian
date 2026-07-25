package com.loveqoo.queryguardian.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.loveqoo.queryguardian.api.BlockedException
import com.loveqoo.queryguardian.api.ConflictException
import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.api.NotFoundException
import com.loveqoo.queryguardian.api.QueryDto
import com.loveqoo.queryguardian.api.QuerySummaryDto
import com.loveqoo.queryguardian.api.ReviewRequest
import com.loveqoo.queryguardian.api.SaveQueryRequest
import com.loveqoo.queryguardian.approval.ApprovalGate
import com.loveqoo.queryguardian.auth.AccessControl
import com.loveqoo.queryguardian.approval.ApprovalRequest
import com.loveqoo.queryguardian.approval.Directory
import com.loveqoo.queryguardian.approval.QueryReviewEvent
import com.loveqoo.queryguardian.approval.QueryReviewEventRepository
import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.rules.RuleService
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

@Service
class QueryService(
    private val lintService: LintService,
    private val repository: SavedQueryRepository,
    private val objectMapper: ObjectMapper,
    private val ruleService: RuleService,
    private val approvalGate: ApprovalGate,
    private val parser: DialectParser,
    private val reviewEvents: QueryReviewEventRepository,
    private val access: AccessControl,
) {
    /** 저장 게이트: 룰(422) 선행 → 승인 검사(403) (spec 005 §4, H4). */
    fun save(actor: String, request: SaveQueryRequest): QueryDto {
        val (approval, report) = gate(actor, request)
        val now = Instant.now()
        val saved = repository.save(
            SavedQuery(
                name = request.name, dialect = request.dialect, sqlText = request.sql,
                purposeCode = approval.purposeCode,           // 클라이언트 입력 무시, 요청에서 주입 (C1)
                requestId = approval.id!!,
                reviewStatus = ReviewStatus.PENDING_REVIEW.name,
                lintReportJson = objectMapper.writeValueAsString(report),
                createdAt = now, updatedAt = now,
            )
        )
        return toDto(saved)
    }

    /** 수정: 게이트 재실행 + **검토 상태 리셋**(C5 — 검토 도장을 단 채 본문이 바뀌는 구멍 차단). */
    fun update(id: Long, actor: String, request: SaveQueryRequest): QueryDto {
        val existing = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        val (approval, report) = gate(actor, request)
        val saved = repository.save(
            existing.copy(
                name = request.name, dialect = request.dialect, sqlText = request.sql,
                purposeCode = approval.purposeCode, requestId = approval.id!!,
                reviewStatus = ReviewStatus.PENDING_REVIEW.name,   // 항상 재검토로 리셋
                reviewer = null, reviewedAt = null, reviewNote = null,
                lintReportJson = objectMapper.writeValueAsString(report),
                updatedAt = Instant.now(),
            )
        )
        return toDto(saved)
    }

    /**
     * 검토 결정 (spec 005 §3.2). 결정 직전 **재-lint**(C6): 현재 규칙 기준 BLOCK이면 승인 불가(409).
     * 자가 검토(요청자 == 검토 actor)는 409 — 스텁 identity라 우회 가능하나 정책을 코드로 표현(M3).
     */
    fun review(id: Long, actor: String, request: ReviewRequest): QueryDto {
        requireNotNull(Directory.findAnyone(actor)) { "등록되지 않은 행위자: $actor" }
        val existing = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        val decision = ReviewStatus.entries.firstOrNull { it.name == request.decision.uppercase() && it != ReviewStatus.PENDING_REVIEW }
            ?: throw IllegalArgumentException("decision은 APPROVED 또는 REJECTED여야 합니다")

        val approval = approvalGate.findRequest(existing.requestId)
        if (approval != null && approval.requester == actor) {
            throw ConflictException("본인이 요청한 쿼리는 스스로 검토할 수 없습니다")
        }

        // 재-lint: 저장 시점 스냅샷이 아니라 현재 규칙·카탈로그로 판정
        val current = LintReportDto.from(lintService.lint(existing.sqlText, existing.purposeCode))
        if (decision == ReviewStatus.APPROVED && current.blocked) {
            throw ConflictException("현재 규칙 기준으로 위반이 있어 검토 승인할 수 없습니다: " +
                current.violations.filter { it.severity.name == "BLOCK" }.joinToString("; ") { it.message })
        }

        val now = Instant.now()
        val saved = repository.save(existing.copy(
            reviewStatus = decision.name, reviewer = actor, reviewedAt = now, reviewNote = request.note,
            lintReportJson = objectMapper.writeValueAsString(current), // 재-lint 결과로 갱신
        ))
        reviewEvents.save(QueryReviewEvent(
            queryId = id, actor = actor, decision = decision.name, note = request.note,
            sqlHash = sha256(existing.sqlText),
            lintSnapshotJson = objectMapper.writeValueAsString(current), at = now,
        ))
        return toDto(saved)
    }

    /**
     * 목록은 **본인 것만** (STEWARD/ADMIN은 전체 — 검토가 그들의 일이다).
     *
     * 이 스코프가 없으면 로그인한 아무나 남의 쿼리 본문을 읽는다. SQL 본문에는 "누가 무엇을 조사하는지"와
     * 조건에 쓴 상수가 담기므로 **열람 자체가 유출**이고, 실행을 막아도 그것은 막히지 않는다
     * (spec 005 §5가 M2 선행 조건으로 예고한 지점, 결정 15).
     */
    fun list(actor: String, privileged: Boolean): List<QuerySummaryDto> =
        repository.findAll()
            .filter { privileged || ownerOf(it) == actor }
            .map {
                QuerySummaryDto(it.id!!, it.name, it.dialect, it.purposeCode, it.requestId, it.reviewStatus,
                    it.reviewer, it.createdAt, it.updatedAt)
            }

    fun get(id: Long, actor: String, privileged: Boolean): QueryDto = toDto(visible(id, actor, privileged))

    fun delete(id: Long, actor: String, privileged: Boolean) {
        visible(id, actor, privileged) // 남의 쿼리를 지울 수도 없다
        repository.deleteById(id)
    }

    /**
     * 쿼리의 소유자 = **근거 승인 요청의 요청자**. 저장 게이트가 `requester == actor`를 강제하므로
     * (ApprovalGate `REQUESTER_MISMATCH`) 이 정의가 저장 시점 행위자와 일치한다.
     * 소유자를 별도 컬럼으로 복제하지 않는 이유: 두 값이 어긋날 여지를 만들지 않는다.
     */
    private fun ownerOf(query: SavedQuery): String? = approvalGate.findRequest(query.requestId)?.requester

    /** 없으면 404, 남의 것이면 403. 존재 여부를 숨기지 않는다 — id는 순번이므로 숨겨도 의미가 없다. */
    internal fun visible(id: Long, actor: String, privileged: Boolean): SavedQuery {
        val query = repository.findById(id).orElseThrow { NotFoundException("쿼리 $id 없음") }
        if (!privileged && ownerOf(query) != actor) {
            throw ForbiddenException("본인이 저장한 쿼리만 조회할 수 있습니다")
        }
        return query
    }

    // ---- 게이트 (spec 005 §4 — 실행 순서: 룰 422 → 승인 403) ----

    private fun gate(actor: String, request: SaveQueryRequest): Pair<ApprovalRequest, LintReportDto> {
        require(request.name.isNotBlank() && request.name.length <= 100) { "이름은 1~100자여야 합니다" }
        // purposeCode는 클라이언트 입력이 아니라 승인 요청에서 주입한다 (C1). 요청이 없으면 null로 lint 후 403.
        val purposeCode = request.requestId?.let { approvalGate.findRequest(it)?.purposeCode }

        // 1) 데이터 권한 검사 — 룰보다 **앞** (spec 007 §6.0). 권한 없는 사용자에게 위반 메시지를 주지 않는다.
        val parsedIr = when (val r = parser.parse(request.sql)) {
            is ParseResult.Success -> r.ir
            is ParseResult.Failure -> null // 파싱 실패는 룰 게이트가 BLOCK으로 보고
        }
        parsedIr?.let { access.checkTables(actor, approvalGate.physicalTables(it)) }

        // 2) 룰 게이트 — BLOCK이면 422. 규칙 hit 통계는 권한 통과 후에만 기록(spec 004 §7 개정).
        val report = LintReportDto.from(lintService.lint(request.sql, purposeCode))
        val ruleIds = report.violations
            .filter { it.ruleId.startsWith("rule/") }
            .mapNotNull { it.ruleId.removePrefix("rule/").toLongOrNull() }
            .toSet()
        if (ruleIds.isNotEmpty()) ruleService.recordHits(ruleIds)
        if (report.blocked) throw BlockedException(report)

        // 3) 승인 검사 — 요청 존재·승인·요청자·테이블 커버
        val ir = parsedIr ?: throw BlockedException(report)
        val approval = approvalGate.check(request.requestId, actor, ir)
        return approval to report
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun toDto(saved: SavedQuery): QueryDto = QueryDto(
        id = saved.id!!, name = saved.name, dialect = saved.dialect, sql = saved.sqlText,
        purposeCode = saved.purposeCode, requestId = saved.requestId,
        reviewStatus = saved.reviewStatus, reviewer = saved.reviewer, reviewedAt = saved.reviewedAt,
        reviewNote = saved.reviewNote,
        lintReport = objectMapper.readValue(saved.lintReportJson),
        createdAt = saved.createdAt, updatedAt = saved.updatedAt,
    )
}
