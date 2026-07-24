# 002 — 제약 카탈로그 개편: 컬럼 클래스 × kind 기반 제약 메타모델

> 상태: **승인 (v2 — 적대 검토 반영)**
> 작성: 2026-07-25 · 근거: `.dev/learning/004-design-deliverable-analysis.md`(디자인 분석), spec 001
> v2 변경: IR 컬럼 참조 수집 필수화(C1), 미지원 FILTER 매핑 거부(C2), 복합 파티션 지원(C4),
> 카탈로그 무결성 규칙(H5), API DTO 명세(H3), cls 변경 정책(H1), 강제식 안전 규칙(H4) 반영.

## 1. 목표

spec 001의 테이블 단위 제약 2종(PARTITION_KEY/REQUIRED_PREDICATE)을 디자인의 **2계층 어휘 통제**로 전환한다:

```
제약 정의 사전 (컬럼 클래스별, kind 6종)  →  컬럼 매핑  →  룰은 매핑된 제약만 참조
```

이번 단계의 강제 방식은 **판정(lint)** 이다 — 재작성(마스킹 치환·술어 주입)은 실행 기능 도입 시 별도 스펙 (결정 §9-1).

## 2. 범위 / 비범위

### 범위
- **제약 정의(constraint_def)**: 컬럼 클래스 6종 × kind 6종 사전 + `{col}`/`:param` 강제식
- **컬럼 클래스**: 자동 판별 + PII 플래그 + 수동 override
- **컬럼 매핑**: 컬럼 ↔ 제약 정의 다대다 (+ purpose 조건, 파라미터 값)
- **IR 확장: 스코프별 컬럼 참조 수집** — BLOCK 판정의 전제 (§5.1, 적대 검토 C1)
- **기존 룰 이관**: require-partition-key(복합 파티션 지원으로 확장)·require-predicate가 신모델을 읽도록 전환
- **신규 판정**: `no-blocked-column`(BLOCK) — 매핑된 컬럼을 쿼리가 참조하면 차단
- **LIMIT 상한**: require-limit에 상한 검사 추가(초과 시 WARN)
- **카탈로그 화면 개편**: 디자인의 2탭(제약 정의 / 컬럼 매핑) 구조
- 설정 DB 스키마 개편(재생성 방식) + 데모 시드

### 비범위 (후속 스펙)
- 규칙 빌더·사용자 정의 규칙(AND/OR 트리) → **spec 003**
- mask/join/integrity kind의 **판정 로직** — 사전 등록·매핑까지만 (판정은 spec 003)
- `:param`의 쿼리 작성 시점 바인딩 (매핑 시점 고정값만)
- 승인·권한·실행·재작성·멀티 벤더·셸/탐색기·AI

## 3. 제약 정의 모델

### 3.1 컬럼 클래스 (cls)
`PII / BOOLEAN / DATETIME / NUMERIC / KEY / STRING`

**판별 우선순위(결정적)**: `is_pii → PII` > 타입 BOOL → BOOLEAN > DATE/TIME/TIMESTAMP → DATETIME >
정수·소수 타입 중 이름 `id`/`*_id` 패턴 → KEY, 아니면 NUMERIC > 그 외(TEXT/BLOB/JSON/ENUM/VARCHAR 등) → STRING.
알려진 한계(문서화): 문자 타입으로 저장된 id(uuid 등)는 KEY가 아니라 STRING이 된다.

- 판별값은 컬럼에 저장, **수동 override 가능**. is_pii는 컬럼 등록·수정 시 체크박스.
- **cls·is_pii 변경 시 기존 매핑을 자동 삭제하지 않는다**(fail-open 방지, H1). 클래스 불일치가 된 매핑은
  화면에 경고 표시하되 **판정은 계속 발화**한다(fail-closed). 자동 재판별은 저장된 override를 덮어쓰지 않는다.

### 3.2 kind 6종과 이번 단계의 판정 의미

| kind | 디자인 엔진 동작(목표) | **이번 단계(판정)** | expr |
|---|---|---|---|
| `MASK` | SELECT 절 치환 | 사전 등록·매핑만 (판정은 spec 003) | 필수, `{col}` 포함 |
| `FILTER` | WHERE 술어 주입 | **매핑된 술어가 WHERE에 없으면 위반** — require-predicate 이관 | 필수, `{col}` 포함 |
| `BLOCK` | 컬럼 참조 시 거부 | **쿼리 어느 위치든 해당 컬럼 참조 시 차단** (신규 룰 no-blocked-column) | 없음 |
| `JOIN` | 필수 조인 검사 | 사전 등록·매핑만 (판정은 spec 003) | 필수 |
| `INTEGRITY` | 무결성 술어 주입 | 사전 등록·매핑만 (판정은 spec 003) | 필수, `{col}` 포함 |
| `PARTITION` | (결정 §9-3으로 추가) | **파티션 컬럼 조건(=/IN/BETWEEN, 베어 컬럼) 없으면 차단** — 이관 | 없음 |

판정은 spec 001 §6 계약(fail-closed·최상위 AND conjunct·인스턴스 귀속·Raw 미충족)을 그대로 상속한다.
주: `SELECT *`를 통한 BLOCK 컬럼 노출은 `no-select-star`(BLOCK)가 담당한다 — no-blocked-column은
명시적 컬럼 참조만 본다. **no-select-star의 severity 강등은 이 의존 때문에 금지**(M4).

### 3.3 강제식 (expression) — 안전 규칙 (H4)

- `{col}` = 매핑된 컬럼 치환자, `:name` = 파라미터(매핑 시 `params_json`으로 값 고정, **스칼라 리터럴만**).
- 등록 검증(전부 만족해야 등록 허용):
  1. `{col}`·`:param`을 표본값으로 치환한 결과가 `DialectParser.parsePredicate`로 파싱 가능
  2. **단일 술어 표현식만** — 서브쿼리·집합 연산·다중 문 포함 시 거부
  3. MASK/FILTER/INTEGRITY는 expr에 `{col}`이 **최소 1회 등장** (M1 — 매핑 의미 보장)
- 치환은 **파싱 후 AST 노드 치환**으로 구현한다 — 문자열 replace 금지(리터럴 내 `{col}`, 특수 컬럼명에 깨짐).
- **판정 미지원 형태의 FILTER 정의**(§6.5 닫힌 동치 밖, 예: `{col} >= NOW() - INTERVAL 90 DAY`):
  **등록은 허용하되 매핑을 거부한다**(C2 확정). 사유 노출: "spec 003 전까지 이 형태는 판정할 수 없어
  매핑 시 해당 테이블 전체가 차단됩니다 — 매핑은 spec 003에서 지원". 사전에 미리 등록해 두는 것만 허용.

## 4. 데이터 모델 (설정 DB)

```
catalog_table(id, name, description)
catalog_column(id, catalog_table, name, type,
               is_pii BOOLEAN NOT NULL DEFAULT FALSE,
               cls VARCHAR(16) NOT NULL)                      -- 판별값 저장, override 반영
catalog_purpose(id, code, description)
constraint_def(id, cls VARCHAR(16), kind VARCHAR(16), name, description, expression NULL)
constraint_mapping(id,
                   column_id  → catalog_column,
                   def_id     → constraint_def,
                   purpose_code NULL,                         -- FILTER 조건부 적용
                   params_json NULL,
                   UNIQUE(column_id, def_id, purpose_code))   -- 중복 매핑 방지 (H5)
saved_query(...)
```

**무결성 규칙 (H5)**: def 삭제 시 매핑이 남아 있으면 **거부**(먼저 매핑 해제 요구) · 컬럼/테이블 삭제 시 매핑 **연쇄 삭제** ·
purpose 삭제 시 참조 매핑 있으면 **거부**.

**이관 방식 (C3 확정)**: 설정 DB는 로컬 데모 데이터뿐이므로 **전체 스키마 재생성(= saved_query 포함 초기화, 저장
쿼리 소실 수용)** + 데모 시드 재등록. 시드는 기존 대표 시나리오와 BLOCK 검증용을 포함한다:
- `user_events`(event_date DATETIME, consent_yn, …) + PARTITION def 매핑(event_date) + FILTER def `{col} = 'Y'`("마케팅 동의 필수") 매핑(consent_yn, purpose=marketing)
- `users`(id, email·name·phone·ssn = is_pii) + BLOCK def("조회 전면 차단") 매핑(ssn) — 디자인 표본
- purpose `marketing`

**{col} 일반화 규칙 (H2, 문서 목적)**: 구모델 술어를 def로 옮길 때는 requiredForm으로 파싱해 얻은
**컬럼 토큰만** `{col}`로 치환하고 리터럴·연산자는 보존한다. def.cls = 해당 컬럼의 판별 cls.
다중 컬럼·미지원 형태는 자동 이관하지 않는다(수동 등록).

## 5. 백엔드 변경

### 5.1 IR 확장 — 컬럼 참조 수집 (C1, **BLOCK 룰의 전제 — 미구현 시 BLOCK 착수 금지**)

`SelectScope`에 `columnRefs: List<ColumnRef>` 추가. `ColumnRef(table: TableRef?, column: String)` —
resolver 체인으로 해석된 **TableRef 자체**를 담는다(상관 서브쿼리가 바깥 테이블을 참조해도 그 스코프의
columnRefs에 바깥 TableRef가 잡힌다).

파서는 스코프의 **select 목록·WHERE·GROUP BY·HAVING·ORDER BY·JOIN ON(내부·외부 불문)·함수 인자·
CASE·Between/In 피연산자** 전체를 visitor로 재귀 순회해 모든 `SQLIdentifierExpr`/`SQLPropertyExpr`를
수집·해석한다. 서브쿼리 경계에서 순회를 멈춘다(자식 스코프가 자체 수집).
비한정 참조가 귀속 불가(table=null)면 그대로 담는다 — 룰이 fail-closed로 처리.

### 5.2 룰

- **`no-blocked-column`(BLOCK, 신규)**: 스코프의 columnRefs 각각에 대해 —
  (a) `ref.table != null && ref.table.physical` 이고 `catalog.blockedColumns(ref.table.name)`에 ref.column 포함 → 차단.
  (b) `ref.table == null`(귀속 불가)이고 스코프 테이블 중 하나라도 동명 컬럼이 BLOCK 매핑이면 → 차단(fail-closed).
- **`require-partition-key` 이관 + 복합 파티션 (C4)**: `TableCatalog.partitionKeys(table): List<String>` 로
  시그니처 변경. **각 키는 독립 요건** — 전부 §6.6 형태로 충족해야 통과(하나라도 누락 시 차단).
- **`require-predicate` 이관**: FILTER 매핑 조회로 전환(purpose 의미 동일). 매핑 시점에 판정 가능 형태만
  들어오므로(§3.3) "검증 불가" 경로는 방어적으로 유지하되 정상 흐름에선 발생하지 않는다.
- **`require-limit` 확장**: LIMIT 부재 → WARN(현행) + LIMIT > 상한 → WARN("권장 최대 N 이내").
  상한은 `application.yml`의 `guardian.limit.max`(기본 1000)로 주입 (M3).
- 기타 룰(no-select-star, unknown-table)과 §6 계약·게이트 가드는 무변경.

### 5.3 API (DTO 명세 — H3)

```
GET  /api/catalog/defs                  → [DefDto]
POST /api/catalog/defs {cls,kind,name,description,expression?} → 201 DefDto | 400(검증 실패 사유)
PUT  /api/catalog/defs/{id}             → DefDto        DELETE → 204 | 409(매핑 존재)
GET  /api/catalog/mappings?tableId=&columnId=&defId=    → [MappingDto]   (필터 축 3종)
POST /api/catalog/mappings {columnId,defId,purposeCode?,paramsJson?} → 201 MappingDto
     | 400(클래스 불일치·판정 미지원 FILTER·params 검증) | 409(중복)
DELETE /api/catalog/mappings/{id}       → 204
GET/POST/PUT/DELETE /api/catalog/tables — 컬럼에 {id,name,type,isPii,cls,clsOverride?} 포함 (읽기+쓰기)
GET  /api/catalog/purposes …            — 유지, DELETE는 참조 매핑 존재 시 409
GET  /api/catalog/schema                → 유지: {table:[column]} (자동완성 계약 불변, L2)

DefDto     = {id, cls, kind, name, description, expression, mappingCount}     -- "N개 컬럼 매핑" (H3)
MappingDto = {id, tableId, tableName, columnId, columnName, defId, defName, defKind,
              purposeCode?, paramsJson?, clsMismatch: boolean}                 -- 불일치 경고 (H1)
```

## 6. 프론트엔드 변경 (카탈로그 화면만)

디자인의 제약 카탈로그 구조 채택 — 탭 2개 (디자인 세부 충실도 최대화, learning 004 §1.6 기준):
1. **제약 정의**: 클래스별 섹션 6종, 정의 행(kind 태그[마스킹/필터/차단/조인/무결성/파티션] + 이름 + 설명 +
   **강제식 SQL 프리뷰(모노스페이스·파란색)** + **"N개 컬럼 매핑"** + 편집/삭제), 섹션별 "정의 추가".
   정의 모달: kind select + **"엔진 동작: …" 설명 문구**(§3.2 표의 목표 동작) + 강제식 입력
   ("대상 컬럼은 `{col}`, 입력값은 `:param`") + **파싱 미리보기**(치환 결과 다크 코드블록) + 검증 오류 표시.
2. **컬럼 매핑**: 테이블 select → 컬럼 목록(**PII 빨간 태그**, cls 라벨, is_pii 체크·cls override 편집,
   매핑된 제약 **제거 가능한 칩**(불일치 시 경고색), **매핑** 버튼).
   매핑 모달: "같은 클래스에 등록된 제약 중에서 선택" — 동일 cls 정의만 후보, FILTER면 purpose select +
   파라미터 입력, 판정 미지원 FILTER는 후보에서 비활성 + 사유 툴팁(§3.3).
셸(7메뉴)·탐색기는 5순위 스펙 — 현행 3메뉴 유지(명시적 비범위). 디자인의 색·카피는 가능한 그대로 따른다.

## 7. 검증 기준

- [x] **spec 001 §12 스위트 전부 회귀 통과** (신모델 위에서)
- [x] 대표 시나리오 2종(파티션·동의 술어)이 신모델 등록 경로로 동일하게 차단·통과 (E2E)
- [x] **BLOCK 우회 스위트 (C1)** — 전부 차단: `COUNT(ssn)`·`LEFT(ssn,3)` 함수 인자 / `GROUP BY ssn` /
      `ORDER BY ssn` / `HAVING SUM(ssn) > 0` / `WHERE LENGTH(ssn) = 13` / 파생 재수출
      `SELECT s FROM (SELECT ssn AS s FROM users) t` / 서브쿼리·CTE 내 참조 / 백틱 `` `ssn` `` / alias `u.ssn`
- [x] BLOCK 오탐 스위트 — 통과: 동명 컬럼의 타 테이블(비매핑) 참조 / users의 다른 컬럼만 조회 /
      `WHERE created_at > NOW() - INTERVAL 1 DAY` 같은 무관 함수식
- [x] 복합 파티션: PARTITION 매핑 2개 등록 → 한 키만 조건 시 차단, 둘 다 있으면 통과
- [x] 카탈로그 검증: 클래스 불일치 매핑 400 / 중복 매핑 409 / 판정 미지원 FILTER 매핑 400 /
      `{col}` 누락 MASK·FILTER 등록 400 / 서브쿼리 포함 expr 등록 400 / 매핑 있는 def 삭제 409
- [x] LIMIT 1500 → WARN, LIMIT 100 → 통과
- [x] 카탈로그 2탭 화면: 정의 CRUD·매핑 CRUD·클래스 필터·불일치 경고 (화면 검증)
- [x] ArchUnit 격리 유지

## 8. 마일스톤

1. **M1**: IR columnRefs 수집(§5.1) + 데이터 모델 + TableCatalog 재구현 + 기존 룰 이관(복합 파티션 포함) + 회귀
2. **M2**: no-blocked-column + LIMIT 상한 + BLOCK 우회/오탐 스위트
3. **M3**: API + 카탈로그 화면 개편 + E2E

## 9. 결정 기록

1. **강제 방식 = 단계적** (2026-07-25 사용자): 이번 단계는 판정만, 재작성은 실행 스펙에서.
2. **승인 요청서의 규칙 선택 = 디자인 그대로** (2026-07-25 사용자): C9 자가 면제 리스크 수용 —
   통제 책임은 순차 승인 라인으로 이동. spec 004에서 승인자 보조 장치(미적용 규칙 표시) 검토.
3. **파티션 키 = kind `PARTITION` 추가** (2026-07-25 사용자) + 복합 파티션은 다중 매핑·독립 요건(C4, AI 제안).
4. **반영 순서 = 분석 제안대로** (2026-07-25 사용자).
5. severity 충돌은 spec 003에서 조건 단위 severity로 해소 — no-select-star는 BLOCK 유지(§3.2 의존성 명시).
6. **미지원 FILTER = 등록 허용·매핑 거부** (C2, AI 제안 — 테이블 전면 차단이라는 숨은 결과 방지).
7. **스키마 재생성 = 전체 초기화**(saved_query 포함, 로컬 데모 한정 수용) (C3).
