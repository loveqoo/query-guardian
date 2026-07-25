package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.SelectScope

/**
 * 루트 + 모든 자식 스코프에 시스템 룰을 실행한다. 한 스코프의 위반 = 제출 전체의 위반 (§6.2).
 * 하이브리드(spec 004): 시스템 룰 + 사용자 정의 규칙 평가기의 위반을 **순수 union** — 억제 없음,
 * BLOCK은 어느 계층에서 와도 차단.
 */
class RuleEngine(
    private val rules: List<Rule>,
    private val userRuleEvaluator: UserRuleEvaluator? = null,
) {

    fun lint(ir: QueryIR, catalog: TableCatalog, context: LintContext): LintReport {
        val violations = mutableListOf<Violation>()
        fun walk(scope: SelectScope) {
            scope.unverifiable?.let {
                violations += Violation("unverifiable-scope", Severity.BLOCK, "검증 불가한 쿼리 형태라 차단합니다: $it")
            }
            rules.forEach { violations += it.check(scope, catalog, context) }
            scope.children.forEach(::walk)
        }
        walk(ir.root)
        userRuleEvaluator?.let { violations += it.evaluate(ir, catalog) } // 사용자 규칙 합류 (§6)
        return LintReport(violations.distinct())
    }

    companion object {
        fun withDefaultRules(maxLimit: Long = 1000, userRuleEvaluator: UserRuleEvaluator? = null): RuleEngine =
            RuleEngine(
                listOf(
                    NoSelectStarRule(), RequireLimitRule(maxLimit),
                    RequirePartitionKeyRule(), RequirePredicateRule(),
                    NoBlockedColumnRule(), UnknownTableRule(), MustBeMaskedRule(),
                ),
                userRuleEvaluator,
            )
    }
}
