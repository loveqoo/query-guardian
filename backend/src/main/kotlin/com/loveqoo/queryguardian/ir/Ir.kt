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
)

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
