package com.loveqoo.queryguardian.lint

import com.loveqoo.queryguardian.parser.DialectParser
import com.loveqoo.queryguardian.parser.InspectResult
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
     * 접수 위반(spec 008 §2.6)을 **룰 판정과 함께** 보고한다.
     *
     * 스펙은 접수 검사를 실행 게이트에 뒀지만 실행 대상 = 저장된 쿼리이므로 두 집합이 같다. 저장·lint 시점에
     * 잡으면 같은 방어를 유지하면서 "승인까지 받았는데 실행 불가"를 없앤다. **단락시키지 않고 추가 위반**으로
     * 넣는 이유: 주석으로 가려진 쿼리도 나머지 룰 판정 결과를 함께 보여줘야 사용자가 무엇을 고칠지 안다.
     */
    fun lint(sql: String, purposeCode: String? = null): LintReport = judge(parser.inspect(sql), purposeCode)

    /**
     * **이미 파싱한 결과**로 판정한다 — 실행 게이트는 IR·핸들·접수 위반을 한 번의 파싱으로 얻어
     * 판정과 재작성이 같은 AST를 쓴다(spec 008 결정 13). `lint(sql)`이 다시 파싱하면 그 보장이 깨진다.
     */
    fun judge(inspected: InspectResult, purposeCode: String? = null): LintReport {
        val intakeViolations = inspected.intakeViolations.map {
            Violation("intake/${it.code.name.lowercase().replace('_', '-')}", Severity.BLOCK, it.message)
        }
        return when (inspected) {
            is InspectResult.Unparsed -> LintReport(
                intakeViolations + Violation(
                    "parse/${inspected.failure.kind.name.lowercase().replace('_', '-')}",
                    Severity.BLOCK,
                    inspected.failure.message,
                )
            )
            is InspectResult.Parsed -> LintReport(
                intakeViolations + engine.lint(inspected.ir, catalog, LintContext(purposeCode)).violations
            )
        }
    }
}
