package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.catalog.CatalogPurposeRepository
import com.loveqoo.queryguardian.ir.Dialect
import org.springframework.stereotype.Component

/**
 * SQL 길이 상한 (적대 검토 MEDIUM). 파서 상한(65536B)이 저장 컬럼 `TEXT`(65535B)보다 커서,
 * 그 사이 길이의 SQL이 **감사 기록 중 `Data too long`으로 죽어 500 + 무기록**이 됐다 —
 * "모든 시도를 기록한다"(§6)가 깨지는 유일한 경로였다. API에서 먼저 자른다.
 */
private const val MAX_SQL_BYTES = 60_000

@Component
class RequestValidation(private val purposes: CatalogPurposeRepository) {

    /** 길이를 넘으면 422 — 파싱·감사보다 **앞에서** 막아 무기록 500을 없앤다. */
    fun validateSql(sql: String) {
        val bytes = sql.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_SQL_BYTES) {
            "SQL이 너무 깁니다: ${bytes}B (상한 ${MAX_SQL_BYTES}B)"
        }
    }

    /** dialect는 필수·검증 — 전방 호환이 무검증 통과가 되지 않게 (spec §8, L1). */
    fun validateDialect(dialect: String) {
        require(Dialect.entries.any { it.name == dialect }) { "지원하지 않는 dialect: $dialect" }
    }

    /** purpose는 관리형 목록 — 미등록 코드는 거부 (spec §5.4, C9). */
    fun validatePurpose(purposeCode: String?) {
        if (purposeCode != null) {
            require(purposes.findByCode(purposeCode) != null) { "등록되지 않은 purpose: $purposeCode" }
        }
    }
}
