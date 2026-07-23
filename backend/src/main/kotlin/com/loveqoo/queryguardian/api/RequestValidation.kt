package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.catalog.CatalogPurposeRepository
import com.loveqoo.queryguardian.ir.Dialect
import org.springframework.stereotype.Component

@Component
class RequestValidation(private val purposes: CatalogPurposeRepository) {

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
