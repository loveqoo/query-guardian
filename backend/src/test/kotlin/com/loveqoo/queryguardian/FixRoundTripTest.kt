package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.rules.Fix
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.RuleCondition
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.RuleGroup
import com.loveqoo.queryguardian.rules.RuleOp
import com.loveqoo.queryguardian.rules.RuleScope
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.rules.UserRule
import com.loveqoo.queryguardian.rules.UserRuleEvaluator
import com.loveqoo.queryguardian.rules.Violation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **제안을 적용하면 판정을 통과한다** (spec 012 I3 · spec 013 S3).
 *
 * ## 왜 왕복인가
 *
 * 제안은 "그럴듯한 문장"이면 안 된다. 사용자가 그대로 적용했을 때 **실제로 통과**해야 하고,
 * 아니면 사용자는 우리가 시킨 대로 하고도 막힌 채 남는다 — 그 순간 제품이 성립하지 않는다.
 * 제안과 판정이 같은 출처(등록된 강제식)를 보므로 원리상 갈라질 수 없지만, "원리상"은 측정이 아니다.
 *
 * ## 여기 있는 적용기가 곧 화면의 계약이다
 *
 * [applyFix]는 화면이 `[적용]` 버튼에서 할 일을 그대로 한다. 서버는 SQL을 만들어 주지 않으므로
 * (spec 012 §9 — 조각만 준다), **적용의 의미는 이 함수가 정의한다.** 화면이 다르게 적용하면
 * 그 화면에서만 왕복이 깨진다.
 *
 * ## 종류를 빠뜨리면 실패한다
 *
 * [모든 조각 종류가 왕복으로 검증된다]가 `Fix`의 sealed 하위 타입을 전수로 세어 대조한다.
 * 새 종류를 추가하고 시나리오를 안 쓰면 그 자리에서 빨간불이 켜진다 — 이 파일이 "일부만 재고
 * 전부 잰 척"하지 않게 하는 장치다(retrospect 019: 표본으로 안전을 보지 않는다).
 */
class FixRoundTripTest {

    private val catalog = InMemoryTableCatalog(
        tables = setOf("users", "marketing_consents"),
        masked = mapOf("users" to setOf("email")),
        maskTemplates = mapOf("users" to mapOf("email" to "mask_email({col})")),
        required = listOf(
            InMemoryTableCatalog.Entry(
                table = "marketing_consents",
                purposeCode = null,
                predicate = com.loveqoo.queryguardian.rules.RequiredPredicate(
                    name = "동의 필수",
                    column = "consent_yn",
                    template = "{col} = 'Y'",
                    predicate = Fixtures.parsedFixture("consent_yn = 'Y'"),
                ),
            ),
        ),
        conditionPredicates = mapOf(
            100L to com.loveqoo.queryguardian.rules.ConditionPredicate(
                com.loveqoo.queryguardian.rules.RequiredForm("consent_yn", "Y"),
                "{col} = 'Y'",
            ),
        ),
    )

    private fun lint(sql: String, vararg rules: UserRule) =
        LintService(
            Fixtures.parser,
            RuleEngine.withDefaultRules(userRuleEvaluator = UserRuleEvaluator { rules.toList() }),
            catalog,
        ).lint(sql)

    private fun userRule(op: RuleOp, table: String, column: String) = UserRule(
        1, "테스트 규칙", RuleScope.SINGLE, true,
        RuleGroup(
            RuleGroup.Combinator.ALL,
            listOf(RuleCondition(op, Severity.BLOCK, table = table, column = column, defId = 100)),
        ),
    )

    /**
     * 화면의 `[적용]`이 하는 일. **서버를 부르지 않는다** — 에디터 텍스트만 고친다(spec 013 F4).
     *
     * 단순한 텍스트 조작인 이유: 제안은 조각이고, 조각을 어디에 넣을지는 문법이 이미 정해 준다.
     * 여기서 SQL을 다시 조립하면 그것이 재작성이다.
     */
    private fun applyFix(sql: String, fix: Fix): String = when (fix) {
        // 투영 자리의 컬럼 참조 하나를 강제식으로 바꾼다. 경계 셋이 모두 필요하다 —
        // 앞이 식별자/점/**여는 괄호**면 안 되고(마지막 것이 이미 감싼 것을 또 감싸는 걸 막는다),
        // 뒤가 식별자/여는 괄호여도 안 된다. 화면의 `src/api/fix.ts`와 **같은 규칙**이다.
        is Fix.ReplaceProjection ->
            sql.replaceFirst(Regex("(?<![\\w.(])${Regex.escape(fix.from)}(?![\\w(])"), fix.to)
        // 최상위 WHERE에 AND로 잇는다. WHERE가 없으면 FROM 뒤(다음 절 앞)에 새로 만든다.
        is Fix.AddPredicate ->
            if (sql.contains(" WHERE ", ignoreCase = true)) {
                sql.replaceFirst(Regex(" WHERE ", RegexOption.IGNORE_CASE), " WHERE ${fix.predicate} AND ")
            } else {
                val tail = Regex(" (LIMIT|GROUP BY|ORDER BY|HAVING) ", RegexOption.IGNORE_CASE).find(sql)
                if (tail == null) "$sql WHERE ${fix.predicate}"
                else sql.substring(0, tail.range.first) + " WHERE ${fix.predicate}" + sql.substring(tail.range.first)
            }
    }

    private data class Scenario(val name: String, val sql: String, val rules: List<UserRule> = emptyList())

    private val scenarios = listOf(
        Scenario("시스템 룰 — 맨몸 마스킹 컬럼", "SELECT email FROM users LIMIT 10"),
        Scenario("시스템 룰 — 필수 조건 누락", "SELECT id FROM marketing_consents LIMIT 10"),
        Scenario(
            "사용자 규칙 — requires",
            "SELECT id FROM marketing_consents LIMIT 10",
            listOf(userRule(RuleOp.REQUIRES, "marketing_consents", "consent_yn")),
        ),
        Scenario(
            "사용자 규칙 — must_be_masked",
            "SELECT email FROM users LIMIT 10",
            listOf(userRule(RuleOp.MUST_BE_MASKED, "users", "email")),
        ),
        Scenario(
            "필수 조건 — 이미 다른 WHERE가 있을 때",
            "SELECT id FROM marketing_consents WHERE id > 0 LIMIT 10",
        ),
    )

    /**
     * **적용기가 이미 가려진 것을 또 감싸지 않는가.**
     *
     * 화면 쪽 계약 테스트(`frontend/tests/apply-fix.spec.ts`)가 이 결함을 먼저 잡았고, 같은 정규식이
     * 여기에도 있었다. 판정상으로는 이 상황이 위반이 아니라 왕복 시나리오로는 안 잡힌다 —
     * 적용기를 직접 겨눠야 보인다.
     */
    @Test
    fun `이미 가려진 투영은 적용기가 건드리지 않는다`() {
        val sql = "SELECT mask_email(email) FROM users LIMIT 10"
        val fix = Fix.ReplaceProjection("users", "email", from = "email", to = "mask_email(email)")
        assertEquals(sql, applyFix(sql, fix), "이미 가려진 것을 또 감쌌다 — 이중 마스킹은 사용자 의도를 바꾼다")
    }

    /** 이 시나리오에서 나온 조각들 — 없으면 그 자체가 실패다(막혔는데 고칠 방법을 안 줬다). */
    private fun fixesOf(scenario: Scenario): List<Fix> {
        val report = lint(scenario.sql, *scenario.rules.toTypedArray())
        assertTrue(report.blocked, "${scenario.name}: 막히지 않았다 — 시나리오가 더 이상 위반이 아니다: ${report.violations}")
        val withFix = report.violations.filter { it.fix != null }
        assertTrue(
            withFix.isNotEmpty(),
            "${scenario.name}: 막았는데 고칠 방법이 없다 — 사용자는 여기서 멈춘다: ${report.violations.map(Violation::message)}",
        )
        return withFix.mapNotNull { it.fix }
    }

    @Test
    fun `제안을 적용하면 그 위반이 사라진다`() {
        for (scenario in scenarios) {
            for (fix in fixesOf(scenario)) {
                val fixed = applyFix(scenario.sql, fix)
                assertTrue(fixed != scenario.sql, "${scenario.name}: 적용해도 SQL이 그대로다 — $fix")
                val after = lint(fixed, *scenario.rules.toTypedArray())
                assertTrue(
                    !after.blocked,
                    "${scenario.name}: 제안대로 고쳤는데 여전히 막힌다\n  before: ${scenario.sql}\n  fix: $fix" +
                        "\n  after:  $fixed\n  위반: ${after.violations.map(Violation::message)}",
                )
            }
        }
    }

    /**
     * 조각의 **내용**이 등록된 강제식에서 나왔는지. 통과만 재면 "아무 말이나 넣고 위반이 사라지는"
     * 경우를 구별하지 못한다 — 예전에 `CONCAT(email,'')`이 그렇게 통과했다(적대 검토 CRITICAL).
     */
    @Test
    fun `조각의 내용은 등록된 강제식이다`() {
        val mask = fixesOf(scenarios[0]).filterIsInstance<Fix.ReplaceProjection>().single()
        assertEquals("mask_email(email)", mask.to)
        assertEquals("email", mask.from)
        assertEquals("users", mask.table)

        val predicate = fixesOf(scenarios[1]).filterIsInstance<Fix.AddPredicate>().single()
        assertEquals("marketing_consents.consent_yn = 'Y'", predicate.predicate)
        assertEquals("marketing_consents", predicate.table)
    }

    /**
     * **전수 대조** — 시나리오가 `Fix`의 모든 하위 타입을 덮는가.
     *
     * `sealedSubclasses`는 추측이 아니라 컴파일러가 아는 목록이므로, 형태를 손으로 나열할 때 생기는
     * 조용한 누락이 없다(learning 020). 새 종류를 추가하고 시나리오를 안 쓰면 여기서 막힌다.
     */
    @Test
    fun `모든 조각 종류가 왕복으로 검증된다`() {
        val covered = scenarios.flatMap { fixesOf(it) }.map { it::class }.toSet()
        val declared = Fix::class.sealedSubclasses.toSet()
        assertEquals(
            declared, covered,
            "왕복이 재지 않는 조각 종류가 있다: ${declared - covered} — 종류를 늘렸으면 시나리오도 늘려야 한다",
        )
    }
}
