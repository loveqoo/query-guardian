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

### 2.6 접수 검사 (신설 — 다른 결함의 폭발 반경을 줄이므로 M1보다 **선행**)

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

M0(접수 검사 + 실행 격리)은 **구현·검증 완료**. 구현 중 스펙에서 갈라진 결정과, 2차 적대 검토가 실측으로
잡은 것들:

**스펙에서 갈라진 결정(의도적)**
1. **접수 검사는 lint·저장 시점에도 발화한다** — §2.6은 실행 게이트에 뒀지만 *실행 대상 = 저장된 쿼리*라 두 집합이
   같다. 저장 때 잡으면 같은 방어를 유지하면서 "승인까지 받았는데 실행 불가"가 없어진다. **단락시키지 않고
   추가 위반**으로 넣어 나머지 룰 판정 결과를 함께 보여준다.
2. **파싱은 1회** (`DialectParser.inspect(sql) → (ParseResult, List<IntakeViolation>)`) — 접수 검사용 2차 파싱은
   타임아웃 레이스로 **판정 대상과 검사 대상이 갈라짐이 실측**됐다(84회 중 57회 fail-open 관측). M1의 재작성
   파싱도 이 경로에 얹는다.
3. **검사 불가 = 위반**(`UNVERIFIABLE`) — 빈 목록은 "위반 없음"으로 읽혀 접수 검사를 독립 단계로 호출하는
   §5 경로가 fail-open한다. `INTO DUMPFILE`·`PROCEDURE ANALYSE`·`TABLE users`가 이 경로였다.
4. **`--`는 MySQL 규칙(뒤에 공백류/문장 끝)을 따른다** — Druid 프린터가 `- -1`을 `--1`로 출력하므로,
   무조건 거부하면 `intake(sql) 통과 ⟹ intake(rewrite(sql)) 통과`가 깨져 §3.0.3 재작성 검증이 성립하지 않는다.
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

**3차 검토(타사 모델, 정합성 축)**
공격 축은 위 2차가 완주했으므로 타사 모델에는 **왕복 정합성과 오탐**만 맡겼다(첫 시도는 우회 탐색 프레이밍이
해당 모델의 안전 필터에 걸려 거부됐다 — 검증 채널마다 요청 형태를 맞춰야 한다).
- **왕복 정합성 성립**: 160개 입력(산술·리터럴·캐릭터셋 도입자·헥스/비트·인용 식별자·CTE·UNION·윈도우·
  `INTERVAL`·`COLLATE`·JSON 연산자·`CAST`·정렬·프린터 정규화 등)에서 `checkIntake(sql)` 통과 ⟹
  `checkIntake(print(parse(sql)))` 통과의 반례 **0건**. §3.0.3의 전제가 확인됐다.
  → 축약 코퍼스를 `parser/IntakeRoundTripTest`로 **영구 회귀**로 고정(Druid를 쓰므로 parser 테스트 패키지).
- **CRITICAL(오탐이 물고 있던 우회)**: 파생 테이블 본문이 UNION이면(`FROM (SELECT … UNION ALL SELECT …) d`)
  `collectTables`가 `SQLUnionQueryTableSource`를 **조용히 버려** 그 스코프가 IR에서 사라졌다. 주석엔
  "미수집이면 fail-closed"라고 적혀 있었지만, 스코프가 사라지면 **그 안의 BLOCK 컬럼도 사라진다** —
  바깥에 물리 테이블이 하나라도 있으면 0-테이블 검사조차 발화하지 않아 완전히 조용히 통과했다(spec 001 §6.2).
  → UNION 테이블 소스를 자식 스코프로 등록하고, **미지원 FROM 형태는 `unverifiable`로 차단**한다.

**남은 부채(M1·M2에서)**: 문형 검사를 화이트리스트로 전환(현재는 아는 나쁜 것 열거라 MySQL 신문법마다
구멍), 파서 executor 상한, 물리명 삽입 시 백틱 인용, `demo_table_map` 관리 UI(대소문자 구분 환경),
실행 커넥션 `sql_mode` 고정을 스캐너 전제와 하나의 계약 테스트로 묶기.

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
- 식별자 접수 검사: `catalog_column.name`·`demo_table_map.physical_name`에 정규식 검증
  (`^[A-Za-z_][A-Za-z0-9_]{0,63}$`) — 컬럼명을 통한 injection의 근본 차단.
- 패키지 경계(ArchUnit): `parser/SqlRewriter`는 **방언 중립 `RewritePlan`만** 받아 AST를 조작(카탈로그·권한 미인지),
  `exec/RewritePlanner`가 IR+카탈로그로 plan을 만든다(Druid 타입 미접촉). **`exec`도 `auth` 미의존**
  (재작성이 권한에 따라 달라지면 "권한 없는 사용자가 마스킹을 덜 받는" 역전 — spec 007 C4와 같은 함정).
  기존 규칙에 빠진 `..catalog..`도 이번에 보강.

### 3.5 M1 실행 계획 (2026-07-25 작성 — **승인 대기**)

**구조 결정 3건 (Scaffolding)**
1. `RewritePlan` 어휘는 **`ir` 패키지**에 둔다. `exec`에 두면 `parser`가 `exec`를 의존해야 해서 M0의
   ArchUnit 규칙(`parserKnowsOnlyIr`)과 충돌한다. `ir`은 양쪽이 이미 의존하는 공용 어휘다.
2. `SelectScope.path` 추가(`root`, `root/0`, `root/0/1` …) — plan 항목이 대상 스코프를 가리키는 유일한 수단.
   기본값 `""`로 두어 기존 생성자 호출은 그대로 둔다.
3. **AST를 폐기하지 않는다** — `inspect()`가 만든 AST를 **불투명 핸들**(`ParsedStatement`, parser 패키지의
   인터페이스로 Druid 타입 미노출)로 함께 반환하고, 재작성은 *판정된 그 AST*를 고친다. 핸들은 path→AST 노드
   맵을 함께 보관한다. §2.5-1이 경고한 판정-실행 분기를 **사후 검증(§3.0.3)으로 완화**하는 대신 **구조적으로
   제거**한다. §3.0.3은 이중 방어로 유지한다(핸들이 없는 경로·미래 방언 대비).

**마일스톤 분해**

| # | 산출물 | 완료 기준 |
|---|---|---|
| M1-1 | `SelectScope.path` + `ParsedStatement` 핸들 + ArchUnit 보강(`ir`은 아무것도 의존하지 않음) | 기존 132 테스트 회귀; path가 전 스코프에 유일 |
| M1-2 | `ir/RewritePlan.kt` — `MaskProjection(path, instanceKey, column, forcedExpr)` · `InjectPredicate(path, predicateSql)` · `CapLimit(path, n)` · `MapTable(logical→physical)` | 방언 중립(문자열·IR 타입만), Druid·카탈로그 미인지 |
| M1-3 | `exec/RewritePlanner` — IR+카탈로그+purposeCode → plan. MASK는 `columnRefs` 기준(§3.0.1), 비-투영 위치는 `MASK_NOT_EXPRESSIBLE` 거부; FILTER/INTEGRITY 중복 시 생략; OUTER JOIN null-producing 쪽 대상은 거부(§3.0.2); 물리명 치환은 **마지막 단계** | 논리명으로만 카탈로그 조회(물리명 조회 시 0건 적용되는 함정 회귀 테스트 포함) |
| M1-4 | `parser/SqlRewriter` — 주입 3원칙: 원본 WHERE `setParenthesized(true)` 후 AND 결합 / LIMIT 단일 장치 `min(user, cap)+1` / 강제식은 `toSQLExpr` 재파싱 노드로 삽입. UNION **전 팔**, CTE·파생 **내부 스코프**까지 | 최상위 OR에서 주입이 무력화되지 않음; 팔 누락 0 |
| M1-5 | 재작성 자체 검증(§3.0.3) 5항목 + **접수 재검사**(왕복 성질, M0에서 실측 확인) → 실패 시 실행 거부·부분 적용 금지 | 검증 실패 케이스가 예외 없이 차단 |
| M1-6 | 단위 스위트(§9의 재작성 항목 전부) + `must_be_masked` 판정(저장 시 WARN, 비-투영 위치는 BLOCK — 결정 9) | §9 체크박스 중 재작성 항목 전부 충족 |

`preview-rewrite` API는 스펙대로 **M1에서 노출하지 않는다**(권한 게이트가 붙는 M2까지 — 무권한 카탈로그
오라클 방지).

**OFFSET 처리 (2026-07-25 사용자 결정: 금지)**: 접수 검사에 `LIMIT_OFFSET_NOT_ALLOWED`를 추가해 거부한다.
근거 — 상한의 의미를 "이 실행으로 나간 행 수"로 고정해야 감사에서 총 반출량을 셀 수 있다. offset을 허용하면
페이지네이션이 되고(§2에서 이미 비범위) 여러 번 실행해 상한을 무한 우회할 수 있다. M1-1에 포함한다.

### 3.6 M1 구현 결과 (2026-07-25 — 검증 2회·수정 2라운드 반영)

M1(재작성 엔진)은 **구현·검증 완료**(216 테스트). 검증이 잡은 것과 그로 인한 계약 변경:

**타사 모델(정합성 축)**
- 왕복 정합성 성립(160개 입력, 반례 0) → `parser/IntakeRoundTripTest`로 영구 고정.
- CRITICAL: 검증기가 `CONCAT(users.email,'')` 같은 **항등 표현식**을 통과시켰다("bare 투영 아님"만 검사) →
  계획한 강제식과 대조한다.
- HIGH: `LIMIT 0`이 상한으로 확대됐다(0을 미지정 취급) → 0도 사용자 의도로 존중, 재작성은 `LIMIT 0` 주입.
- HIGH: 마스킹이 **many-to-one**이라(실측: 두 이메일 → 같은 `j***@naver.com`, `COUNT(DISTINCT)` 3→2)
  DISTINCT·GROUP BY 별칭·ORDER BY 서수가 결과 의미를 바꾼다 → IR에 출력 별칭·DISTINCT·출력 참조를 담아 거부.

**사내 적대 검토(공격 축) — 평문 PII 반출 4경로를 MySQL 실측으로 확인** (전부 판정·계획·검증 통과 상태였다)
1. `LATERAL (...)`이 `SQLExprTableSource`로 와서 `else -> expr.toString()` 폴백에 의해 **물리 테이블로 위장**,
   안쪽 참조가 IR에서 소멸 → 모르는 FROM 형태는 `unverifiable` 차단(§2.8의 UNION 수정과 같은 결함의 잔여).
2. 다중 테이블 FROM의 **비한정** 마스킹 컬럼이 `ABSENT`로 판정(귀속 불가를 인스턴스 키로 세어서) →
   순회 축을 `maskFindings`로 통일하고 귀속 불가는 표현 불가로 확정. `no-blocked-column`에는 이미 있던
   폴백을 마스킹 축에 옮기지 않은 것이 원인이었다.
3. **상관 서브쿼리** 스코프가 부모 인스턴스를 참조하면 아무 스코프도 그 짝을 검사하지 않았다 → 같은 수정으로 해소.
4. **ORDER BY/GROUP BY 서브쿼리**가 스코프로 등록되지 않아 `ORDER BY (SELECT u.ssn LIKE '90%')`가
   주민번호 **불리언 오라클**이 됐다 → 해당 절도 수집. 나아가 **"AST의 모든 쿼리 노드 = 정확히 하나의 스코프"**
   불변식을 테스트로 세웠고(`ScopeCoverageTest`), 그것이 즉시 OUTER JOIN ON 서브쿼리 구멍을 추가로 찾았다.

**주입 안전성**(신설 `SelectScope.injectable`): 부정 문맥(`NOT EXISTS`/`NOT IN`·표현식 수준 서브쿼리)과
OUTER JOIN null 생성 경로(파생 래퍼 포함)에는 **주입하지 않는다**. 부정 문맥 주입은 필터를 **반전**시켜
보호 대상만 골라내는 도구가 됨이 실측됐다. 주입을 포기해도 require-predicate가 모든 스코프에서 계속 요구한다.

**검증기 독립**: `verify(rewrittenSql, plan, judgedIr, maskedColumnsOf)` — 판정 IR과 카탈로그로 기대 마스킹을
**스스로 재도출**한다. 기대치를 계획에서만 뽑으면 "계획이 마스킹을 빠뜨린 경우"에 정의상 눈이 먼다.

**scopeId에 파싱별 난스**: 순번만 쓰면 모든 파싱이 `s0`부터 시작해 **다른 파싱의 계획이 그대로 적용**됐다.
결정 13("판정-실행 분기를 구조적으로 제거")은 이 성질 위에서만 성립한다.

**철회한 완화**: "그 컬럼에 이미 최상위 조건이 있으면 주입 생략"은 연산자·값을 보지 않아 `<> 'Y'`도
"제약됨"으로 읽어 필수 조건을 아예 주입하지 않았다(fail-open). 중복 주입은 무해하므로 항상 주입한다.
대신 판정 축과 재작성 축이 같은 FILTER 집합을 본다는 **계약 테스트**를 추가했다(그 일치가 우연이었다).

**M2 착수 전 유의**: `DemoTableResolver`(§2.7-3 매핑 총체성)는 아직 **어디서도 호출되지 않는다** —
"최후 방어선"은 M2 배선 시점에 생긴다. 위 결함들을 완화해 주지 않는다.

### 3.1 must_be_masked 판정 (spec 004 잔여)
MASK 매핑된 컬럼이 select-item에 **원본으로** 등장하면 위반. 단 자동 재작성이 기본이므로
실무 흐름에서는 재작성이 먼저 적용돼 위반이 발생하지 않는다 → **판정은 "재작성을 끈 저장 경로"에서만 의미**를 갖는다.
따라서 저장(lint) 시에는 **WARN**("실행 시 자동 마스킹됩니다")으로, 실행 시에는 재작성으로 처리한다.

### 3.7 M2 실행 계획 (2026-07-25 작성 — **승인 대기**)

**구조 결정 3건 (Scaffolding)**
1. 오케스트레이션은 **`query/QueryExecutionService`** — 인증·승인·룰·재작성·실행을 모두 아는 유일한 계층.
   `exec`에 둘 수 없다(ArchUnit `execKnowsNothingAboutAuth`: 재작성이 권한에 따라 달라지면 "권한 없는
   사용자가 마스킹을 덜 받는" 역전이 생긴다).
2. **실행 DataSource는 별도 빈**이고 설정 DataSource가 `@Primary`를 유지한다 — Spring Data JDBC 레포지토리가
   실수로 실행 커넥션을 쓰지 못하게. 전용 소형 풀(최대 3), `readOnly`, 커넥션 타임아웃,
   그리고 **`sql_mode` 고정**(M0 어휘 스캐너의 전제 `NO_BACKSLASH_ESCAPES` 부재를 보장 — §2.8 부채 상환).
3. **감사는 주 DataSource + `REQUIRES_NEW`** — 실행 커넥션은 읽기 전용이라 쓸 수 없고, 타임아웃 롤백에도
   기록이 살아남아야 한다. 차단은 예외 핸들러가 아니라 **차단 지점에서 기록 후 throw**.

**선행 조건(발견)**: `GET /api/queries`·`/api/queries/{id}`에 **스코프가 없다**. 인증만 걸려 있어 인증된
아무나 남의 승인 쿼리 본문(`sql_text`·`lint_report_json`)을 읽는다. SQL 본문에는 조사 대상·상수가 담기므로
열람 자체가 유출이다. spec 005 §5가 M2 선행 조건으로 예고한 지점 — M2-1에서 먼저 닫는다.

**게이트 순서 (§5 확정)**
```
인증(401) → 데이터 권한 **현재 기준 재검사**(403) → 접수 검사(422) → 룰 **재판정**(422)
→ 검토 APPROVED(403) → 요청자 == 세션 principal(403) → 데모 매핑 총체성(422 NO_DEMO_MAPPING)
→ 재작성(422) → 재작성 검증(422) → 실행
```
파싱은 **1회**(`inspect`)로 IR+핸들을 얻어 판정·계획·재작성이 같은 AST를 쓴다(결정 13).

**마일스톤 분해**

| # | 산출물 | 완료 기준 |
|---|---|---|
| M2-1 | 읽기 경로 스코프(본인 + STEWARD/ADMIN), 실행 이력 조회 권한 | 남의 쿼리 조회 403, 목록에 남의 것 없음 |
| M2-2 | 실행 DataSource·전용 풀·읽기 전용·타임아웃·`sql_mode` 고정 | 실행 커넥션에서 INSERT 실패, 설정 스키마 접근 실패(이중 방어 확인) |
| M2-3 | `execution_event`(TEXT 컬럼) + 감사 서비스 | SUCCESS·BLOCKED·ERROR 모두 기록, **결과 행 미저장**, 사용자 응답엔 분류 코드만 |
| M2-4 | `QueryExecutionService` 게이트 + `DemoTableResolver` 배선 | 게이트 순서 회귀, 미매핑 테이블 실행 거부(§2.7-3 최후 방어선이 여기서 생긴다) |
| M2-5 | 실행기(상한 `cap+1` 읽고 truncated 확정, 타임아웃) + 결과 DTO | 1,500행 데모로 `truncated=true`, 타임아웃은 분류 코드 |
| M2-6 | API 3종 `POST /queries/{id}/execute`·`GET /queries/{id}/executions`·`POST /preview-rewrite` | preview에 게이트 전체 적용, `applied`는 접근 허용 테이블 항목만 |
| M2-7 | E2E: 실제 마스킹 값 확인 | 결과에 `j***@naver.com`이 있고 **평문 이메일이 없음**을 단정 |

**검증 계획**: 실제 데이터가 흐르는 첫 마일스톤이므로 **공격 축(사내 적대 검토)을 먼저** 띄우고 정합성 축(타사)을
병렬로 돌린다(retrospect 010). 검토자에게는 별도 build 디렉터리 또는 워크트리를 지정해 결과 오염을 막는다.
공격 축 브리프의 핵심 질문: ⑴ 게이트를 통과해 **의도되지 않은 데이터**를 응답으로 빼낼 수 있는가
⑵ 감사가 실제로 일어난 일을 기록하는가(누락·위조) ⑶ 오류·타임아웃 경로로 정보가 새는가(오류 메시지 에코).

**대행 실행 (2026-07-25 사용자 결정: 불허)**: 실행은 **요청자 본인만**. STEWARD/ADMIN도 남의 승인 쿼리를
실행할 수 없다 — 최소권한에 부합하고, 감사 로그에서 "그 PII를 누가 봤는가"가 모호해지지 않는다.
검토 목적에는 `preview-rewrite`(실행 없이 재작성 SQL만)로 충분하다. `on_behalf_of` 컬럼은 두지 않는다
(쓰지 않는 필드는 나중에 "허용해도 되겠지"의 근거가 된다).

### 3.8 M2 구현 결과 (2026-07-25 — 검증 2라운드 반영)

M2-1~M2-7 전부 구현. E2E가 디자인의 결과 표를 실제로 재현한다 — `j***@naver.com`이 나오고 **평문 이메일이
응답 어디에도 없다**. 백엔드 테스트 233건 통과.

**검증 1라운드(타사 모델)** — 반영 완료(커밋 `d8d693b`). 핵심은 **감사 계약 위반**이었다: 미리보기에서 승인
게이트가 막은 두 경로가 403인데 `execution_event`가 0건이었다(`approvalGate.check`를 감사 래퍼 없이 호출).
그 밖에 LIMIT `cap+1` 투명성 표기, 감사 실패 방향 분리(성공=fail-closed, 오류=원래 실패가 이김).

**검증 2라운드(사내 적대 검토)** — 아래 7건을 이번 라운드에서 닫았다.

| # | 결함 | 무엇이 위험했나 | 수정 |
|---|---|---|---|
| 1 | `QueryService.update`에 스코프 없음 (CRITICAL) | 남의 저장 쿼리를 **덮어쓸 수 있었다**. 소유권이 `request_id`로 정의되므로 그 컬럼 교체는 **소유권 이전**이었다 | `visible(id, actor, privileged)` + `requestId` 교체 거부 |
| 2 | 감사 읽기가 저장 쿼리에 매달림 (HIGH) | 쿼리를 지우면 실행 기록이 404 — **행위자가 스스로 감사를 은닉**. PREVIEW 기록(`query_id` null)은 어떤 API로도 볼 수 없었다(write-only 감사) | `GET /api/executions`(STEWARD/ADMIN, `actor` 필터) — 대상 행의 생사와 무관한 경로 |
| 3 | SQL 길이 무제한 (HIGH) | 파서 한계(65536 B) > 감사 TEXT 한계(65535 B). 그 사이 크기는 `REQUIRES_NEW` 감사 INSERT에서 죽어 **500 + 감사 0건** — 유일한 미기록 경로 | `validateSql` 60,000 B 상한을 파싱·감사 **앞에서** 적용 |
| 4 | 실행 커넥션 `sql_mode` **치환** (MEDIUM) | 서버 기본값의 `ONLY_FULL_GROUP_BY`가 사라져, 검토자가 승인한 SQL이 실행 시점에 **다른 의미**로 돌았다(그룹당 임의 행) | 합집합으로 바꾸고 `NO_BACKSLASH_ESCAPES`만 제거(M0 스캐너 전제). 계약 테스트로 고정 |
| 5 | 승인 요청 읽기 무제한 (MEDIUM) | `requester` 파라미터를 비우면 전건 — 목적·대상 테이블·승인 라인이 조직 내부 정보다 | 비특권자는 **본인 요청 + 자기가 승인선에 든 요청**만. 남의 것은 404(존재 은닉) |
| 6 | `truncated` 한 필드가 두 사실을 뭉갬 (LOW) | `false`가 "상한 없음"과 "상한 안에 다 들어왔음"을 구분하지 못했다 | `rowCap` + `moreRowsExist`. **`LIMIT 0`이면 `moreRowsExist = null`**(초과 여부를 조회조차 하지 않았다 — `false`로 단정하지 않는다) |
| 7 | 스키마 이력 마이그레이션 부재 (HIGH, ⑹의 파생) | `spring.sql.init.mode=always`인데 `CREATE TABLE IF NOT EXISTS`는 **기존 테이블에 컬럼을 더해주지 않는다**. 기존 DB는 옛 모양으로 남아 감사 INSERT가 죽고, **감사가 죽으면 실행이 무기록으로 통과**한다 | `information_schema` 기반 멱등 DDL(값 이전 후 옛 컬럼 제거). 실제 개발 DB(옛 모양)에 적용해 실측 + 회귀 테스트 |

| 8 | 감사 본문 컬럼 TEXT (HIGH, ⑶의 파생) | 입력 상한(60,000 B)이 TEXT 한계(65,535 B)에 붙어 있는데 **재작성 SQL은 원본보다 커진다**(`email` → `mask_email(demo_users.email) AS email`). 입력을 통과한 SQL이 감사 INSERT에서 죽는다 = 무기록 실행 | 본문 4컬럼을 **MEDIUMTEXT**로. 상한 하나를 다른 상한에 매달지 않고 **결합을 끊었다** |

⑺·⑻은 검토자가 지적한 것이 아니라 ⑹·⑶을 고치다 발견했다 — **스키마를 바꾸는 변경은 "새 DB에서 되는가"가 아니라
"기존 DB에서 되는가"로 검증해야 한다**는 것, 그리고 **상한을 고칠 때는 그 값에 매달린 다른 상한을 함께 봐야
한다**는 것을 회귀로 고정했다(`SchemaMigrationTest`).

**검증 3라운드(수정 자체를 적대 검토)** — 2라운드 수정본을 다시 공격했다. **수정이 새 결함을 만들 수 있다**는
것이 이 라운드의 수확이다.

| # | 결함 | 무엇이 위험했나 | 수정 |
|---|---|---|---|
| D1 | `sql_mode` 합집합이 `ANSI_QUOTES`를 **상속** (HIGH, **2라운드가 만든 회귀**) | `"ssn"`이 판정기에는 문자열 리터럴, 실행 세션에는 **컬럼 참조**가 된다 → MASK·BLOCK 판정이 그 자리를 보지 못하고 원문이 나간다. 옛 코드(전체 치환)는 이걸 우연히 막고 있었다 | 합집합이 아니라 **금지 목록**: 판정-실행 해석을 갈라놓는 5개 모드(`NO_BACKSLASH_ESCAPES`·`ANSI_QUOTES`·`PIPES_AS_CONCAT`·`IGNORE_SPACE`·`HIGH_NOT_PRECEDENCE`)를 제거하고, **다시 읽어 확인**해 남아 있으면 실행 거부(fail-closed) |
| D2 | `query_id NOT NULL` 마이그레이션 누락 (HIGH) | 기존 DB에서 미리보기 감사 INSERT가 전부 죽는다 → 차단 403이 **500으로 바뀌고** 감사 0건. ⑺에서 컬럼 추가만 마이그레이션하고 **nullability는 빠뜨렸다** | 멱등 `MODIFY COLUMN` 추가. 픽스처를 **실제 옛 DDL**(커밋 `52ae36a`)로 교체 — 처음 테스트는 픽스처가 이미 새 모양이어서 이 결함을 볼 수 없었다 |
| D3 | 감사 **밀어내기** 은닉 (HIGH) | 최근 200건만 주면, 미리보기를 200번 호출해(요청당 감사 1행) 옛 SUCCESS를 조회 범위 밖으로 밀어낼 수 있다 — 삭제 은닉을 닫았는데 다른 문으로 같은 결말 | 커서 페이징(`before`) + `outcome` 필터 + 전체 건수 헤더 |
| D4 | 커넥션 누수 → 전용 풀(3) 영구 고갈 (MEDIUM) | `apply {}` 안의 초기화가 던지면 커넥션이 닫히지 않는다. 2라운드가 이 블록에 `SET SESSION`을 하나 더 넣어 확률을 올렸다 | 초기화 실패 시 `close()` 보장 |
| D5 | `rowCap`이 **설정 상한**과 **사용자 LIMIT**을 뭉갬 (MEDIUM) | `LIMIT 2`로 스스로 좁힌 실행이 감사에 "상한 2가 걸려 잘렸다"로 남는다 — 거버넌스 절단과 구분 불가. ⑹에서 쪼개면서 **축을 하나 잘못 골랐다** | `configuredCap` + `effectiveLimit` + `moreRowsExist` 세 값. 계획에 상한이 없으면 실행 거부(fail-closed) |
| D6 | 승인 요청 존재·상태 **오라클** (MEDIUM) | `get`이 404로 숨기는데 ⑴ `/cancel`이 상태를 먼저 봐서 404/409-PENDING/409-APPROVED로 상태를 확정해 주고 ⑵ 403 본문(`ApprovalBlockedDto.requestStatus`)이 남의 요청 상태를 알려줬다 | 자격 검사를 상태 검사보다 **앞으로**(자격 없으면 404), 차단 본문의 `requestId`·`requestStatus`는 요청자·승인선에게만 |
| D7 | `update(privileged = true)` (LOW) | STEWARD의 대행 수정을 **명시적으로 허용**하는 코드였고, 실제로 막힌 이유는 게이트의 `REQUESTER_MISMATCH`라는 **우연**이었다 | `privileged`를 제거 — 결정 14(대행 실행 불허)의 대칭 |
| D8 | 잔여 (LOW) | lint 경로에 길이 검증 없음 / `saved_query` 본문이 TEXT / 승인 이벤트를 `save` **전에** 기록해 409로 성립하지 않은 승인이 감사에 남음 | 셋 다 수정(`logEvent`는 저장 성공 후) |

**직접 실측으로 찾은 것 (검토자 지적 아님)**: 입력 상한(60,000 B)을 통과한 SQL도 마스킹 치환으로 부풀어
**파서 상한(64 KiB)** 을 넘을 수 있다 — 재작성 검증이 결과를 다시 파싱하므로 그 지점에서 걸린다(3,000개 투영에서
실측). 동작은 fail-closed였지만 메시지가 "검증 실패"라 사용자가 무엇을 고쳐야 할지 알 수 없었다.
크기 초과를 별도 사유로 갈라 **"컬럼 수를 줄여 나눠 실행하세요"** 로 안내한다. 그리고 이 발견으로 ⑻의 근거가
바뀌었다: `rewritten_sql`은 파서 상한 때문에 64 KiB를 넘을 수 없지만 **`applied_json`은 마스킹 컬럼 수에
비례해 커져 실제로 65,535 B를 넘는다**(2,000개 투영 실측) — MEDIUMTEXT가 필요한 진짜 이유는 여기다.

**3라운드가 남긴 규율 3개**
1. **수정 자체를 검토 대상으로 삼는다.** D1은 2라운드 수정이 만든 회귀다 — "고쳤다"는 사실이 안전을 보장하지 않는다.
2. **테스트가 통과하는 이유를 본다.** 2라운드의 `sql_mode` 계약 테스트는 기본 설정 MySQL에서 **아무것도 하지
   않아도 통과**했고(위험 모드가 애초에 없다), 마이그레이션 테스트는 픽스처가 이미 새 모양이어서 D2를 볼 수
   없었다. 두 테스트 모두 지금은 **되돌려서 실패하는 것을 확인**했다(옛 코드 복원 → 정확히 그 결함으로 실패).
3. **한 축을 쪼갤 때 축을 옳게 고른다.** D5는 "두 사실을 뭉쳤다"를 고치면서 잘못된 두 개로 쪼갠 사례다.

**의도적으로 미룬 것 (사유 명시)**

| 항목 | 왜 지금 아닌가 |
|---|---|
| 컬럼 수준 카탈로그 검증(카탈로그엔 있고 물리 스키마엔 없는 컬럼) | **판정 축을 새로 추가**하는 일이라 별도 스펙 대상. 현 상태에서도 실패는 크게 나고 감사에 남으며 데이터는 나가지 않는다(422 SQL_ERROR) |
| `demo_table_map` 정합성 제약·변경 감사 | 매핑 **UI**의 선행 조건이지 M3의 선행 조건이 아니다. 지금은 seed로만 바뀐다 |
| 파서 실행 시간·깊이 상한 | 접수 검사가 문형을 좁혀 폭발 반경이 작다. 길이 상한(⑶)이 1차 방어 |
| ~~감사 조회 페이징~~ | **철회** — 은닉 경로였다(D3). 커서 페이징 구현 완료 |
| `/api/users`·`/api/directory/*` 전 인증 공개 | spec 007 H3 카브아웃 — 승인선을 편성하려면 후보 목록이 필요하다. 반환은 id·이름·직함·역할까지 |

## 4. 실행 (동일 DB 전제)

- **데모 데이터**: 설정 DB에 `demo_users`·`demo_marketing_consents`·`demo_user_events` 같은 실제 데이터 테이블을
  시드로 만든다. 카탈로그의 논리 테이블명(`users`)과 물리 데모 테이블명을 **매핑 테이블**(`demo_table_map`)로 연결하고,
  실행 시에만 치환한다 — 카탈로그·룰·권한은 논리명을 그대로 쓴다(모델 오염 방지).
- **읽기 전용 연결**: 실행 전용 `DataSource`(별도 빈, `readOnly=true` 트랜잭션 + `SET SESSION TRANSACTION READ ONLY`).
  SELECT 외 문은 파서 게이트가 이미 막지만 **이중 방어**.
- **행 상한·타임아웃**: `guardian.exec.max-rows`(기본 1000), `guardian.exec.timeout-ms`(기본 5000).
  `Statement.setMaxRows`·`setQueryTimeout` + 초과 시 명확한 오류 메시지.
- 결과: 컬럼 메타 + 행 배열 + `rowCount`·`elapsedMs`·`appliedRewrites` + 상한 3값
  **`configuredCap`**(설정 상한)·**`effectiveLimit`**(적용 상한)·**`moreRowsExist`**(§3.8-⑹·D5).
  거버넌스 절단은 `effectiveLimit == configuredCap`일 때만이다 — 사용자가 `LIMIT 2`로 스스로 좁힌 것과
  구분되어야 감사가 거짓말을 하지 않는다. `moreRowsExist = null`은 **알 수 없음**(상한 0이면 초과 탐지용
  1행조차 조회하지 않는다).

## 5. 실행 게이트 (순서 — v2: 완전 재판정)

```
인증(401) → **SQL 길이**(422, §3.8-⑶ — 파싱·감사보다 앞) → 데이터 권한(403, **현재 권한**으로 재검사)
→ 접수 검사(§2.6, 422)
→ 룰 **재판정**(현재 규칙·현재 카탈로그, 422) → 검토 APPROVED 확인(403)
→ **요청자 == 세션 principal**(403 REQUESTER_MISMATCH) → 재작성(422) → 재작성 검증(§3.0.3, 422) → 실행
```
- **실행은 항상 현재 상태로 재판정한다**(fail-closed). 저장 시점 `lint_report_json`은 표시용이며 게이트 근거가 아니다.
  (검토에는 재-lint를 요구하면서 실행에 요구하지 않는 것은 비대칭이자 fail-open — spec 005 C6과 대칭을 맞춘다.)
- 재판정 BLOCK이면 실행 거부 + `execution_event(outcome=BLOCKED)` 기록. `review_status`는 되돌리지 않고
  화면에 "재검토 필요" 배지(감사 이력 보존).
- **남의 승인 쿼리 실행 금지**: `saved_query.request_id → approval_request.requester == 세션 principal`.
  STEWARD/ADMIN도 대행 실행 불가(결정 14 — 감사에서 열람 주체를 단일하게 유지한다). `on_behalf_of` 컬럼은 없다.
  ~~선행 조건: 읽기 경로 스코프~~ — M2-1에서 적용 완료. **읽기만으로는 부족했다**: 쓰기 경로(`PUT /api/queries/{id}`)도
  같은 스코프를 지나야 하고, 소유권이 `request_id`로 정의되므로 **그 컬럼의 교체 자체를 금지**해야 한다(§3.8-⑴).
- **검토 상태 게이트**(사용자 확정): `review_status = APPROVED`인 **저장된 쿼리만** 실행 가능.
  즉 실행은 에디터의 임의 SQL이 아니라 **저장·검토 승인된 쿼리 실행**이다(`POST /api/queries/{id}/execute`).
  → spec 005 §2의 "review_status는 표시용" 한계가 **여기서 게이트로 승격**된다.
- 에디터의 임의 SQL 실행은 **비범위**(디자인의 실행 버튼은 "저장·승인된 쿼리 실행"으로 의미를 좁힌다).
  저장 전 미리보기가 필요하면 **재작성 결과만 보여주는 `POST /api/preview-rewrite`**(실행 없음)를 제공한다.

## 6. 감사 (append-only)

```
execution_event(id, query_id, actor, original_sql, rewritten_sql, applied_json,
                row_count, elapsed_ms, configured_cap, effective_limit, more_rows_exist,
                outcome[SUCCESS|BLOCKED|ERROR|PREVIEW], error_code, error_detail, at)
  ※ query_id는 **nullable** — 미리보기는 저장 쿼리가 없다. 그래서 조회 경로도 저장 쿼리와 분리해야 한다(§3.8-⑵).
```
- BLOCKED(게이트 차단)·ERROR(타임아웃·SQL 오류)도 기록한다 — 실행 시도 자체가 감사 대상.
- 원본·재작성 SQL을 모두 남겨 "무엇이 자동 적용됐는지"를 사후 검증할 수 있게 한다. `applied_json`에 **적용된 강제식 원문**도 남긴다(STEWARD가 마스크 식을 약화시켜도 사후 탐지 가능).
- **감사 쓰기는 주 DataSource + `REQUIRES_NEW`** — 실행 커넥션은 읽기 전용이라 쓸 수 없고, 타임아웃 롤백에도 살아남아야 한다. 게이트 차단은 예외 핸들러가 아니라 **차단 지점에서 기록 후 throw**.
- **결과 행은 저장하지 않는다**(불변식). `error_message`는 SQLState+vendor code+정제 메시지만 — MySQL 오류는
  데이터 값을 에코하므로(`Truncated incorrect ... value: '...'`) 사용자 응답에는 **분류 코드만**(TIMEOUT/SQL_ERROR/REWRITE_FAILED) 반환하고 원문은 STEWARD/ADMIN에게만.
- `original_sql`·`rewritten_sql`·`applied_json`·`error_detail`은 **MEDIUMTEXT** (VARCHAR(500)이면 감사가 잘려
  사후 검증 불가, TEXT면 입력 상한 60,000 B와 65,535 B가 너무 가깝다 — **재작성은 원본보다 커진다**. §3.8-⑻).

## 7. API

```
POST /api/queries/{id}/execute        → 200 ExecutionResultDto | 403(권한·미검토) | 422(룰·재작성 실패)
POST /api/preview-rewrite {sql, requestId}  → 200 {rewrittenSql, applied[]} (실행 없음)
       ※ requestId **필수**(purposeCode 서버 주입 — spec 005 C1 계승), §6.0 게이트 전체 적용,
         applied[]는 접근 허용 테이블 항목만, 감사 기록. **권한 게이트와 같은 마일스톤에서만 노출**
         (M1에만 배포하면 무권한 카탈로그 오라클이 열린다 — 어떤 컬럼이 MASK인지·강제식 원문이 노출)
GET  /api/queries/{id}/executions     → [ExecutionEventDto] (본인·STEWARD/ADMIN)
GET  /api/executions?actor=&outcome=&before=  → [ExecutionEventDto] (STEWARD/ADMIN, 200건씩 커서 페이징)
       ※ 헤더 `X-QG-Audit-Total`에 전체 건수. **커서가 없으면 은닉이 성립한다** — 새 기록을 200건 쌓아
         옛 기록을 조회 범위 밖으로 밀어낼 수 있다(§3.8-D3).
       ※ **저장 쿼리와 분리된** 경로다 — 쿼리를 지우면 기록이 404가 되던 은닉 경로와 조회 불가였던
         PREVIEW 기록(query_id null)을 닫는다(§3.8-⑵). append-only 감사가 대상 행의 생사에
         매달려 있으면 통제 수단이 아니다.
```
```
ExecutionResultDto {
  columns: [{name, type}], rows: [[Any?]],
  rowCount, elapsedMs, configuredCap, effectiveLimit, moreRowsExist,
  rewrittenSql, applied: [{kind, table, column, detail}]
}
```

## 8. 프론트엔드

- **에디터 실행 결과 탭**(현재 스텁 → 실): 저장·검토 승인된 쿼리를 선택했을 때만 실행 활성.
  결과 그리드(마스킹된 실제 값) + "N rows · Xms · 상한 N행 적용(초과 행 있음/없음/미확인)" +
  **적용된 재작성 목록**(무엇이 자동 적용됐는지) +
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

0. ~~**M0 (선행)**: 접수 검사(§2.6) + 실행 격리 3종(§2.7 스키마·계정·매핑 총체성)~~ — **완료(§2.8 참조)**
1. ~~**M1**: SqlRewriter(RewritePlan 경계·주입 3원칙·MASK columnRefs·재작성 검증) + 단위 스위트~~
   — **완료(§3.6 참조)**. `preview-rewrite`는 권한 게이트가 붙는 M2까지 **미노출**.
2. ~~**M2**: 실행 인프라 + 실행 게이트(완전 재판정) + 감사 + execute·preview API~~ — **완료(§3.8 참조)**. 검증 2라운드(타사 정합성 + 사내 적대) 반영
3. **M3**: 에디터 실행 결과·재작성 표시 + 저장쿼리 실행 액션·이력 + E2E·화면 대조

## 11. 결정 기록 (2026-07-25 사용자)

1. **동일 DB로 시작** — 설정 DB에 데모 데이터 테이블. 원격 대상 DB·자격증명은 ④에서 재검토.
2. **자동 재작성** — MASK 치환·FILTER 주입을 서버가 수행. 디자인의 마스킹 결과가 실제로 동작.
3. **안전장치 4종 전부** — 검토 상태 게이트 · 행 상한·타임아웃 · 읽기 전용 연결 · 실행 감사 로그.
4. (파생·AI) 실행 대상은 **저장·검토 승인된 쿼리**로 좁힌다(임의 SQL 실행 비범위). 저장 전에는 `preview-rewrite`.
5. (파생·AI) 논리 테이블명 ↔ 데모 물리 테이블 매핑은 **실행 시점에만** 치환(카탈로그·룰·권한 모델 오염 방지).
6. (파생·AI) 재작성 실패는 fail-closed(부분 적용 금지).
7. (v2·적대 검토) **접수 검사**(주석 전면 금지·문형 허용목록·변수/한정자/0-테이블 거부)를 M0로 선행.
8. (v2) **실행 격리 3종**(별도 스키마·별도 계정·매핑 총체성) — 앱 버그와 무관한 최후 방어선.
9. (v2) MASK는 columnRefs 기준, 비-투영 위치는 실행 거부 + `must_be_masked` **BLOCK**(위치에 따라 WARN/BLOCK 분기).
   `judged=false→true` 전환이 기존 규칙 평가·enforced 배지를 소급 변경함을 spec 004 §4.1에 주석.
10. (v2) 주입 3원칙(괄호·LIMIT 단일장치·강제식 AST 삽입) + **재작성 결과 자체 검증**.
11. (v2) 실행은 **완전 재판정**, 남의 승인 쿼리 실행 금지, 감사는 REQUIRES_NEW·결과 행 미저장·오류 메시지 정제.
12. (M1 계획·사용자) **OFFSET 금지** — `LIMIT 1000,1000`으로 행 상한을 무한 우회할 수 있고, 상한의 의미를
    "이 실행으로 나간 행 수"로 고정해야 감사가 총 반출량을 셀 수 있다. 접수 검사에서 거부(`LIMIT_OFFSET_NOT_ALLOWED`).
13. (M1 계획·AI) **AST를 폐기하지 않는다** — `inspect()`의 AST를 불투명 핸들로 보관해 재작성이 *판정된 그 AST*를
    고친다. §2.5-1의 판정-실행 분기를 사후 검증으로 완화하는 대신 구조적으로 제거하고, §3.0.3은 이중 방어로 남긴다.
14. (M2 계획·사용자) **대행 실행 불허** — 실행은 요청자 본인만. 감사에서 열람 주체를 단일하게 유지한다.
15. (M2 계획·AI) 읽기 경로 스코프를 M2-1에서 먼저 닫는다 — 현재 인증된 누구나 남의 쿼리 본문을 읽을 수 있고,
    실행 차단만으로는 열람이 막히지 않는다(spec 005 §5가 예고한 선행 조건).
