package com.loveqoo.queryguardian.exec

import com.loveqoo.queryguardian.audit.AuditCode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component

/**
 * 논리 테이블명 ↔ 데모 물리 테이블명 (spec 008 §2.7-3, 결정 5).
 *
 * 카탈로그·룰·권한은 **논리명만** 쓴다. 물리명 치환은 재작성의 **마지막 단계에서만** 일어난다 —
 * 물리명으로 제약을 조회하면 `boundFor("demo_users")`가 빈 목록이라 마스킹·필터가 **조용히 0건 적용**된다(§3 원칙).
 */
@Table("demo_table_map")
data class DemoTableMapping(
    @Id val id: Long? = null,
    val logicalName: String,
    val physicalName: String,
)

interface DemoTableMapRepository : CrudRepository<DemoTableMapping, Long>

/** 매핑 해석 결과 — 실행 가능([Resolved])이 아닌 모든 경우는 실행 거부다. */
sealed interface DemoMapping {
    data class Resolved(val byLogical: Map<String, String>) : DemoMapping

    /**
     * 거부 — **자기 감사 코드와 사유를 안다.**
     *
     * 예전에는 호출부가 세 변종을 손으로 분해해 코드와 문구를 조립했고, 그 짝은 각 변종의 **주석에만**
     * 적혀 있었다("미매핑 → `NO_DEMO_MAPPING`"). 주석은 컴파일되지 않는다. 선례는 같은 패키지에 있다 —
     * [ExecutionFailure.Kind]가 `auditCode`를 필드로 든다("이름 규약이 아니라 필드로").
     */
    sealed interface Failed : DemoMapping {
        val auditCode: AuditCode
        val message: String
    }

    /**
     * 요청 테이블 집합이 비었음 → 거부. [Incomplete]와 분리한 이유: `unmapped.isNotEmpty()`로 분기하는
     * 호출자가 생기면 "미매핑 목록이 빈 Incomplete"가 통과해 fail-open한다.
     */
    data object Empty : Failed {
        override val auditCode = AuditCode.NO_DEMO_MAPPING
        override val message = "실행할 대상 테이블이 없습니다"
    }

    data class Incomplete(val unmapped: List<String>) : Failed {
        override val auditCode get() = AuditCode.NO_DEMO_MAPPING
        override val message get() = "실행 대상 매핑이 없는 테이블이 있습니다: ${unmapped.joinToString(", ")}"
    }

    /** 식별자 접수 위반. 컬럼·테이블명을 통한 injection의 근본 차단. */
    data class Invalid(val badNames: List<String>) : Failed {
        override val auditCode get() = AuditCode.INVALID_PHYSICAL_NAME
        override val message get() = "실행 대상 테이블명이 식별자 규칙을 위반했습니다: ${badNames.joinToString(", ")}"
    }
}

/**
 * `demo_table_map`은 매핑표이면서 **실행 허용목록을 겸한다**.
 *
 * 부분 매핑을 허용하면 매핑되지 않은 테이블이 원래 이름 그대로 실행돼 `SELECT tree_json FROM rule`처럼
 * **실재하는 거버넌스 테이블을 직격**한다. 그래서 총체성(전부 매핑)이 실행의 필요조건이다.
 */
@Component
class DemoTableResolver(private val repository: DemoTableMapRepository) {

    fun resolve(logicalTables: Set<String>): DemoMapping =
        resolve(repository.findAll().toList(), logicalTables)

    companion object {
        /** spec 008 §3 식별자 접수 검사 — MySQL 식별자 최대 64자, 인용 없이 안전한 문자만. */
        val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

        /**
         * [logicalTables]가 **모두** 매핑되고 양쪽 이름이 식별자 접수 검사를 통과할 때만 [DemoMapping.Resolved].
         * 빈 집합은 거부한다 — 테이블 0개 쿼리는 모든 테이블 기반 게이트를 통과하기 때문
         * (§2.6 NO_PHYSICAL_TABLE과 같은 이유). 저장소 없이 검증할 수 있도록 순수 함수로 분리했다.
         */
        fun resolve(mappings: List<DemoTableMapping>, logicalTables: Set<String>): DemoMapping {
            if (logicalTables.isEmpty()) return DemoMapping.Empty

            val all = mappings.associateBy { it.logicalName.lowercase() }
            val wanted = logicalTables.map { it.lowercase() }.toSortedSet()

            val unmapped = wanted.filter { it !in all }
            if (unmapped.isNotEmpty()) return DemoMapping.Incomplete(unmapped)

            val resolved = wanted.associateWith { all.getValue(it) }
            val bad = resolved.values.filterNot {
                IDENTIFIER.matches(it.physicalName) && IDENTIFIER.matches(it.logicalName)
            }
            if (bad.isNotEmpty()) return DemoMapping.Invalid(bad.map { "${it.logicalName} → ${it.physicalName}" })

            return DemoMapping.Resolved(resolved.mapValues { (_, m) -> m.physicalName })
        }
    }
}
