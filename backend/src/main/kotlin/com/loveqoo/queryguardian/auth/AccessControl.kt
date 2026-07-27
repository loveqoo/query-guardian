package com.loveqoo.queryguardian.auth

import com.loveqoo.queryguardian.api.AccessBlockedDto
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.catalog.GovernedServer
import com.loveqoo.queryguardian.catalog.CatalogTableRepository
import org.springframework.stereotype.Component

/** 데이터 권한 차단 (spec 007 §6.5) — 코드로 미허용/미등록/요청자 불일치를 구분한다. */
class AccessBlockedException(val detail: AccessBlockedDto) : RuntimeException(detail.message)

/**
 * 데이터 접근 권한 판정 (spec 007 §3.1·§6).
 * 권한 키는 **테이블명 단독**(단일 서버 전제, C3). 행 부재 = 허용(default-allow), 명시적 false만 차단.
 *
 * 주의: 이 컴포넌트는 **조회·게이트 계층 전용**이다. 판정 경로(TableCatalog/LintService/RuleEngine)는
 * 권한을 알지 못한다 — §6.4 불변식(ArchUnit으로 강제).
 */
@Component
class AccessControl(
    private val serverPerms: UserServerPermissionRepository,
    private val tablePerms: UserTablePermissionRepository,
    private val tables: CatalogTableRepository,
) {
    /** 사본이었다 — 정의는 [GovernedServer.KEY] 하나다. 둘이면 한쪽만 고쳐지는 날이 온다. */
    private val DEFAULT_SERVER = GovernedServer.KEY

    /** 테이블 하나가 이 사용자에게 허용되는가. 카탈로그 미등록은 여기서 판단하지 않는다(check가 구분). */
    fun isTableAllowed(userId: String, tableName: String): Boolean {
        val serverOff = serverPerms.findByUserId(userId).any { it.serverKey == DEFAULT_SERVER && !it.allowed }
        if (serverOff) return false
        val row = tablePerms.find(userId, tableName)
        return row?.allowed ?: true // 행 부재 = 허용
    }

    fun allowedTableNames(userId: String): Set<String> =
        tables.findAll().map { it.name }.filter { isTableAllowed(userId, it) }.toSet()

    /**
     * 쿼리가 참조하는 테이블 집합을 검사한다 (spec 007 §6.1).
     * 미등록 → TABLES_UNKNOWN(fail-closed, 오타와 권한 부족 구분), 미허용 → TABLES_NOT_PERMITTED.
     */
    fun checkTables(userId: String, referenced: Set<String>) {
        if (referenced.isEmpty()) return
        val registered = tables.findAll().map { it.name.lowercase() }.toSet()
        val unknown = referenced.filterNot { registered.contains(it.lowercase()) }.sorted()
        if (unknown.isNotEmpty()) {
            throw AccessBlockedException(AccessBlockedDto(
                AuditCode.TABLES_UNKNOWN,
                "카탈로그에 등록되지 않은 테이블이라 권한을 판정할 수 없습니다: ${unknown.joinToString(", ")}",
                unknown))
        }
        val denied = referenced.filterNot { isTableAllowed(userId, it) }.sorted()
        if (denied.isNotEmpty()) {
            throw AccessBlockedException(AccessBlockedDto(
                AuditCode.TABLES_NOT_PERMITTED,
                "접근 권한이 없는 테이블입니다: ${denied.joinToString(", ")}",
                denied))
        }
    }
}
