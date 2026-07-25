# 008 — 쿼리 실행 · 마스킹 재작성

> 상태: **v2 (적대 검토 반영 — 착수 전 필독)**
> 작성: 2026-07-25 · 근거: learning 004 §2.5(디자인의 실행·마스킹), spec 002 §9-1(단계적 강제 — 재작성 보류 결정), spec 005 §2(review_status 승격 예고)
> 사용자 지시 순서 ③. spec 002가 미룬 **재작성(rewrite)** 을 도입하고 디자인의 실행 결과 화면을 실제로 만든다.

## 1. 목표

1. **IR→SQL 재작성 엔진**: MASK는 SELECT 절 치환, FILTER·INTEGRITY는 WHERE 술어 주입, LIMIT 상한 주입.
2. **쿼리 실행**: 재작성된 SQL을 실제로 실행해 결과를 반환. 디자인의 마스킹된 결과(`j***@naver.com`)가 진짜가 된다.
3. **안전장치 4종**(사용자 확정): 검토 상태 게이트 · 행 상한·타임아웃 · 읽기 전용 연결 · 실행 감사 로그.
4. `must_be_masked` 판정 구현(spec 004에서 미판정으로 남긴 것) — 재작성이 있으므로 "마스킹 안 됨"을 정의할 수 있다.

## 2. 범위 / 비범위

### 범위
- **재작성 엔진**(`SqlRewriter`): MASK(SELECT 치환)·FILTER/INTEGRITY(WHERE 주입)·LIMIT 상한 주입
- **실행**: 설정 DB(동일 MySQL)에 **데모 데이터 테이블** 생성 후 그 위에서 실행 (사용자 확정: 동일 DB로 시작)
- **실행 게이트**: 인증 → 데이터 권한 → 룰 → **검토 승인(APPROVED)** → 실행
- 읽기 전용 실행 연결(별도 DataSource·읽기 전용 트랜잭션), 행 상한·질의 타임아웃
- `execution_event` append-only 감사(누가·언제·원본 SQL·재작성 SQL·행 수·소요·결과)
- `must_be_masked` 판정: MASK 매핑 컬럼이 **재작성 없이 원본으로 조회되면** 위반
- 에디터 "실행" 버튼·실행 결과 탭 실동작(재작성 SQL 표시 포함)

### 비범위
- 별도 원격 대상 DB 등록·자격증명 보관(사용자 확정: 동일 DB로 시작 → ④에서 재검토)
- 결과 내보내기(CSV·다운로드), 페이지네이션(첫 N행만)
- 비동기·장기 실행 쿼리 관리(큐·취소), 결과 캐시
- JOIN kind 판정(spec 004의 joins로 이미 커버), 멀티 벤더(④)

## 2.5 착수 전 필수 전제 — 적대 검토가 **실측**으로 밝힌 사실 (v2)

검토자가 druid-1.2.28로 하네스를 만들어 확인한 것들. **초안의 전제가 틀렸다**:

1. **파서는 AST를 보관하지 않는다** — `QueryIR`은 `raw: String`만 들고 `SQLStatement`는 즉시 폐기된다.
   따라서 재작성은 **필연적으로 2차 파싱**이고, 그 결과가 판정된 IR과 다를 수 있다(**판정-실행 분기**).
2. **Druid는 주석을 출력에 보존하고 MySQL은 `/*! */`를 실행한다** —
   `SELECT email FROM users /*!50000 UNION SELECT ssn FROM users */` → IR은 ssn·UNION을 **전혀 못 본다**(BLOCK·커버·MASK 전부 무발화),
   출력엔 주석이 살아남아 **평문 ssn이 반환된다**. 후행 `--`/`#` 주석은 **주입한 LIMIT을 삼킨다**.
3. **Druid는 괄호를 자동 삽입하지 않는다** — `WHERE a=1 OR b=2`에 AND 결합하면
   `WHERE a = 1 OR b = 2 AND consent='Y'`가 되어 주입 술어가 `b=2` 가지에만 붙는다(우선순위 붕괴).
   해법(실측 확인): 원본 WHERE에 `setParenthesized(true)` 후 AND 결합.
4. **MASK를 select-item `Column`으로만 잡으면 한 겹 표현식에 뚫린다** —
   `LOWER(email)`·`CONCAT(email,'')`·`CASE WHEN…email`·`email COLLATE …`는 IR에서 `SelectItem.Expr`이라 치환 대상이 아니다.
5. **스키마 한정자·`INTO OUTFILE`·사용자 변수·0-테이블 쿼리는 IR에서 사라지고 출력엔 남는다** —
   `SELECT ssn FROM otherdb.users`는 논리 `users` 권한으로 통과하고, `SELECT LOAD_FILE(...)`·`SELECT @v`는
   **테이블이 0개라 모든 테이블 기반 게이트를 통과**한다(`AccessControl.checkTables`는 빈 집합이면 즉시 return).
6. **`Expressions.substitute`는 아직 문자열 replace다** — spec 002 §3.3의 "AST 노드 치환" 미이행.
   판정 전용일 때는 무해했지만 재작성이 이 문자열을 최종 SQL에 넣으면 injection 표면이 된다.

### 2.6 위생 게이트 (신설 — 다른 결함의 폭발 반경을 줄이므로 M1보다 **선행**)

실행·재작성 대상 SQL은 **AST에서** 다음을 통과해야 한다(IR은 lossy하므로 IR로 검사하면 안 된다). 위반 시 422:
- **모든 주석 금지**(`--`·`#`·`/* */`·`/*! */`) — 리터럴·백틱을 인식하는 어휘 프리스캔으로 검출
  (`sql.contains("--")` 같은 단순 검사는 `note = 'a--b'`를 오차단하므로 금지). 코드 `COMMENT_NOT_ALLOWED`
- **문형 허용목록**: `INTO`(OUTFILE/DUMPFILE/변수)·`FOR UPDATE`·`LOCK IN SHARE MODE`·`PROCEDURE`·옵티마이저 힌트 거부
- **변수 참조 금지**: 사용자(`@x`)·시스템(`@@x`)·바인드(`?`,`:name`) — 2단 유출(`SELECT email INTO @v` → `SELECT @v`) 차단
- **스키마 한정자 거부**(`SCHEMA_QUALIFIER`) — 판정은 `users`, 실행은 `otherdb.users`가 되는 분기 차단
- **0-테이블 쿼리 거부**(`NO_PHYSICAL_TABLE`) — `LOAD_FILE`·`@@version`·`SLEEP` 정찰 차단
- 금지 함수 즉시 목록: `LOAD_FILE`·`SLEEP`·`BENCHMARK`·`GET_LOCK`

### 2.7 실행 격리 3종 (신설 — 애플리케이션 버그와 무관한 최후 방어선)

초안의 "설정 DB에 데모 테이블"은 실행 계정이 `app_user.password_hash`·`rule`·`approval_request`를
읽을 수 있게 만든다. 다음으로 교체한다:
1. **별도 스키마** `queryguardian_demo`에 데모 데이터.
2. **별도 MySQL 계정** `qg_exec` — `GRANT SELECT ON queryguardian_demo.*`만. 설정 스키마 **무권한**, FILE/PROCESS/SUPER 없음.
   (스키마·계정 생성은 `schema.sql`로 불가 → `docker/compose.yml`의 `/docker-entrypoint-initdb.d` 초기화 스크립트 필요.)
3. **`demo_table_map`이 실행 허용목록을 겸한다** — 참조 테이블 중 **미매핑이 하나라도 있으면 실행 거부**(`NO_DEMO_MAPPING`).
   부분 매핑을 허용하면 `SELECT tree_json FROM rule`처럼 **실재하는 거버넌스 테이블을 직격**한다.

### 2.8 M0 구현 결과 (2026-07-25 — 적대 검토 2차 반영)

M0(위생 게이트 + 실행 격리)은 **구현·검증 완료**. 구현 중 스펙에서 갈라진 결정과, 2차 적대 검토가 실측으로
잡은 것들:

**스펙에서 갈라진 결정(의도적)**
1. **위생은 lint·저장 시점에도 발화한다** — §2.6은 실행 게이트에 뒀지만 *실행 대상 = 저장된 쿼리*라 두 집합이
   같다. 저장 때 잡으면 같은 방어를 유지하면서 "승인까지 받았는데 실행 불가"가 없어진다. **단락시키지 않고
   추가 위반**으로 넣어 나머지 룰 판정 결과를 함께 보여준다.
2. **파싱은 1회** (`DialectParser.inspect(sql) → (ParseResult, List<HygieneViolation>)`) — 위생용 2차 파싱은
   타임아웃 레이스로 **판정 대상과 검사 대상이 갈라짐이 실측**됐다(84회 중 57회 fail-open 관측). M1의 재작성
   파싱도 이 경로에 얹는다.
3. **검사 불가 = 위반**(`UNVERIFIABLE`) — 빈 목록은 "위반 없음"으로 읽혀 위생을 독립 단계로 호출하는
   §5 경로가 fail-open한다. `INTO DUMPFILE`·`PROCEDURE ANALYSE`·`TABLE users`가 이 경로였다.
4. **`--`는 MySQL 규칙(뒤에 공백류/문장 끝)을 따른다** — Druid 프린터가 `- -1`을 `--1`로 출력하므로,
   무조건 거부하면 `hygiene(sql) 통과 ⟹ hygiene(rewrite(sql)) 통과`가 깨져 §3.0.3 재작성 검증이 성립하지 않는다.
5. **0-테이블 판정은 IR 기준**(스코프별 CTE 해석) — AST에서 CTE 이름을 전역 수집하면 동명 집계 CTE
   (`WITH user_events AS (SELECT … FROM user_events)`, 분석가 표준 관용구)를 오차단한다. `dual`은 물리 테이블에서 제외.

**2차 적대 검토가 실측으로 잡은 결함(전부 수정)**
- **CRITICAL**: `` `sleep`(5) `` — Druid가 함수명에 백틱을 남겨 금지 함수 목록 4종이 **전부 무효**였고,
  MySQL 8.4는 백틱 함수명을 빌트인으로 실행했다(3초 지연 실측). → 함수명도 `norm()` 통과(spec 001 §6.5의 누락분).
- **HIGH**: `FOR SHARE`(MySQL 8의 현행 문법)가 Druid의 어떤 플래그에도 담기지 않아 통과했다.
  `START TRANSACTION READ ONLY`에서도 **실행되므로** DB 권한이 막아주지 않는 유일한 잠금 형태였다. → 어휘 검사.
- **HIGH**: `FROM DUAL` 네 글자로 0-테이블 검사가 무효화됐다(`SELECT @@version … FROM DUAL`).
- **MEDIUM**: 마스킹 함수가 `DEFINER=root`로 생성됐고 `GRANT EXECUTE ON queryguardian_demo.*`가 **미래 루틴까지**
  포함했다. → `SQL SECURITY INVOKER` + 함수별 `GRANT EXECUTE`.
- 스캐너 전제 명문화: `NO_BACKSLASH_ESCAPES`가 **없는** `sql_mode` 가정 → M2 실행 커넥션에서 고정해야 한다.

**M0에서 발견된 별건 (spec 001 §6.2 위반, 수정 + 우회 스위트 추가)**
CTE에 물리 테이블과 **같은 이름**을 붙이면 그 본문의 물리 테이블 참조가 IR에서 사라져 BLOCK 룰이 발화하지
않았다. MySQL은 비재귀 CTE 본문의 자기 이름 참조를 **물리 테이블로 해석**한다(`WITH demo_users AS
(SELECT id, ssn FROM demo_users) …`가 실제 ssn 반환 실측). → CTE 가시 범위를 "앞서 정의된 CTE만"으로
바로잡고(RECURSIVE면 자기 이름 포함) `BypassSuiteTest`에 회귀 고정.

**남은 부채(M1·M2에서)**: 문형 검사를 화이트리스트로 전환(현재는 아는 나쁜 것 열거라 MySQL 신문법마다
구멍), 파서 executor 상한, 물리명 삽입 시 백틱 인용, `demo_table_map` 관리 UI(대소문자 구분 환경).

## 3. 재작성 엔진 (SqlRewriter)

입력: 원본 SQL + IR + 카탈로그 매핑 + purposeCode. 출력: `RewriteResult(sql, applied: List<AppliedRewrite>)`.

| kind | 재작성 | 예 |
|---|---|---|
| MASK | 해당 컬럼이 select-item으로 조회되면 **강제식으로 치환** + 원래 별칭 유지 | `SELECT email` → `SELECT mask_email(email) AS email` |
| FILTER | 술어를 **최상위 AND conjunct로 주입**(이미 있으면 생략) | `WHERE …` → `WHERE … AND consent_yn = 'Y'` |
| INTEGRITY | 동일하게 WHERE 주입 | `AND col IS NOT NULL` |
| LIMIT | 없으면 상한 주입, 상한 초과면 **하향 조정** | `LIMIT 5000` → `LIMIT 1000` |
| BLOCK/PARTITION | **재작성하지 않는다** — 판정(차단·요구)이 본질 | — |

### 3.0 주입 정확성 3원칙 (v2 — 실측 근거)
1. **WHERE 주입은 원본 WHERE를 괄호로 감싼 뒤 AND 결합**(`setParenthesized(true)`). Druid는 괄호를 자동
   삽입하지 않으므로 이걸 빠뜨리면 최상위 OR에서 주입이 무력화된다. 주입 술어 자체가 이항식이면 그것도 괄호.
2. **LIMIT은 단일 장치** — 유효 상한 `N = min(사용자 LIMIT ?: ∞, guardian.exec.max-rows)`,
   AST에 `LIMIT N+1` 주입, **`setMaxRows` 병용 금지**(Connector/J의 `SQL_SELECT_LIMIT`은 명시 LIMIT에 밀리고
   커넥션에 잔류한다). `N+1`번째 행이 오면 `truncated=true`로 확정하고 그 행은 버린다.
   **OFFSET은 보존하거나 금지**(둘 중 명시) — `LIMIT 1000,1000`으로 상한을 무한 우회할 수 있고, 현재 IR은 offset을 버린다.
   UNION은 팔이 아니라 **union 노드의 limit**을 조정한다.
3. **강제식 삽입도 문자열 조립 금지** — 치환된 강제식을 `toSQLExpr`로 **재파싱해 단일 `SQLExpr` 노드로** 얻어
   AST에 넣는다. 파싱 실패·서브쿼리 포함이면 실행 거부. (spec 002 §3.3 미이행분을 여기서 이행)

### 3.0.1 MASK 판정 기준 변경 (columnRefs 기반)
select-item이 아니라 **`columnRefs`** 로 판정한다:
- 스코프의 select-item **최상위 bare 컬럼** → 강제식 치환. **출력명은 원 별칭이 있으면 그 별칭, 없으면 원 컬럼명 유지**
  (초안 예시대로 `AS email`을 강제하면 `email AS mail`의 이름이 바뀌어 외부 참조가 깨진다).
- **그 외 모든 위치**(함수 인자·CASE·COLLATE·연산·GROUP BY/HAVING/ORDER BY/WHERE) → **표현 불가로 실행 거부**
  (`MASK_NOT_EXPRESSIBLE`) + 저장 시 `must_be_masked`는 **BLOCK**. spec 001 §6.3(Raw는 요건을 충족시키지 못한다)의 직계 적용.
- 파생 테이블에서 물리 테이블로부터 투영될 때는 **가장 안쪽 스코프에서** 치환(외곽 alias는 `physical=false`라 아무것도 안 걸린다).

### 3.0.2 스코프별 주입 규칙
- UNION은 **모든 팔**에 주입(한 팔 누락 = 그 팔로 전량 유출 — spec 001 §6.2 "더러운 팔 세탁"의 실행판).
- CTE 본문·파생 테이블·EXISTS/IN 서브쿼리는 **그 스코프 안**에 주입(외곽에는 그 컬럼이 없을 수 있다).
- **OUTER JOIN의 null-producing 쪽 테이블에 FILTER 대상이 있으면 재작성 거부** — WHERE 주입이 LEFT JOIN을
  사실상 INNER로 바꿔 의미가 변한다. 의미 변경보다 거부가 안전(fail-closed).

### 3.0.3 재작성 결과 자체 검증 (재작성 정확성의 유일한 기계적 안전망)
재작성된 SQL을 **다시 파싱**해 전부 단정한다. 하나라도 실패 시 실행하지 않는다(`REWRITE_VERIFY_FAILED`):
⑴ 문 1개·SELECT ⑵ 물리 테이블 집합 == 매핑된 물리명 집합 ⑶ 주입 술어가 각 대상 스코프 `whereConjuncts`의
**최상위 conjunct** ⑷ MASK 컬럼이 select-item Column으로 남아 있지 않음 ⑸ 루트 LIMIT ≤ 상한.

원칙:
- **재작성 실패 = 실행 차단**(fail-closed). 부분 적용 금지.
- 파이프라인 순서 고정: ① **논리명**으로 IR·제약·룰 판정 → ② 논리명 기준 `RewritePlan` 산출 →
  ③ AST에 plan 적용 → ④ **마지막 단계에서만** 테이블명 치환. 제약 조회는 절대 물리명으로 하지 않는다
  (물리명으로 조회하면 `boundFor("demo_users")`가 빈 목록이라 **마스킹·필터가 조용히 0건 적용**된다).
- 재작성 결과 노출은 **호출자가 접근 허용된 테이블의 항목만**.
- 식별자 위생: `catalog_column.name`·`demo_table_map.physical_name`에 정규식 검증
  (`^[A-Za-z_][A-Za-z0-9_]{0,63}$`) — 컬럼명을 통한 injection의 근본 차단.
- 패키지 경계(ArchUnit): `parser/SqlRewriter`는 **방언 중립 `RewritePlan`만** 받아 AST를 조작(카탈로그·권한 미인지),
  `exec/RewritePlanner`가 IR+카탈로그로 plan을 만든다(Druid 타입 미접촉). **`exec`도 `auth` 미의존**
  (재작성이 권한에 따라 달라지면 "권한 없는 사용자가 마스킹을 덜 받는" 역전 — spec 007 C4와 같은 함정).
  기존 규칙에 빠진 `..catalog..`도 이번에 보강.

### 3.1 must_be_masked 판정 (spec 004 잔여)
MASK 매핑된 컬럼이 select-item에 **원본으로** 등장하면 위반. 단 자동 재작성이 기본이므로
실무 흐름에서는 재작성이 먼저 적용돼 위반이 발생하지 않는다 → **판정은 "재작성을 끈 저장 경로"에서만 의미**를 갖는다.
따라서 저장(lint) 시에는 **WARN**("실행 시 자동 마스킹됩니다")으로, 실행 시에는 재작성으로 처리한다.

## 4. 실행 (동일 DB 전제)

- **데모 데이터**: 설정 DB에 `demo_users`·`demo_marketing_consents`·`demo_user_events` 같은 실제 데이터 테이블을
  시드로 만든다. 카탈로그의 논리 테이블명(`users`)과 물리 데모 테이블명을 **매핑 테이블**(`demo_table_map`)로 연결하고,
  실행 시에만 치환한다 — 카탈로그·룰·권한은 논리명을 그대로 쓴다(모델 오염 방지).
- **읽기 전용 연결**: 실행 전용 `DataSource`(별도 빈, `readOnly=true` 트랜잭션 + `SET SESSION TRANSACTION READ ONLY`).
  SELECT 외 문은 파서 게이트가 이미 막지만 **이중 방어**.
- **행 상한·타임아웃**: `guardian.exec.max-rows`(기본 1000), `guardian.exec.timeout-ms`(기본 5000).
  `Statement.setMaxRows`·`setQueryTimeout` + 초과 시 명확한 오류 메시지.
- 결과: 컬럼 메타 + 행 배열 + `rowCount`·`elapsedMs`·`appliedRewrites`·`truncated:Boolean`.

## 5. 실행 게이트 (순서 — v2: 완전 재판정)

```
인증(401) → 데이터 권한(403, **현재 권한**으로 재검사) → 위생 게이트(§2.6, 422)
→ 룰 **재판정**(현재 규칙·현재 카탈로그, 422) → 검토 APPROVED 확인(403)
→ **요청자 == 세션 principal**(403 REQUESTER_MISMATCH) → 재작성(422) → 재작성 검증(§3.0.3, 422) → 실행
```
- **실행은 항상 현재 상태로 재판정한다**(fail-closed). 저장 시점 `lint_report_json`은 표시용이며 게이트 근거가 아니다.
  (검토에는 재-lint를 요구하면서 실행에 요구하지 않는 것은 비대칭이자 fail-open — spec 005 C6과 대칭을 맞춘다.)
- 재판정 BLOCK이면 실행 거부 + `execution_event(outcome=BLOCKED)` 기록. `review_status`는 되돌리지 않고
  화면에 "재검토 필요" 배지(감사 이력 보존).
- **남의 승인 쿼리 실행 금지**: `saved_query.request_id → approval_request.requester == 세션 principal`.
  (STEWARD/ADMIN의 검토 목적 실행을 허용할지는 별도 결정 — 허용 시 `on_behalf_of` 기록.)
  **선행 조건**: spec 007 §6.2의 읽기 경로 스코프를 `GET /api/queries` 계열에 먼저 적용해야 id 열거가 막힌다.
- **검토 상태 게이트**(사용자 확정): `review_status = APPROVED`인 **저장된 쿼리만** 실행 가능.
  즉 실행은 에디터의 임의 SQL이 아니라 **저장·검토 승인된 쿼리 실행**이다(`POST /api/queries/{id}/execute`).
  → spec 005 §2의 "review_status는 표시용" 한계가 **여기서 게이트로 승격**된다.
- 에디터의 임의 SQL 실행은 **비범위**(디자인의 실행 버튼은 "저장·승인된 쿼리 실행"으로 의미를 좁힌다).
  저장 전 미리보기가 필요하면 **재작성 결과만 보여주는 `POST /api/preview-rewrite`**(실행 없음)를 제공한다.

## 6. 감사 (append-only)

```
execution_event(id, query_id, actor, original_sql, rewritten_sql, applied_json,
                row_count, elapsed_ms, truncated, outcome[SUCCESS|BLOCKED|ERROR], error_message, at)
```
- BLOCKED(게이트 차단)·ERROR(타임아웃·SQL 오류)도 기록한다 — 실행 시도 자체가 감사 대상.
- 원본·재작성 SQL을 모두 남겨 "무엇이 자동 적용됐는지"를 사후 검증할 수 있게 한다. `applied_json`에 **적용된 강제식 원문**도 남긴다(STEWARD가 마스크 식을 약화시켜도 사후 탐지 가능).
- **감사 쓰기는 주 DataSource + `REQUIRES_NEW`** — 실행 커넥션은 읽기 전용이라 쓸 수 없고, 타임아웃 롤백에도 살아남아야 한다. 게이트 차단은 예외 핸들러가 아니라 **차단 지점에서 기록 후 throw**.
- **결과 행은 저장하지 않는다**(불변식). `error_message`는 SQLState+vendor code+정제 메시지만 — MySQL 오류는
  데이터 값을 에코하므로(`Truncated incorrect ... value: '...'`) 사용자 응답에는 **분류 코드만**(TIMEOUT/SQL_ERROR/REWRITE_FAILED) 반환하고 원문은 STEWARD/ADMIN에게만.
- `original_sql`·`rewritten_sql`·`applied_json`은 **TEXT**(VARCHAR(500)이면 감사가 잘려 사후 검증 불가).

## 7. API

```
POST /api/queries/{id}/execute        → 200 ExecutionResultDto | 403(권한·미검토) | 422(룰·재작성 실패)
POST /api/preview-rewrite {sql, requestId}  → 200 {rewrittenSql, applied[]} (실행 없음)
       ※ requestId **필수**(purposeCode 서버 주입 — spec 005 C1 계승), §6.0 게이트 전체 적용,
         applied[]는 접근 허용 테이블 항목만, 감사 기록. **권한 게이트와 같은 마일스톤에서만 노출**
         (M1에만 배포하면 무권한 카탈로그 오라클이 열린다 — 어떤 컬럼이 MASK인지·강제식 원문이 노출)
GET  /api/queries/{id}/executions     → [ExecutionEventDto] (본인·STEWARD/ADMIN)
```
```
ExecutionResultDto {
  columns: [{name, type}], rows: [[Any?]],
  rowCount, elapsedMs, truncated,
  rewrittenSql, applied: [{kind, table, column, detail}]
}
```

## 8. 프론트엔드

- **에디터 실행 결과 탭**(현재 스텁 → 실): 저장·검토 승인된 쿼리를 선택했을 때만 실행 활성.
  결과 그리드(마스킹된 실제 값) + "N rows · Xms · LIMIT 적용됨" + **적용된 재작성 목록**(무엇이 자동 적용됐는지) +
  재작성 SQL 보기(토글). 미검토 쿼리는 "검토 승인 후 실행할 수 있습니다" 안내.
- **저장된 쿼리 화면**: 검토 승인 행에 "실행" 액션, 상세에 최근 실행 이력.
- 실행 버튼의 스텁 메시지(spec 003 §4-2) 제거 — 이제 실제 동작.

## 9. 검증 기준

- [ ] 기존 102 테스트 회귀
- [ ] **재작성 단위**: MASK 치환(별칭 유지) / FILTER 주입(중복 시 생략) / LIMIT 하향 / INTEGRITY 주입 /
      서브쿼리·CTE·UNION 팔 내부까지 적용 / BLOCK·PARTITION은 재작성 안 함
- [ ] **재작성 실패 = 차단**: 표현 불가 강제식·파싱 실패 시 실행 거부(부분 적용 없음)
- [ ] **실행 게이트**: 미검토 쿼리 실행 → 403 NOT_REVIEWED / 권한 없는 테이블 → 403 / 룰 BLOCK → 422
- [ ] **실행 결과가 실제로 마스킹**됨: `email`이 `j***@naver.com` 형태로 반환(원본 유출 없음)
- [ ] 행 상한: 상한 초과 데이터에서 `truncated=true`·행 수 제한 / 타임아웃 시 명확한 오류
- [ ] 읽기 전용: 실행 연결에서 INSERT/UPDATE 시도 시 실패(이중 방어 확인)
- [ ] 감사: SUCCESS·BLOCKED·ERROR 모두 `execution_event`에 원본+재작성 SQL과 함께 기록
- [ ] must_be_masked: 저장 시 WARN("실행 시 자동 마스킹")로 표시, 실행에서는 재작성 적용
- [ ] 화면: 실행 결과·적용된 재작성·재작성 SQL 토글·미검토 안내 (스크린샷)

## 10. 마일스톤

0. ~~**M0 (선행)**: 위생 게이트(§2.6) + 실행 격리 3종(§2.7 스키마·계정·매핑 총체성)~~ — **완료(§2.8 참조)**
1. **M1**: SqlRewriter(RewritePlan 경계·주입 3원칙·MASK columnRefs·재작성 검증) + 단위 스위트
   (`preview-rewrite`는 권한 게이트가 붙는 M2까지 **미노출**)
2. **M2**: 실행 인프라(별도 DataSource·상한·타임아웃·작은 전용 풀) + 실행 게이트(완전 재판정) + 감사 + execute·preview API
3. **M3**: 에디터 실행 결과·재작성 표시 + 저장쿼리 실행 액션·이력 + E2E·화면 대조

## 11. 결정 기록 (2026-07-25 사용자)

1. **동일 DB로 시작** — 설정 DB에 데모 데이터 테이블. 원격 대상 DB·자격증명은 ④에서 재검토.
2. **자동 재작성** — MASK 치환·FILTER 주입을 서버가 수행. 디자인의 마스킹 결과가 실제로 동작.
3. **안전장치 4종 전부** — 검토 상태 게이트 · 행 상한·타임아웃 · 읽기 전용 연결 · 실행 감사 로그.
4. (파생·AI) 실행 대상은 **저장·검토 승인된 쿼리**로 좁힌다(임의 SQL 실행 비범위). 저장 전에는 `preview-rewrite`.
5. (파생·AI) 논리 테이블명 ↔ 데모 물리 테이블 매핑은 **실행 시점에만** 치환(카탈로그·룰·권한 모델 오염 방지).
6. (파생·AI) 재작성 실패는 fail-closed(부분 적용 금지).
7. (v2·적대 검토) **위생 게이트**(주석 전면 금지·문형 허용목록·변수/한정자/0-테이블 거부)를 M0로 선행.
8. (v2) **실행 격리 3종**(별도 스키마·별도 계정·매핑 총체성) — 앱 버그와 무관한 최후 방어선.
9. (v2) MASK는 columnRefs 기준, 비-투영 위치는 실행 거부 + `must_be_masked` **BLOCK**(위치에 따라 WARN/BLOCK 분기).
   `judged=false→true` 전환이 기존 규칙 평가·enforced 배지를 소급 변경함을 spec 004 §4.1에 주석.
10. (v2) 주입 3원칙(괄호·LIMIT 단일장치·강제식 AST 삽입) + **재작성 결과 자체 검증**.
11. (v2) 실행은 **완전 재판정**, 남의 승인 쿼리 실행 금지, 감사는 REQUIRES_NEW·결과 행 미저장·오류 메시지 정제.
