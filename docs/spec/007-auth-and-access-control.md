# 007 — 인증(세션) · 접근 권한

> 상태: **승인 (v2 — 적대 검토 반영)**
> 작성: 2026-07-25 · 근거: learning 004 §2.4, spec 005 §5(actor 스텁 교체 예고), spec 003(권한 화면 스텁)
> v2 변경: 판정 경로 무권한 불변식+ArchUnit(C4), 자가 승인 금지(C1), 권한 키 단일 서버로 확정(C3),
> 게이트 순서 전 경로 통일(H1), 읽기 경로 스코프(C2), 사용자·권한 API·부트스트랩(H3), title 분리(H4),
> 카탈로그 조회 3분할(H5), 테스트 헬퍼·시드 전환(H6·H7), 세션 하드닝·비밀번호(H8·H9).

## 1. 목표

1. **세션 로그인** — actor 스텁 제거. 주체는 세션 principal뿐. `X-QG-Actor`는 **400**.
2. **접근 권한 강제** — 쓰기·판정 경로 + **읽기 경로**까지(§6).
3. **역할 3종** — ANALYST/STEWARD/ADMIN (§5).

## 2. 범위 / 비범위

### 범위
`app_user`(+권한 테이블) · 세션 인증(로그인/로그아웃/me) · actor 헤더 400 · 역할 매트릭스 ·
권한 게이트(lint·저장·요청 생성·읽기 경로·자동완성) · 사용자·권한 관리 API/화면 ·
탐색기 잠금 실데이터 · Directory 상수 폐기 · 테스트/시드 인증 전환 · spec 005 §5 경고에 "007로 해소" 주석

### 비범위
외부 IdP·비밀번호 재설정·2FA·레이트 리밋 · 조직도 자동 승인자 · 실행·마스킹(③)·멀티 벤더(④) ·
**규칙·카탈로그 변경 승인 절차**(감사 로그만, M3 근거) · 분산 세션 · CSRF 토큰(§7 한계)

## 3. 엔티티

```
app_user(id VARCHAR(64) PK,          -- u1..u4, ap1..ap4, adm1 (기존 actor 값 승계)
         display_name, title VARCHAR(64),      -- title=직책("마케팅본부장") — 감사·화면용 (H4-a)
         role[ANALYST|STEWARD|ADMIN], password_hash, enabled BOOLEAN)
user_server_permission(id, user_id, server_key, allowed BOOLEAN, UNIQUE(user_id, server_key))
user_table_permission(id, user_id, table_name, allowed BOOLEAN, UNIQUE(user_id, table_name))
permission_change_event(id, target_user_id, actor, scope, target, before_allowed, after_allowed, at)  -- append-only (M2)
```

### 3.1 권한 키 결정 (C3)
파서가 스키마 한정자를 버리고(`schema.table → table`) `catalog_table.name`이 **전역 UNIQUE**이므로,
권한 키는 **테이블명 단독**으로 확정한다(옵션 B). `user_server_permission`은 디자인의 DB on/off 스위치에 대응하는
**서버 단위** 토글이며, 백엔드가 단일 서버(mysql-prod)인 동안은 사실상 전체 on/off다.
멀티 서버·DB 차원은 ④ 멀티 벤더 스펙에서 `catalog_table`에 server/db를 추가할 때 재설계한다(명시적 이연).

**행 부재의 의미 (축별 명문화)**: `user_server_permission` 부재 = **허용**, `user_table_permission` 부재 = **허용**
(default-allow). 명시적 `allowed=false` 행만 차단한다. 디자인 `buildDefaultPerms`(전부 허용 후 특정 항목 revoke)와 동일.
→ **알려진 fail-open 방향**: 신규 등록 테이블은 즉시 열람 가능(§7).

### 3.2 사용자 시드 배정 (H4-d)
| id | 표시명 | title | role |
|---|---|---|---|
| u1 | 김도현 | 데이터 분석가 | ANALYST |
| u2 | 이서연 | 데이터 분석가 | ANALYST |
| u3 | 박민준 | 데이터 엔지니어 | ANALYST |
| u4 | 정하윤 | 데이터 거버넌스 | STEWARD |
| ap1~ap4 | 최지훈·한도윤·서준호·김영은 | 마케팅본부장·데이터플랫폼장·정보보호책임자(CISO)·최고데이터책임자(CDO) | STEWARD |
| adm1 | 시스템 관리자 | 플랫폼 관리자 | ADMIN |

- `request_approver.role`에는 **title**을 넣는다(감사 기록 불변, spec 005 L3 유지). `app_user.role`(열거형)과 혼동 금지.
- `Directory.businessReqs`(5종)는 **상수 유지**(승인자 풀과 무관, H4-b).
- `/api/directory/*`는 폐기 → `/api/users`로 대체(H4-c). 프론트의 이름→id 복원표도 제거.
- 부트스트랩: `app_user`·권한·비밀번호 해시는 **`data.sql` 리터럴 시딩**(API 닭-달걀 회피, H3).

## 4. 인증

- `POST /api/auth/login {userId, password}` → 세션 + 사용자. 실패는 사유 구분 없이 **401 동일 메시지**.
  `enabled=false`도 401(사유 미노출, M4).
- `POST /api/auth/logout` · `GET /api/auth/me`(미인증 401 — **프론트 부트스트랩의 정상 흐름**, 에러 토스트 금지).
- **공개 경로는 `POST /api/auth/login` 단 하나** + 정적 자원. 그 외 `/api/**` 미인증 → **401**.
  로그인 화면의 사용자 목록은 **프론트 상수**로 둔다(공개 API 추가 금지, L4). actuator·H2 콘솔 미도입 — 추가 시 인증 필수.
- **`X-QG-Actor` 헤더 수신 시 400**(전역 인터셉터, 우회 경로 없음).
- 하드닝(H8): 로그인 성공 시 `request.changeSessionId()`(세션 고정 방지) ·
  쿠키 `http-only: true`, `same-site: lax` · **상태 변경 엔드포인트는 `consumes=application/json` 강제**(폼 CSRF 완화).
- 비밀번호(H9): **BCrypt**(`spring-security-crypto`만 추가 — starter-security는 필터체인 충돌로 미도입).
  `password_hash`는 **어떤 DTO·로그·에러에도 노출 금지**. 데모 공통 비밀번호는 `data.sql` 주석에 "운영 반입 금지" 경고.
- 인증 인터셉터는 **매 요청 principal의 enabled·role을 DB 재조회**(세션에 role 캐싱 금지) → 비활성화·권한 변경 즉시 반영(M4).
- 예외: `UnauthenticatedException`→401, `ForbiddenException`→403 (`ErrorResponse{message}` 재사용, M7).

## 5. 역할 매트릭스

| 기능 | ANALYST | STEWARD | ADMIN |
|---|---|---|---|
| lint·쿼리 작성/저장/수정 | ✅ | ✅ | ✅ |
| 승인 요청 생성·본인 취소 | ✅ | ✅ | ✅ |
| 승인/반려 · 쿼리 검토 | ❌ | ✅ | ✅ |
| 카탈로그·규칙 쓰기(정의·매핑·purpose·규칙) | ❌ | ✅ | ✅ |
| 규칙 test 스텁 호출 | ❌ | ✅ | ✅ (L5) |
| 사용자·권한 **쓰기** | ❌ | ❌ | ✅ |
| 사용자 목록 조회(`GET /api/users`) | ✅ | ✅ | ✅ (승인 라인 편성에 필요, H3 카브아웃) |

- 승인자로는 **STEWARD/ADMIN만** 지정 가능(ANALYST 지정 시 400).
- **자가 승인 금지 (C1)**: `requester ∉ approvers`(전 단계) — 위반 시 **400 `REQUESTER_IS_APPROVER`**.
  Directory 풀 통합으로 열리는 구멍이므로 불변식으로 못박는다.
- 역할 부족 → **403**(미인증 401과 구분).

## 6. 권한 게이트

### 6.0 게이트 순서 — 전 경로 통일 (H1)
```
인증(401) → 데이터 권한(403) → 룰(422) → 승인(403)
```
권한을 룰보다 **앞**에 두어, 권한 없는 사용자가 위반 메시지로 카탈로그 구조를 추론하지 못하게 한다.
룰 hit 통계(spec 004 §7)는 **권한 통과 후에만** 기록한다. spec 005 §4(룰 선행)는 이 스펙으로 개정된다.

### 6.1 쓰기·판정 경로
- **lint**: 참조 테이블(spec 005 §4.1 전 스코프 합집합) 중 미허용 → **403 `TABLES_NOT_PERMITTED`**(+deniedTables).
  카탈로그로 해석되지 않는 테이블 → **403 `TABLES_UNKNOWN`**(fail-closed, M6 — 오타와 권한 부족을 구분).
- **저장/수정**: §6.0 순서. `requestId`는 **세션 principal이 요청자인 것만** 허용(아니면 403 `REQUESTER_MISMATCH`) — lint도 동일(H2).
- **승인 요청 생성**: 대상 테이블에 미허용 → **400**.

### 6.2 읽기 경로 (C2)
- `GET /api/queries`(목록): 참조 테이블이 내 허용 범위 밖인 항목 제외. 상세는 403.
  **예외**: STEWARD/ADMIN은 검토 목적으로 전건 열람(결정 기록).
- `GET /api/approvals`·`/{id}`: 요청자 본인 · 라인 승인자 · STEWARD/ADMIN만. `?requester=` 임의 열람 차단.
- `GET /api/catalog/defs|mappings`, `GET /api/rules/{id}`: STEWARD/ADMIN 전용.

### 6.3 카탈로그 조회 3분할 (H5)
| 엔드포인트 | 용도 | 권한 |
|---|---|---|
| `GET /api/catalog/schema` | 자동완성 사전 | **허용 테이블만**(컬럼 포함) |
| `GET /api/catalog/tables` | 카탈로그 관리 CRUD | **무필터**, STEWARD/ADMIN 전용 |
| `GET /api/my/tables` | 탐색기·요청 피커 | 전 테이블 + `accessible:Boolean`, 비허용은 **컬럼 생략** |
노출 금지 대상은 테이블 **이름**이 아니라 **컬럼**이다(잠금 UI를 위해 이름은 보여야 함).

### 6.4 판정 경로 무권한 불변식 (C4 — 이 스펙에서 가장 위험한 실수 방지)
> 권한 필터는 **조회 API 계층(`CatalogService`)에만** 적용한다. 판정 경로 — `TableCatalog`/`DbTableCatalog`,
> `LintService`, `RuleEngine`, `UserRuleEvaluator`, `ApprovalGate.physicalTables` — 는 **어떤 경우에도 권한·세션·
> principal을 인자로 받지 않으며**, 해당 패키지는 인증 패키지에 의존하지 않는다.

근거: 판정 카탈로그에 권한이 새면 권한 없는 사용자에게 `partitionKeys=[]`·`blockedColumns=∅`가 되어
파티션·필터·BLOCK 룰이 **한 건도 발화하지 않는다**(권한이 없을수록 룰을 덜 받는 역전). spec 001 §6 계약 파괴.
**ArchUnit 규칙 추가**: `..rules..`·`..lint..`·`..catalog.DbTableCatalog`는 `..auth..`에 의존 금지(Druid 격리와 동일 방식).

### 6.5 응답 계약
```
401 ErrorResponse{message}                                   -- 미인증
403 ErrorResponse{message}                                   -- 역할 권한 부족
403 AccessBlockedDto{code:"TABLES_NOT_PERMITTED"|"TABLES_UNKNOWN"|"REQUESTER_MISMATCH", message, deniedTables[]}
```

## 7. 알려진 한계

- **default-allow(fail-open 방향)**: 신규 등록 테이블은 즉시 열람 가능. 완화 — 저장은 승인 라인이 2차 울타리,
  열리는 것은 lint·자동완성·컬럼 목록. 권한 화면에 "미분류 신규 테이블 N건" 배너. 후속(실행 게이트)에서 default-deny 전환 검토.
- **ADMIN 자기 권한 상향**: `permission_change_event` append-only 감사 + **자기 자신의 권한 행 편집 금지**(403 `CANNOT_EDIT_OWN_PERMISSION`) (M2).
- **STEWARD의 자기 이익 규칙·카탈로그 약화**: 승인 절차 없음(비범위). `rule_change_event`·`catalog_change_event`
  append-only 감사 + 화면에 "최근 변경" 표시로 완화 (M3).
- **권한 변경 비소급**: 저장된 쿼리·승인된 요청은 재검사하지 않는다. 단 **진행 중 세션에는 즉시 반영**(§4 재조회, M4).
- CSRF 토큰 미도입(JSON 강제·same-site lax로 완화), 레이트 리밋 없음, 세션 인메모리, 데모 공통 비밀번호.

## 8. 프론트엔드

- **로그인 화면**(신규): 사용자 select(프론트 상수) + 비밀번호. 미인증 시 전 라우트 리다이렉트.
  `GET /api/auth/me` 401은 정상 흐름(토스트 금지).
- **셸**: 프로필을 `/api/auth/me` 실데이터로, 로그아웃 실동작. **ActorSelect 제거**.
- **세션 처리(M5)**: same-origin(vite 프록시) 전제 — `credentials` 지정 불필요. `request()`에 **전역 401 훅**
  (로그인 리다이렉트 + 진행 중 요청 취소). 로그인/로그아웃 시 무효화 대상: `localStorage['qg.actor']` 삭제,
  **`/catalog/schema` 자동완성 사전**, `/approvals/usable`, 목록 캐시, actor 모듈·이름→id 복원표·ActorSelect 제거.
- **권한 관리**: 실 사용자·서버 토글·테이블 체크박스(ADMIN 쓰기, 그 외 읽기 전용 + 안내). 변경 이력 표시.
- **탐색기**: `/api/my/tables`의 `accessible`로 잠금·"열람 전용" 배너·"이 테이블로 쿼리" 비활성.
- **역할별 UI**: 권한 없는 액션 비활성 + 툴팁. 403은 규칙 위반과 별도 영역("권한 없는 테이블: …" / "미등록 테이블: …").

## 9. 검증 기준

- [x] **기존 102 테스트 회귀**(통합 27 / 단위 75 — 단위는 in-process라 무영향, 실측치로 정정). 통합은 로그인 헬퍼로 전환
- [ ] `X-QG-Actor` 전송 → **400** / 미인증 → 401 / 로그인 → 200·세션 유지 / 로그아웃 후 → 401
- [ ] 역할: ANALYST의 승인·검토·카탈로그·규칙 쓰기·권한 쓰기 → 403 / STEWARD는 권한 쓰기만 403 / ADMIN 전부 ✅
- [ ] ANALYST를 승인자 지정 → 400 / **자기를 승인자로 지정 → 400 `REQUESTER_IS_APPROVER`**(C1)
- [ ] 권한 게이트: 미허용 테이블 lint → 403 TABLES_NOT_PERMITTED / 저장 → 403 / 요청에 담기 → 400 /
      미등록 테이블 lint → 403 TABLES_UNKNOWN
- [ ] **순서(H1)**: 권한 없는 사용자의 룰 위반 쿼리 → **403 먼저**(422 아님, 위반 메시지 미노출) + 규칙 hit 미증가
- [ ] 남의 requestId로 lint·저장 → 403 REQUESTER_MISMATCH (헤더 위조 불가 확인, H2)
- [ ] 읽기 경로: ANALYST가 권한 밖 쿼리 상세·타인 요청 상세·카탈로그 매핑·규칙 상세 → 403/제외 (C2)
- [ ] **판정 완전성(C4)**: users 권한 없는 STEWARD가 users 쿼리 검토(재-lint) → BLOCK 판정 그대로 발화
- [ ] ArchUnit: `rules`·`lint`·`DbTableCatalog`가 `auth` 미의존
- [ ] 서버 토글 off → 그 서버 테이블 전부 차단 / 테이블 명시 차단 → 그 테이블만
- [ ] 자동완성 사전은 허용 테이블만, `/api/catalog/tables`는 무필터(STEWARD+), `/api/my/tables`는 accessible 플래그
- [ ] ADMIN 자기 권한 편집 → 403 CANNOT_EDIT_OWN_PERMISSION / 권한 변경이 감사 이벤트로 남음
- [ ] `data.sql` 부트스트랩으로 로그인 가능(adm1 포함) / **seed.sh 재실행 후 데모 상태 정상**(H7)
- [ ] 화면: 로그인·프로필·역할별 비활성·탐색기 잠금·권한 관리·로그아웃 후 뒤로가기 시 미노출(L6)

## 10. 마일스톤

1. **M1**: `app_user`·권한·`data.sql` + 세션 인증 + actor 400 + **테스트 헬퍼 전환**(아래) + seed.sh 인증 전환
   + **프론트 로그인·쿠키 전환 최소분**(L3 — 안 하면 M1~M2 내내 화면 사망)
2. **M2**: 역할 매트릭스 + 권한 게이트(§6.0~6.3) + 판정 불변식 ArchUnit + 읽기 경로 스코프 + 신규 스위트
3. **M3**: 사용자·권한 관리 API/화면 + 탐색기 잠금 + 역할별 UI + 감사 이벤트 표시 + E2E·화면 대조

**테스트 헬퍼 계약 (H6 — M1 완료 조건)**: `TestRestTemplate`에는 쿠키 저장소가 없다.
`sessionOf(userId)`가 로그인 1회로 `JSESSIONID`를 actor별 캐시하고, `postAs/getAs/putAs/deleteAs`가
`Cookie` 헤더를 주입한다. **기존 `postAs(path, actor, body)` 시그니처를 유지**해 호출 지점 112곳을 수정하지 않는다
(헤더만 `X-QG-Actor` → `Cookie`로 교체). 무인증 `post()`는 `postAs(..., "adm1")`로 일괄 치환.

## 11. 결정 기록

1~3. (사용자) 세션 로그인 / 전 경로 강제 / 역할 3종.
4. (파생·AI) 승인자는 `app_user`(STEWARD+)로 승계, Directory 상수 폐기. `title`을 별도 컬럼으로(감사 문자열 보존).
5. (파생·AI) ADMIN도 데이터 권한 우회 안 함 + **자기 권한 편집 금지**·감사 이벤트.
6. (파생·AI) **권한 키는 테이블명 단독**(단일 서버 전제) — 멀티 서버·DB 차원은 ④로 이연 (C3).
7. (파생·AI) **게이트 순서 전 경로 통일: 인증 → 권한 → 룰 → 승인** — spec 005 §4·spec 004 §7 개정 (H1).
8. (파생·AI) **판정 경로는 권한을 모른다** + ArchUnit 강제 (C4).
9. (파생·AI) `requester ∉ approvers` 불변식 (C1).
10. (파생·AI) 읽기 경로 스코프 + 카탈로그 조회 3분할 (C2·H5). STEWARD/ADMIN은 검토 목적 전건 열람.
11. (파생·AI) default-allow 유지 + fail-open 방향 문서화 (M1).
