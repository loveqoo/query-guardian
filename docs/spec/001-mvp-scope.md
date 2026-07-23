# 001 — Query Guardian MVP 범위·아키텍처

> 상태: **승인 (v2 — 적대적 검토 반영)**
> 작성: 2026-07-24 · 근거: `.dev/learning/001-multi-dialect-sql-parsing.md`, 적대 검토: `.dev/learning/002-spec-001-adversarial-review.md`
> v2 변경: 룰 판정 계약(§6) 신설, 재귀 IR·SELECT 전용 게이트·관리형 purpose로 전환, 검증 기준을 우회/오탐 스위트로 강화

## 1. 배경·목적

Query Guardian은 **통제된 환경에서 안전하게 SQL을 작성·등록하는 SQL 거버넌스 서비스**다.
사용자가 쿼리 에디터에서 SQL을 작성하면, 시스템이 이를 파싱(AST)하고 등록된 룰에 비추어
검사한 뒤, **룰 위반 시 저장을 차단**하고 위반 내용을 알려준다.

대표 시나리오:
- 테이블 `user_events`는 파티션 키 `event_date`가 WHERE에 반드시 있어야 한다 → 누락 시 차단.
- 마케팅 목적 조회는 `consent_yn = 'Y'` 조건이 반드시 있어야 한다 → 누락 시 차단.

장기 비전은 "쿼리 기반 변종 서비스를 이 하나로" — 방언 추가(PostgreSQL, Trino)와 룰 확장이
국소 변경이 되도록 처음부터 경계를 설계한다.

## 2. MVP 범위 / 비범위

### 범위 (이번 단위)
- **방언 1개: MySQL** (테스트 용이성 기준 선정)
- **문 종류: SELECT만 저장 가능** — INSERT/UPDATE/DELETE/DDL은 게이트에서 hard-fail
  (변이문에 대한 파티션·술어 룰 적용은 다음 단위. 무제한 DELETE를 통과시키는 게이트보다 안전한 기본값)
- **룰 4개**: 구조 룰 2 + 의미 룰 2 (§7)
- **쿼리 에디터**(CodeMirror 6): 하이라이팅, 스키마 자동완성, 타이핑 중 룰 경고(debounce lint)
- **저장 게이트**: 파스 실패 또는 BLOCK 위반 시 저장 거부. **fail-closed 원칙**(§6)
- **메타데이터 카탈로그**: 테이블·컬럼·파티션 키·필수 술어 + **관리형 purpose 목록** (관리 화면 포함)
- **쿼리 저장소**: 저장·목록·조회·수정(재검사)·삭제
- 설정 DB(MySQL) docker compose 구성

### 비범위 (명시적으로 다음 단위로)
- PostgreSQL·Trino 지원 (파서 어댑터 추가로 대응할 수 있게 경계만 확보)
- 변이문(INSERT/UPDATE/DELETE) 거버넌스 — MVP는 저장 자체를 차단
- 저장된 쿼리의 **실제 실행**(대상 DB 연결·결과 조회)
- 인증·권한(SSO, 역할별 접근 제어) — MVP는 단일 사용자 가정
- 문맥 인식 고급 자동완성(dt-sql-parser 등) — 스키마 사전 완성까지만
- 쿼리 버전 관리·승인 워크플로
- 카탈로그 변경 시 기존 저장 쿼리 일괄 재검사(`relint`) — 백로그 (lint 결과는 저장 시점 스냅샷임을 명시)

## 3. 아키텍처 개요

```
[React SPA]                         [Spring Boot (Kotlin)]
 CodeMirror 6 에디터 ── debounce ──→ POST /api/lint ──┐
 저장 버튼 ─────────────────────────→ POST /api/queries │
 카탈로그 관리 화면 ←──────────────── /api/catalog/**   │
                                                      ▼
                                    파서 어댑터 (Druid MySQL 파서)
                                                      ▼
                                    IR (우리 소유, 재귀적 스코프 트리)
                                                      ▼
                                    룰 엔진 ←── 메타데이터 카탈로그
                                                      ▼
                                    판정: PASS → 저장 / BLOCK → 거부+위반 목록
                                                      │
                                              [설정 DB: MySQL]
```

원칙:
1. **룰은 IR만 본다.** 파서(Druid) 타입은 어댑터 밖으로 새지 않는다.
2. **게이트는 fail-closed.** 파싱 불가·판정 불가·표현 불가는 전부 차단 쪽으로 떨어진다(§6).
3. 방언 추가 = `DialectParser` 구현체 추가 (PG: Druid PG 파서, Trino: `io.trino:trino-parser`).

## 4. 저장소 구조

```
backend/          Kotlin + Spring Boot + Gradle(kts)
  src/main/kotlin/.../
    parser/       DialectParser 인터페이스 + DruidMySqlParser 어댑터
    ir/           IR 모델(sealed class) + visitor
    rules/        Rule 인터페이스 + 구현 4종 + RuleEngine
    catalog/      메타데이터 카탈로그 (entity/repo/service)
    query/        쿼리 저장소 (entity/repo/service)
    api/          REST 컨트롤러
frontend/         Vite + React + TypeScript + antd + zod + react-hook-form + CodeMirror 6
docker/           compose.yml (설정 DB MySQL 8)
```

## 5. 핵심 컴포넌트

### 5.1 파서 어댑터
```kotlin
interface DialectParser {
    val dialect: Dialect                    // MYSQL (추후 POSTGRESQL, TRINO)
    fun parse(sql: String): ParseResult     // Success(ir: QueryIR) | Failure(errors)
}
```
MVP 구현: `DruidMySqlParser` — `com.alibaba:druid`(버전 고정)의
`SQLUtils.parseStatements(sql, DbType.mysql)` → visitor로 `QueryIR` 구성.
- **멀티 스테이트먼트 거부**: 문이 2개 이상이면 전용 위반으로 hard-fail (말미 세미콜론 1개는 허용).
- **SELECT 외 거부**: 최상위 문이 SELECT가 아니면 hard-fail.
- **입력 가드**: SQL 최대 64KB, 파싱 타임아웃 2초 — 초과 시 500이 아닌 위반 응답.
- Druid 타입은 `parser/` 패키지 밖 노출 금지 — **ArchUnit 테스트로 강제**.
  (Druid는 커넥션 풀 전체를 포함하는 아티팩트 — DruidDataSource/StatViewServlet 절대 연결 금지, CVE 워치 대상)

### 5.2 IR — 재귀적 스코프 트리

```kotlin
data class QueryIR(
    val root: SelectScope,
)
// 스코프 = 거버넌스 판정의 단위. 서브쿼리·파생 테이블·CTE 본문·UNION 각 팔이 전부 자식 스코프.
data class SelectScope(
    val tables: List<TableRef>,             // 이 스코프의 FROM 대상 (alias 포함)
    val selectItems: List<SelectItem>,      // Column / Star(qualifier?) / Aggregate / Expr — select-item 단위로 Star 태깅
    val whereConjuncts: List<Predicate>,    // WHERE의 최상위 AND conjunct 목록 (§6.1). INNER JOIN ON의 conjunct 포함, OUTER JOIN ON 제외
    val limit: Long?,
    val children: List<ChildScope>,         // ChildScope(kind: SUBQUERY|DERIVED|CTE|UNION_ARM|EXISTS, scope: SelectScope)
)
sealed interface Predicate {
    data class Comparison(val column: ResolvedColumn, val op: Op, val value: Value) : Predicate
    data class In(...) : Predicate; data class Between(...) : Predicate
    data class Or(val branches: List<Predicate>) : Predicate
    data class Not(val inner: Predicate) : Predicate
    data class Raw(val sqlFragment: String) : Predicate   // 표현 불가 escape hatch — 절대 요건을 충족시키지 못함(§6.3)
}
data class ResolvedColumn(val table: String?, val column: String)
// table = alias 해석 결과. 다중 테이블 FROM에서 비한정 컬럼이라 귀속 불가면 null (§6.4)
```
설계 규칙: 거버넌스 룰이 필요로 하는 조각만 담는다. SQL 전체 정규화 금지.

### 5.3 룰 엔진
```kotlin
interface Rule {
    val id: String
    val severity: Severity                  // BLOCK / WARN
    fun check(scope: SelectScope, catalog: TableCatalog, context: LintContext): List<Violation>
}
```
- **룰은 모든 스코프에 대해 실행된다** (루트 + 재귀 자식 전부). 한 스코프의 위반 = 제출 전체의 위반.
- UNION: 각 팔이 독립 스코프 — 어느 한 팔의 위반도 전체 위반.
- 결과: `LintReport(violations)` — BLOCK 1건 이상이면 저장 거부, WARN은 저장 허용+리포트에 기록.

### 5.4 메타데이터 카탈로그
- 테이블 단위 등록: 컬럼 목록(자동완성 사전 겸용), 파티션 키/필수 인덱스 컬럼,
  필수 술어(예: `consent_yn = 'Y'`) + 적용 조건(항상 or 특정 purpose일 때).
- **purpose는 관리형 목록**(자유 입력 금지): 카탈로그에서 CRUD, 에디터에서는 select로만 선택.
  BLOCK 룰의 적용 여부를 사용자가 임의 문자열로 결정할 수 없게 하기 위함(적대 검토 C9).
- 필수 술어는 문자열로 저장하되 **로드 시 동일 DialectParser로 파싱해 Predicate로 보관** — 비교는 항상 구조적(§6.5).

### 5.5 프론트엔드
- **에디터**: CodeMirror 6 + `@codemirror/lang-sql`(MySQL 방언). 스키마 자동완성 사전은
  `/api/catalog/schema`에서 로드. 타이핑 멈춤 500ms 후 `/api/lint` 호출, 위반을 에디터에 표시.
- **화면 3개**: ① 쿼리 에디터(방언 select + purpose select + 저장), ② 쿼리 목록(수정·삭제 포함), ③ 카탈로그 관리(테이블·제약·purpose).
- 폼 검증: zod + react-hook-form. UI: antd.

## 6. 룰 판정 계약 (fail-closed invariants) — 게이트의 핵심 §

적대 검토(C1~C9, H1~H4)에서 도출. **구현 전 이 계약을 테스트로 먼저 고정한다.**

### 6.1 요건 충족 = "최상위 AND conjunct"만 인정
필수 술어·파티션 키 요건은 해당 스코프 `whereConjuncts`(WHERE의 최상위 AND 분해 목록)에서
**`Or`/`Not` 아래에 있지 않은** 구조적 매치로만 충족된다.
`WHERE consent_yn='Y' OR 1=1` → 미충족(차단). `NOT (consent_yn='Y')`, `consent_yn <> 'Y'` → 미충족.
INNER JOIN ON의 conjunct는 WHERE와 동치로 인정, **OUTER JOIN ON의 술어는 요건을 충족시키지 못한다**(LEFT JOIN ON은 행을 필터링하지 않음).

### 6.2 스코프 은닉 금지
룰은 루트뿐 아니라 모든 자식 스코프(서브쿼리·파생 테이블·CTE 본문·UNION 팔·IN/EXISTS 서브쿼리)에서 실행된다.
`WITH x AS (SELECT * FROM user_events) SELECT id FROM x` → `no-select-star`와 의미 룰이 CTE 본문에서 발화.

### 6.3 Raw는 요건을 충족시키지 못한다
구조적으로 인식된 술어만 요건을 충족한다. `Raw`(IR로 표현 못 한 조각)는 **절대 충족으로 치지 않으며**,
요건 대상 테이블의 WHERE 판정이 Raw 때문에 불가능하면 "검증 불가 술어" 사유로 **차단**한다(fail-closed).

### 6.4 컬럼 귀속 불가 = 미충족, 귀속 단위는 테이블 "인스턴스"
`Comparison.column`은 alias 해석을 거친 `ResolvedColumn`이다. 다중 테이블 FROM에서 비한정 컬럼이라
거버넌스 대상 테이블로 **양의 귀속이 안 되면 요건 미충족**으로 처리한다(fail-closed).
`SELECT ue.id FROM user_events ue, audit_events ae WHERE ae.event_date='...'` → user_events의 파티션 키 미충족 → 차단.
**귀속 단위는 물리 테이블명이 아니라 FROM 안의 인스턴스(alias)다** — 셀프 조인
`user_events a JOIN user_events b`에서 `a.event_date=...`는 `b` 인스턴스의 요건을 충족시키지 못한다.
파생 테이블/CTE의 alias는 물리 테이블이 아니므로 카탈로그 조회 대상에서 제외한다(우연히 이름이 겹쳐도 오차단 금지).
(M1 검증 F2·F5 반영)

### 6.5 술어 비교는 구조적, 매치 규칙은 닫힌 목록
문자열 비교 금지(주석 삽입·공백·대소문자에 깨짐). 카탈로그의 필수 술어는 파싱된 Predicate와
구조 비교하며, 인정하는 동치는 다음뿐: `=`의 피연산자 순서 무시, 식별자는 MySQL 규칙으로 케이스 정규화,
`IN ('Y')` ≡ `= 'Y'`. 그 외 변형은 미충족.
**식별자 정규화에는 인용 부호(백틱) 제거가 포함된다** — 테이블·컬럼·alias·한정자 모두 IR 진입 전에
정규화한다. `` `user_events` `` ≠ `user_events`로 갈라지면 한쪽은 우회, 한쪽은 오차단이 된다. (M1 검증 F1 반영)

### 6.6 파티션 키 충족 형태는 닫힌 목록
베어 컬럼에 대한 리터럴 `=` / `IN` / `BETWEEN` 만 충족. **함수로 감싼 컬럼은 미충족**
(`DATE(event_date)='...'`는 MySQL이 파티션 프루닝을 못 하므로 목적 자체가 무산됨).
알려진 한계(문서화): 존재 기반 검사이므로 `event_date >= '1900-01-01'` 같은 저선택도 술어는 통과한다.

### 6.7 no-select-star의 정확한 발화 조건
select-item 수준의 `*` / `t.*` 에만 발화. `COUNT(*)` 등 집계 star는 발화하지 않음.
`EXISTS (SELECT * ...)` 내부의 star는 관용 표현이므로 **면제** (단, 그 서브쿼리에도 의미 룰은 그대로 적용).

## 7. MVP 룰 목록

| ID | 종류 | 내용 | severity |
|---|---|---|---|
| `no-select-star` | 구조 | select-item `*`/`t.*` 금지 (§6.7의 예외 적용) | BLOCK |
| `require-limit` | 구조 | 루트 스코프 SELECT에 LIMIT 필수 (한계: `LIMIT 0`/거대값도 통과 — 문서화) | WARN |
| `require-partition-key` | 의미 | 파티션 키 등록 테이블은 §6.1+§6.6 형태로 WHERE에 필수 | BLOCK |
| `require-predicate` | 의미 | 필수 술어 등록 테이블은 §6.1+§6.5 매치로 필수 (purpose 조건 지원) | BLOCK |
| `unknown-table` | 의미 | 카탈로그 미등록 물리 테이블 경고 — 자동완성·의미 룰 미적용을 알림. CTE/파생 alias는 오탐 방지 위해 제외 (사용자 요청, v2 이후 추가) | WARN |

## 8. API 스케치

```
POST   /api/lint             { dialect, sql, purposeId? } → { violations: [...] }
POST   /api/queries          { name, dialect, sql, purposeId? } → 201(body에 LintReport 포함 — WARN 표시용) | 422(위반 목록)
GET    /api/queries          목록 · GET /api/queries/{id}
PUT    /api/queries/{id}     수정 — 저장과 동일 게이트로 재검사
DELETE /api/queries/{id}
GET    /api/catalog/tables   목록 · POST/PUT/DELETE 등록 관리
GET    /api/catalog/purposes 목록 · POST/DELETE (관리형 purpose)
GET    /api/catalog/schema   자동완성용 { table: [columns] } 사전
```
- `dialect`는 필수·검증(미지원 값 4xx 거부) — 전방 호환이 무검증 통과가 되지 않게.
- 입력 가드(§5.1)는 두 엔드포인트 공통.

## 9. 데이터 모델 (설정 DB)

```
catalog_table(id, name, description)                -- 주: 방언 컬럼 없음 — PG/Trino 추가 전 재검토 (L3)
catalog_column(id, table_id, name, type)
catalog_purpose(id, code, description)              -- 관리형 purpose
catalog_constraint(id, table_id, kind[PARTITION_KEY|REQUIRED_PREDICATE],
                   column_name?, predicate_sql?, purpose_id?)
saved_query(id, name, dialect, sql, purpose_id?, lint_report_json, created_at, updated_at)
                                                    -- lint_report_json은 저장 시점 스냅샷 (카탈로그 변경 시 재검사는 백로그)
```

## 10. 기술 스택 확정

| 영역 | 선택 | 비고 |
|---|---|---|
| 백엔드 | Kotlin, Spring Boot 3.x, Gradle(kts), JDK 21 | |
| SQL 파서 | Alibaba Druid (MySQL) — 버전 고정 | 파서 API만 사용, ArchUnit로 격리 강제 |
| 영속성 | spring-data-jdbc | 확정 (§12.2) |
| 설정 DB | MySQL 8 (docker compose, OrbStack 호환) | |
| 프론트 | Vite, React, TypeScript, antd, zod, react-hook-form | |
| 에디터 | CodeMirror 6 + @codemirror/lang-sql | 확정 |

## 11. 마일스톤

1. **M1 — 코어 파이프라인 (백엔드만)**: 파서 어댑터 → 재귀 IR → 룰 4종.
   **§6 계약을 테스트로 먼저 고정**(우회·오탐 스위트) 후 구현. 리스크 최대 지점.
2. **M2 — API + 설정 DB**: 카탈로그(purpose 포함)·쿼리 저장소·lint/save API + docker compose.
3. **M3 — 프론트엔드**: 에디터 + lint 연동 + 카탈로그 관리 화면.
4. **M4 — E2E 검증**: §12 체크리스트 전체.

## 12. 완료 기준 (Verification 체크리스트)

기동·플로우:
- [x] `docker compose up` + 백엔드 + 프론트 기동으로 전체 플로우 동작
- [x] 파티션 키 누락 쿼리 저장 시도 → 422 + 위반 사유 표시 (E2E)
- [x] 필수 술어(`consent_yn='Y'`) 누락 → 차단, 포함 → 저장 성공 (E2E)
- [x] 에디터: 하이라이팅·스키마 자동완성·타이핑 중 위반 경고 동작

**우회 시도 스위트 — 전부 차단되어야 함:**
- [x] `WHERE consent_yn='Y' OR 1=1` (OR 가지 세탁)
- [x] 파생 테이블/CTE/IN-서브쿼리에 숨긴 `SELECT *`·거버넌스 테이블
- [x] UNION의 한 팔만 위반 (더러운 팔 세탁)
- [x] `SELECT 1; SELECT * FROM user_events` (멀티 스테이트먼트)
- [x] `DELETE FROM user_events` 등 비-SELECT 제출
- [x] `NOT (consent_yn='Y')` / `consent_yn <> 'Y'` (부정형)
- [x] `DATE(event_date) = '...'` (함수 래핑 파티션 키)
- [x] Raw 강제 WHERE (표현 불가 술어 → 검증 불가 차단)
- [x] `-- consent_yn='Y'` 주석 삽입 (구조 비교 검증)
- [x] 문법 오류·64KB 초과·타임아웃 → 위반 응답(500 아님)

**오탐 스위트 — 전부 통과되어야 함:**
- [x] `SELECT COUNT(*) FROM user_events WHERE event_date='...'`
- [x] `EXISTS (SELECT * FROM ...)` 관용구
- [x] `e.event_date = '...'` (alias 한정 — 조인에서 올바른 테이블 귀속)
- [x] `event_date IN (...)` / `BETWEEN ...` (유효한 프루닝 형태)
- [x] `'Y' = consent_yn` / `consent_yn IN ('Y')` (§6.5 동치)

단위·구조:
- [x] 룰 4종 단위 테스트 (통과/위반/경계)
- [x] alias 해석 단위 테스트 (다중 테이블 귀속·귀속 불가 fail-closed)
- [x] Druid AST→IR: 대표 코퍼스(조인·서브쿼리·CTE·UNION·별칭) 변환 테스트
- [x] ArchUnit: `com.alibaba.druid` 타입이 `parser/` 밖에서 참조되지 않음

## 13. 결정 기록 (구 오픈 퀘스천)

1. **purpose 모델**: ~~자유 태그~~ → **관리형 목록** (적대 검토 C9로 번복 — 자유 태그는 BLOCK 룰 자가 면제 허용)
2. **영속성 계층**: spring-data-jdbc 확정
3. **WARN 룰 UX**: 확인 없이 저장 + 201 응답 body의 LintReport로 표시 (M6 반영)
4. **모노레포 빌드**: backend/frontend 독립 빌드 확정
