package com.loveqoo.queryguardian.parser

import com.alibaba.druid.DbType
import com.alibaba.druid.sql.SQLUtils
import com.alibaba.druid.sql.ast.SQLExpr
import com.alibaba.druid.sql.ast.SQLLimit
import com.alibaba.druid.sql.ast.SQLObject
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
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource
import com.alibaba.druid.sql.ast.statement.SQLSelect
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource
import com.alibaba.druid.sql.ast.statement.SQLTableSource
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery
import com.alibaba.druid.sql.ast.statement.SQLUnionQueryTableSource
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter
import com.loveqoo.queryguardian.ir.ColumnEquality
import com.loveqoo.queryguardian.ir.ColumnRef
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

    /**
     * 한 번의 파싱 동안 유지되는 스코프 등록부 (spec 008 §3.5 M1-1).
     * 발급 순서대로 `s0`,`s1`,… id를 주고 그 스코프를 만든 AST 노드를 같이 기억한다 —
     * 재작성이 **판정된 그 AST**를 지목할 수 있게 하는 유일한 연결이다.
     */
    private class ScopeRegistry {
        private var next = 0
        val nodes = linkedMapOf<String, SQLObject>()
        fun register(node: SQLObject): String = "s${next++}".also { nodes[it] = node }
    }

    /** Druid AST를 감싼 불투명 핸들 — Druid 타입은 이 클래스 밖으로 나가지 않는다. */
    internal class DruidParsedStatement(
        internal val statement: SQLSelectStatement,
        internal val scopeNodes: Map<String, SQLObject>,
    ) : ParsedStatement {
        override val dialect = Dialect.MYSQL
        override val scopeIds: Set<String> get() = scopeNodes.keys
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "druid-parse").apply { isDaemon = true }
    }

    /** 기존 호출자 유지 — 파싱과 위생은 [inspect]에서 **한 번의 파싱으로** 함께 나온다. */
    override fun parse(sql: String): ParseResult = inspect(sql).parse

    /**
     * 단독 호출용 위생 검사. 파싱 실패·비-SELECT는 "검사 불가"이므로 [HygieneCode.UNVERIFIABLE]을 얹는다 —
     * 빈 목록으로 돌려주면 위생을 독립 단계로 호출하는 경로(spec 008 §5)가 fail-open한다.
     */
    override fun checkHygiene(sql: String): List<HygieneViolation> {
        val result = inspect(sql)
        val failure = result.parse as? ParseResult.Failure ?: return result.hygiene
        return result.hygiene + HygieneViolation(
            HygieneCode.UNVERIFIABLE,
            "형태를 검사할 수 없습니다: ${failure.message}",
        )
    }

    /**
     * **파싱 1회**로 IR과 위생 위반을 함께 만든다.
     *
     * 파싱을 두 번 하면 (1) 두 결과가 갈라질 수 있고(타임아웃 레이스 실측됨) (2) 비용이 2배이며
     * (3) 취소되지 않은 파싱 스레드가 누적된다. 재작성(M1)이 3차 파싱을 더할 예정이라 지금 합친다.
     */
    override fun inspect(sql: String): InspectResult {
        // ⑴ 어휘 층: 주석과, AST에 남지 않는 문형 키워드. AST 유무와 무관하게 항상 검사한다.
        val scan = SqlCommentScanner.scan(sql)
        val lexical = mutableListOf<HygieneViolation>()
        scan.commentAt?.let { at ->
            lexical += HygieneViolation(
                HygieneCode.COMMENT_NOT_ALLOWED,
                "SQL 주석은 허용되지 않습니다 (${at + 1}번째 문자). 실행 주석은 판정을 우회하고, " +
                    "후행 주석은 주입된 LIMIT을 삼킵니다",
            )
        }
        // FOR SHARE(MySQL 8이 LOCK IN SHARE MODE를 대체한 현행 문법)는 Druid의 어떤 플래그에도 담기지 않으면서
        // 출력에는 보존된다 — 읽기 전용 트랜잭션에서도 실행되므로 DB 권한이 막아주지 않는다(적대 검토 결함 2).
        FOR_SHARE.find(scan.withoutLiterals)?.let {
            lexical += HygieneViolation(HygieneCode.STATEMENT_FORM_NOT_ALLOWED, "허용되지 않는 문형입니다: FOR SHARE")
        }

        if (sql.toByteArray(Charsets.UTF_8).size > maxSqlBytes) {
            return InspectResult(
                ParseResult.Failure(FailureKind.INPUT_TOO_LARGE, "SQL이 최대 크기(${maxSqlBytes}B)를 초과했습니다"),
                lexical,
            )
        }
        val future = executor.submit<List<com.alibaba.druid.sql.ast.SQLStatement>> {
            SQLUtils.parseStatements(sql, DbType.mysql)
        }
        val statements = try {
            future.get(parseTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true) // 방치하면 파싱 스레드가 계속 돌아 풀에 누적된다
            return InspectResult(
                ParseResult.Failure(FailureKind.TIMEOUT, "파싱이 ${parseTimeoutMillis}ms 안에 끝나지 않았습니다"),
                lexical,
            )
        } catch (e: Exception) {
            return InspectResult(
                ParseResult.Failure(FailureKind.SYNTAX_ERROR, "문법 오류: ${e.cause?.message ?: e.message}"),
                lexical,
            )
        }

        if (statements.size != 1) {
            return InspectResult(
                ParseResult.Failure(FailureKind.MULTI_STATEMENT, "문은 정확히 1개여야 합니다 (${statements.size}개 제출됨)"),
                lexical,
            )
        }
        val statement = statements[0] as? SQLSelectStatement
            ?: return InspectResult(ParseResult.Failure(FailureKind.NOT_SELECT, "SELECT 문만 저장할 수 있습니다"), lexical)

        val registry = ScopeRegistry()
        val root = buildFromSelect(statement.select, ScopeKind.ROOT, parentResolver = null, registry = registry)
        return InspectResult(
            ParseResult.Success(QueryIR(root, sql)),
            lexical + astHygiene(statement, root),
            DruidParsedStatement(statement, registry.nodes.toMap()),
        )
    }

    // ---- 위생 게이트 (spec 008 §2.6) ----

    /** AST·IR에서만 보이는 위생 위반. [root]는 같은 파싱에서 만든 IR이므로 판정-검사 분기가 없다. */
    private fun astHygiene(statement: SQLSelectStatement, root: SelectScope): List<HygieneViolation> {
        val out = mutableListOf<HygieneViolation>()
        val bannedFunctions = mutableSetOf<String>()
        val variables = mutableSetOf<String>()
        val schemaQualified = mutableSetOf<String>()
        val forms = mutableSetOf<String>()
        var limitOffset: String? = null

        /** 문형 검사 — base·MySQL 블록 양쪽에서 호출된다. */
        fun checkForm(x: SQLSelectQueryBlock) {
            if (x.into != null) forms += "INTO"
            if (x.isForUpdate) forms += "FOR UPDATE"
            if (x.hintsSize > 0) forms += "옵티마이저 힌트"
            if (x is MySqlSelectQueryBlock) {
                if (x.isLockInShareMode) forms += "LOCK IN SHARE MODE"
                if (x.procedureName != null) forms += "PROCEDURE"
                // 주입한 LIMIT을 무시하고 전체 행을 세게 만들어 상한의 비용 통제를 무력화한다
                if (x.isCalcFoundRows) forms += "SQL_CALC_FOUND_ROWS"
            }
        }

        // MySQL 전용 노드(OUTFILE 등)는 MySqlASTVisitor가 아니면 accept가 예외를 던진다 → MySQL 어댑터를 쓴다.
        val visitor = object : MySqlASTVisitorAdapter() {
            override fun visit(x: SQLExprTableSource): Boolean {
                if (x.expr is SQLPropertyExpr) schemaQualified += x.expr.toString()
                return true
            }

            override fun visit(x: SQLVariantRefExpr): Boolean {
                variables += x.name
                return false
            }

            override fun visit(x: SQLMethodInvokeExpr): Boolean {
                // 백틱을 벗기지 않으면 `\`sleep\`(5)`가 목록을 통째로 우회한다 —
                // §6.5 "모든 식별자는 norm()을 통과한다"가 여기서 빠져 있었다(적대 검토 결함 1).
                norm(x.methodName)?.uppercase()?.let { if (it in BANNED_FUNCTIONS) bannedFunctions += it }
                return true
            }

            // LIMIT은 쿼리 블록·UNION 어디에나 붙을 수 있어 SQLLimit 자체를 본다 (spec 008 결정 12)
            override fun visit(x: SQLLimit): Boolean {
                if (x.offset != null) limitOffset = x.toString()
                return true
            }

            override fun visit(x: SQLSelectQueryBlock): Boolean { checkForm(x); return true }

            override fun visit(x: MySqlSelectQueryBlock): Boolean { checkForm(x); return true }
        }

        // AST를 완주하지 못하면(미지원 노드 등) 검사 결과를 신뢰할 수 없다 → 거부가 기본값 (fail-closed).
        try {
            statement.accept(visitor)
        } catch (e: Exception) {
            return out + HygieneViolation(
                HygieneCode.UNVERIFIABLE,
                "검사할 수 없는 문 형태입니다: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        if (forms.isNotEmpty()) {
            out += HygieneViolation(
                HygieneCode.STATEMENT_FORM_NOT_ALLOWED,
                "허용되지 않는 문형입니다: ${forms.sorted().joinToString(", ")}",
            )
        }
        if (variables.isNotEmpty()) {
            out += HygieneViolation(
                HygieneCode.VARIABLE_NOT_ALLOWED,
                "변수·바인드 참조는 허용되지 않습니다: ${variables.sorted().joinToString(", ")}",
            )
        }
        if (schemaQualified.isNotEmpty()) {
            out += HygieneViolation(
                HygieneCode.SCHEMA_QUALIFIER,
                "테이블에 스키마 한정자를 붙일 수 없습니다: ${schemaQualified.sorted().joinToString(", ")} " +
                    "(판정과 실행 대상이 달라집니다)",
            )
        }
        limitOffset?.let {
            out += HygieneViolation(
                HygieneCode.LIMIT_OFFSET_NOT_ALLOWED,
                "LIMIT에 OFFSET을 쓸 수 없습니다: $it — OFFSET을 반복하면 행 상한을 무한 우회할 수 있고, " +
                    "감사에서 총 반출 행 수를 셀 수 없게 됩니다",
            )
        }
        if (bannedFunctions.isNotEmpty()) {
            out += HygieneViolation(
                HygieneCode.BANNED_FUNCTION,
                "금지된 함수입니다: ${bannedFunctions.sorted().joinToString(", ")}",
            )
        }
        // 물리 테이블 판정은 **IR 기준**이다: IR의 AliasResolver가 CTE를 스코프별로 이미 정확히 해석하므로
        // AST에서 CTE 이름을 전역 수집하면 `WITH user_events AS (SELECT ... FROM user_events)`처럼
        // 동명 CTE가 실제 물리 테이블까지 지워 정상 쿼리를 오차단한다(적대 검토 결함 3).
        if (physicalTables(root).isEmpty()) {
            out += HygieneViolation(
                HygieneCode.NO_PHYSICAL_TABLE,
                "물리 테이블을 참조하지 않는 쿼리는 실행할 수 없습니다 (테이블 기반 게이트를 통과해 버립니다)",
            )
        }
        return out
    }

    /** 전 스코프의 물리 테이블 집합. `dual`은 테이블이 아니다 — `FROM DUAL`이 0-테이블 검사를 만족시켰다(결함 4). */
    private fun physicalTables(scope: SelectScope): Set<String> =
        scope.tables.filter { it.physical && it.name.lowercase() !in PSEUDO_TABLES }.map { it.name.lowercase() }.toSet() +
            scope.children.flatMap { physicalTables(it) }

    override fun parsePredicate(predicateSql: String): Predicate? = try {
        val expr = SQLUtils.toSQLExpr(predicateSql, DbType.mysql)
        toPredicate(expr, AliasResolver(emptyList(), null), mutableListOf(), ScopeRegistry())
    } catch (e: Exception) {
        null
    }

    override fun predicateContainsSubquery(predicateSql: String): Boolean = try {
        var found = false
        SQLUtils.toSQLExpr(predicateSql, DbType.mysql).accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLQueryExpr): Boolean { found = true; return false }
            override fun visit(x: SQLInSubQueryExpr): Boolean { found = true; return false }
            override fun visit(x: SQLExistsExpr): Boolean { found = true; return false }
        })
        found
    } catch (e: Exception) {
        true // 파싱 불가 표현식은 어차피 등록 거부 대상 — fail-closed
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
        private val byQualifier: Map<String, TableRef> = buildMap {
            for (t in ownTables) put((t.alias ?: t.name).lowercase(), t)
        }

        /** 한정 참조를 TableRef로 해석 — 상관 서브쿼리는 부모 체인에서 바깥 TableRef를 찾는다. */
        fun resolveQualifiedRef(qualifier: String): TableRef? =
            byQualifier[qualifier.lowercase()] ?: parent?.resolveQualifiedRef(qualifier)

        fun resolveQualified(qualifier: String): String? = resolveQualifiedRef(qualifier)?.instanceKey

        /** 비한정 컬럼: 현재 스코프 FROM이 단일 인스턴스일 때만 귀속. 그 외 null = fail-closed (§6.4). */
        fun resolveUnqualifiedRef(): TableRef? =
            ownTables.distinctBy { it.instanceKey }.singleOrNull()

        fun resolveUnqualified(): String? = resolveUnqualifiedRef()?.instanceKey

        /** FROM이 참조한 이름이 (상위 포함) WITH 절의 CTE인가 — CTE는 물리 테이블이 아니다. */
        fun isCte(name: String): Boolean =
            cteNames.contains(name.lowercase()) || (parent?.isCte(name) ?: false)
    }

    private fun buildFromSelect(select: SQLSelect, kind: ScopeKind, parentResolver: AliasResolver?, registry: ScopeRegistry): SelectScope {
        val with = select.withSubQuery
        val cteNames = with?.entries
            ?.mapNotNull { entry -> norm(entry.alias)?.lowercase() }
            ?.toSet() ?: emptySet()

        // CTE 본문의 가시 범위는 **앞서 정의된 CTE만**이다. 비재귀 CTE 본문에서 자기 이름은 MySQL이
        // **물리 테이블로 해석**하므로(실측: `WITH users AS (SELECT ssn FROM users) …`가 실제 ssn을 반환),
        // 자기 이름을 CTE로 가려주면 카탈로그 조회가 건너뛰어져 BLOCK 룰이 발화하지 않는 숨김 통로가 된다 (§6.2).
        val recursive = with?.recursive == true
        val cteChildren = mutableListOf<SelectScope>()
        val visible = mutableSetOf<String>()
        with?.entries?.forEach { entry ->
            val own = norm(entry.alias)?.lowercase()
            val bodyNames = if (recursive && own != null) visible + own else visible.toSet()
            val bodyResolver = if (bodyNames.isEmpty()) parentResolver
            else AliasResolver(emptyList(), parentResolver, bodyNames)
            cteChildren += buildFromSelect(entry.subQuery, ScopeKind.CTE, bodyResolver, registry)
            own?.let { visible += it }
        }

        // 본문 스코프(메인 쿼리)는 모든 CTE 이름을 본다 — FROM에서 CTE를 참조하면 물리 테이블로 취급하지 않는다
        val resolver = if (cteNames.isEmpty()) parentResolver
        else AliasResolver(emptyList(), parentResolver, cteNames)
        val scope = buildFromQuery(select.query, kind, resolver, registry)
        return if (cteChildren.isEmpty()) scope else scope.copy(children = cteChildren + scope.children)
    }

    private fun buildFromQuery(query: SQLSelectQuery, kind: ScopeKind, parentResolver: AliasResolver?, registry: ScopeRegistry): SelectScope {
        return when (query) {
            is SQLSelectQueryBlock -> buildFromQueryBlock(query, kind, parentResolver, registry)
            is SQLUnionQuery -> {
                val arms = unionArms(query).map { buildFromQuery(it, ScopeKind.UNION_ARM, parentResolver, registry) }
                SelectScope(
                    kind = kind,
                    tables = emptyList(),
                    selectItems = emptyList(),
                    whereConjuncts = emptyList(),
                    limit = query.limit?.rowCount?.let { (it as? SQLIntegerExpr)?.number?.toLong() },
                    children = arms,
                    scopeId = registry.register(query),
                )
            }
            // 표현 불가한 SELECT 변형(VALUES 등)은 fail-open이 아니라 검증 불가 차단으로 떨어뜨린다 (§3)
            else -> SelectScope(
                kind, emptyList(), emptyList(), emptyList(), null, emptyList(),
                unverifiable = "지원하지 않는 쿼리 형태: ${query.javaClass.simpleName}",
                scopeId = registry.register(query),
            )
        }
    }

    private fun unionArms(union: SQLUnionQuery): List<SQLSelectQuery> {
        val relations = union.relations
        val arms = if (!relations.isNullOrEmpty()) relations else listOfNotNull(union.left, union.right)
        return arms.flatMap { if (it is SQLUnionQuery) unionArms(it) else listOf(it) }
    }

    private fun buildFromQueryBlock(block: SQLSelectQueryBlock, kind: ScopeKind, parentResolver: AliasResolver?, registry: ScopeRegistry): SelectScope {
        val tables = mutableListOf<TableRef>()
        val children = mutableListOf<SelectScope>()
        val innerOnExprs = mutableListOf<SQLExpr>()
        val allOnExprs = mutableListOf<SQLExpr>()

        // FROM: 테이블 수집을 먼저 끝내야 resolver가 완성된다. 파생 테이블 스코프는 resolver 완성 후에 만든다.
        val derivedSources = mutableListOf<SQLSubqueryTableSource>()
        val unionSources = mutableListOf<SQLUnionQueryTableSource>()
        val unsupportedSources = mutableListOf<String>()
        val nullProducing = mutableSetOf<String>()
        val isCte: (String) -> Boolean = { name -> parentResolver?.isCte(name) ?: false }
        block.from?.let {
            collectTables(
                it, tables, derivedSources, unionSources, unsupportedSources,
                innerOnExprs, allOnExprs, nullProducing, isCte,
            )
        }
        val resolver = AliasResolver(tables, parentResolver)
        derivedSources.forEach { children += buildFromSelect(it.select, ScopeKind.DERIVED, resolver, registry) }
        // 파생 테이블 본문이 UNION인 경우(`FROM (SELECT … UNION ALL SELECT …) d`)도 스코프로 등록한다.
        // 버리면 그 안의 BLOCK 컬럼·거버넌스 테이블이 IR에서 사라져 룰이 발화하지 않는다 (§6.2).
        unionSources.forEach { children += buildFromQuery(it.union, ScopeKind.DERIVED, resolver, registry) }

        // WHERE·INNER ON의 최상위 AND conjunct만 평탄화 (§6.1). 같은 경로에서 컬럼=컬럼 등식(joins 근거)도 수집 (§5).
        val conjuncts = mutableListOf<Predicate>()
        val joinEqualities = mutableListOf<ColumnEquality>()
        block.where?.let { flattenAnd(it, conjuncts, resolver, children, registry, joinEqualities) }
        innerOnExprs.forEach { flattenAnd(it, conjuncts, resolver, children, registry, joinEqualities) }

        val selectItems = mutableListOf<SelectItem>()
        for (item in block.selectList) {
            selectItems += toSelectItem(item.expr, resolver, children, registry)
        }

        // 컬럼 참조 수집 (spec 002 §5.1) — BLOCK 판정의 근거. 술어 모델과 독립적으로 전 절을 훑는다.
        val columnRefs = mutableListOf<ColumnRef>()
        val refExprs = mutableListOf<SQLExpr>()
        block.selectList.forEach { refExprs += it.expr }
        block.where?.let { refExprs += it }
        block.groupBy?.let { groupBy ->
            refExprs += groupBy.items.filterIsInstance<SQLExpr>()
            groupBy.having?.let { refExprs += it }
        }
        block.orderBy?.items?.forEach { refExprs += it.expr }
        refExprs += allOnExprs
        refExprs.forEach { collectColumnRefs(it, resolver, columnRefs) }

        val limit = block.limit?.rowCount?.let { (it as? SQLIntegerExpr)?.number?.toLong() }
        return SelectScope(kind, tables, selectItems, conjuncts, limit, children,
            // 모르는 FROM 형태는 **차단**한다. 조용히 버리면 그 스코프의 위반이 함께 사라진다(스코프 은닉).
            unverifiable = unsupportedSources.takeIf { it.isNotEmpty() }
                ?.let { "지원하지 않는 FROM 형태: ${it.distinct().joinToString(", ")}" },
            columnRefs = columnRefs, joinEqualities = joinEqualities, scopeId = registry.register(block),
            nullProducingInstances = nullProducing)
    }

    /**
     * 표현식 안의 모든 컬럼 참조를 수집한다 — 함수 인자·CASE·Between/In 피연산자 포함.
     * 서브쿼리 경계에서 멈춘다(자식 스코프가 자체 수집). star는 no-select-star 담당이라 제외.
     */
    private fun collectColumnRefs(expr: SQLExpr, resolver: AliasResolver, into: MutableList<ColumnRef>) {
        expr.accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLIdentifierExpr): Boolean {
                into += ColumnRef(resolver.resolveUnqualifiedRef(), norm(x.name)!!)
                return false
            }

            override fun visit(x: SQLPropertyExpr): Boolean {
                if (x.name != "*") {
                    val table = qualifierOf(x)?.let { resolver.resolveQualifiedRef(it) }
                    into += ColumnRef(table, norm(x.name)!!)
                }
                return false
            }

            // 서브쿼리 경계 — 단, IN의 좌변 피연산자는 이 스코프의 참조이므로 수집한다
            override fun visit(x: SQLInSubQueryExpr): Boolean {
                collectColumnRefs(x.expr, resolver, into)
                return false
            }

            override fun visit(x: SQLQueryExpr): Boolean = false
            override fun visit(x: SQLExistsExpr): Boolean = false
        })
    }

    private fun collectTables(
        source: SQLTableSource,
        tables: MutableList<TableRef>,
        derived: MutableList<SQLSubqueryTableSource>,
        unions: MutableList<SQLUnionQueryTableSource>,
        unsupported: MutableList<String>,
        innerOnExprs: MutableList<SQLExpr>,
        allOnExprs: MutableList<SQLExpr>,
        nullProducing: MutableSet<String>,
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
                collectTables(source.left, tables, derived, unions, unsupported, innerOnExprs, allOnExprs, nullProducing, isCte)
                collectTables(source.right, tables, derived, unions, unsupported, innerOnExprs, allOnExprs, nullProducing, isCte)
                // OUTER JOIN의 보존되지 않는 쪽은 null이 생성된다 → 그 인스턴스에 WHERE 술어를 주입하면
                // 조인이 사실상 INNER로 바뀌어 의미가 변한다 (spec 008 §3.0.2). 모르는 종류는 양쪽 다 담는다.
                when (source.joinType) {
                    SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN,
                    SQLJoinTableSource.JoinType.NATURAL_LEFT_JOIN,
                    SQLJoinTableSource.JoinType.OUTER_APPLY,
                    -> nullProducing += instanceKeysOf(source.right, isCte)

                    SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN,
                    SQLJoinTableSource.JoinType.NATURAL_RIGHT_JOIN,
                    -> nullProducing += instanceKeysOf(source.left, isCte)

                    in INNER_JOIN_TYPES -> Unit
                    else -> {
                        nullProducing += instanceKeysOf(source.left, isCte)
                        nullProducing += instanceKeysOf(source.right, isCte)
                    }
                }
                val condition = source.condition
                if (condition != null) {
                    // 컬럼 참조 수집은 조인 종류 불문(§5.1). WHERE 동치 인정은 INNER 계열만(§6.1).
                    allOnExprs += condition
                    if (source.joinType in INNER_JOIN_TYPES) {
                        innerOnExprs += condition
                    }
                }
            }
            is SQLSubqueryTableSource -> {
                derived += source
                // 파생 테이블 alias는 물리 테이블이 아니다 — physical=false로 카탈로그 조회에서 제외해
                // alias가 우연히 거버넌스 테이블명과 겹쳐도 오차단하지 않는다. (본문은 자식 스코프로 검사됨)
                source.alias?.let { a -> norm(a)!!.let { tables += TableRef(it, it, physical = false) } }
            }
            is SQLUnionQueryTableSource -> {
                unions += source
                // alias는 파생 테이블과 같은 취급 — 물리 테이블이 아니다(본문은 자식 스코프로 검사된다)
                source.alias?.let { a -> norm(a)!!.let { tables += TableRef(it, it, physical = false) } }
            }
            // 미지원 FROM 형태는 조용히 버리지 않는다 — 버리면 그 스코프의 위반이 사라진다(스코프 은닉).
            else -> unsupported += source.javaClass.simpleName
        }
    }

    /** 테이블 소스 하위 트리의 인스턴스 키 전부 — OUTER JOIN의 어느 쪽이 null 생성인지 표시할 때 쓴다. */
    private fun instanceKeysOf(source: SQLTableSource, isCte: (String) -> Boolean): Set<String> {
        val keys = mutableSetOf<String>()
        when (source) {
            is SQLExprTableSource -> {
                val name = when (val e = source.expr) {
                    is SQLIdentifierExpr -> e.name
                    is SQLPropertyExpr -> e.name
                    else -> source.expr.toString()
                }
                keys += norm(source.alias) ?: norm(name)!!
            }
            is SQLJoinTableSource -> {
                keys += instanceKeysOf(source.left, isCte)
                keys += instanceKeysOf(source.right, isCte)
            }
            else -> source.alias?.let { keys += norm(it)!! }
        }
        return keys
    }

    // ---- 술어 변환 ----

    /**
     * WHERE 전체를 받아 최상위 AND만 평탄화한다. OR/NOT 아래는 트리 그대로 보존 (§6.1).
     * [joinEqs]가 null이 아니면(=최상위 경로) 이 conjunct들에서 컬럼=컬럼 등식을 수집한다 (§5).
     * OR 하위의 중첩 AND(toPredicate 경유)는 joinEqs=null로 호출돼 수집되지 않는다 — OR-세탁 방지(C2).
     */
    private fun flattenAnd(
        expr: SQLExpr,
        into: MutableList<Predicate>,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
        registry: ScopeRegistry,
        joinEqs: MutableList<ColumnEquality>? = null,
    ) {
        if (expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanAnd) {
            flattenAnd(expr.left, into, resolver, children, registry, joinEqs)
            flattenAnd(expr.right, into, resolver, children, registry, joinEqs)
        } else {
            if (joinEqs != null) columnEqualityOf(expr, resolver)?.let { joinEqs += it }
            into += toPredicate(expr, resolver, children, registry)
        }
    }

    /** 양변이 모두 컬럼인 `=`이면 ColumnEquality. 그 외(컬럼=리터럴, 함수 래핑 등)는 null (§5). */
    private fun columnEqualityOf(expr: SQLExpr, resolver: AliasResolver): ColumnEquality? {
        if (expr !is SQLBinaryOpExpr || expr.operator != SQLBinaryOperator.Equality) return null
        val left = toColumnRef(expr.left, resolver) ?: return null
        val right = toColumnRef(expr.right, resolver) ?: return null
        return ColumnEquality(left, right)
    }

    /** 단일 컬럼 표현식 → TableRef 귀속 컬럼 참조. 컬럼이 아니면 null. 귀속 불가 시 table=null. */
    private fun toColumnRef(expr: SQLExpr, resolver: AliasResolver): ColumnRef? = when (expr) {
        is SQLIdentifierExpr -> ColumnRef(resolver.resolveUnqualifiedRef(), norm(expr.name)!!)
        is SQLPropertyExpr ->
            if (expr.name == "*") null
            else ColumnRef(qualifierOf(expr)?.let { resolver.resolveQualifiedRef(it) }, norm(expr.name)!!)
        else -> null
    }

    private fun toPredicate(
        expr: SQLExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
        registry: ScopeRegistry,
    ): Predicate = when {
        expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanOr -> {
            val branches = mutableListOf<Predicate>()
            fun flattenOr(e: SQLExpr) {
                if (e is SQLBinaryOpExpr && e.operator == SQLBinaryOperator.BooleanOr) {
                    flattenOr(e.left); flattenOr(e.right)
                } else branches += toPredicate(e, resolver, children, registry)
            }
            flattenOr(expr)
            Predicate.Or(branches)
        }
        expr is SQLBinaryOpExpr && expr.operator == SQLBinaryOperator.BooleanAnd -> {
            val conjuncts = mutableListOf<Predicate>()
            flattenAnd(expr, conjuncts, resolver, children, registry)
            Predicate.And(conjuncts)
        }
        expr is SQLBinaryOpExpr -> toComparison(expr, resolver, children, registry)
        expr is SQLNotExpr -> Predicate.Not(toPredicate(expr.expr, resolver, children, registry))
        expr is SQLInListExpr -> {
            // 피연산자에 숨은 서브쿼리도 스코프로 등록 — §6.2 (검증 F3)
            collectSubqueries(expr.expr, resolver, children, registry)
            expr.targetList.forEach { collectSubqueries(it, resolver, children, registry) }
            val column = toColumn(expr.expr, resolver)
            if (column == null || expr.isNot) {
                Predicate.Raw(expr.toString())
            } else {
                val values = expr.targetList.map { literalText(it) }
                Predicate.InList(column, if (values.all { it != null }) values.filterNotNull() else null)
            }
        }
        expr is SQLBetweenExpr -> {
            collectSubqueries(expr.testExpr, resolver, children, registry)
            collectSubqueries(expr.beginExpr, resolver, children, registry)
            collectSubqueries(expr.endExpr, resolver, children, registry)
            val column = toColumn(expr.testExpr, resolver)
            if (column == null || expr.isNot) Predicate.Raw(expr.toString())
            else Predicate.Between(column, literalText(expr.beginExpr), literalText(expr.endExpr))
        }
        expr is SQLInSubQueryExpr -> {
            children += buildFromSelect(expr.subQuery, ScopeKind.SUBQUERY, resolver, registry)
            Predicate.Raw(expr.toString())
        }
        expr is SQLExistsExpr -> {
            children += buildFromSelect(expr.subQuery, ScopeKind.EXISTS, resolver, registry)
            Predicate.Raw(expr.toString())
        }
        else -> {
            collectSubqueries(expr, resolver, children, registry)
            Predicate.Raw(expr.toString())
        }
    }

    private fun toComparison(
        expr: SQLBinaryOpExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
        registry: ScopeRegistry,
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
        collectSubqueries(expr.left, resolver, children, registry)
        collectSubqueries(expr.right, resolver, children, registry)
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
    private fun collectSubqueries(expr: SQLExpr, resolver: AliasResolver, children: MutableList<SelectScope>, registry: ScopeRegistry) {
        expr.accept(object : SQLASTVisitorAdapter() {
            override fun visit(x: SQLQueryExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.SUBQUERY, resolver, registry); return false
            }
            override fun visit(x: SQLInSubQueryExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.SUBQUERY, resolver, registry); return false
            }
            override fun visit(x: SQLExistsExpr): Boolean {
                children += buildFromSelect(x.subQuery, ScopeKind.EXISTS, resolver, registry); return false
            }
        })
    }

    private fun toSelectItem(
        expr: SQLExpr,
        resolver: AliasResolver,
        children: MutableList<SelectScope>,
        registry: ScopeRegistry,
    ): SelectItem = when {
        expr is SQLAllColumnExpr -> SelectItem.Star(null)
        expr is SQLPropertyExpr && expr.name == "*" -> SelectItem.Star(qualifierOf(expr))
        expr is SQLIdentifierExpr -> SelectItem.Column(ResolvedColumn(resolver.resolveUnqualified(), norm(expr.name)!!))
        expr is SQLPropertyExpr -> SelectItem.Column(toColumn(expr, resolver)!!)
        expr is SQLAggregateExpr -> {
            expr.arguments.forEach { arg -> if (arg !is SQLAllColumnExpr) collectSubqueries(arg, resolver, children, registry) }
            SelectItem.Expr(expr.toString()) // COUNT(*) 포함 — select-item Star가 아니다 (§6.7)
        }
        else -> {
            collectSubqueries(expr, resolver, children, registry)
            SelectItem.Expr(expr.toString())
        }
    }

    companion object {
        /** spec 008 §2.6 즉시 목록 — 파일 읽기·시간 지연·잠금. 실행 계정 권한과 무관하게 문형에서 막는다. */
        private val BANNED_FUNCTIONS = setOf(
            "LOAD_FILE",                                              // 파일 읽기
            "SLEEP", "BENCHMARK",                                     // 시간 지연·자원 소모
            "GET_LOCK", "RELEASE_LOCK", "IS_USED_LOCK", "IS_FREE_LOCK", // 잠금(읽기 전용 전제 위반)
            "FOUND_ROWS", "ROW_COUNT",                                // 주입된 LIMIT 우회용 행수 캐시
            "MASTER_POS_WAIT", "SOURCE_POS_WAIT",                     // 복제 대기 = 시간 지연
        )

        /** `FOR SHARE`는 Druid의 어떤 플래그에도 담기지 않으므로 어휘로 잡는다 (리터럴 제거 텍스트에만 적용). */
        private val FOR_SHARE = Regex("(?i)\\bFOR\\s+SHARE\\b")

        /** 테이블처럼 쓰이지만 데이터가 없는 이름 — 0-테이블 검사에서 물리 테이블로 세지 않는다. */
        private val PSEUDO_TABLES = setOf("dual")

        private val INNER_JOIN_TYPES = setOf(
            SQLJoinTableSource.JoinType.COMMA,
            SQLJoinTableSource.JoinType.JOIN,
            SQLJoinTableSource.JoinType.INNER_JOIN,
            SQLJoinTableSource.JoinType.CROSS_JOIN,
            SQLJoinTableSource.JoinType.STRAIGHT_JOIN,
        )
    }
}
