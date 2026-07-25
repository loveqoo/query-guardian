package com.loveqoo.queryguardian.ir

enum class Dialect { MYSQL }

/**
 * 룰이 바라보는 유일한 어휘. 파서(Druid) 타입은 여기로 새어 들어오면 안 된다.
 * SQL 전체를 정규화하지 않는다 — 거버넌스 룰이 필요한 조각만 담는다 (spec 001 §5.2).
 */
data class QueryIR(val root: SelectScope, val raw: String)

/** 스코프 = 거버넌스 판정의 단위. 서브쿼리·파생 테이블·CTE 본문·UNION 팔 전부 자식 스코프다 (§6.2). */
enum class ScopeKind { ROOT, SUBQUERY, DERIVED, CTE, UNION_ARM, EXISTS }

data class SelectScope(
    val kind: ScopeKind,
    val tables: List<TableRef>,
    val selectItems: List<SelectItem>,
    /** WHERE의 최상위 AND conjunct + INNER JOIN ON의 conjunct. OUTER JOIN ON은 제외 (§6.1). */
    val whereConjuncts: List<Predicate>,
    val limit: Long?,
    val children: List<SelectScope>,
    /** IR로 표현 불가한 쿼리 형태(예: VALUES). null이 아니면 엔진이 무조건 차단한다 — fail-closed (§3). */
    val unverifiable: String? = null,
    /**
     * 이 스코프가 참조하는 모든 컬럼 (spec 002 §5.1) — select 목록·WHERE·GROUP BY·HAVING·ORDER BY·
     * JOIN ON·함수 인자·CASE·Between/In 피연산자 전체에서 수집. BLOCK 판정(no-blocked-column)의 근거.
     * 술어 모델(whereConjuncts)과 달리 "어디에 어떤 형태로" 쓰였는지는 담지 않는다 — 참조 사실만.
     */
    val columnRefs: List<ColumnRef> = emptyList(),
    /**
     * 컬럼=컬럼 등식 (spec 004 §5) — joins 판정의 근거. §6.1과 동일하게 한정 수집한다:
     * INNER 계열 JOIN ON + WHERE의 **최상위 AND conjunct**만. OUTER ON·OR/Not 하위는 제외
     * (OUTER는 필터 무력화, OR은 세탁 — C1·C2). 양변 모두 컬럼으로 귀속된 `=`만 담긴다.
     */
    val joinEqualities: List<ColumnEquality> = emptyList(),
    /**
     * 파서가 발급한 **불투명 스코프 식별자** (spec 008 §3.5 M1-1). 재작성 계획(`RewritePlan`)이
     * "어느 스코프에 주입/치환하라"를 가리키는 유일한 수단이다.
     *
     * 생성 순서대로 `s0`,`s1`,… 이 붙고 **한 번의 파싱 안에서만** 의미가 있다. 값의 형태에 의존하지 말 것
     * (계층 경로가 아니다). 판정에 쓰인 AST와 재작성 대상 AST가 같아야 하므로, 이 id는
     * 그 파싱이 만든 `ParsedStatement` 핸들과 **짝으로만** 사용한다.
     */
    val scopeId: String = "",
    /**
     * 이 스코프 FROM에서 **null을 생성하는 쪽**(OUTER JOIN의 보존되지 않는 쪽) 테이블 인스턴스 키들.
     *
     * WHERE에 술어를 주입하면 LEFT JOIN이 사실상 INNER로 바뀌어 **의미가 변한다** — spec 008 §3.0.2는
     * 그런 대상에 대한 재작성을 거부하라고 요구하고, 그 판단의 근거가 이 집합이다.
     * 알 수 없는 조인 종류는 **양쪽 모두** 담는다(fail-closed — 의미 변경보다 거부가 안전).
     */
    val nullProducingInstances: Set<String> = emptySet(),
)

/** 컬럼=컬럼 등식. 방향 무관(a=b ≡ b=a). 어느 한쪽이라도 귀속 불가(table=null)면 joins는 fail-closed 미충족. */
data class ColumnEquality(val left: ColumnRef, val right: ColumnRef)

/**
 * 해석된 컬럼 참조. [table]은 resolver 체인으로 찾은 TableRef 자체 — 상관 서브쿼리가 바깥 테이블을
 * 참조하면 그 바깥 TableRef가 담긴다. 귀속 불가면 null (룰이 fail-closed로 처리, §6.4).
 */
data class ColumnRef(val table: TableRef?, val column: String)

/**
 * [physical]=false는 파생 테이블/CTE의 alias 참조 — 물리 테이블이 아니므로 카탈로그 조회 대상이 아니다.
 * [instanceKey]는 FROM 안에서 이 테이블 출현을 유일하게 가리키는 키(alias 우선) — 셀프 조인에서
 * 인스턴스별 요건 판정에 쓰인다 (§6.4).
 */
data class TableRef(val name: String, val alias: String?, val physical: Boolean = true) {
    val instanceKey: String get() = alias ?: name
}

sealed interface SelectItem {
    data class Column(val column: ResolvedColumn) : SelectItem
    /** select-item 수준의 `*` / `t.*` 만 Star. COUNT(*) 등 집계 star는 Expr이다 (§6.7). */
    data class Star(val qualifier: String?) : SelectItem
    data class Expr(val text: String) : SelectItem
}

/**
 * alias 해석을 거친 컬럼 참조. [table]은 귀속된 테이블 출현의 instanceKey(alias 우선, 없으면 테이블명).
 * 셀프 조인에서 `a.event_date`와 `b.event_date`를 구분하기 위해 물리 테이블명이 아니라 인스턴스 키다.
 * 귀속이 안 되면 null — 요건 판정에서 미충족 처리 (§6.4 fail-closed).
 */
data class ResolvedColumn(val table: String?, val column: String)

enum class Op { EQ, NEQ, GT, GTE, LT, LTE, LIKE }

sealed interface Predicate {
    /** [value]는 리터럴 텍스트(문자열은 따옴표 제거), 리터럴이 아니면 null. */
    data class Comparison(val column: ResolvedColumn, val op: Op, val value: String?) : Predicate

    /** [values]는 전부 리터럴일 때만 채워진다. 하나라도 비리터럴이면 null. */
    data class InList(val column: ResolvedColumn, val values: List<String>?) : Predicate

    data class Between(val column: ResolvedColumn, val low: String?, val high: String?) : Predicate

    data class Or(val branches: List<Predicate>) : Predicate
    data class And(val conjuncts: List<Predicate>) : Predicate
    data class Not(val inner: Predicate) : Predicate

    /** IR로 표현 못 한 조각. 어떤 요건도 절대 충족시키지 못한다 — fail-closed (§6.3). */
    data class Raw(val fragment: String) : Predicate
}
