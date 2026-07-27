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

    /**
     * MASK 대상 컬럼 이름 집합 — **정규화까지 끝난 어휘**다.
     *
     * 예전에는 `maskExpressions(t).map { it.column.lowercase() }.toSet()`이 계획 수립기와 게이트에
     * **각각** 적혀 있었다. §3.0.3이 요구하는 것은 검증기가 계획과 **독립적으로** 기대치를 얻는 것인데,
     * 같은 식을 복사하는 것은 독립이 아니다 — 한쪽에서 `lowercase()`가 빠지면 두 축이 조용히 갈라진다
     * (learning 011: "식별자 비교 한 곳의 `norm()` 누락이 금지 목록 전체를 무효화").
     * 독립성은 "계획을 기대치로 쓰지 않는다"로 확보하고, **어휘는 여기 한 곳**에서 나온다.
     */
    fun maskedColumns(tableName: String): Set<String> =
        maskExpressions(tableName).map { it.column.lowercase() }.toSet()
}

@Component
class DbRewriteCatalog(
    private val bindings: ConstraintBindingReader,
    private val objectMapper: ObjectMapper,
) : RewriteCatalog {

    /**
     * 재작성이 읽는 강제식은 **MASK 하나**로 줄었다 (spec 013 S2).
     * FILTER·INTEGRITY는 주입이 사라지면서 **판정만의 어휘**가 됐다 — `TableCatalog.requiredPredicates`.
     * purpose 스코프도 그쪽으로 갔다: 재작성은 이제 purpose를 알 필요가 없다.
     */
    override fun maskExpressions(tableName: String): List<ForcedExpression> =
        bindings.forTable(tableName)
            .filter { it.def.kind == DefKind.MASK }
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
