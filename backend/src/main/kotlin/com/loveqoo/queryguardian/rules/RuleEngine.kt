package com.loveqoo.queryguardian.rules

import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.SelectScope

/** 루트 + 모든 자식 스코프에 룰을 실행한다. 한 스코프의 위반 = 제출 전체의 위반 (§6.2). */
class RuleEngine(private val rules: List<Rule>) {

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
        return LintReport(violations.distinct())
    }

    companion object {
        fun withDefaultRules(maxLimit: Long = 1000): RuleEngine = RuleEngine(
            listOf(
                NoSelectStarRule(), RequireLimitRule(maxLimit),
                RequirePartitionKeyRule(), RequirePredicateRule(),
                NoBlockedColumnRule(), UnknownTableRule(),
            )
        )
    }
}
