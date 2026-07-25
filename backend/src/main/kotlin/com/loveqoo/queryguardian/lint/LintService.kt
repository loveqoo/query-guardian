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
    /**
     * 형식 위반(spec 008 §2.6)을 **룰 판정과 함께** 보고한다.
     *
     * 스펙은 형식 검사를 실행 게이트에 뒀지만 실행 대상 = 저장된 쿼리이므로 두 집합이 같다. 저장·lint 시점에
     * 잡으면 같은 방어를 유지하면서 "승인까지 받았는데 실행 불가"를 없앤다. **단락시키지 않고 추가 위반**으로
     * 넣는 이유: 주석으로 가려진 쿼리도 나머지 룰 판정 결과를 함께 보여줘야 사용자가 무엇을 고칠지 안다.
     */
    fun lint(sql: String, purposeCode: String? = null): LintReport {
        val inspected = parser.inspect(sql)
        val formViolations = inspected.formViolations.map {
            Violation("form/${it.code.name.lowercase().replace('_', '-')}", Severity.BLOCK, it.message)
        }
        return when (val result = inspected.parse) {
            is ParseResult.Failure -> LintReport(
                formViolations + Violation(
                    "parse/${result.kind.name.lowercase().replace('_', '-')}",
                    Severity.BLOCK,
                    result.message,
                )
            )
            is ParseResult.Success -> LintReport(
                formViolations + engine.lint(result.ir, catalog, LintContext(purposeCode)).violations
            )
        }
    }
}
