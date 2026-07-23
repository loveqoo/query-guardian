package com.loveqoo.queryguardian.parser

import com.alibaba.druid.DbType
import com.alibaba.druid.sql.SQLUtils
import com.alibaba.druid.sql.ast.SQLExpr
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator
import com.alibaba.druid.sql.ast.expr.SQLBooleanExpr
import com.alibaba.druid.sql.ast.expr.SQLCharExpr
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr
import com.alibaba.druid.sql.ast.expr.SQLInListExpr
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr
import com.alibaba.druid.sql.ast.expr.SQLNotExpr
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource
import com.alibaba.druid.sql.ast.statement.SQLSelect
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource
import com.alibaba.druid.sql.ast.statement.SQLTableSource
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter
import com.loveqoo.queryguardian.ir.Dialect
import com.loveqoo.queryguardian.ir.Op
import com.loveqoo.queryguardian.ir.Predicate
import com.loveqoo.queryguardian.ir.QueryIR
import com.loveqoo.queryguardian.ir.ResolvedColumn
import com.loveqoo.queryguardian.ir.ScopeKind
import com.loveqoo.queryguardian.ir.SelectItem
import com.loveqoo.queryguardian.ir.SelectScope
import com.loveqoo.queryguardian.ir.TableRef
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DruidMySqlParser(
    private val maxSqlBytes: Int = 64 * 1024,
    private val parseTimeoutMillis: Long = 2_000,
) : DialectParser {

    override val dialect = Dialect.MYSQL

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "druid-parse").apply { isDaemon = true }
    }

    override fun parse(sql: String): ParseResult {
        if (sql.toByteArray(Charsets.UTF_8).size > maxSqlBytes) {
            return ParseResult.Failure(FailureKind.INPUT_TOO_LARGE, "SQL이 최대 크기(${maxSqlBytes}B)를 초과했습니다")
        }
        val statements = try {
            val future = executor.submit<List<com.alibaba.druid.sql.ast.SQLStatement>> {
                SQLUtils.parseStatements(sql, DbType.mysql)
            }
            future.get(parseTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            return ParseResult.Failure(FailureKind.TIMEOUT, "파싱이 ${parseTimeoutMillis}ms 안에 끝나지 않았습니다")
        } catch (e: Exception) {
            return ParseResult.Failure(FailureKind.SYNTAX_ERROR, "문법 오류: ${e.cause?.message ?: e.message}")
        }

        if (statements.size != 1) {
            return ParseResult.Failure(FailureKind.MULTI_STATEMENT, "문은 정확히 1개여야 합니다 (${statements.size}개 제출됨)")
        }
        val statement = statements[0] as? SQLSelectStatement
            ?: return ParseResult.Failure(FailureKind.NOT_SELECT, "SELECT 문만 저장할 수 있습니다")

        val root = buildFromSelect(statement.select, ScopeKind.ROOT, parentResolver = null)
        return ParseResult.Success(QueryIR(root, sql))
    }

    override fun parsePredicate(predicateSql: String): Predicate? = try {
        val expr = SQLUtils.toSQLExpr(predicateSql, DbType.mysql)
        toPredicate(expr, AliasResolver(emptyList(), null), mutableListOf())
    } catch (e: Exception) {
        null
    }

    // ---- 스코프 구성 ----

    /** MySQL 식별자 정규화: 백틱 제거 등. 모든 식별자는 IR에 들어가기 전에 반드시 통과한다 (§6.5). */
    private fun norm(identifier: String?): String? = identifier?.let { SQLUtils.normalize(it) }

    /**
     * 한정자(소문자) → 테이블 instanceKey. 자식 스코프는 부모 체인으로 한정 참조를 해석한다(상관 서브쿼리).
     * 셀프 조인 대응: 귀속 결과는 물리 테이블명이 아니라 인스턴스 키다 — `a.x`가 `b` 인스턴스의 요건을
     * 충족시키지 못하게 한다 (§6.4).
     */
    private class AliasResolver(
        val ownTables: List<TableRef>,
        val parent: AliasResolver?,
        private val cteNames: Set<String> = emptySet(),
    ) {
        private val byQualifier: Map<String, String> = buildMap {
            for (t in ownTables) put((t.alias ?: t.name).lowercase(), t.instanceKey)
        }

        fun resolveQualified(qualifier: String): String? =
            byQualifier[qualifier.lowercase()] ?: parent?.resolveQualified(qualifier)

        /** 비한정 컬럼: 현재 스코프 FROM이 단일 인스턴스일 때만 귀속. 그 외 null = fail-closed (§6.4). */
        fun resolveUnqualified(): String? = ownTables.map { it.instanceKey }.distinct().singleOrNull()

        /** FROM이 참조한 이름이 (상위 포함) WITH 절의 CTE인가 — CTE는 물리 테이블이 아니다. */
        fun isCte(name: String): Boolean =
            cteNames.contains(name.lowercase()) || (parent?.isCte(name) ?: false)
    }

    private fun buildFromSelect(select: SQLSelect, kind: ScopeKind, parentResolver: AliasResolver?): SelectScope {
        val cteNames = select.withSubQuery?.entries
            ?.mapNotNull { entry -> norm(entry.alias)?.lowercase() }
            ?.toSet() ?: emptySet()
        // CTE 이름을 하위 스코프 전체에 전파 — FROM에서 CTE를 참조하면 물리 테이블로 취급하지 않는다
        val resolver = if (cteNames.isEmpty()) parentResolver
        else AliasResolver(emptyList(), parentResolver, cteNames)

        val cteChildren = mutableListOf<SelectScope>()
        select.withSubQuery?.entries?.forEach { entry ->
            cteChildren += buildFromSelect(entry.subQuery, ScopeKind.CTE, resolver)
        }
        val scope = buildFromQuery(select.query, kind, resolver)
        return if (cteChildren.isEmpty()) scope else scope.copy(children = cteChildren + scope.children)
    }

    private fun buildFromQuery(query: SQLSelectQuery, kind: ScopeKind, parentResolver: AliasResolver?): SelectScope {
        return when (query) {
            is SQLSelectQueryBlock -> buildFromQueryBlock(query, kind, parentResolver)
            is SQLUnionQuery -> {
                val arms = unionArms(query).map { buildFromQuery(it, ScopeKind.UNION_ARM, parentResolver) }
                SelectScope(
                    kind = kind,
                    tables = emptyList(),
                    selectItems = emptyList(),
                    whereConjuncts = emptyList(),
                    limit = query.limit?.rowCount?.let { (it as? SQLIntegerExpr)?.number?.toLong() },
                    children = arms,
                )
            }
            // 표현 불가한 SELECT 변형(VALUES 등)은 fail-open이 아니라 검증 불가 차단으로 떨어뜨린다 (§3)
            else -> SelectScope(
                kind, emptyList(), emptyList(), emptyList(), null, emptyList(),
                unverifiable = "지원하지 않는 쿼리 형태: ${query.javaClass.simpleName}",
            )
        }
    }

    private fun unionArms(union: SQLUnionQuery): List<SQLSelectQuery> {
        val relations = union.relations
        val arms = if (!relations.isNullOrEmpty()) relations else listOfNotNull(union.left, union.right)
        return arms.flatMap { if (it is SQLUnionQuery) unionArms(it) else listOf(it) }
    }

    private fun buildFromQueryBlock(block: SQLSelectQueryBlock, kind: ScopeKind, parentResolver: AliasResolver?): SelectScope {
        val tables = mutableListOf<TableRef>()
        val children = mutableListOf<SelectScope>()
        val innerOnExprs = mutableListOf<SQLExpr>()

        // FROM: 테이블 수집을 먼저 끝내야 resolver가 완성된다. 파생 테이블 스코프는 resolver 완성 후에 만든다.
        val derivedSources = mutableListOf<SQLSubqueryTableSource>()
        val isCte: (String) -> Boolean = { name -> parentResolver?.isCte(name) ?: false }
        block.from?.let { collectTables(it, tables, derivedSources, innerOnExprs, isCte) }
        val resolver = AliasResolver(tables, parentResolver)
        derivedSources.forEach { children += buildFromSelect(it.select, ScopeKind.DERIVED, resolver) }

        val conjuncts = mutableListOf<Predicate>()
        block.where?.let { flattenAnd(it, conjuncts, resolver, children) }
        innerOnExprs.forEach { flattenAnd(it, conjuncts, resolver, children) }

        val selectItems = mutableListOf<SelectItem>()
        for (item in block.selectList) {
            selectItems += toSelectItem(item.expr, resolver, children)
        }

        val limit = block.limit?.rowCount?.let { (it as? SQLIntegerExpr)?.number?.toLong() }
        return SelectScope(kind, tables, selectItems, conjuncts, limit, children)
    }

    private fun collectTables(
        source: SQLTableSource,
        tables: MutableList<TableRef>,
        derived: MutableList<SQLSubqueryTableSource>,
        innerOnExprs: MutableList<SQLExpr>,
        isCte: (String) -> Boolean,
    ) {
        when (source) {
            is SQLExprTableSource -> {
                val name = when (val e = source.expr) {
                    is SQLIdentifierExpr -> e.name
                    is SQLPropertyExpr -> e.name // schema.table → table
                    else -> source.expr.toString()
                }
                val normalized = norm(name)!!
                // CTE 참조는 물리 테이블이 아니다 — 카탈로그 조회·미등록 경고 대상에서 제외
                tables += TableRef(normalized, norm(source.alias), physical = !isCte(normalized))
            }
            is SQLJoinTableSource -> {
                collectTables(source.left, tables, derived, innerOnExprs, isCte)
                collectTables(source.right, tables, derived, innerOnExprs, isCte)
                // INNER 계열 ON만 WHERE 동치로 인정. OUTER JOIN ON은 행을 필터링하지 않는다 (§6.1).
                val condition = source.condition
                if (condition != null && source.joinType in INNER_JOIN_TYPES) {
                    innerOnExprs += condition
                }
            }
            is SQLSubqueryTableSource -> {
                derived += source
                // 파생 테이블 alias는 물리 테이블이 아니다 — physical=false로 카탈로그 조회에서 제외해
                // alias가 우연히 거버넌스 테이블명과 겹쳐도 오차단하지 않는다. (본문은 자식 스코프로 검사됨)
                source.alias?.let { a -> norm(a)!!.let { tables += TableRef(it, it, physical = false) } }
            }
            else -> { /* UNION table source 등 희귀 케이스: 테이블 미수집 → 귀속 불가 fail-closed */ }
        }
    }

    // ---- 술어 변환 ----

    /** WHERE 전체를 받아 최상위 AND만 평탄화한다. OR/NOT 아래는 트리 그대로 보존 (§6.1). */
    private fun flattenAnd(
        expr: SQLExpr,
        into: MutableList<Predicate>,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
    ) {
        if (expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanAnd) {
            flattenAnd(expr.left, into, resolver, children)
            flattenAnd(expr.right, into, resolver, children)
        } else {
            into += toPredicate(expr, resolver, children)
        }
    }

    private fun toPredicate(
        expr: SQLExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
    ): Predicate = when {
        expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanOr -> {
            val branches = mutableListOf<Predicate>()
            fun flattenOr(e: SQLExpr) {
                if (e is SQLBinaryOpExpr && e.operator == SQLBinaryOperator.BooleanOr) {
                    flattenOr(e.left); flattenOr(e.right)
                } else branches += toPredicate(e, resolver, children)
            }
            flattenOr(expr)
            Predicate.Or(branches)
        }
        expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanAnd -> {
            val conjuncts = mutableListOf<Predicate>()
            flattenAnd(expr, conjuncts, resolver, children)
            Predicate.And(conjuncts)
        }
        expr is SQLBinaryOpExpr -> toComparison(expr, resolver, children)
        expr is SQLNotExpr -> Predicate.Not(toPredicate(expr.expr, resolver, children))
        expr is SQLInListExpr -> {
            // 피연산자에 숨은 서브쿼리도 스코프로 등록 — §6.2 (검증 F3)
            collectSubqueries(expr.expr, resolver, children)
            expr.targetList.forEach { collectSubqueries(it, resolver, children) }
            val column = toColumn(expr.expr, resolver)
            if (column == null || expr.isNot) {
                Predicate.Raw(expr.toString())
            } else {
                val values = expr.targetList.map { literalText(it) }
                Predicate.InList(column, if (values.all { it != null }) values.filterNotNull() else null)
            }
        }
        expr is SQLBetweenExpr -> {
            collectSubqueries(expr.testExpr, resolver, children)
            collectSubqueries(expr.beginExpr, resolver, children)
            collectSubqueries(expr.endExpr, resolver, children)
            val column = toColumn(expr.testExpr, resolver)
            if (column == null || expr.isNot) Predicate.Raw(expr.toString())
            else Predicate.Between(column, literalText(expr.beginExpr), literalText(expr.endExpr))
        }
        expr is SQLInSubQueryExpr -> {
            children += buildFromSelect(expr.subQuery, ScopeKind.SUBQUERY, resolver)
            Predicate.Raw(expr.toString())
        }
        expr is SQLExistsExpr -> {
            children += buildFromSelect(expr.subQuery, ScopeKind.EXISTS, resolver)
            Predicate.Raw(expr.toString())
        }
        else -> {
            collectSubqueries(expr, resolver, children)
            Predicate.Raw(expr.toString())
        }
    }

    private fun toComparison(
        expr: SQLBinaryOpExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
    ): Predicate {
        val op = when (expr.operator) {
            SQLBinaryOperator.Equality -> Op.EQ
            SQLBinaryOperator.NotEqual, SQLBinaryOperator.LessThanOrGreater -> Op.NEQ
            SQLBinaryOperator.GreaterThan -> Op.GT
            SQLBinaryOperator.GreaterThanOrEqual -> Op.GTE
            SQLBinaryOperator.LessThan -> Op.LT
            SQLBinaryOperator.LessThanOrEqual -> Op.LTE
            SQLBinaryOperator.Like -> Op.LIKE
            else -> null
        }
        collectSubqueries(expr.left, resolver, children)
        collectSubqueries(expr.right, resolver, children)
        if (op == null) return Predicate.Raw(expr.toString())

        val leftColumn = toColumn(expr.left, resolver)
        val rightColumn = toColumn(expr.right, resolver)
        return when {
            leftColumn != null && rightColumn == null ->
                Predicate.Comparison(leftColumn, op, literalText(expr.right))
            rightColumn != null && leftColumn == null ->
                Predicate.Comparison(rightColumn, mirror(op), literalText(expr.left))
            else -> Predicate.Raw(expr.toString())
        }
    }

    private fun mirror(op: Op): Op = when (op) {
        Op.GT -> Op.LT; Op.GTE -> Op.LTE; Op.LT -> Op.GT; Op.LTE -> Op.GTE
        else -> op // EQ/NEQ/LIKE는 대칭
    }

    private fun toColumn(expr: SQLExpr, resolver: AliasResolver): ResolvedColumn? = when (expr) {
        is SQLIdentifierExpr -> ResolvedColumn(resolver.resolveUnqualified(), norm(expr.name)!!)
        is SQLPropertyExpr -> ResolvedColumn(qualifierOf(expr)?.let { resolver.resolveQualified(it) }, norm(expr.name)!!)
        else -> null
    }

    private fun qualifierOf(expr: SQLPropertyExpr): String? = when (val owner = expr.owner) {
        is SQLIdentifierExpr -> norm(owner.name)
        is SQLPropertyExpr -> norm(owner.name) // db.table.column → table (검증 F6)
        else -> null
    }

    private fun literalText(expr: SQLExpr): String? = when (expr) {
        is SQLCharExpr -> expr.text
        is SQLIntegerExpr -> expr.number.toString()
        is SQLNumberExpr -> expr.number.toString()
        is SQLBooleanExpr -> expr.value.toString()
        else -> null
    }

    /** 함수 인자 등 임의 표현식 안에 숨은 서브쿼리도 스코프로 등록한다 (§6.2 스코프 은닉 금지). */
    private fun collectSubqueries(expr: SQLExpr, resolver: AliasResolver, children: MutableList<SelectScope>) {
        expr.accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLQueryExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.SUBQUERY, resolver); return false
            }
            override fun visit(x: SQLInSubQueryExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.SUBQUERY, resolver); return false
            }
            override fun visit(x: SQLExistsExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.EXISTS, resolver); return false
            }
        })
    }

    private fun toSelectItem(
        expr: SQLExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
    ): SelectItem = when {
        expr is SQLAllColumnExpr -> SelectItem.Star(null)
        expr is SQLPropertyExpr && expr.name == "*" -> SelectItem.Star(qualifierOf(expr))
        expr is SQLIdentifierExpr -> SelectItem.Column(ResolvedColumn(resolver.resolveUnqualified(), norm(expr.name)!!))
        expr is SQLPropertyExpr -> SelectItem.Column(toColumn(expr, resolver)!!)
        expr is SQLAggregateExpr -> {
            expr.arguments.forEach { arg -> if (arg !is SQLAllColumnExpr) collectSubqueries(arg, resolver, children) }
            SelectItem.Expr(expr.toString()) // COUNT(*) 포함 — select-item Star가 아니다 (§6.7)
        }
        else -> {
            collectSubqueries(expr, resolver, children)
            SelectItem.Expr(expr.toString())
        }
    }

    companion object {
        private val INNER_JOIN_TYPES = setOf(
            SQLJoinTableSource.JoinType.COMMA,
            SQLJoinTableSource.JoinType.JOIN,
            SQLJoinTableSource.JoinType.INNER_JOIN,
            SQLJoinTableSource.JoinType.CROSS_JOIN,
            SQLJoinTableSource.JoinType.STRAIGHT_JOIN,
        )
    }
}
