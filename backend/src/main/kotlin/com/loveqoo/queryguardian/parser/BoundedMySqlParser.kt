package com.loveqoo.queryguardian.parser

import com.alibaba.druid.sql.ast.SQLExpr
import com.alibaba.druid.sql.ast.SQLObject
import com.alibaba.druid.sql.ast.SQLStatement
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlExprParser
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlSelectParser
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser
import com.alibaba.druid.sql.parser.Lexer
import com.alibaba.druid.sql.parser.SQLExprParser
import com.alibaba.druid.sql.parser.Token

/**
 * 파싱 재귀가 우리 상한을 넘었다 — `StackOverflowError` 대신 이것이 난다.
 *
 * 차이가 중요하다: `StackOverflowError`는 **JVM 스택 크기**가 정한 경계라 환경마다 다르고 셀 수 없다.
 * 이 예외는 **우리가 정한 경계**라 값이 결정적이고 테스트로 고정된다.
 */
internal class ParseTooDeep(val where: String, val limit: Int) :
    RuntimeException("$where 중첩이 상한 ${limit}을 넘었습니다")

/**
 * Druid 재귀 고리에 끼우는 **공유 계수기**.
 *
 * 상한은 **정상 쿼리가 필요로 하는 만큼**으로 잡는다 — 스택이 견디는 만큼(실측 약 20,000, 환경 의존)이
 * 아니다. 상한이 스택 용량에 가까우면 **계수기가 세기 전에 스택이 먼저 터진다**(실측: 상한 1000은
 * 뚫렸고 200은 잡았다). 실제 쿼리의 파싱 깊이는 **2**다.
 */
internal class DepthGuard(private val limit: Int) {
    private var depth = 0

    inline fun <T> at(where: String, body: () -> T): T {
        enter(where)
        try {
            return body()
        } finally {
            exit()
        }
    }

    fun enter(where: String) {
        if (++depth > limit) {
            depth--
            throw ParseTooDeep(where, limit)
        }
    }

    fun exit() {
        depth--
    }
}

/**
 * 괄호·기본식 재귀(`primary` ↔ `expr` ↔ `primaryLParen`)에 상한을 끼운다.
 *
 * `SELECT ((((…1…))))`처럼 괄호가 깊으면 이 고리가 돈다. 실측으로 반복 프레임을 세어 특정했다.
 */
private class BoundedExprParser(lexer: Lexer, private val guard: DepthGuard) : MySqlExprParser(lexer) {
    override fun primary(): SQLExpr = guard.at("괄호·기본식") { super.primary() }
}

/**
 * 서브쿼리 재귀(`query` ↔ `select` ↔ `parseFrom` ↔ `parseTableSource`)에 상한을 끼운다.
 *
 * `SELECT … FROM (SELECT … FROM (…))`가 이 고리다. 위 고리와 **계수기를 공유**한다 — 둘을 섞은
 * 입력이 각각 상한 아래이면서 합쳐서 스택을 넘길 수 있기 때문이다.
 */
private class BoundedSelectParser(exprParser: SQLExprParser, private val guard: DepthGuard) :
    MySqlSelectParser(exprParser) {
    override fun query(parent: SQLObject?, acceptUnion: Boolean): SQLSelectQuery =
        guard.at("서브쿼리") { super.query(parent, acceptUnion) }
}

/**
 * 재귀 깊이가 유계인 MySQL 파서.
 *
 * Druid의 재귀는 "남의 코드라 못 고친다"가 **아니다** — 폭주하는 스택의 반복 프레임을 세어 고리 셋을
 * 특정했고, 그중 둘은 이음매(`primary()`·`query()`)가 열려 있어 여기서 막는다.
 *
 * **막지 못하는 고리 하나**: 산술 좌결합(`additiveRest` 자기 재귀). `additive`·`bitAnd`·`bitOr`·`shift`가
 * 전부 `public final`이라 상속으로 뚫을 수 없고, 위쪽 이음매(`relational()`)에서 가로채려면 우선순위
 * 사슬 전체를 재구현해야 한다. 손으로 만든 식 파서가 Druid와 다르게 읽으면 "판정과 실행이 같은 문법을
 * 본다"는 이 제품의 전제가 깨지므로 하지 않는다. 그 고리는 [SqlShape.additiveRunTooLong]이 파싱 **전에**
 * 텍스트로 막고, 놓친 경로는 파서를 별 스레드에서 돌리는 격리가 값으로 받는다.
 */
internal class BoundedMySqlParser(sql: String, maxDepth: Int) : MySqlStatementParser(sql) {

    private val guard = DepthGuard(maxDepth)

    init {
        // 상위 생성자가 만든 기본 식 파서를 우리 것으로 바꾼다. 렉서는 그대로 쓴다 —
        // `MySqlLexer`를 일반 `Lexer`로 바꾸면 백틱 식별자·MySQL 키워드 해석이 달라진다.
        exprParser = BoundedExprParser(lexer, guard)
    }

    override fun createSQLSelectParser(): MySqlSelectParser = BoundedSelectParser(exprParser, guard)

    /**
     * `SQLUtils.parseStatements`와 **같은 계약**: 문 목록을 파싱하고 **EOF까지 소비했는지 확인**한다.
     *
     * EOF 검사를 빠뜨리면 문장 뒤에 남은 내용이 조용히 버려진다 — 멀티문 검사(§2.6)를 우회하는 길이
     * 되므로 계약을 그대로 옮긴다.
     */
    fun parseAll(): List<SQLStatement> {
        val statements = parseStatementList()
        if (lexer.token() != Token.EOF) {
            throw com.alibaba.druid.sql.parser.ParserException("syntax error : ${lexer.info()}")
        }
        return statements
    }
}
