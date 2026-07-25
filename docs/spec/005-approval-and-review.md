# 005 — 승인 요청 · 쿼리 검토 워크플로

> 상태: **구현 완료 (v2 — 적대 검토 반영)**
> 작성: 2026-07-25 · 근거: learning 004 §2.3, spec 003(화면 스텁), spec 004(실 규칙·게이트)
> 모델 교정(사용자): **쿼리는 승인을 받은 뒤에 작성·저장**된다. 상태는 쿼리가 아니라 요청/검토에 있다.
> v2 변경: purposeCode 승계(C1), 테이블 커버 집합 정의·우회 스위트(C2), 승인 라인 무결성·원자성(C3·C4),
> 검토 실효성(모든 수정은 재검토 리셋 C5, 검토 전 재-lint C6), 게이트 순서·응답 계약(H4·H5),
> actor 스텁 한계 명문화(H1), 규칙 내용 스냅샷(H2), append-only 감사 로그(H6), 스키마 재생성(H7).

## 1. 목표·모델

```
승인 요청서 → 순차 승인 → 승인됨 → (그때) 쿼리 작성·저장 → 쿼리 검토 → 검토 승인/반려
     [승인 요청 화면]                      [에디터: 승인된 요청 필수]   [저장된 쿼리: 검토 상태]
```
상태는 두 곳: ① 승인 요청(PENDING/APPROVED/REJECTED/CANCELLED), ② 쿼리 검토(PENDING_REVIEW/APPROVED/REJECTED).

## 2. 범위 / 비범위

### 범위
- 승인 요청 엔티티(목적·**관리형 purpose_code**·대상 테이블·규칙 스냅샷·비즈니스 요건·순차 승인 라인·상태)
- 순차 승인(원자적 전이) · 저장 게이트 확장(승인된 요청 필수 + 테이블 커버) · 쿼리 검토(재-lint 포함)
- append-only 감사 로그 2종 · 화면 3종 실 연결 · directory(사용자/승인자/요건) 관리형 목록

### 비범위 (명시)
- 실 인증·권한 — actor는 스텁(§5). **접근 통제가 아님.**
- 쿼리 실행·마스킹 재작성. must_be_* 판정.
- **승인 철회·유효기간**: APPROVED 요청은 **불변**(취소·삭제 불가) — 저장 쿼리가 참조하므로 dangling 원천 차단. `DELETE /api/approvals`는 제공하지 않는다(의도).
- **비즈니스 요건 → purposeCode 매핑 안 함** — 요건은 감사·표시 전용, purpose는 별도 관리형 필드(§3.1).
- **review_status는 이번 스펙에서 표시·감사용** — 실행/재사용을 차단하지 않는다(실행 스펙에서 게이트로 승격). 화면에도 표기(M7).
- db(스키마) 검증 — 파서가 `schema.table → table`로 스키마를 버려 검증 불가. **표시·감사 전용**(C2).

## 3. 엔티티·상태 기계

### 3.1 승인 요청
```
approval_request(id, purpose_title, purpose_code NOT NULL,      -- purpose_code → catalog_purpose.code (관리형, C1)
                 requester, status[PENDING|APPROVED|REJECTED|CANCELLED],
                 current_step INT NOT NULL, version BIGINT,     -- @Version (원자 전이, C4)
                 submitted_at, decided_at NULL)
request_table(request_id, db, table_name)                        -- 카탈로그에서 선택만 (자유 입력 400)
request_rule(request_id, rule_id, rule_name, severity_summary,
             tree_json_snapshot, forced BOOLEAN)                 -- 내용 동결 스냅샷 (H2)
request_business_req(request_id, code)                           -- 관리형 5종 화이트리스트 검증
request_approver(request_id, step, name, role,
                 decision[PENDING|APPROVED|REJECTED], decided_at,
                 UNIQUE(request_id, step))                       -- 단계당 1명 (C4)
approval_event(id, request_id, step, actor, action[SUBMIT|APPROVE|REJECT|CANCEL], note, at)  -- append-only (H6)
```
불변식:
- **승인자 ≥ 1명 필수**(0명 → 400). 생성 시 상태는 항상 PENDING이며 **어떤 경로로도 승인자 결정 없이 APPROVED가 될 수 없다** (C3).
- step은 **1..N 연속**(비연속 400), **동일 인물 중복 편성 금지**(400). 승인자는 directory 풀 대조(미등록 400).
- 대상 테이블 ≥ 1(L1). `request_approver`는 directory FK 없이 name/role 비정규화 저장 — 인사 변동에도 감사 불변(의도, L3).

전이(전부 **원자적**, C4): 조건부 UPDATE
`... SET current_step=?, status=? WHERE id=? AND status='PENDING' AND current_step=?` → **affected rows 0이면 409**.
`request_approver.decision`도 `AND decision='PENDING'` 조건부 갱신(재결정 방지).
- current_step 승인자 APPROVE → step+1. 마지막 단계 APPROVE → 요청 APPROVED(decided_at).
- 어느 단계든 REJECT → 요청 REJECTED(종료, 이전 단계 이력은 event 로그에 보존).
- requester CANCEL(PENDING만) → CANCELLED. REJECTED 요청 재활성 없음 — 새 요청 생성만(M1).

### 3.2 쿼리 검토 (saved_query 확장)
```
saved_query( ...기존...,
  request_id BIGINT NOT NULL,                       -- 근거 승인 요청 (APPROVED 필수)
  review_status VARCHAR NOT NULL DEFAULT 'PENDING_REVIEW',
  reviewer NULL, reviewed_at NULL, review_note NULL)
query_review_event(id, query_id, actor, decision, note, sql_hash, lint_snapshot_json, at)  -- append-only (H6)
```
- **모든 PUT(수정)은 sql/name 변경 여부와 무관하게 `review_status`를 PENDING_REVIEW로 리셋**하고 reviewer·reviewed_at·review_note를 NULL로 초기화한다 (C5 — 검토된 쿼리의 본문이 도장을 단 채 바뀌는 구멍 차단). 이전 결정은 event 로그에 보존.
- 검토 결정 **직전 재-lint**(C6): `decision=APPROVED`인데 현재 규칙 기준 BLOCK이면 **409**(위반 목록 반환). 재-lint 결과로 `lint_report_json` 갱신, 화면에 "저장 시점 vs 현재" 비교 표시.
- 자가 검토(요청 requester == 검토 actor) → **409**(정책을 코드로; 단 actor 위조 가능성은 §5, M3).

## 4. 저장 게이트 (실행 순서대로 — H4)

> **개정 주의 (spec 007 §6.0)**: 인증·데이터 권한 게이트가 룰보다 **선행**하도록 개정됐다.
> 최종 순서는 인증(401) → 데이터 권한(403) → 룰(422) → 승인(403). 룰 hit은 권한 통과 후에만 기록.

1. **룰 게이트(기존, 선행)**: 파싱·시스템 룰·사용자 규칙 → BLOCK이면 **422 LintReportDto**로 즉시 반환.
   규칙 hit 카운트는 이 단계에서 기록(spec 004 §7 "저장 시도 기준" 계약 유지).
2. 요청 검사: `requestId` 필수 / 존재 / status=APPROVED → 아니면 **403**
3. 요청자 일치(스텁 — §5 단서 적용) → **403**
4. **테이블 커버 검사** → **403**

### 4.1 테이블 커버 정의 (C2)
- `queryTables` = IR의 **루트 + 모든 자손 스코프**(서브쿼리·파생·CTE 본문·UNION 팔·EXISTS)를 재귀 순회해 모은
  `tables.filter { it.physical }.map { it.name.lowercase() }`의 **합집합**. CTE/파생 alias(physical=false)는 제외
  (그 본문의 물리 테이블은 자식 스코프에서 잡힘). 셀프 조인은 집합이라 무해. 백틱은 파서가 이미 제거.
- `requestTables` = `request_table.table_name` 집합(소문자). **요청 생성 시 테이블은 `/api/catalog/tables`에서 선택만 가능**(자유 입력 400) — 문자열 불일치로 인한 오차단·우회 차단.
- `queryTables ⊄ requestTables` → 403 + **초과 테이블 목록** 반환. 요청이 쿼리가 안 쓰는 테이블을 포함하는 것은 허용(상위집합 OK).
- **결정(수용)**: 미등록 테이블은 요청에 담을 수 없으므로 저장도 불가 — `unknown-table`이 실질적으로 WARN→차단으로 승격된다. 데모 시드·테스트 쿼리는 카탈로그 등록 테이블로 맞춘다.

### 4.2 purposeCode 승계 (C1 — 가장 중요)
- **lint/save의 `purposeCode`는 클라이언트 입력을 받지 않는다.** 서버가 `approval_request.purpose_code`에서 주입한다.
  `SaveQueryRequest.purposeCode`·`LintRequest.purposeCode`는 폐기(전달 시 무시). `POST /api/lint`는 **`requestId`를 받는다**
  — 에디터 디바운스 lint와 저장 게이트가 같은 purposeCode를 써야 "lint 통과 → 저장 422"가 안 생긴다.
- 효과: purpose가 "요청자가 고르는 값" → "**승인 라인이 승인한 값**"으로 승격되어 spec 002 C9(자가 면제)가 구조적으로 닫힌다.

## 5. 행위자 스텁 — **접근 통제가 아님** (H1)

> **[spec 007로 해소됨]** 아래 한계는 spec 007(세션 인증)에서 제거됐다 — actor는 세션 principal에서만 오고
> `X-QG-Actor` 헤더는 400으로 거부된다. 이하 문단은 spec 005 시점의 기록.
>
> **본 스펙의 actor는 인증되지 않은 클라이언트 제공 문자열이다. 순차 승인·자가 검토 금지·요청자 일치 검사는
> 워크플로·감사 장치이며 접근 통제가 아니다(위조 가능).** fail-closed 보장은 §4.1·§4.4(요청 존재·상태·테이블 커버)와
> 룰 판정에만 적용되고, §4.3(요청자 일치)은 신원 기반이라 **인증 도입 전까지 보증되지 않는다.**

- actor는 body가 아닌 **헤더 `X-QG-Actor`** 로 통일(인증 도입 시 교체 지점 단일화). 인증 도입 시 클라이언트 제공 actor는 400으로 거부.
- `GET /api/approvals?status=APPROVED&requester=` 도 같은 계열(임의 requester로 열람) — 위 문구에 포함.
- 화면(§8)의 승인·검토 액션 옆에 "데모 — 신원 위조 가능, 인증 후속" 상시 표기.

## 6. 규칙 연계 (감사)

- 요청서 규칙 체크박스는 `/api/rules` 실 목록 참조. 제출 시 **내용 스냅샷**(rule_name·severity·tree_json·forced) 동결(H2).
  규칙이 이후 편집·삭제돼도 승인 당시 기록은 불변 → 규칙 삭제에 역참조 가드 불필요.
- 요청 상세는 스냅샷 vs 현재를 비교해 **"승인 당시와 규칙이 달라졌습니다 (N건 변경/M건 삭제)"** 배지 표시.
- **판정은 항상 실 규칙 엔진**. 요청의 규칙 선택은 감사·표시이며 판정을 대체하지 않는다. 화면 필수 카피(H3):
  "체크한 규칙은 **감사 기록용**입니다. 실제 판정에는 **활성 규칙 전체**가 항상 적용되며, 체크를 해제해도 면제되지 않습니다."
  요청자 작성 화면·승인자 화면 모두에 **미선택 강제(BLOCK) 규칙 목록**을 실시간 표시.

## 7. API

```
GET    /api/approvals?status=&requester=&approver=   → [RequestDto]
GET    /api/approvals/{id}                            → RequestDetailDto (테이블·규칙 스냅샷·요건·라인·이벤트)
POST   /api/approvals {purposeTitle,purposeCode,tables[],ruleIds[],businessReqs[],approvers[]}
                                                      → 201 | 400(승인자 0·비연속 step·중복 인물·미등록 purpose/actor/요건·자유 테이블·테이블 0)
POST   /api/approvals/{id}/approve  (X-QG-Actor)      → 200 | 409(순서·상태·재결정·동시성) | 404
POST   /api/approvals/{id}/reject   {note}            → 200 | 409 | 404
POST   /api/approvals/{id}/cancel                     → 200 (requester·PENDING만) | 409 | 404
GET    /api/approvals/usable?requester=               → 승인된 요청(에디터 선택용, 구 approvable — M6 개명)

POST   /api/lint    {dialect,sql,requestId}           → LintReportDto (purposeCode는 요청에서 주입, C1)
POST   /api/queries {name,dialect,sql,requestId}      → 201 | 422 룰 | 403 승인
PUT    /api/queries/{id}                              → 200(검토 리셋) | 422 | 403
POST   /api/queries/{id}/review {decision,note}       → 200 | 409(현재 BLOCK·자가 검토) | 404
GET    /api/directory/users|approvers|business-reqs   → 관리형 목록(화이트리스트 검증 근거)
```
**차단 응답 계약 (H5)**:
```
422 LintReportDto                     -- 룰 차단 (기존, 불변)
403 ApprovalBlockedDto {
      code: "NO_REQUEST"|"NOT_APPROVED"|"REQUESTER_MISMATCH"|"TABLES_NOT_COVERED",
      message, requestId?, requestStatus?, uncoveredTables: [String] }
```
입력 가드(L2): purposeTitle·note 길이, 승인 라인 최대 10단계, 요청당 테이블·규칙 최대 개수.

## 8. 프론트엔드

- **승인 요청**(스텁→실): 목록(실 요청·상태·pending 액션 승인/반려/취소 + actor 선택) + 요청서 폼
  (목적 제목·**관리형 purpose select**·카탈로그 테이블 선택·실 규칙 체크박스(+§6 카피·미선택 BLOCK 표시)·요건·승인 라인).
- **에디터**: 목적 select 제거 → **"승인 요청 선택"**(내 승인된 요청). 선택 요청의 목적·대상 테이블·요건을 읽기 전용 표시.
  lint·save 모두 requestId 전송. 승인된 요청이 없으면 "먼저 승인을 받으세요" + 저장 비활성.
  403 응답은 **규칙 위반과 별도 영역**에 표시("승인 범위에 없는 테이블: users, orders").
- **저장된 쿼리**: 상태 컬럼 = 실 review_status(+"표시용" 주석), 상세에 근거 REQ 링크·재-lint 결과(저장 시점 vs 현재)·검토 승인/반려.
- 화면 대조 기준: `docs/design/query-guardian-design` 승인 요청(418–556)·저장된 쿼리(369–416)·에디터(240–366) (L5).

## 9. 검증 기준

- [x] spec 001/002/004 스위트 회귀 — 단, 저장 테스트는 승인 요청 선행 생성으로 갱신(H8)
- [x] **C1 회귀**: purpose 조건부 FILTER가 승인 요청 경로로도 동일하게 차단(요청 purpose_code=marketing → consent 누락 422)
- [x] 요청: 생성 → 2단계 순차 승인 → APPROVED / 중간 반려 → REJECTED / PENDING 취소 → CANCELLED
- [x] **승인자 0명 → 400** (C3) / 비연속 step·중복 인물·미등록 승인자 → 400
- [x] **동시 승인 2건 → 하나만 200, 나머지 409(단계 미증가)** (C4) / 결정된 요청 재승인 409 / 순서 아닌 actor 409
- [x] 게이트 순서(H4): requestId 없이 BLOCK 쿼리 저장 → **422**(룰 선행, 규칙 hit 증가) / 룰 통과·요청 없음 → 403
- [x] **테이블 커버 우회 스위트 — 전부 403** (C2): CTE 은닉 `WITH x AS (SELECT id FROM users) SELECT id FROM x` /
      IN·EXISTS 서브쿼리 미승인 테이블 / UNION 한 팔 미승인 / 파생 테이블 미승인
- [x] 커버 오탐 — 통과: 백틱·대소문자 변형(승인된 테이블) / 셀프 조인 / 요청이 상위집합
- [x] 검토: 저장 시 PENDING_REVIEW / 승인 → APPROVED / 반려 → REJECTED / **APPROVED 쿼리 PUT 수정 → PENDING_REVIEW 리셋+reviewer 초기화**(C5)
- [x] **저장 후 BLOCK 규칙 추가 → 검토 승인 시도 409**(재-lint, C6) / 자가 검토 409
- [x] 규칙 스냅샷: 승인 후 규칙 편집해도 요청 상세는 당시 내용 표시 + 변경 배지
- [x] 화면: 승인 요청 실 목록·제출·승인/반려 / 에디터 요청 선택·403 표시 / 저장쿼리 검토 (스크린샷 대조)

## 10. 마일스톤

1. **M1**: 승인 엔티티·순차 승인(원자)·directory + 저장 게이트 확장(순서·커버·purposeCode 승계) +
   **에디터 요청 선택도 함께**(M4 — 게이트만 먼저 넣으면 M1~M2 내내 모든 저장이 403이 되므로) + 스키마 재생성·시드 갱신
2. **M2**: 쿼리 검토(리셋·재-lint·자가 검토)·감사 이벤트 로그 2종·규칙 스냅샷/변경 배지 + API 완성
3. **M3**: 승인 요청·저장된 쿼리 화면 실 연결 + actor 스텁 UI·경고 카피 + E2E·화면 대조

## 11. 결정 기록

1~4. (사용자) 승인된 요청 필수 / 행위자 선택 스텁 / 실 규칙 목록 참조 / 검토 단계 포함.
5. (파생·AI) 쿼리 검토는 단일 검토자, 모든 수정은 재검토 리셋, 자가 검토 409.
6. (파생·AI) **purposeCode는 승인 요청에서 서버 주입**, 클라이언트 입력 폐기 — C9 구조적 폐쇄 (C1).
7. (파생·AI) 테이블 커버는 전 스코프 물리 테이블 합집합·카탈로그 선택 강제, db는 표시 전용 (C2).
8. (파생·AI) 승인자 ≥1·step 연속·중복 금지·원자 전이(409) (C3·C4).
9. (파생·AI) 검토는 결정 직전 재-lint, 현재 BLOCK이면 승인 불가 (C6). 감사 이벤트는 append-only (H6).
10. (파생·AI) APPROVED 요청은 불변(철회·삭제 없음) — dangling 차단. review_status는 이번 스펙 표시용(M7).
11. (파생·AI) **스키마 전체 재생성**(spec 002 C3과 동일, 로컬 데모 한정·저장 쿼리 소실 수용) + seed.sh에 승인 요청 시드 추가 (H7).
