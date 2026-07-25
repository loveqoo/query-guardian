package com.loveqoo.queryguardian.parser

import com.loveqoo.queryguardian.ir.LimitCap
import com.loveqoo.queryguardian.ir.MaskProjection
import com.loveqoo.queryguardian.ir.PredicateInjection
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.RewriteKind
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.TableRename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * spec 008 §3.5 M1-4 — AST 재작성. **출력 SQL 문자열로** 검증한다(중간 상태가 아니라 실행될 텍스트가 계약이다).
 *
 * 가장 중요한 케이스는 우선순위다: 원본 WHERE가 최상위 OR인데 괄호 없이 AND 결합하면
 * `WHERE a = 1 OR b = 2 AND c = 'Y'`가 되어 주입이 한 가지에만 붙는다(실측). 그 회귀를 여기서 막는다.
 */
class SqlRewriterTest {

    private val parser = DruidMySqlParser()
    private val rewriter = SqlRewriter(parser)

    private fun inspect(sql: String): Pair<QueryIR, ParsedStatement> {
        val result = parser.inspect(sql)
        return (result.parse as ParseResult.Success).ir to result.statement!!
    }

    private fun scopes(scope: SelectScope): List<SelectScope> =
        listOf(scope) + scope.children.flatMap { scopes(it) }

    /** 재작성 결과 SQL — 개행·탭을 공백 하나로 눌러 비교하기 쉽게 만든다. */
    private fun rewrite(sql: String, plan: (QueryIR) -> RewritePlan): String {
        val (ir, handle) = inspect(sql)
        val outcome = rewriter.rewrite(handle, plan(ir))
        val rewritten = assertIs<RewriteOutcome.Rewritten>(outcome, "재작성이 거부됨: $outcome")
        return rewritten.sql.replace(Regex("\\s+"), " ").trim()
    }

    private fun refusal(sql: String, plan: (QueryIR) -> RewritePlan): RewriteOutcome.Refused {
        val (ir, handle) = inspect(sql)
        return assertIs<RewriteOutcome.Refused>(rewriter.rewrite(handle, plan(ir)))
    }

    private fun mask(ir: QueryIR, instance: String, column: String, template: String = "mask_email({col})") =
        MaskProjection(ir.root.scopeId, instance, column, template, column)

    private fun inject(ir: QueryIR, sql: String, instance: String = "t") =
        PredicateInjection(ir.root.scopeId, instance, sql, "테스트")

    // ---- 주입 정확성 ⑴ 괄호 ----

    /** 최상위 OR에 괄호 없이 AND를 붙이면 주입이 무력화된다 — 이 테스트가 그 회귀를 막는다. */
    @Test
    fun `최상위 OR인 WHERE는 괄호로 감싼 뒤 결합한다`() {
        val out = rewrite("SELECT id FROM user_events WHERE a = 1 OR b = 2") { ir ->
            RewritePlan(injections = listOf(inject(ir, "consent_yn = 'Y'", "user_events")))
        }
        // 주입 술어 자체도 괄호로 감싼다 — 템플릿이 `a = 1 OR b = 2` 같은 이항식일 때 필수다(§3.0-1)
        assertTrue(out.contains("WHERE (a = 1 OR b = 2) AND (consent_yn = 'Y')"), "괄호가 없다: $out")
    }

    @Test
    fun `WHERE가 없으면 그대로 술어가 된다`() {
        val out = rewrite("SELECT id FROM user_events") { ir ->
            RewritePlan(injections = listOf(inject(ir, "consent_yn = 'Y'", "user_events")))
        }
        assertTrue(out.contains("WHERE consent_yn = 'Y'"), out)
    }

    @Test
    fun `AND WHERE에도 붙는다`() {
        val out = rewrite("SELECT id FROM user_events WHERE a = 1 AND b = 2") { ir ->
            RewritePlan(injections = listOf(inject(ir, "c = 'Y'", "user_events")))
        }
        assertTrue(out.contains("WHERE a = 1 AND b = 2 AND (c = 'Y')"), out)
    }

    @Test
    fun `주입 술어에 서브쿼리를 쓸 수 없다`() {
        val refused = refusal("SELECT id FROM user_events") { ir ->
            RewritePlan(injections = listOf(inject(ir, "id IN (SELECT id FROM users)", "user_events")))
        }
        assertEquals(RewriteRefusal.EXPRESSION_NOT_USABLE, refused.refusal)
    }

    // ---- 주입 정확성 ⑵ LIMIT ----

    @Test
    fun `LIMIT은 상한 더하기 1로 주입된다`() {
        val added = rewrite("SELECT id FROM users") { ir -> RewritePlan(limitCap = LimitCap(ir.root.scopeId, 1000)) }
        assertTrue(added.endsWith("LIMIT 1001"), added)

        val lowered = rewrite("SELECT id FROM users LIMIT 5000") { ir ->
            RewritePlan(limitCap = LimitCap(ir.root.scopeId, 1000))
        }
        assertTrue(lowered.endsWith("LIMIT 1001"), lowered)
        assertTrue(!lowered.contains("5000"), "원본 LIMIT이 남아 있다: $lowered")
    }

    /** UNION은 팔이 아니라 **union 노드**의 limit을 조정해야 전체 결과가 제한된다 (§3.0-2). */
    @Test
    fun `UNION은 union 노드에 LIMIT을 넣는다`() {
        val out = rewrite("SELECT id FROM users UNION ALL SELECT id FROM users") { ir ->
            RewritePlan(limitCap = LimitCap(ir.root.scopeId, 100))
        }
        assertTrue(out.endsWith("LIMIT 101"), out)
    }

    // ---- 주입 정확성 ⑶ 강제식 ----

    @Test
    fun `MASK는 강제식으로 치환하고 출력 이름을 유지한다`() {
        val out = rewrite("SELECT email, name AS n FROM users") { ir -> RewritePlan(maskProjections = listOf(mask(ir, "users", "email"))) }
        assertTrue(out.contains("mask_email(email) AS email"), out)
        assertTrue(out.contains("name AS n"), "다른 항목이 변형됐다: $out")
    }

    /** 원 별칭이 있으면 그것을 유지한다 — 강제로 컬럼명을 붙이면 외부 참조가 깨진다(§3.0.1). */
    @Test
    fun `원 별칭이 있으면 별칭을 유지한다`() {
        val out = rewrite("SELECT email AS mail FROM users") { ir -> RewritePlan(maskProjections = listOf(mask(ir, "users", "email"))) }
        assertTrue(out.contains("mask_email(email) AS mail"), out)
        assertTrue(!out.contains("AS email"), "별칭이 바뀌었다: $out")
    }

    /** 한정 참조는 한정자가 보존되어야 한다 — `mask_email(email)`이 되면 조인에서 모호해진다. */
    @Test
    fun `한정 참조는 한정자를 보존한다`() {
        val out = rewrite("SELECT u.email FROM users u JOIN users v ON v.id = u.id") { ir ->
            RewritePlan(maskProjections = listOf(mask(ir, "u", "email")))
        }
        assertTrue(out.contains("mask_email(u.email) AS email"), out)
    }

    /** 셀프 조인: 계획이 `v`를 지목했으면 `u.email`은 건드리지 않는다 (§6.4 인스턴스 귀속). */
    @Test
    fun `계획이 지목한 인스턴스만 치환한다`() {
        val out = rewrite("SELECT u.email, v.email FROM users u JOIN users v ON v.id = u.id") { ir ->
            RewritePlan(maskProjections = listOf(mask(ir, "v", "email")))
        }
        assertTrue(out.contains("mask_email(v.email) AS email"), out)
        assertTrue(out.contains("u.email,") || out.contains("u.email "), "u.email이 변형됐다: $out")
    }

    @Test
    fun `강제식이 파싱되지 않으면 거부한다`() {
        val refused = refusal("SELECT email FROM users") { ir ->
            RewritePlan(maskProjections = listOf(mask(ir, "users", "email", template = "mask_email({col}")))
        }
        assertEquals(RewriteRefusal.EXPRESSION_NOT_USABLE, refused.refusal)
    }

    @Test
    fun `강제식에 서브쿼리를 쓸 수 없다`() {
        val refused = refusal("SELECT email FROM users") { ir ->
            RewritePlan(
                maskProjections = listOf(
                    mask(ir, "users", "email", template = "(SELECT MAX(x) FROM leak WHERE y = {col})"),
                ),
            )
        }
        assertEquals(RewriteRefusal.EXPRESSION_NOT_USABLE, refused.refusal)
    }

    /** 계획은 투영을 봤는데 AST에서 못 찾으면 **거부**한다 — 조용히 넘기면 평문이 나간다. */
    @Test
    fun `계획과 AST가 어긋나면 거부한다`() {
        val refused = refusal("SELECT id FROM users") { ir -> RewritePlan(maskProjections = listOf(mask(ir, "users", "email"))) }
        assertEquals(RewriteRefusal.VERIFY_FAILED, refused.refusal)
    }

    // ---- 스코프 ----

    /** 파생 테이블·CTE 내부 스코프에 계획이 걸리면 **그 안쪽**이 바뀌어야 한다. */
    @Test
    fun `파생 테이블 내부 스코프에 적용된다`() {
        val sql = "SELECT d.email FROM (SELECT email FROM users) d"
        val (ir, handle) = inspect(sql)
        val inner = scopes(ir.root).first { it.scopeId != ir.root.scopeId }
        val outcome = rewriter.rewrite(
            handle,
            RewritePlan(maskProjections = listOf(MaskProjection(inner.scopeId, "users", "email", "mask_email({col})", "email"))),
        )
        val out = assertIs<RewriteOutcome.Rewritten>(outcome).sql.replace(Regex("\\s+"), " ")
        assertTrue(out.contains("SELECT mask_email(email) AS email FROM users"), out)
        assertTrue(out.contains("SELECT d.email FROM ("), "외곽 투영이 변형됐다: $out")
    }

    /** UNION 팔 각각이 독립 스코프다 — 한 팔만 재작성되면 다른 팔로 전량 유출된다(§3.0.2). */
    @Test
    fun `UNION 두 팔에 각각 적용된다`() {
        val sql = "SELECT email FROM users WHERE id = 1 UNION ALL SELECT email FROM users WHERE id = 2"
        val (ir, handle) = inspect(sql)
        val arms = scopes(ir.root).filter { it.tables.any { t -> t.name == "users" } }
        assertEquals(2, arms.size, "UNION 팔이 두 개여야 함")
        val outcome = rewriter.rewrite(
            handle,
            RewritePlan(maskProjections = arms.map { MaskProjection(it.scopeId, "users", "email", "mask_email({col})", "email") }),
        )
        val out = assertIs<RewriteOutcome.Rewritten>(outcome).sql
        assertEquals(2, Regex("mask_email").findAll(out).count(), "두 팔 모두 치환되어야 함: $out")
    }

    // ---- 물리명 치환 ----

    @Test
    fun `물리명 치환은 한정 참조를 깨지 않는다`() {
        val out = rewrite("SELECT users.email FROM users") { ir ->
            RewritePlan(tableRenames = listOf(TableRename(ir.root.scopeId, "users", "users", "demo_users")))
        }
        // alias로 논리명을 남겨 `users.email`이 계속 유효하다
        assertTrue(out.contains("FROM demo_users users"), out)
        assertTrue(out.contains("users.email"), out)
    }

    @Test
    fun `alias가 있으면 그대로 두고 테이블만 바꾼다`() {
        val out = rewrite("SELECT u.email FROM users u") { ir ->
            RewritePlan(tableRenames = listOf(TableRename(ir.root.scopeId, "u", "users", "demo_users")))
        }
        assertTrue(out.contains("FROM demo_users u"), out)
    }

    @Test
    fun `조인 안의 인스턴스도 찾아 치환한다`() {
        val out = rewrite("SELECT u.id FROM orders o JOIN users u ON u.id = o.user_id") { ir ->
            RewritePlan(tableRenames = listOf(TableRename(ir.root.scopeId, "u", "users", "demo_users")))
        }
        assertTrue(out.contains("demo_users u"), out)
        assertTrue(out.contains("orders o"), "다른 테이블이 바뀌었다: $out")
    }

    // ---- 핸들 계약 ----

    @Test
    fun `핸들은 한 번만 쓸 수 있다`() {
        val (ir, handle) = inspect("SELECT email FROM users")
        val plan = RewritePlan(maskProjections = listOf(mask(ir, "users", "email")))
        assertIs<RewriteOutcome.Rewritten>(rewriter.rewrite(handle, plan))
        val second = assertIs<RewriteOutcome.Refused>(rewriter.rewrite(handle, plan))
        assertEquals(RewriteRefusal.SCOPE_NOT_FOUND, second.refusal)
    }

    /** 다른 파싱의 계획을 적용하면 엉뚱한 노드를 고칠 수 있다 — id 집합으로 막는다. */
    @Test
    fun `다른 파싱의 스코프를 가리키는 계획은 거부한다`() {
        val (_, handle) = inspect("SELECT email FROM users")
        val refused = assertIs<RewriteOutcome.Refused>(
            rewriter.rewrite(handle, RewritePlan(maskProjections = listOf(MaskProjection("s999", "users", "email", "mask_email({col})", "email")))),
        )
        assertEquals(RewriteRefusal.SCOPE_NOT_FOUND, refused.refusal)
    }

    // ---- 전체 조합 ----

    @Test
    fun `마스킹 필터 상한 물리명이 한 번에 적용된다`() {
        val out = rewrite("SELECT u.email FROM users u WHERE u.id > 0 OR u.id < 0") { ir ->
            RewritePlan(
                maskProjections = listOf(mask(ir, "u", "email")),
                injections = listOf(inject(ir, "u.id IS NOT NULL", "u")),
                limitCap = LimitCap(ir.root.scopeId, 1000),
                tableRenames = listOf(TableRename(ir.root.scopeId, "u", "users", "demo_users")),
            )
        }
        assertTrue(out.contains("mask_email(u.email) AS email"), out)
        assertTrue(out.contains("WHERE (u.id > 0 OR u.id < 0) AND (u.id IS NOT NULL)"), out)
        assertTrue(out.contains("FROM demo_users u"), out)
        assertTrue(out.endsWith("LIMIT 1001"), out)
    }

    @Test
    fun `적용 목록에 무엇이 자동 적용됐는지 남는다`() {
        val (ir, handle) = inspect("SELECT email FROM users")
        val outcome = rewriter.rewrite(
            handle,
            RewritePlan(
                maskProjections = listOf(mask(ir, "users", "email")),
                limitCap = LimitCap(ir.root.scopeId, 1000),
            ),
        )
        val applied = assertIs<RewriteOutcome.Rewritten>(outcome).applied
        assertEquals(setOf(RewriteKind.MASK, RewriteKind.LIMIT), applied.map { it.kind }.toSet())
        // 강제식 원문이 남아야 STEWARD가 마스크 식을 약화시켜도 사후 탐지가 가능하다 (§6)
        assertTrue(applied.first { it.kind == RewriteKind.MASK }.detail.contains("mask_email(email)"))
    }

    /** 상한 0이면 `LIMIT 0`을 넣는다 — `0+1`을 넣으면 0행을 요청한 쿼리가 1행을 읽는다. */
    @Test
    fun `상한 0은 LIMIT 0으로 주입된다`() {
        val out = rewrite("SELECT id FROM users LIMIT 0") { ir -> RewritePlan(limitCap = LimitCap(ir.root.scopeId, 0)) }
        assertTrue(out.endsWith("LIMIT 0"), out)
    }
}
