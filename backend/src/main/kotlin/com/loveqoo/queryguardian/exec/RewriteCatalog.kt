package com.loveqoo.queryguardian.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.loveqoo.queryguardian.catalog.ConstraintBindingReader
import com.loveqoo.queryguardian.catalog.DefKind
import com.loveqoo.queryguardian.catalog.Expressions
import org.springframework.stereotype.Component

/**
 * 강제식 하나 — 재작성이 쓰는 형태.
 *
 * [template]은 `{col}` 자리표시자를 가진 강제식이고 `:param`은 이미 치환됐다. **null이면 재작성 불가**
 * (강제식 미등록·params 누락·`{col}` 없음) → 계획 수립자가 **거부**해야 한다. 조용히 건너뛰면
 * "마스킹이 걸린 줄 알았는데 평문이 나가는" 최악의 실패가 된다.
 */
data class ForcedExpression(val column: String, val template: String?, val label: String)

/**
 * 재작성이 필요로 하는 카탈로그 정보 — 판정용 `TableCatalog`와 **다른 축**이다.
 * 판정은 구조 비교 가능한 정규형을 원하고, 재작성은 `{col}` 자리를 가진 강제식 원문을 원한다.
 *
 * 인자는 언제나 **논리** 테이블명이다(spec 008 §3 원칙).
 */
interface RewriteCatalog {
    fun maskExpressions(tableName: String): List<ForcedExpression>
    fun filterExpressions(tableName: String, purposeCode: String?): List<ForcedExpression>
    fun integrityExpressions(tableName: String): List<ForcedExpression>
}

@Component
class DbRewriteCatalog(
    private val bindings: ConstraintBindingReader,
    private val objectMapper: ObjectMapper,
) : RewriteCatalog {

    override fun maskExpressions(tableName: String): List<ForcedExpression> =
        expressions(tableName, DefKind.MASK, purposeCode = null, purposeScoped = false)

    /** FILTER는 purpose 스코프를 갖는다 — 항상 적용(null) + 현재 purpose에 등록된 것만. */
    override fun filterExpressions(tableName: String, purposeCode: String?): List<ForcedExpression> =
        expressions(tableName, DefKind.FILTER, purposeCode, purposeScoped = true)

    override fun integrityExpressions(tableName: String): List<ForcedExpression> =
        expressions(tableName, DefKind.INTEGRITY, purposeCode = null, purposeScoped = false)

    private fun expressions(
        tableName: String,
        kind: DefKind,
        purposeCode: String?,
        purposeScoped: Boolean,
    ): List<ForcedExpression> =
        bindings.forTable(tableName)
            .filter { it.def.kind == kind }
            .filter { !purposeScoped || it.mapping.purposeCode == null || it.mapping.purposeCode == purposeCode }
            .map { bound ->
                val expression = bound.def.expression
                val params = Expressions.parseParams(objectMapper, bound.mapping.paramsJson)
                val template = when {
                    expression == null || params == null -> null
                    !expression.contains(Expressions.COL) -> null // {col}이 없으면 어느 컬럼을 감쌀지 알 수 없다
                    else -> Expressions.substituteParams(expression, params)
                }
                ForcedExpression(bound.column.name, template, bound.def.name)
            }
}
