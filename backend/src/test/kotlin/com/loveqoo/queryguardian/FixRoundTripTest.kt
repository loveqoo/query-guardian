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
        ).lint(sql, purposeCode = null)

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
     * **`frontend/src/api/fix.ts`와 같은 규칙이어야 한다.** 예전에는 그 약속을 주석으로만 적어 뒀고
     * 실제로 갈라져 있었다: 여기서는 `" WHERE "`를 리터럴 공백으로 찾고 저쪽은 `\s`로 찾아서,
     * 줄바꿈 SQL에서 여기만 WHERE를 하나 더 만들었다. 이제 [applyFixCases]가 양쪽을 같은 표로 묶는다.
     *
     * **확신할 수 없으면 고치지 않는다.** 텍스트 조작이므로 괄호 깊이와 문자열 리터럴을 보고,
     * 판단할 수 없으면(최상위 WHERE가 여럿 = UNION) 원본을 그대로 돌려준다.
     */
    private fun applyFix(sql: String, fix: Fix): String {
        val shape = scan(sql)
        return when (fix) {
            is Fix.ReplaceProjection -> {
                // 경계 셋: 앞이 식별자/점/여는 괄호면 안 되고(마지막이 이중 마스킹을 막는다),
                // 뒤가 식별자/여는 괄호여도 안 된다. 문자열 리터럴 안도 건드리지 않는다.
                val hit = Regex("(?<![\\w.(])${Regex.escape(fix.from)}(?![\\w(])")
                    .findAll(sql).firstOrNull { !shape.inLiteral[it.range.first] }
                if (hit == null) sql
                else sql.substring(0, hit.range.first) + fix.to + sql.substring(hit.range.last + 1)
            }
            is Fix.AddPredicate -> {
                val wheres = topLevel(sql, Regex("\\sWHERE\\s", RegexOption.IGNORE_CASE), shape)
                when {
                    // 최상위 WHERE가 둘 이상 = 여러 갈래(UNION). 어느 갈래인지 조각이 말해 주지 않는다.
                    wheres.size > 1 -> sql
                    wheres.size == 1 -> wheres[0].let { w ->
                        // 매치한 공백을 그대로 되돌려 사용자의 줄바꿈·들여쓰기를 보존한다.
                        sql.substring(0, w.range.first) + w.value + "${fix.predicate} AND " +
                            sql.substring(w.range.last + 1)
                    }
                    else -> {
                        val tails = topLevel(
                            sql,
                            Regex("\\s(LIMIT|GROUP\\s+BY|ORDER\\s+BY|HAVING)\\s", RegexOption.IGNORE_CASE),
                            shape,
                        )
                        if (tails.isEmpty()) "$sql WHERE ${fix.predicate}"
                        else sql.substring(0, tails[0].range.first) + " WHERE ${fix.predicate}" +
                            sql.substring(tails[0].range.first)
                    }
                }
            }
        }
    }

    /** 인덱스별 괄호 깊이와 "문자열 리터럴 안인가" — 파서가 아니라 **물러날 자리를 알기 위한** 최소 정보. */
    private class Shape(val depth: IntArray, val inLiteral: BooleanArray)

    private fun scan(sql: String): Shape {
        val depth = IntArray(sql.length)
        val inLiteral = BooleanArray(sql.length)
        var level = 0
        var quote: Char? = null
        var i = 0
        while (i < sql.length) {
            val ch = sql[i]
            if (quote != null) {
                inLiteral[i] = true
                depth[i] = level
                if (ch == quote) {
                    if (i + 1 < sql.length && sql[i + 1] == quote) {
                        // `''` — 리터럴 안의 이스케이프다. 닫는 것이 아니다.
                        inLiteral[i + 1] = true
                        depth[i + 1] = level
                        i++
                    } else {
                        quote = null
                    }
                }
                i++
                continue
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch
                inLiteral[i] = true
                depth[i] = level
                i++
                continue
            }
            if (ch == '(') level++
            depth[i] = level
            if (ch == ')') level = maxOf(0, level - 1)
            i++
        }
        return Shape(depth, inLiteral)
    }

    /** 괄호 밖(깊이 0)이고 리터럴이 아닌 매치만. **키워드 위치**로 판단한다 — 앞 공백은 깊이가 다를 수 있다. */
    private fun topLevel(sql: String, regex: Regex, shape: Shape): List<MatchResult> =
        regex.findAll(sql).filter { m ->
            val keywordAt = m.range.first + (m.value.length - m.value.trimStart().length)
            shape.depth[keywordAt] == 0 && !shape.inLiteral[keywordAt]
        }.toList()

    /**
     * **적용기 계약 표** — `tests/apply-fix-cases.json`(저장소 루트)을 화면 쪽과 함께 읽는다.
     *
     * 구현이 두 벌인 것은 불가피하다(브라우저에서 Kotlin을 못 돌린다). 케이스까지 두 벌이면
     * 갈라져도 아무도 모른다 — 이 표가 그 자리를 빨간불로 만든다.
     */
    private data class ApplyCase(val name: String, val why: String?, val sql: String, val fix: Fix, val expected: String)

    private fun applyFixCases(): List<ApplyCase> {
        val path = java.nio.file.Path.of("..", "tests", "apply-fix-cases.json")
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(path))
        return root["cases"].map { node ->
            val f = node["fix"]
            val fix = when (val kind = f["kind"].asText()) {
                "REPLACE_PROJECTION" -> Fix.ReplaceProjection(
                    f["table"].asText(), f["column"].asText(), f["from"].asText(), f["to"].asText())
                "ADD_PREDICATE" -> Fix.AddPredicate(f["table"].asText(), f["column"].asText(), f["to"].asText())
                else -> error("표에 모르는 조각 종류가 있다: $kind")
            }
            ApplyCase(
                node["name"].asText(), node["why"]?.asText(),
                node["sql"].asText(), fix, node["expected"].asText(),
            )
        }
    }

    @Test
    fun `적용기가 화면과 같은 규칙을 지킨다`() {
        val cases = applyFixCases()
        // 경로가 틀리거나 표가 비면 아래 루프가 0회 돌고 **전부 통과**한다 — 그 착시를 먼저 막는다.
        assertTrue(cases.size > 5, "케이스 표를 못 읽었다(${cases.size}건) — tests/apply-fix-cases.json 경로를 확인하라")
        for (case in cases) {
            assertEquals(
                case.expected, applyFix(case.sql, case.fix),
                "${case.name}${case.why?.let { " — $it" } ?: ""}\n  화면(frontend/src/api/fix.ts)과 갈라졌다",
            )
        }
    }

    /**
     * [noFixExpected]는 **"조각을 못 준다는 것이 정답"**인 형태다. 그런 형태를 시나리오에서 빼면
     * 표본이 통과하기 쉬운 것만 남는다 — 여기 넣어 두면 나중에 조각을 줄 수 있게 됐을 때
     * 이 자리가 빨간불로 알려 준다(현행 고정의 반대 방향).
     */
    private data class Scenario(
        val name: String,
        val sql: String,
        val rules: List<UserRule> = emptyList(),
        val noFixExpected: String? = null,
    )

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
        // ── 여기부터는 적대 검토가 반례로 제시한 형태다. 단정을 "그 위반만"으로 바꾼 덕에 들어올 수 있다.
        Scenario(
            "CTE — 조각이 겨눈 곳은 바깥인데 첫 WHERE는 CTE 안에 있다",
            "WITH recent AS (SELECT user_id FROM marketing_consents WHERE consent_yn IS NOT NULL) " +
                "SELECT c.id FROM marketing_consents c JOIN recent r ON r.user_id = c.id LIMIT 10",
        ),
        Scenario(
            "UNION — 두 팔이 각각 조건을 요구한다",
            "SELECT id FROM marketing_consents a WHERE a.id > 0 " +
                "UNION ALL SELECT id FROM marketing_consents b WHERE b.id < 0",
            noFixExpected = "두 팔은 UNION_ARM 스코프라 조각을 주지 않는다 — 적용기가 어느 팔인지 알 수 없다",
        ),
        Scenario(
            "문자열 리터럴 안에 컬럼 이름과 같은 글자가 있다",
            "SELECT 'email' AS tag, email FROM users LIMIT 10",
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

    /** 이 시나리오에서 나온 조각들. [Scenario.noFixExpected]가 없는데 조각도 없으면 실패다(막혔는데 고칠 방법을 안 줬다). */
    private fun fixesOf(scenario: Scenario): List<Fix> {
        val report = lint(scenario.sql, *scenario.rules.toTypedArray())
        assertTrue(report.blocked, "${scenario.name}: 막히지 않았다 — 시나리오가 더 이상 위반이 아니다: ${report.violations}")
        val withFix = report.violations.filter { it.fix != null }
        if (scenario.noFixExpected != null) {
            assertTrue(
                withFix.isEmpty(),
                "${scenario.name}: 조각을 안 줄 자리인데 줬다(${scenario.noFixExpected}) — " +
                    "적용기가 안전하게 넣을 수 있게 됐다면 이 시나리오의 기대를 바꿔라: ${withFix.map { it.fix }}",
            )
            return emptyList()
        }
        assertTrue(
            withFix.isNotEmpty(),
            "${scenario.name}: 막았는데 고칠 방법이 없다 — 사용자는 여기서 멈춘다: ${report.violations.map(Violation::message)}",
        )
        return withFix.mapNotNull { it.fix }
    }

    /**
     * **단정은 "그 조각이 겨눈 위반이 사라졌는가"다** — "위반이 전부 사라졌는가"가 아니다.
     *
     * 처음에는 `!after.blocked`로 썼는데, 그 기준은 **어려운 형태를 구조적으로 배제**했다(적대 검토가
     * 지적): 셀프 조인·UNION·제약 둘인 테이블처럼 위반이 여럿 나오는 쿼리는 조각 하나로 전부 없앨 수
     * 없으므로, 시나리오로 추가하는 순간 빨간불이 되고 그래서 아무도 추가하지 않는다. 통과하기 쉬운
     * 형태만 남아 "왕복을 잰다"는 말이 표본 안에서만 참이 된다(retrospect 019: 분모를 밝혀라).
     *
     * 그래서 조각마다 **자기 위반**만 본다. 남은 위반은 남은 조각이 각각 담당한다.
     */
    @Test
    fun `제안을 적용하면 그 위반이 사라진다`() {
        for (scenario in scenarios) {
            val before = lint(scenario.sql, *scenario.rules.toTypedArray())
            for (violation in before.violations.filter { it.fix != null }) {
                val fix = violation.fix!!
                val fixed = applyFix(scenario.sql, fix)
                assertTrue(fixed != scenario.sql, "${scenario.name}: 적용해도 SQL이 그대로다 — $fix")
                val after = lint(fixed, *scenario.rules.toTypedArray())
                assertTrue(
                    after.violations.none { it.ruleId == violation.ruleId && it.message == violation.message },
                    "${scenario.name}: 제안대로 고쳤는데 **그 위반이** 그대로다" +
                        "\n  before: ${scenario.sql}\n  위반: ${violation.message}\n  fix: $fix" +
                        "\n  after:  $fixed\n  남은 위반: ${after.violations.map(Violation::message)}",
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
