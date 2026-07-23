package com.loveqoo.queryguardian.lint

import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.ParseResult
import com.loveqoo.queryguardian.rules.LintContext
import com.loveqoo.queryguardian.rules.LintReport
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.rules.TableCatalog
import com.loveqoo.queryguardian.rules.Violation

/** 게이트 진입점: 파스 실패도 룰 위반과 같은 형태의 BLOCK 위반으로 내려준다 (게이트는 500을 내지 않는다, §5.1). */
class LintService(
    private val parser: DialectParser,
    private val engine: RuleEngine,
    private val catalog: TableCatalog,
) {
    fun lint(sql: String, purposeCode: String? = null): LintReport =
        when (val result = parser.parse(sql)) {
            is ParseResult.Failure -> LintReport(
                listOf(Violation("parse/${result.kind.name.lowercase().replace('_', '-')}", Severity.BLOCK, result.message))
            )
            is ParseResult.Success -> engine.lint(result.ir, catalog, LintContext(purposeCode))
        }
}
