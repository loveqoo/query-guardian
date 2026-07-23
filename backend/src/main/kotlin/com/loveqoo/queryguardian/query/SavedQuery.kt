package com.loveqoo.queryguardian.query

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

@Table("saved_query")
data class SavedQuery(
    @Id val id: Long? = null,
    val name: String,
    val dialect: String,
    @Column("sql_text") val sqlText: String,
    val purposeCode: String? = null,
    val lintReportJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SavedQueryRepository : CrudRepository<SavedQuery, Long>
