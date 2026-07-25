package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.MaskUsage
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.SelectItem
import com.loveqoo.queryguardian.ir.maskFindings
import com.loveqoo.queryguardian.ir.SelectScope

/**
 * 재작성 결과를 **다시 파싱해** 계획대로 됐는지 단정한다 (spec 008 §3.0.3).
 *
 * 판정된 AST를 제자리에서 고치므로(§3.5 결정 13) 판정-실행 분기는 이미 구조적으로 막혀 있다.
 * 이 검증은 그와 **독립된 이중 방어**다 — 재작성 코드에 버그가 있어도 잘못된 SQL이 실행되지 않게 한다.
 * 검증 대상은 "실제로 실행될 텍스트"이므로, 재작성 산출물을 문자열에서 다시 읽는 것이 요점이다.
 *
 * 실패는 전부 차단이다(부분 적용 없음, 결정 6).
 */
class RewriteVerifier(private val parser: DialectParser) {

    /**
     * 위반 사유 목록. 비어 있으면 통과.
     *
     * [judgedIr]와 [maskedColumnsOf]는 **계획 밖의 근거**다. 기대치를 계획에서만 뽑으면 검증기는 정의상
     * "계획이 마스킹을 빠뜨린 경우"에 눈이 먼다 — 적대 검토가 실제로 그 경로(계획 A를 핸들 B에 적용)로
     * 평문이 나가는 것을 실증했다. 그래서 판정에 쓰인 IR과 카탈로그로 **기대 마스킹을 다시 도출**해 대조한다.
     */
    fun verify(
        rewrittenSql: String,
        plan: RewritePlan,
        judgedIr: QueryIR,
        maskedColumnsOf: (String) -> Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()

        // ⓐ 계획 밖의 근거: 판정 IR에서 마스킹이 필요했던 지점을 재도출해 계획과 대조한다.
        for (scope in allScopes(judgedIr.root)) {
            for (finding in maskFindings(scope) { table -> maskedColumnsOf(table) }) {
                when (finding.usage) {
                    MaskUsage.ABSENT -> Unit
                    MaskUsage.NOT_EXPRESSIBLE -> problems +=
                        "표현할 수 없는 마스킹 사용이 남아 있는데 재작성이 진행됐습니다: " +
                            "${finding.logicalTable}.${finding.column}"
                    MaskUsage.PROJECTION_ONLY -> {
                        val planned = plan.maskProjections.any {
                            it.instanceKey == finding.instanceKey && it.column.equals(finding.column, ignoreCase = true)
                        }
                        if (!planned) {
                            problems += "계획이 마스킹을 빠뜨렸습니다: ${finding.logicalTable}.${finding.column} " +
                                "(인스턴스 ${finding.instanceKey})"
                        }
                    }
                }
            }
        }
        val inspected = parser.inspect(rewrittenSql)

        // ⑴ 문 1개·SELECT — 재작성이 문장을 깨뜨렸거나 문 종류를 바꾸지 않았는가
        val ir = when (val result = inspected.parse) {
            is ParseResult.Failure -> return listOf("재작성 결과를 다시 파싱할 수 없습니다: ${result.message}")
            is ParseResult.Success -> result.ir
        }

        // ⑹ 위생 재검사 — 재작성 산출물도 위생 게이트를 통과해야 한다(왕복 정합성). 프린터가 만든
        //    텍스트가 주석·변수처럼 읽히면 실행 시점에 의미가 달라진다.
        if (inspected.hygiene.isNotEmpty()) {
            problems += "재작성 결과가 위생 게이트에 걸립니다: ${inspected.hygiene.map { it.code }}"
        }

        val scopes = allScopes(ir.root)

        // ⑵ 물리 테이블 집합 — 치환 대상 논리명이 남아 있으면 데모가 아닌 실제 테이블을 조회할 수 있다
        val physical = scopes.flatMap { s -> s.tables.filter { it.physical }.map { it.name.lowercase() } }.toSet()
        for (rename in plan.tableRenames) {
            if (rename.physicalName.lowercase() !in physical) {
                problems += "물리 테이블 치환이 적용되지 않았습니다: ${rename.logicalName} → ${rename.physicalName}"
            }
            if (rename.logicalName.lowercase() != rename.physicalName.lowercase() &&
                rename.logicalName.lowercase() in physical
            ) {
                problems += "치환 대상 논리 테이블이 아직 남아 있습니다: ${rename.logicalName}"
            }
        }

        // ⑶ 주입 술어가 어느 스코프의 **최상위 conjunct**로 존재하는가
        //    (재파싱하면 scopeId가 새로 발급되므로 스코프를 id로 지목할 수 없다 — 존재로 검증한다.
        //     whereConjuncts 자체가 "최상위 AND만" 담는 축이므로, 여기 있다는 것이 곧 최상위라는 뜻이다.)
        for (injection in plan.injections) {
            if (injection.alreadySatisfied) continue // 주입하지 않았으므로 검증 대상이 아니다
            val expected = parser.parsePredicate(injection.predicateSql)
            val found = scopes.any { scope ->
                scope.whereConjuncts.any { matches(it, expected, injection.predicateSql) }
            }
            if (!found) {
                problems += "주입한 술어가 최상위 조건으로 남아 있지 않습니다: ${injection.predicateSql}"
            }
        }

        // ⑷ MASK가 **계획한 그 강제식으로** 적용됐는가.
        //
        //    "bare 투영으로 남지 않았다"만 확인하면 부족하다 — 적대 검토가 실증한 대로
        //    `CONCAT(users.email, '')`처럼 **항등에 가까운 아무 표현식**으로 감싸도 통과하고, 그것은 평문을 반환한다.
        //    그래서 계획의 강제식 템플릿을 실제로 적용한 형태와 **텍스트로 대조**한다.
        for (mask in plan.maskProjections) {
            val bare = scopes.any { scope ->
                scope.selectItems.any { item ->
                    item is SelectItem.Column &&
                        item.column.table == mask.instanceKey &&
                        item.column.column.equals(mask.column, ignoreCase = true)
                }
            }
            if (bare) {
                problems += "마스킹 대상이 원본 투영으로 남아 있습니다: ${mask.instanceKey}.${mask.column}"
                continue
            }
            // 한정·비한정 두 형태 모두 정상이다: `mask_email(u.email)` / `mask_email(email)`
            val expected = setOf(
                normalize(mask.expressionTemplate.replace("{col}", "${mask.instanceKey}.${mask.column}")),
                normalize(mask.expressionTemplate.replace("{col}", mask.column)),
            )
            val applied = scopes.any { scope ->
                scope.selectItems.any { item -> item is SelectItem.Expr && normalize(item.text) in expected }
            }
            if (!applied) {
                problems += "계획한 마스킹 강제식이 적용되지 않았습니다: ${mask.instanceKey}.${mask.column} " +
                    "(기대: ${mask.expressionTemplate})"
            }
        }

        // ⑸ 루트 LIMIT ≤ 상한+1 (재작성기는 truncated 판정을 위해 상한+1을 넣는다)
        plan.limitCap?.let { cap ->
            val limit = ir.root.limit
            if (limit == null || limit > cap.maxRows + 1) {
                problems += "행 상한이 적용되지 않았습니다: LIMIT ${limit ?: "없음"} (상한 ${cap.maxRows})"
            }
        }

        return problems
    }

    /**
     * 술어 동일성 — **테이블 귀속은 무시**한다.
     *
     * [DialectParser.parsePredicate]는 FROM 없이 파싱하므로 컬럼의 인스턴스 귀속이 null이지만, 재작성 결과의
     * IR은 실제 인스턴스로 귀속한다. 이 검증의 목적은 "그 술어가 최상위에 있는가"이므로 귀속 차이는 무의미하다.
     * 판정에서는 절대 이렇게 느슨하게 비교하지 않는다(§6.4 인스턴스 귀속은 판정의 안전장치다).
     */
    private fun matches(conjunct: Predicate, expected: Predicate?, rawSql: String): Boolean = when {
        expected == null -> normalize(conjunct.rawText()) == normalize(rawSql)
        expected is Predicate.Comparison && conjunct is Predicate.Comparison ->
            conjunct.column.column.equals(expected.column.column, ignoreCase = true) &&
                conjunct.op == expected.op && conjunct.value == expected.value
        expected is Predicate.InList && conjunct is Predicate.InList ->
            conjunct.column.column.equals(expected.column.column, ignoreCase = true) &&
                conjunct.values == expected.values
        expected is Predicate.Between && conjunct is Predicate.Between ->
            conjunct.column.column.equals(expected.column.column, ignoreCase = true) &&
                conjunct.low == expected.low && conjunct.high == expected.high
        // `IS NOT NULL` 같은 형태는 양쪽 모두 Raw다 — 원문 비교로 확인한다(공백·대소문자 무시).
        expected is Predicate.Raw && conjunct is Predicate.Raw ->
            normalize(conjunct.fragment) == normalize(expected.fragment)
        else -> false
    }

    private fun Predicate.rawText(): String = (this as? Predicate.Raw)?.fragment ?: toString()

    /**
     * 공백·대소문자와 **감싼 괄호**를 무시한다. 재작성기가 주입 술어를 괄호로 감싸므로(§3.0-1)
     * `Raw` 원문이 `(u.id IS NOT NULL)`로 나오는데, 그것과 `u.id IS NOT NULL`은 같은 술어다.
     */
    private fun normalize(text: String): String =
        // 백틱은 식별자 인용일 뿐이다 — `mask_email(`u`.`email`)`과 `mask_email(u.email)`은 같은 표현식이다
        // (적대 검토가 찾은 오차단: 백틱을 쓴 정상 쿼리가 전부 실행 거부됐다).
        stripOuterParens(text.replace(Regex("\\s+"), " ").trim()).replace("`", "").lowercase()

    private fun stripOuterParens(text: String): String {
        var current = text
        while (current.length > 2 && current.startsWith("(") && current.endsWith(")")) {
            var depth = 0
            var closesAtEnd = true
            for ((index, c) in current.withIndex()) {
                if (c == '(') depth++
                if (c == ')') depth--
                // 여는 괄호가 끝 이전에 닫히면 전체를 감싼 괄호가 아니다: `(a) AND (b)`
                if (depth == 0 && index < current.lastIndex) { closesAtEnd = false; break }
            }
            if (!closesAtEnd) return current
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    private fun allScopes(scope: SelectScope): List<SelectScope> =
        listOf(scope) + scope.children.flatMap { allScopes(it) }
}
