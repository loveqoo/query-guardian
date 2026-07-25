package com.loveqoo.queryguardian.parser

import com.alibaba.druid.DbType
import com.alibaba.druid.sql.SQLUtils
import com.alibaba.druid.sql.ast.SQLExpr
import com.alibaba.druid.sql.ast.SQLExprImpl
import com.alibaba.druid.sql.ast.SQLLimit
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock
import com.alibaba.druid.sql.ast.statement.SQLTableSource
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter
import com.loveqoo.queryguardian.ir.AppliedRewrite
import com.loveqoo.queryguardian.ir.MaskProjection
import com.loveqoo.queryguardian.ir.PredicateInjection
import com.loveqoo.queryguardian.ir.RewriteKind
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.ir.RewritePlan
import com.loveqoo.queryguardian.ir.RewriteRefusal
import com.loveqoo.queryguardian.ir.TableRename

/**
 * 계획을 **판정에 쓰인 그 AST**에 적용한다 (spec 008 §3.5 M1-4).
 *
 * 이 클래스는 카탈로그·권한·규칙을 알지 못한다 — 방언 중립 [RewritePlan]만 받는다(ArchUnit `parserKnowsOnlyIr`).
 * 무엇을 마스킹할지 결정하는 것은 `exec/RewritePlanner`의 일이고, 여기서는 **어떻게 AST를 고칠지**만 안다.
 *
 * 주입 정확성 3원칙(§3.0, 전부 실측 근거):
 * 1. 원본 WHERE를 **괄호로 감싼 뒤** AND 결합. Druid는 괄호를 자동 삽입하지 않으므로 이걸 빠뜨리면
 *    `WHERE a = 1 OR b = 2 AND c = 'Y'`가 되어 주입이 `b = 2` 가지에만 붙는다(우선순위 붕괴).
 * 2. LIMIT은 **단일 장치** — `maxRows + 1`을 AST에 넣는다. `setMaxRows` 병용 금지.
 * 3. 강제식은 문자열로 이어붙이지 않고 **재파싱해 단일 표현식 노드로** 삽입한다. 파싱 실패·서브쿼리 포함이면 거부.
 */
class SqlRewriter(
    private val parser: DialectParser,
    /** 검증은 **선택이 아니다** — 생성자에서 받되 기본값을 두어 호출자가 빠뜨릴 수 없게 한다(§3.0.3). */
    private val verifier: RewriteVerifier = RewriteVerifier(parser),
) {

    fun rewrite(statement: ParsedStatement, plan: RewritePlan): RewriteOutcome {
        val handle = statement as? DruidMySqlParser.DruidParsedStatement
            ?: return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "이 방언의 재작성 핸들이 아닙니다")

        // AST를 제자리에서 고치므로 핸들은 **한 번만** 쓸 수 있다. 두 번 적용하면 강제식이 이중으로 감싸이고
        // 주입 술어가 중복된다 — 조용한 이중 적용보다 거부가 안전하다.
        if (handle.rewritten) {
            return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "이미 재작성된 핸들입니다 — 다시 파싱해야 합니다")
        }
        val unknown = plan.referencedScopeIds - handle.scopeIds
        if (unknown.isNotEmpty()) {
            return refuse(
                RewriteRefusal.SCOPE_NOT_FOUND,
                "계획이 이 파싱에 없는 스코프를 가리킵니다: ${unknown.sorted().joinToString(", ")} — " +
                    "다른 파싱의 계획을 적용하려 한 것입니다",
            )
        }
        handle.rewritten = true

        val applied = mutableListOf<AppliedRewrite>()

        for (mask in plan.maskProjections) {
            val block = queryBlock(handle, mask.scopeId)
                ?: return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "MASK 대상 스코프가 쿼리 블록이 아닙니다: ${mask.scopeId}")
            applyMask(block, mask, applied)?.let { return it }
        }

        for (injection in plan.injections) {
            val block = queryBlock(handle, injection.scopeId)
                ?: return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "주입 대상 스코프가 쿼리 블록이 아닙니다: ${injection.scopeId}")
            applyInjection(block, injection, applied)?.let { return it }
        }

        plan.limitCap?.let { cap ->
            when (val node = handle.scopeNodes[cap.scopeId]) {
                // `maxRows + 1`을 넣는다 — 실행기가 마지막 행을 보면 truncated로 확정하고 그 행을 버린다
                is SQLSelectQueryBlock -> node.limit = SQLLimit((cap.maxRows + 1).toInt())
                is SQLUnionQuery -> node.limit = SQLLimit((cap.maxRows + 1).toInt())
                else -> return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "LIMIT 대상 스코프를 찾을 수 없습니다: ${cap.scopeId}")
            }
            applied += AppliedRewrite(RewriteKind.LIMIT, "-", null, "LIMIT ${cap.maxRows} 적용")
        }

        // **마지막 단계**에서만 물리명으로 바꾼다 (§3 원칙) — 이 앞의 모든 단계는 논리명으로 동작했다.
        for (rename in plan.tableRenames) {
            val block = queryBlock(handle, rename.scopeId)
                ?: return refuse(RewriteRefusal.SCOPE_NOT_FOUND, "테이블 치환 대상 스코프가 없습니다: ${rename.scopeId}")
            applyRename(block, rename, applied)?.let { return it }
        }

        val rewritten = SQLUtils.toSQLString(handle.statement, DbType.mysql)

        // §3.0.3 이중 방어: 실제로 실행될 **텍스트**를 다시 읽어 계획대로 됐는지 단정한다.
        val problems = verifier.verify(rewritten, plan)
        if (problems.isNotEmpty()) {
            return refuse(RewriteRefusal.VERIFY_FAILED, "재작성 결과 검증 실패 — ${problems.joinToString("; ")}")
        }
        return RewriteOutcome.Rewritten(rewritten, applied)
    }

    private fun refuse(refusal: RewriteRefusal, message: String) = RewriteOutcome.Refused(refusal, message)

    private fun queryBlock(handle: DruidMySqlParser.DruidParsedStatement, scopeId: String): SQLSelectQueryBlock? =
        handle.scopeNodes[scopeId] as? SQLSelectQueryBlock

    // ---- MASK ----

    private fun applyMask(
        block: SQLSelectQueryBlock,
        mask: MaskProjection,
        applied: MutableList<AppliedRewrite>,
    ): RewriteOutcome.Refused? {
        var replaced = 0
        for (item in block.selectList) {
            if (!isColumnOfInstance(block, item.expr, mask.instanceKey, mask.column)) continue

            // `{col}` 자리에 **원본 컬럼 표현식을 그대로** 넣는다 — `u.email`의 한정자가 보존되어야 한다.
            val forced = mask.expressionTemplate.replace("{col}", item.expr.toString())
            val parsed = parseExpression(forced)
                ?: return refuse(
                    RewriteRefusal.EXPRESSION_NOT_USABLE,
                    "마스킹 강제식을 표현식으로 파싱할 수 없습니다: $forced",
                )
            if (containsSubquery(parsed)) {
                return refuse(RewriteRefusal.EXPRESSION_NOT_USABLE, "마스킹 강제식에 서브쿼리를 쓸 수 없습니다: $forced")
            }
            // 원 별칭이 있으면 그대로, 없으면 원 컬럼명을 별칭으로 — 강제식 텍스트가 출력 컬럼명이 되면
            // 이 결과를 참조하던 쪽이 깨진다(§3.0.1).
            val alias = item.alias
            item.expr = parsed
            if (alias == null) item.alias = mask.outputName
            replaced++
            applied += AppliedRewrite(
                RewriteKind.MASK, mask.instanceKey, mask.column, "$forced${if (alias == null) " AS ${mask.outputName}" else ""}",
            )
        }
        return if (replaced == 0) {
            // 계획은 투영을 봤는데 AST에서 못 찾았다 = 계획과 AST가 어긋났다. 조용히 넘기면 평문이 나간다.
            refuse(
                RewriteRefusal.VERIFY_FAILED,
                "마스킹 대상 투영을 AST에서 찾지 못했습니다: ${mask.instanceKey}.${mask.column}",
            )
        } else {
            null
        }
    }

    /**
     * [expr]이 [instanceKey] 인스턴스의 [column] 참조인가.
     *
     * 비한정 참조(`email`)는 **FROM이 그 인스턴스 하나일 때만** 인정한다 — 여러 인스턴스가 있으면 IR도
     * 귀속을 포기했으므로(fail-closed) 계획이 만들어지지 않지만, 재작성기도 독립적으로 확인해
     * 엉뚱한 인스턴스의 컬럼을 감싸지 않는다.
     */
    private fun isColumnOfInstance(
        block: SQLSelectQueryBlock,
        expr: SQLExpr,
        instanceKey: String,
        column: String,
    ): Boolean = when (expr) {
        is SQLPropertyExpr -> {
            val owner = (expr.owner as? SQLIdentifierExpr)?.name
            normalize(owner) == instanceKey.lowercase() && normalize(expr.name) == column.lowercase()
        }
        is SQLIdentifierExpr ->
            normalize(expr.name) == column.lowercase() && singleInstanceKey(block.from) == instanceKey.lowercase()
        else -> false
    }

    private fun singleInstanceKey(from: SQLTableSource?): String? = when (from) {
        is SQLExprTableSource -> {
            val name = (from.expr as? SQLIdentifierExpr)?.name ?: (from.expr as? SQLPropertyExpr)?.name
            normalize(from.alias ?: name)
        }
        else -> null // 조인·파생 등 다중 인스턴스면 비한정 참조를 귀속하지 않는다
    }

    // ---- 술어 주입 ----

    private fun applyInjection(
        block: SQLSelectQueryBlock,
        injection: PredicateInjection,
        applied: MutableList<AppliedRewrite>,
    ): RewriteOutcome.Refused? {
        val predicate = parseExpression(injection.predicateSql)
            ?: return refuse(
                RewriteRefusal.EXPRESSION_NOT_USABLE,
                "주입할 술어를 파싱할 수 없습니다: ${injection.predicateSql}",
            )
        if (containsSubquery(predicate)) {
            return refuse(RewriteRefusal.EXPRESSION_NOT_USABLE, "주입 술어에 서브쿼리를 쓸 수 없습니다: ${injection.predicateSql}")
        }

        val existing = block.where
        if (existing == null) {
            block.where = predicate
        } else {
            // Druid는 괄호를 자동 삽입하지 않는다(실측) — 원본이 OR이면 감싸지 않으면 우선순위가 붕괴한다.
            (existing as? SQLExprImpl)?.setParenthesized(true)
            (predicate as? SQLBinaryOpExpr)?.setParenthesized(true)
            block.where = SQLBinaryOpExpr(existing, SQLBinaryOperator.BooleanAnd, predicate, DbType.mysql)
        }
        applied += AppliedRewrite(
            RewriteKind.FILTER, injection.instanceKey, null, "${injection.predicateSql} (${injection.reason})",
        )
        return null
    }

    // ---- 물리 테이블명 치환 ----

    private fun applyRename(
        block: SQLSelectQueryBlock,
        rename: TableRename,
        applied: MutableList<AppliedRewrite>,
    ): RewriteOutcome.Refused? {
        val source = findTableSource(block.from, rename.instanceKey)
            ?: return refuse(
                RewriteRefusal.SCOPE_NOT_FOUND,
                "치환할 테이블 인스턴스를 찾지 못했습니다: ${rename.instanceKey}",
            )
        source.expr = SQLIdentifierExpr(rename.physicalName)
        // alias가 없으면 논리명을 alias로 남긴다 — `users.email` 같은 한정 참조가 깨지지 않도록(실측 확인).
        if (source.alias == null) source.alias = rename.logicalName
        applied += AppliedRewrite(
            RewriteKind.TABLE_MAP, rename.logicalName, null, "${rename.logicalName} → ${rename.physicalName}",
        )
        return null
    }

    private fun findTableSource(from: SQLTableSource?, instanceKey: String): SQLExprTableSource? = when (from) {
        is SQLExprTableSource -> {
            val name = (from.expr as? SQLIdentifierExpr)?.name ?: (from.expr as? SQLPropertyExpr)?.name
            if (normalize(from.alias ?: name) == instanceKey.lowercase()) from else null
        }
        is SQLJoinTableSource ->
            findTableSource(from.left, instanceKey) ?: findTableSource(from.right, instanceKey)
        else -> null
    }

    // ---- 공용 ----

    private fun parseExpression(sql: String): SQLExpr? = try {
        SQLUtils.toSQLExpr(sql, DbType.mysql)
    } catch (e: Exception) {
        null
    }

    private fun containsSubquery(expr: SQLExpr): Boolean {
        var found = false
        expr.accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLQueryExpr): Boolean { found = true; return false }
            override fun visit(x: SQLInSubQueryExpr): Boolean { found = true; return false }
            override fun visit(x: SQLExistsExpr): Boolean { found = true; return false }
        })
        return found
    }

    private fun normalize(identifier: String?): String? = identifier?.let { SQLUtils.normalize(it).lowercase() }
}
