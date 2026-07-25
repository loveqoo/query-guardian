# 백로그 (작업 중단 시점: spec 008 M2 완료, 커밋 14bf0c3)

사용자 코드 리뷰를 위해 작업을 멈춘 시점의 대기 목록. 재개하면 **위에서부터** 6단계 루프로 처리한다.
(리뷰 결과로 나온 항목은 이 파일 맨 위 "리뷰 후속"에 쌓는다 — 기존 순서보다 앞선다.)

## 즉시 — 적대 검토가 찾은 **현행 코드 결함** (설계 대기와 무관, spec 010 §9)

- **D-A. 마스킹 강제식에 항등식 등록 가능.** 등록 검증이 `{col}` 포함·파싱 가능성만 본다
  (`CatalogService.kt:102,108`). `CONCAT({col}, '')`을 등록하면 계획·재작성·`RewriteVerifier` ⑷가 전부
  통과하고 **평문 반환 + 감사엔 "MASK 적용"**. 실시간 통제 0. → 등록 시 프로브 값으로 평가해 출력≠입력 확인.
- **D-B. `LintService.judge(..., purposeCode: String? = null)` 기본값 null.** 호출부가 purpose를 빠뜨리면
  컴파일 통과 + purpose별 FILTER·BLOCK이 **0건 발화**. `plan()`도 같은 값을 받아 재작성 검증도 못 잡는다.
  → 기본값 제거 + `Purpose` non-null 값 타입.
- ~~**D-C. spec 008 §5의 게이트 순서가 코드와 다르다.**~~ **해결(P0)** — §5를 코드 기준으로 정정하고
  §2에 남아 있던 낡은 사본(=`§5 확정` 표시를 단 더 위험한 배치)을 **삭제**했다. 순서는 정의를 하나만 둔다.

### codex 검토가 추가로 찾은 현행 결함 (실측 확인함)

- **D-D. 감사 append-only가 애플리케이션 관례일 뿐이다.** `ExecutionEventRepository : CrudRepository<…>`
  (`exec/ExecutionAudit.kt:42`)가 `delete`·`deleteById`·`deleteAll`을 상속한다. DB 권한·트리거로 막지 않아
  코드 한 줄로 감사 수정·삭제가 가능하다. → 읽기 전용 상위 인터페이스 + DB 권한(UPDATE/DELETE 회수).
- **D-E. `QueryExecutor`를 게이트 밖에서 주입할 수 있다.** public 클래스이고 ArchUnit에 제한 규칙이 없다
  (`ArchIsolationTest.kt` 5개 규칙 중 없음). 새 컨트롤러·서비스가 주입하면 게이트·증거 토큰이 전부 우회된다.
  → `internal` + "실행기는 검증된 실행 포트만 호출 가능" ArchUnit 규칙.
- **D-F. `RewriteVerifier`의 "계획 밖 재도출"은 마스킹에만 적용된다.** FILTER는 `plan.injections`만
  순회하고(`RewriteVerifier.kt:101`), 테이블은 `plan.tableRenames`만 순회해(`:87-96`) "물리 테이블 집합 ==
  허용 매핑 집합" **동등성을 단정하지 않는다**(008 §3.0.3은 집합 동등성을 요구). planner가 필터·테이블을
  누락하면 검증기도 아무것도 기대하지 않는다. → FILTER·TABLE_MAP·LIMIT·INTEGRITY에도 독립 기대치 산출.
- **D-A 보강.** "프로브 값에서 출력 ≠ 입력"은 마스킹 증명이 아니다 — 프로브만 다르게 반환하는 `CASE`,
  가역 인코딩, 일부 값 항등 함수가 통과한다. → 허용 함수/템플릿 allowlist 또는 비가역성 계약.

## spec 010 P1 작업 목록 — craft-reviewer 기준선 검토 (2026-07-26)

`QueryExecutionService`를 표본으로 한 **설계 품질 전담** 검토(장인 축의 첫 실행, retrospect 014 교정).
spec 010을 안 보고 독립적으로 도출했고, §1 진단(줄기가 주석에만·사본·문법 다양성·`statement!!`)에
수렴했다. **아래 4건은 spec 010이 놓친 것**이다.

**권장 순서 1 → 2 → 3.** 1이 사본을 없애야 2·3의 수정이 한 곳에서 끝난다. 3을 먼저 하면 같은
sealed 분해를 두 벌 고치게 된다.

| # | 없앨 것 | 뽑아낼 이름 |
|---|---|---|
| 1 | `execute`/`previewRewrite`의 파싱 이후 사본 — **의미 차이는 자유변수 3개뿐**(`queryId`·`requestId`·`purposeCode`, 정규화 후 diff 실측) | `runGate(ctx: GateContext): Cleared` |
| 2 | 차단-기록 문법 4종(try/catch·지역 `blocked()`·5인자 `blockedByReport`·`runCatching`) | `GateStop` + `stop(...)` **하나** |
| 3 | 호출부의 sealed 분해 `when` 8개 | `DemoMapping.Failed(auditCode, message)`·`PlanOutcome.Refused.auditCode` — **`ExecutionFailure.Kind` 선례 답습**(`exec → audit`은 ArchUnit이 허용) |
| 4 | `inspected.statement!!` 2곳 | `InspectResult`를 sealed로: `Parsed(ir, statement, intake)` / `Unparsed(failure, intake)` |
| 5 | `maskedColumnsOf()`가 게이트에 사는 것 — **같은 `lowercase()` 식이 `RewritePlanner:83`에도 있다**(복사는 독립이 아니다, §3.0.3의 취지를 배반) | `RewriteCatalog.maskedColumns(tableName)` |
| 6 | 승인 존재·요청자 검사 2벌(게이트 111–115 + `ApprovalGate` 안) | `ApprovalGate.requireOwned()` + `checkCoverage()` |
| 7 | KDoc 68–69의 순서 목록 — **본문과 이미 어긋났다**(`approvalGate.check`·`REWRITE_NO_LIMIT`이 목록에 없다) | 1이 끝나면 목록을 본문으로 승격 |
| 8 | `QueryService.gate()` — **세 번째 사본이고 이미 갈라졌다**(`parse` vs `inspect`, `lint` vs `judge`) | **결정: 단계 단위만 공유**. 저장 게이트는 그 단위를 자기 순서로 조립만 하고 순서·동작은 불변 |

**검토가 남긴 메타 지적**(가장 아프다): learning 015에 *"검증 축은 코드 × 진입점"* 이라고 적은 순간이,
설계 축에서는 **"진입점이 둘인 게 아니라 사본이 둘인 것"** 이라고 읽혔어야 할 자리다.
P0은 사본을 없애는 대신 테스트를 두 벌로 늘렸다. **1을 끝내면 그 축이 절반으로 줄어든다.**

> 독립성 단서: 리뷰어가 `docs/spec/INDEX.md`를 훑다 010 줄을 지나쳐 읽었다고 고지했다(겹치는 항목은
> 표시됨). 다음부터는 "INDEX를 읽지 말고 브리프가 준 목록만" 으로 지시한다 — 에이전트 정의에 반영했다.

## spec 010 P0가 남긴 것 (P1 착수 시 함께 본다)

- **A2의 판정 기준은 이미 파일에 있다** — `AuditCodeCoverageTest`에서 `bodyCode = null`인 시나리오 **9개**가
  "감사에는 남는데 응답에는 코드가 없는" 경로다. 그 `null`이 코드로 바뀌는 것이 A2의 통과 조건.
  재작성 실패 6종의 **403 → 422** 변경도 같은 파일에서 드러난다.
- **실제 실행 실패 분류 경로가 무검증** (codex 검토 #3). `SQLTimeoutException → TIMEOUT`,
  `SQLException → SQL_ERROR`를 실제로 타는 테스트가 없다 — spy가 `ExecutionFailure`를 직접 던진다.
  짝(enum ↔ enum)만 이름 집합으로 고정했다. → spec 010 A7.
- **감사 코드 6종은 정상 입력으로 도달 불가**(2선 방어). 판정이 재작성과 **같은 기준**을 쓰는 한
  `REWRITE_MASK_NOT_EXPRESSIBLE`은 발화하지 않는다. 두 층이 갈라지는 순간에만 나온다.
- **`.claude/agents/`가 없다** — CLAUDE.md가 위임 대상으로 적은 `deep-reasoner`·`fast-worker`가 미정의.
  다음 Scaffolding에서 정의하거나 CLAUDE.md를 현실에 맞춘다.

## 리뷰 후속

사용자 코드 리뷰 1회차(2026-07-25) — `QueryExecutionService.execute` 표본. 3건 전부 인정.

- **R1. enum이 타입이 아니라 문자열 상수로 쓰인다.**
  - 소문자 enum 2개: `rules/RuleTree.kt:15` `RuleOp`, `:30` `Combinator` — 와이어 포맷(JSON)에 맞추려고
    상수 이름을 소문자로 썼다. `@JsonProperty`/`@JsonValue`로 분리해야 한다.
  - 더 깊은 층: 영속 경계에서 enum 규약이 엔티티마다 다르다 —
    `ApprovalRequest.status: RequestStatus`(enum) vs `SavedQuery.reviewStatus: String` vs
    `ExecutionEvent.outcome: String`. 그래서 `ReviewStatus.APPROVED.name` 비교,
    `it.severity.name == "BLOCK"` 문자열 비교(`QueryExecutionService.kt:278`, `QueryService.kt:113`)가 생겼다.
- **R2. 객체지향 기법이 레이어 분리(DI)에서 멈춘다.** `execute`(118줄)와 `previewRewrite`(65줄)가
  ~85% 같은 절차의 복사본. sealed class는 있는데 호출부에서 손으로 `when` 분해를 **두 번씩** 한다.
  `blocked()` 지역 클로저 중복, 생성자 의존 11개, `blockedByReport` 파라미터 5개(컨텍스트 객체 부재).
  → 게이트 단계 체인 + 감사 데코레이터 + 실행 컨텍스트 객체. 두 진입점은 **설정 차이**가 되어야 한다.
- **R3. 예외 처리가 6채널로 갈라져 있다.** `blocked()`→`ForbiddenException`(비권한 실패에도 403),
  타입별 catch-record-rethrow 3벌, `blockedByReport()`→`BlockedException`(422),
  `runCatching{audit}`(오류 경로), 성공 경로 감사 예외 무매핑(→500), `inspected.statement!!`.
  응답 바디 모양이 5종(`ErrorResponse`/`AccessBlockedDto`/`ApprovalBlockedDto`/`LintReportDto`/`mapOf`).
  **감사에는 남는 차단 코드가 응답에는 실리지 않는다** — 스타일 문제가 아니라 계약 결함.

- **R4. 스펙이 구현을 서술하고 있다(방법론 지적).** 정책은 넓은 범위에서 어긋남을 조이고 좁은 범위에서
  구현의 숨통을 트여야 한다. spec 008은 493줄 중 **233줄(47%)** 이 "구현 결과"(§2.8·§3.6·§3.8)와
  시한부 "실행 계획"(§3.5·§3.7)이다. 구현에서 뽑은 정책은 구현을 **판정할 수 없다** — 서술일 뿐이다.
  구조적 원인: CLAUDE.md "모든 플랜은 파일로 → `docs/spec/NNN`"이 시한부 계획을 영구 스펙에 밀어넣는다.
  → 스펙은 불변식 + 관측 가능한 수용 기준만. 서사는 이미 learning/retrospect가 보유(§3.8은 3중 중복).

- **R5. 파서 타임아웃 장치가 자기 목적을 뒤집는다.** `DruidMySqlParser:99` `newCachedThreadPool`은 **무제한**,
  `:154 future.cancel(true)`는 인터럽트 플래그만 세우고 Druid 파서는 순수 CPU라 확인하지 않는다 → 파싱
  스레드는 끝까지 돈다(:154 주석은 방향이 거꾸로다). 타임아웃이 실제로 나는 상황에서 요청마다 죽일 수 없는
  스레드가 남고 새 스레드가 생긴다. 인라인 파싱은 컨테이너 풀로 유계인데 지금은 무계다.
  **`TIMEOUT` 경로 테스트 0건**, `shutdown` 없음, 테스트에서 파서 8개가 각자 풀 생성.
  → 불변식은 "**파싱 비용은 유계다**". 기다림을 재지 말고 입력을 유계로 — 바이트 상한(있음) +
  어휘 스캐너에서 중첩 깊이·토큰 수 상한(§3.8이 미룬 그 항목이 진짜 방어였다). 마감을 이중방어로
  남기려면 **고정 크기 풀 + 거부 정책**.
- **R6. null을 허용으로 착각했다** — nullable 선언 134개, `!!` 34곳. 뿌리 3개:
  - 합의 타입을 곱 타입에 눌러 담음: `InspectResult.statement: ParsedStatement? = null` — 상관("Success일
    때만 있다")을 **KDoc 산문**으로 적었다. → `ParseResult.Success(ir, statement)`로 옮기면 `!!` 2곳 소멸.
  - 엔티티 PK `id: Long? = null` → `e.id!!` **18곳**. 저장 전/후를 한 타입이 겸한다.
  - 헬퍼 하나가 두 정의역을 겸함: `norm(String?): String?` → `norm(x.name)!!` **12곳**.
    → `norm(String): String` + `normOrNull(String?): String?`. 정의역을 넓혀 12곳에서 갚았다.
  - 실패를 null로: `parsePredicate(): Predicate?`("실패 시 null") — 이유가 사라진다. R3과 같은 뿌리.
  - 정당한 null: `moreRowsExist: Boolean?`(도메인의 셋째 상태), `commentAt: Int?`(`?.let` 한 줄 스코프).
    기준은 "null이 도메인의 사실인가, 타입 설계 실패의 흔적인가".

→ 리팩터링 스펙 초안이 필요하다(번호는 **010** — 009는 mobile-layout이 점유).
   R4를 적용해 **불변식만** 적는다. **M3보다 먼저** 할지 사용자 판단 대기.

## 1. spec 008 M3 — 실행 결과 UI (다음 차례, 계획 미승인)

에디터 실행 결과·재작성 표시 + 저장쿼리 실행 액션·이력 + E2E·화면 대조 (spec 008 §10-3).
**계획을 spec 008에 초안으로 쓰고 승인받은 뒤** 착수한다. retrospect 012가 넘긴 제약 2개:

- 상한 3값(`configuredCap`·`effectiveLimit`·`moreRowsExist`)을 **그대로** 보여야 한다 —
  "LIMIT 적용됨" 한 줄로 뭉치면 백엔드에서 쪼갠 이유가 사라진다.
- 감사 화면은 `GET /api/executions`(커서)를 쓴다. 쿼리별 이력만 쓰면 삭제·PREVIEW가 다시 안 보인다.

## 2. 멀티 벤더 + AI 에이전트 (④)

PostgreSQL·Trino 방언 + 에이전트. M3 이후.

## 3. spec 008 §3.8이 사유와 함께 미룬 것 (스펙 대기)

| 항목 | 왜 지금 아닌가 |
|---|---|
| 컬럼 수준 카탈로그 검증 | 판정 축 추가 → **별도 스펙**. 현 상태도 422 SQL_ERROR로 크게 실패하고 감사에 남는다 |
| `demo_table_map` 정합성 제약·변경 감사 | 매핑 **UI**의 선행 조건. 지금은 seed로만 바뀐다 |
| 파서 실행 시간·깊이 상한 | 접수 검사가 문형을 좁혀 폭발 반경이 작다. 길이 상한이 1차 방어 |
| `/api/users`·`/api/directory/*` 전 인증 공개 | spec 007 H3 카브아웃(승인선 편성에 후보 목록 필요) |

## 상태 메모

- 백엔드 **266건 통과**(P0 반영 후 실측), 실패 0.
- **푸시·머지 안 됨** — 사용자 명시 없이는 금지.
