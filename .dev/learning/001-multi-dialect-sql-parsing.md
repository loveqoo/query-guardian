# 001 — 멀티 방언 SQL 파싱 전략 조사

> 2026-07-24. 질문: "방언별 파서가 다르면 일관된 관리가 어렵지 않나? 다른 서비스는 어떻게 푸나?"
> 조사 방식: 서브에이전트 웹 리서치(소스 URL 하단). 결론은 스펙 001에 반영 예정.

## 업계에서 반복되는 3가지 패턴

- **패턴 A — 단일 파서 + 방언 훅, 통합 AST 하나**: sqlglot, JSQLParser, Calcite babel.
  방언 차이를 오버라이드 테이블/추가 문법으로 흡수. 관리 일관성 최고, 방언 정밀도는 타협.
- **패턴 B — 방언별 문법(상속/오버라이드) + 공유 노드 어휘**: sqlfluff(ANSI 베이스 상속), Alibaba Druid(방언별 수제 파서 + 공유 AST 베이스).
- **패턴 C — 방언별 독립 파서 + 정규화 계층(통합 IR)**: Apache ShardingSphere(방언별 ANTLR → visitor → 통합 SQLStatement), Bytebase, LinkedIn Coral.

**공통 수렴점: "룰은 하나의 어휘(통합 AST/IR)만 본다."** sqlglot은 단일 AST로, sqlfluff는 공유 세그먼트 타입명으로, ShardingSphere/Coral은 통합 IR로 — 방법은 달라도 룰 코드가 방언에 독립적이 되게 만드는 게 핵심.

## 프로젝트별 핵심 소견

| 프로젝트 | 패턴 | MySQL 커버리지 | 룰 작성 편의 | 유지보수(2025–26) | 거버넌스 선례 |
|---|---|---|---|---|---|
| sqlglot (Python) | A | 매우 좋음 | 최상 | 매우 활발 | 리니지/최적화 (게이트 아님) |
| sqlfluff (Python) | B | 린터급(파스 실패 관용) | 좋음 | 활발 | 린팅만 — soft-fail은 게이트에 부적합 |
| Calcite babel (JVM) | A | 실전 MySQL 불확실 | 좋음 | 매우 활발 | Coral이 기반으로 사용 |
| JSQLParser (JVM) | A | 일반 DML 좋음, DDL/엣지 공백 문서화됨 | 매우 좋음(타입드 AST·visitor·영문 문서) | 활발(5.3, 2025-05) | JVM SQL 재작성에 광범위 사용 |
| **Alibaba Druid (JVM)** | B | **MySQL DML+DDL 풀 커버(알리바바 실전 검증)** | 좋음(SQLASTVisitor·SchemaStatVisitor) / API 산만·중문 문서 | 활발(1.2.28, 2025-03) | **WallFilter = 프로덕션 SQL 방화벽, 우리와 동일 유스케이스** |
| ANTLR grammars-v4 MySQL (Oracle 문법) | C 원재료 | **최고 충실도**(서버 Yacc 유래, 연 2회 동기화) | 없음 — CST만 제공, AST 층 직접 구축 필요 | 활발 (PT 문법은 2025 제거됨) | ShardingSphere/Bytebase가 이 패턴 |
| ShardingSphere parser (JVM) | C | 매우 좋음 | 중간(샤딩 목적에 튜닝됨) | 매우 활발(ASF) | 샤딩/라우팅용 |

주요 세부:
- **ANTLR 주의점**: (1) ANTLR는 AST가 아니라 **CST(파스 트리)** 를 준다 — AST 구축 visitor 층을 직접 만들고 유지해야 함(ShardingSphere는 이걸 팀 단위로 유지). (2) SQL 문법의 모호성 때문에 성능 문제가 알려져 있음(grammars-v4 #1231; Bytebase는 튜닝으로 70x 개선한 사례 발표 — 즉 가능하지만 튜닝 비용은 우리 몫).
- **Calcite babel은 best-effort 슈퍼셋** — 실전 MySQL(힌트, `ON DUPLICATE KEY UPDATE` 엣지, 방언 DDL)이 새는 지점. 게이트의 1차 파서로는 위험. 나중에 컬럼 리니지/뷰 재작성이 필요해지면 재고.
- **Bytebase의 결론**(파서 서베이 후): "거버넌스에는 범용 파서보다 DB 전용 파서" — 정밀도가 최우선이라서. 게이트는 파스 실패를 **hard-fail** 해야 하므로 린터식 관용 모델은 부적합.
- **Trino는 나중에 `io.trino:trino-parser`**(트리노 자체 파서, 타입드 AST + AstVisitor)를 그대로 사용 — 다른 문법으로 근사하지 말 것.

## 권고 (스펙 001 반영 대상)

**패턴 C + 자체 얇은 IR: 방언별 최고 파서를 사되, 룰이 바라보는 정규화 IR(Kotlin sealed class: Statement/TableRef/Predicate/ColumnRef + visitor)을 우리가 소유한다.**

1. 룰은 IR만 본다 → 파서 교체/추가가 국소 변경이 됨. 거버넌스 룰이 필요한 SQL 조각은 좁다(참조 테이블, WHERE 술어 구조, 컬럼, 문 종류) → IR은 얇게 유지 가능. 희귀한 방언 전용 룰을 위해 원본 AST 노드 escape hatch 노출.
2. **MySQL 1호 어댑터 = Alibaba Druid 파서** — 근거: MySQL DML+DDL 풀 커버(게이트는 정밀도가 1순위), WallFilter라는 동일 유스케이스 실전 선례, SchemaStatVisitor로 테이블/컬럼/조건 추출이 거의 공짜, save-path 동기 실행 가능한 속도. 단점(중문 문서·산만한 API)은 IR 어댑터 뒤에 격리.
   - 차선: JSQLParser(영문 문서·깔끔한 API 선호 시). IR 덕에 나중에 Druid로 교체해도 국소 변경.
3. Calcite babel·raw ANTLR grammars-v4를 1차 게이트로 쓰지 말 것(전자: 커버리지, 후자: CST→AST 구축+성능 튜닝 비용).

## 소스

- sqlglot: github.com/tobymao/sqlglot · deepwiki.com/tobymao/sqlglot/4-dialect-system
- sqlfluff: docs.sqlfluff.com (dialect contributing / rules)
- Calcite: calcite.apache.org/docs/history.html · CALCITE-4802
- JSQLParser: jsqlparser.github.io/JSqlParser/ (unsupported.html, changelog)
- Alibaba Druid: github.com/alibaba/druid/wiki/SQL-Parser · WallFilter
- ANTLR grammars-v4: github.com/antlr/grammars-v4/tree/master/sql/mysql (Oracle README) · issue #1231 · bytebase.com/blog/how-we-improved-sql-parser-speed-70x/
- 파서 지형: bytebase.com/blog/top-open-source-sql-parsers/ (2026-02 갱신)
- 선례: github.com/linkedin/coral · shardingsphere.apache.org (parse reference) · github.com/bytebase/trino-parser
