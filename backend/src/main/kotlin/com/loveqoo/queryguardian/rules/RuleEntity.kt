package com.loveqoo.queryguardian.rules

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

@Table("rule")
data class RuleEntity(
    @Id val id: Long? = null,
    val name: String,
    val scope: String,
    val server: String? = null,
    val enabled: Boolean = true,
    val treeJson: String,
    val hitCount: Long = 0,
)

interface RuleRepository : CrudRepository<RuleEntity, Long> {
    @Modifying
    @Query("UPDATE rule SET hit_count = hit_count + 1 WHERE id = :id")
    fun incrementHit(@Param("id") id: Long) // 원자적 증가 (L1)
}
