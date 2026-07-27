# 백로그

> **순서는 `docs/spec/014-phases.md`가 정한다.** 이 파일은 **왜 아직인가·어떻게 착수하는가**를 보유하고,
> 014는 **끝나면 무엇을 쓸 수 있게 되는가**를 보유한다. 둘을 중복해 적으면 갈라진다.
> 항목이 해결되면 **그 자리에서** 지우거나 취소선을 긋는다 — 이 파일은 옮겨 쓰이는 원본이다.

(원 메모: 사용자 코드 리뷰를 위해 작업을 멈춘 시점의 대기 목록 — spec 008 M2 완료, 커밋 `14bf0c3`.)

## 즉시 — 적대 검토가 찾은 **현행 코드 결함** (설계 대기와 무관, spec 010 §9)

- **D-A. 마스킹 강제식에 항등식 등록 가능.** 등록 검증이 `{col}` 포함·파싱 가능성만 본다
  (`CatalogService.kt:102,108`). `CONCAT({col}, '')`을 등록하면 계획·재작성·`RewriteVerifier` ⑷가 전부
  통과하고 **평문 반환 + 감사엔 "MASK 적용"**. 실시간 통제 0. → 등록 시 프로브 값으로 평가해 출력≠입력 확인.
- ~~**D-B. `LintService.judge(..., purposeCode: String? = null)` 기본값 null.**~~ **해결**(spec 014 L1) — 기본값 제거. 지우자 **호출부 넷이 컴파일 실패**했다(테스트 셋 + 게이트의 파싱 실패 경로). 그 넷이 목적을 조용히 빠뜨리고 있었다는 증거다. 값 타입(`Purpose`)까지는 안 갔다 — 기본값 제거가 구멍의 본체다.
  원래 서술: 호출부가 purpose를 빠뜨리면
  컴파일 통과 + purpose별 FILTER·BLOCK이 **0건 발화**. `plan()`도 같은 값을 받아 재작성 검증도 못 잡는다.
  → 기본값 제거 + `Purpose` non-null 값 타입.
- ~~**D-C. spec 008 §5의 게이트 순서가 코드와 다르다.**~~ **해결(P0)** — §5를 코드 기준으로 정정하고
  §2에 남아 있던 낡은 사본(=`§5 확정` 표시를 단 더 위험한 배치)을 **삭제**했다. 순서는 정의를 하나만 둔다.

### codex 검토가 추가로 찾은 현행 결함 (실측 확인함)

- **D-D. 감사 append-only가 애플리케이션 관례일 뿐이다.** `ExecutionEventRepository : CrudRepository<…>`
  (`exec/ExecutionAudit.kt:42`)가 `delete`·`deleteById`·`deleteAll`을 상속한다. DB 권한·트리거로 막지 않아
  코드 한 줄로 감사 수정·삭제가 가능하다. → 읽기 전용 상위 인터페이스 + DB 권한(UPDATE/DELETE 회수).
- ~~**D-E. `QueryExecutor`를 게이트 밖에서 주입할 수 있다.**~~ **해결(P2 C4, `75e7acd`)** —
  `ArchGateAccessTest.onlyTheGateMayReachTheExecutor` + `ExecutionOrder` 1인자 포트.
  `internal`은 붙이지 않았다(모듈이 하나라 규칙이 주는 것 이상을 주지 않는다).
- **D-F. `RewriteVerifier`의 "계획 밖 재도출"은 마스킹에만 적용된다.** FILTER는 `plan.injections`만
  순회하고(`RewriteVerifier.kt:101`), 테이블은 `plan.tableRenames`만 순회해(`:87-96`) "물리 테이블 집합 ==
  허용 매핑 집합" **동등성을 단정하지 않는다**(008 §3.0.3은 집합 동등성을 요구). planner가 필터·테이블을
  누락하면 검증기도 아무것도 기대하지 않는다. → FILTER·TABLE_MAP·LIMIT·INTEGRITY에도 독립 기대치 산출.
- **D-A 보강.** "프로브 값에서 출력 ≠ 입력"은 마스킹 증명이 아니다 — 프로브만 다르게 반환하는 `CASE`,
  가역 인코딩, 일부 값 항등 함수가 통과한다. → 허용 함수/템플릿 allowlist 또는 비가역성 계약.

## spec 010 P2 앞에 둘 것 — P1 검토 2채널이 넘긴 것 (2026-07-26) — **4건 전부 반영**

craft 검토가 "발급 권한 폐쇄를 **방해**한다"고 지목한 순서다. 커밋 `4887388`(①)·`61a060e`(②③)·`9e15084`(④).
빠뜨린 항목 없음(retrospect 015 반성 1의 재발 방지 — 계획서 커밋 표에 4건을 명시하고 대조했다).

1. **`InspectResult`를 sealed로** (`parser/DialectParser.kt`) — 지금은 `parse` + `statement?`가 나란한
   두 필드라 "Success면 핸들이 있다"가 KDoc 산문이다. `Parsed`가 그 원본을 계속 들고 다니므로,
   생성자를 닫아도 "세 필드가 서로 모순되지 않는다"는 여전히 `parseOnce`의 규율이지 타입의 성질이 아니다.
   **증거 타입이 증거가 되려면 이것부터.**
2. **상태 타입과 발급자가 다른 파일에 있다** — 상태는 `GatePipeline.kt`, 발급은 `GateSteps.kt`.
   `private constructor`를 쓰려면 같은 파일이어야 하고, `internal`은 `query` 패키지 전체
   (즉 `QueryService`)에 열려 폐쇄가 처음부터 샌다.
3. **`AccessDenied`·`ApprovalDenied` 쌍둥이** — 네 멤버 구현이 문자 그대로 같다. 두 DTO가
   `code`+`message`를 공유하는데 공통 상위 타입이 없다.
4. **저장 게이트 줄기가 반만 풀렸다** — `require`(IllegalArgument) · `orThrowWithoutAudit`(GateStop) ·
   생짜 `approvalGate.check`(ApprovalBlocked)가 20줄 안에 나란히 있다. 승인 검사가 값을 돌려줘야 해서
   체인에 못 들어갔는데, 그 값을 상태에 담으면 된다(`Judged → Approved`).

## spec 010 P2 검토가 남긴 것

- **`InspectResult.Parsed(ir, statement)`의 짝 일치는 타입이 보장하지 못한다.** 서로 다른 파싱의 ir과
  핸들을 조합해도 컴파일된다. 오늘은 생성 지점이 `DruidMySqlParser.inspect()` 하나뿐이라 위험이 낮고,
  `scopeId` 난스가 재작성 시점에 짝을 검사한다(spec 008 결정 13). **캐싱·메모이제이션 레이어가 생기면
  다시 봐야 한다** — "판정 대상과 실행 대상이 갈라지는" §2.5-1의 위협이 그 지점에서 다시 열린다.
- **CI가 없다**(`.github/workflows` 부재). ArchUnit·리플렉션 감시자는 누군가 테스트를 돌릴 때만 발화한다.
  P2에서 컴파일러로 올릴 수 있는 것은 전부 올렸으나, 남은 감시자들은 이 사실 위에서 읽어야 한다.

## spec 010 P3 C3 검토가 남긴 것 (2026-07-26)

- **⚠️ 사람의 결정이 필요: STEWARD/ADMIN이 남의 저장 쿼리를 지울 수 있다.** 삭제는 파괴적 쓰기인데
  *열람* 능력(`Viewer.seesEveryone`)으로 통과한다. C3 전에도 같았으므로 회귀는 아니고, 스펙 008·010에
  삭제 스코프 근거는 **0건**이다. 모순 셋: `Viewer`는 "열람 스코프"라 선언했고, `update`는 대행 수정을
  시그니처로 거부하는데(결정 14의 대칭) 같은 파괴성의 삭제는 열려 있고, 행이 사라지면 소유자가
  `GET /api/queries/{id}/executions`에서 404를 받아 **자기 실행 이력 창구를 잃는다**(전역 감사는 STEWARD
  전용). 현행을 `ExecutionFlowIntegrationTest @Order(22)`로 고정해 두었다 — "소유자만"으로 정하면
  그 테스트가 먼저 실패하고, `delete`를 `ownedBy`로 돌리는 것이 한 단위다.
- **오류 원문 노출 정책의 판정지가 둘이다.** 쿼리별 이력은 `viewer.seesRawErrors`(`QueryController`),
  전역 감사는 `requireRole(STEWARD, ADMIN)` 후 무조건 원문(`ExecutionAuditController`). 느슨해지는
  방향은 아니지만 **조여도 절반만 적용된다** — "원문은 ADMIN에게만"으로 바꾸면 `/api/executions`가
  그대로 남고 발화하는 감시자가 없다.
- **`ExecutionEventDto` 매핑이 두 컨트롤러에 12필드씩 복제**되어 있다(`QueryController.executions`,
  `ExecutionAuditController.recent`). 원문 마스킹 정책이 그 사본 하나에만 있다. 뷰어를 받는 매퍼 하나로
  합치면 위 항목도 같이 닫힌다.
- **감시자의 *서술된 범위*를 시험하는 습관이 없다.** C3에서 발급 경로 검사를 리플렉션으로 만들었고
  KDoc은 "파일 전수"를 주장했는데 실제는 클래스 스코프였다 — companion 함수 한 줄로 뚫렸다(실측).
  retrospect 017의 처방("감시자마다 되돌려 실패")을 **"각 감시자의 KDoc이 약속한 범위 밖에 심어 시험"**
  으로 한 칸 넓힐 것. 이번 건은 정확히 그 시험에서 걸린다.

## 우회 조사가 남긴 것 (2026-07-27, retrospect 019)

- **과차단 3건 미수정** — `ShapeCoverageTest`의 `KNOWN_OVERBLOCK = {F2, H6, K4}`. 전부 **겹 경계에서
  멈춤**(CTE 안 테이블 + 바깥 조건)이고 `K4`는 사용자 규칙 축. 방향은 안전하나 **쿼리를 읽기 좋게
  쪼개면 막히고 한 줄로 뭉치면 통과**해서 나쁜 스타일을 유도한다. 요건 룰도 금지 룰처럼
  **사실로 정규화 후 판정**(상한/하한이 있는가)으로 바꾸는 것이 방향 — 다만 이것은 **완화**이고
  완화는 이 저장소에서 fail-open을 만든 적이 있다(retrospect 010). 적대 검증 필수.
  > ~~`G1`·`G5`(부등호 둘 = BETWEEN, OR 분배)~~ **해결**(spec 011 Q1·Q2 — 경계 쌍과 OR 분배를
  > 사실로 본다). 이 줄이 낡은 채로 남아 `docs/OVERVIEW.md` 초안에 그대로 옮겨졌다(2026-07-28).
  > **해결한 항목은 그 자리에서 지우거나 취소선을 긋는다** — 백로그는 옮겨 쓰이는 원본이다.
- **다중 인스턴스 `USING`의 가드가 측정되지 않았다** — `collectUsingEqualities`는 양쪽이 단일
  인스턴스일 때만 등식을 만든다(추측 방지). 그런데 `singleOrNull`→`firstOrNull`로 완화해도 전체
  테스트가 통과한다(실측) = 그 경우를 태우는 형태가 없다. 형태를 넣으려면 **다중 인스턴스 좌변에서
  `USING` 컬럼이 MySQL에서 어떻게 해석되는지(모호성 오류인지 coalesce인지)를 먼저 확인**해야 한다.
  확인 전까지 그 가드의 근거는 논증이지 측정이 아니다.
- ~~**`ShapeCoverageTest`가 사용자 정의 규칙을 재지 않는다**~~ **해결**(`33eb224`) — 축 K 14형태 추가 — 픽스처에 룰 트리가 없다.
  `joins`·`requires` 축의 형태 커버리지는 아직 미측정.
- **방언이 늘면 문법 전수 대조를 다시 해야 한다**(PostgreSQL·Trino). 이번 수정(블록 전수 훑기)이
  비용을 줄였을 뿐 없애지 못한다. 멀티 벤더 착수 시 선행 작업으로 잡을 것.
- **`SINGLE`/`MULTI`·`server`가 평가에 쓰이지 않는다** — `applies()`는 대상 테이블 참조만 본다.
  화면은 "적용 범위"로 제시하는데 실제로는 범위가 아니다. `SINGLE`/`MULTI`는 안전한 방향(넓게 적용)이나
  멀티 벤더에서는 A 서버 규칙이 B 서버 쿼리를 판정하게 된다.
  > ⚠️ **정정(2026-07-28)**: `GLOBAL`은 **정반대**다 — `UserRuleEvaluator`가 `scope != GLOBAL`로 걸러
  > **평가에서 통째로 제외**한다. 넓게가 아니라 **0회**다(→ `D-M`). 이 줄의 "안전한 방향"이 낡은 채
  > 남아 `docs/OVERVIEW.md` 초안에 **정반대 서술**로 옮겨졌다.
  > 그리고 화면은 `server`를 **존재하지 않는 mock 서버 목록**에서 고르게 한다 — 고른 값은 평가에 안 쓰인다.

## 요청서가 규칙을 고른다 (2026-07-27 사용자 논의 — 별도 스펙 대기)

**지금 없는 것**: 규칙을 **적용하지 않을** 길. 제약 등록 시 목적을 지정하면 "그 목적일 때만 적용"은
되지만, "이 요청은 예외"는 표현할 수 없다. 그래서 정당한 예외가 막힌다 —
예: 소프트 삭제된 행을 봐야 하는 복구 작업.

**방향**: 요청서가 **어느 규칙으로 조회할지 고른다**. 자료 구조는 절반 있다 — `request_rule`이
이미 규칙을 참조하는데 지금은 **승인 시점 스냅샷(드리프트 배지)** 용이고 판정에 쓰이지 않는다.
판정이 그 선택을 보게 하면 된다.

"면제"가 아니라 "선택"으로 부르는 이유는 통제력 차이가 아니라 **사람이 판단하기 쉬워서**다 —
요청서에 적히고, 승인자가 보고, 감사에 남는다. 안전은 다른 데서 온다:
⑴ 기본은 엄격한 쪽 ⑵ 고를 수 있는 건 등록된 것만 ⑶ 고른 사실이 승인 대상.

**함께 볼 결함**: 목적별 제약은 **기본이 "적용 안 함"** 이다(`purposeCode == null || == current`).
목적을 새로 만들면 그 목적에는 아무 제약도 안 붙는다 — 안전하게 하려고 목적별로 등록했다가
새 목적에서 조용히 빠진다. 지금도 위험하다.

## spec 012 P4 — 죽은 마스킹 코드 제거 (착수했다가 되돌림, 2026-07-27)

**동작은 이미 없다**(P2b: `planMasks`를 부르지 않는다). 남은 것은 **쓰이지 않는 코드**를 지우는 일:
`RewritePlan.maskProjections` 필드, `RewritePlanner.planMasks`, `SqlRewriter`의 치환 루프,
`RewriteVerifier`의 마스킹 검사 둘(ⓐ·⑷).

한 번 지웠다가 **되돌렸다.** 이유: `SqlRewriterTest`·`RewriteVerifierTest`의 테스트 일곱이
마스킹을 **다른 것을 시험하는 재료**로 쓴다 — 스코프 지목(파생·UNION 팔), 계획과 AST 불일치 거부,
**핸들 한 번만 사용**. 그 기반 기능들은 **아직 살아 있다**(테이블명 치환·행 상한 주입).

그러니 그 테스트들은 지울 것이 아니라 **재료를 바꿔야** 한다(마스킹 → 필터 주입 또는 LIMIT).
단정까지 다시 써야 하므로 단순 치환이 아니다. 여유 없이 하면 재작성 기반의 안전망을
잘못 손대게 되어 되돌렸다.

**착수 시 순서**: ⑴ 그 일곱 테스트의 재료를 주입/상한으로 바꿔 초록 유지 ⑵ 그 뒤에 마스킹
코드를 지운다 ⑶ 되돌려 실패로 각 제거를 확인.

## spec 012 P2b가 남긴 도달 불가 방어선 (2026-07-27)

서버가 마스킹을 하지 않게 되면서 **재작성이 SQL을 부풀리는 현상이 사라졌다.** 그 결과:

- `execution_event`의 **MEDIUMTEXT 전환**과 "재작성 후 파서 상한 초과" 방어가 **도달 불가**가 됐다.
  실측: 입력 상한 60,000 B이므로 사용자가 직접 쓴 SQL로는 TEXT 한계(65,535)에 닿을 수 없고,
  재작성은 테이블명 치환·LIMIT뿐이라 거의 커지지 않는다.
- 지우지 않았다 — 방언이 늘거나 상한이 바뀌면 다시 도달할 수 있고, 그때 없으면 조용히 잘린다.
  대신 **그것을 재던 테스트 둘은 지웠다**(없는 현상을 재는 테스트라서).
- `REWRITE_MASK_NOT_EXPRESSIBLE`·`REWRITE_EXPRESSION_NOT_USABLE`도 같은 처지 — 판정이 먼저 막는다.
  감사 코드 전수 테스트에서 **스파이 강제군**으로 옮겼다.

**다음에 볼 것**: P4(근거 잃은 장치 정리)에서 이 코드들을 지울지 남길지 판단한다.
남긴다면 "도달 불가이며 왜 남기는가"가 코드에 적혀 있어야 한다.

## 범위 밖 — 이번에 도입된 것이 아니나 적대 검토가 발견 (spec 005 H4 재검토 대상)

**저장 게이트의 purpose 오라클.** `QueryService.gate`가 `approvalGate.findRequest(requestId)?.purposeCode`로
**요청자 확인 없이** purpose를 먼저 뽑고(H4에 따라 룰 422가 승인 403보다 앞선다), 그래서 남의
`requestId`를 넣어 임의 SQL을 저장 시도하면 **그 사람의 purpose 기준으로 판정된 룰 보고서**를 받는다.
purpose별 FILTER의 존재 유무를 캐는 오라클이다. 재작성 전에도 순서·획득 방식이 동일했음을 확인했다.

## spec 010 P1 작업 목록 — craft-reviewer 기준선 검토 (2026-07-26) — **완료**

`QueryExecutionService`를 표본으로 한 **설계 품질 전담** 검토(장인 축의 첫 실행, retrospect 014 교정).
spec 010을 안 보고 독립적으로 도출했고, §1 진단(줄기가 주석에만·사본·문법 다양성·`statement!!`)에
수렴했다. **아래 4건은 spec 010이 놓친 것**이다.

8개 항목 전부 반영됐다(3번은 계획서에서 조용히 빠졌다가 검토가 잡아 뒤늦게 처리 — retrospect 015 반성 1).

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

- ~~**A2의 판정 기준은 이미 파일에 있다**~~ **해결** — `AuditCodeCoverageTest`에 `bodyCode = null` 0건 —
  `AuditCodeCoverageTest`에서 `bodyCode = null`인 시나리오 **9개**가
  "감사에는 남는데 응답에는 코드가 없는" 경로다. 그 `null`이 코드로 바뀌는 것이 A2의 통과 조건.
  재작성 실패 6종의 **403 → 422** 변경도 같은 파일에서 드러난다.
- ~~**실제 실행 실패 분류 경로가 무검증**~~ **해결**(spec 014 L13) — `ExecutionFailureClassifierTest`가
  **진짜 JDBC 예외**로 세 갈래를 태운다. 그리고 **결함 하나를 찾았다**: 커넥션 풀은 지연 생성인데
  초기화 실패가 `SQLException`이 아니라 `HikariPool.PoolInitializationException`(RuntimeException 계열)이라
  분류 사슬을 통째로 지나쳤다 → 감사에 `CONNECTION`이 안 남고 정체불명 오류가 나갔다. 원인을 벗겨 분류한다.
  ⚠️ 이 항목이 *"spec 010 P1(A7)로 넘긴다"*고 적혀 있었는데 **A7은 파서 유계로 소진돼 위임처가 사라져 있었다.**
  두 문서가 서로 "저쪽이 한다"고 적으면 아무도 안 한다. 원래 서술:
  (codex 검토 #3). `SQLTimeoutException → TIMEOUT`,
  `SQLException → SQL_ERROR`를 실제로 타는 테스트가 없다 — spy가 `ExecutionFailure`를 직접 던진다.
  짝(enum ↔ enum)만 이름 집합으로 고정했다. → spec 010 A7.
- **감사 코드 6종은 정상 입력으로 도달 불가**(2선 방어). 판정이 재작성과 **같은 기준**을 쓰는 한
  `REWRITE_MASK_NOT_EXPRESSIBLE`은 발화하지 않는다. 두 층이 갈라지는 순간에만 나온다.
- ~~**`.claude/agents/`가 없다**~~ **해결** — `.claude/agents/`에 `deep-reasoner`·`craft-reviewer`·
  `fast-worker` 3종 정의됨 (커밋 `9e8fcd1`) — CLAUDE.md가 위임 대상으로 적은 `deep-reasoner`·`fast-worker`가 미정의.
  다음 Scaffolding에서 정의하거나 CLAUDE.md를 현실에 맞춘다.

## 개요 문서(`docs/OVERVIEW.md`) 사실 검증이 찾은 것 (2026-07-28)

문서를 쓰려고 전 계층을 대조하다 나온 **현행 결함**. 문서 자체의 오류는 문서에서 고쳤다.

- **D-L. 규칙 편집 화면이 `must_be_masked`를 "판정 미구현"으로 표시한다** (MEDIUM).
  프론트 `RulesPage.tsx`의 `JUDGED_OPS = ["requires","blocks","joins"]`·`isDeferredOp`가
  `must_be_masked`를 미강제 취급하는데, 백엔드는 판정한다(`RuleTree.kt` `judged`에 포함,
  `UserRuleEvaluator`가 BLOCK 위반 발화). **화면이 틀린 말을 한다** — 담당자가 "어차피 안 걸린다"고
  믿고 등록하면 실제로는 사용자 쿼리가 막힌다. `docker/seed.sh`의 "미강제(판정 미구현) 데모" 주석도 낡았다.
  → 프론트 상수를 백엔드 `judged` 정의와 한 출처로 묶는다(와이어 대조 도구에 어휘 쌍 추가).
- **D-N. `/api/preview-rewrite`에 소비자가 없다.** 프론트 전수 grep 0건(`src/api/`는 넷뿐)인데
  그 KDoc은 *"무엇이 자동 적용되는지를 보여준다"*고 적혀 있다 — **spec 012 모델과 어긋난다**(서버는
  자동 적용하지 않는다). 소비자 없는 카탈로그 오라클 창구다. → 지울지, 고쳐 쓸지, 게이트를 걸지 판단.
  (`docs/spec/014-phases.md` O7 "근거 잃은 주석"과 같은 갈래.)
- **D-M. 전역(GLOBAL) 스코프 규칙은 평가에서 통째로 제외된다** — `UserRuleEvaluator`가
  `it.scope != RuleScope.GLOBAL`로 거른다. 백로그의 기존 항목("`SINGLE`/`MULTI`가 평가에 안 쓰임,
  지금은 안전한 방향")이 **전역에는 정반대로 적용**된다 — 넓게가 아니라 **0회**다.
  화면은 배지로 알리지만, 전역 규칙을 등록한 담당자는 아무것도 막지 못한다.

**메타**: 이번 검증이 문서에서 찾은 강한 단정 반례 8건 중 넷이 **낡은 인덱스를 옮긴 것**이었다
(감사 코드 21→20, 형태 55→76, 깊이 상한 200→100, 과차단 4→3). 인덱스는 읽기 비용을 낮추려고
만든 자산인데, **낡으면 오류를 싸게 퍼뜨리는 자산이 된다.** 숫자를 인덱스에 적을 때는
그것이 세어서 나온 값인지, 언제 센 것인지가 함께 있어야 한다.

## 리뷰 후속

사용자 코드 리뷰 1회차(2026-07-25) — `QueryExecutionService.execute` 표본. 3건 전부 인정.

- ~~**R1. enum이 타입이 아니라 문자열 상수로 쓰인다.**~~ **해결** — `RuleTree.kt`가 `@JsonValue`로
  상수명과 와이어 표현을 분리, `SavedQuery.reviewStatus`가 enum (spec 010 P3)
  - 소문자 enum 2개: `rules/RuleTree.kt:15` `RuleOp`, `:30` `Combinator` — 와이어 포맷(JSON)에 맞추려고
    상수 이름을 소문자로 썼다. `@JsonProperty`/`@JsonValue`로 분리해야 한다.
  - 더 깊은 층: 영속 경계에서 enum 규약이 엔티티마다 다르다 —
    `ApprovalRequest.status: RequestStatus`(enum) vs `SavedQuery.reviewStatus: String` vs
    `ExecutionEvent.outcome: String`. 그래서 `ReviewStatus.APPROVED.name` 비교,
    `it.severity.name == "BLOCK"` 문자열 비교(`QueryExecutionService.kt:278`, `QueryService.kt:113`)가 생겼다.
- ~~**R2. 객체지향 기법이 레이어 분리(DI)에서 멈춘다.**~~ **해결** — `QueryExecutionService.runGate`로
  사본 통합 (spec 010 P1). `execute`(118줄)와 `previewRewrite`(65줄)가
  ~85% 같은 절차의 복사본. sealed class는 있는데 호출부에서 손으로 `when` 분해를 **두 번씩** 한다.
  `blocked()` 지역 클로저 중복, 생성자 의존 11개, `blockedByReport` 파라미터 5개(컨텍스트 객체 부재).
  → 게이트 단계 체인 + 감사 데코레이터 + 실행 컨텍스트 객체. 두 진입점은 **설정 차이**가 되어야 한다.
- ~~**R3. 예외 처리가 6채널로 갈라져 있다.**~~ **해결** — `GateStop` 값 기반 실패로 통합 (spec 010 P0·P1).
  `blocked()`→`ForbiddenException`(비권한 실패에도 403),
  타입별 catch-record-rethrow 3벌, `blockedByReport()`→`BlockedException`(422),
  `runCatching{audit}`(오류 경로), 성공 경로 감사 예외 무매핑(→500), `inspected.statement!!`.
  응답 바디 모양이 5종(`ErrorResponse`/`AccessBlockedDto`/`ApprovalBlockedDto`/`LintReportDto`/`mapOf`).
  **감사에는 남는 차단 코드가 응답에는 실리지 않는다** — 스타일 문제가 아니라 계약 결함.

- **R4. 스펙이 구현을 서술하고 있다(방법론 지적).** 정책은 넓은 범위에서 어긋남을 조이고 좁은 범위에서
  구현의 숨통을 트여야 한다. spec 008은 493줄 중 **233줄(47%)** 이 "구현 결과"(§2.8·§3.6·§3.8)와
  시한부 "실행 계획"(§3.5·§3.7)이다. 구현에서 뽑은 정책은 구현을 **판정할 수 없다** — 서술일 뿐이다.
  구조적 원인: CLAUDE.md "모든 플랜은 파일로 → `docs/spec/NNN`"이 시한부 계획을 영구 스펙에 밀어넣는다.
  → 스펙은 불변식 + 관측 가능한 수용 기준만. 서사는 이미 learning/retrospect가 보유(§3.8은 3중 중복).

- ~~**R5. 파서 타임아웃 장치가 자기 목적을 뒤집는다.**~~ **해결** — `DruidMySqlParser`가 유계 풀 +
  `@PreDestroy`, `BoundedMySqlParser`가 깊이 상한 (spec 010 P1.5). `DruidMySqlParser:99`
  `newCachedThreadPool`은 **무제한**,
  `:154 future.cancel(true)`는 인터럽트 플래그만 세우고 Druid 파서는 순수 CPU라 확인하지 않는다 → 파싱
  스레드는 끝까지 돈다(:154 주석은 방향이 거꾸로다). 타임아웃이 실제로 나는 상황에서 요청마다 죽일 수 없는
  스레드가 남고 새 스레드가 생긴다. 인라인 파싱은 컨테이너 풀로 유계인데 지금은 무계다.
  **`TIMEOUT` 경로 테스트 0건**, `shutdown` 없음, 테스트에서 파서 8개가 각자 풀 생성.
  → 불변식은 "**파싱 비용은 유계다**". 기다림을 재지 말고 입력을 유계로 — 바이트 상한(있음) +
  어휘 스캐너에서 중첩 깊이·토큰 수 상한(§3.8이 미룬 그 항목이 진짜 방어였다). 마감을 이중방어로
  남기려면 **고정 크기 풀 + 거부 정책**.
- ~~**R6. null을 허용으로 착각했다**~~ **대체로 해결** — `!!` 34→23곳, `norm` 정의역 분할·`parsePredicate`
  타입 실패 (spec 010 P3). 잔여 23곳이 정당한 null인지는 미검토. — nullable 선언 134개, `!!` 34곳. 뿌리 3개:
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

## ~~1. spec 008 M3 — 실행 결과 UI (다음 차례, 계획 미승인)~~ **해결** — spec 013으로 완료

에디터 실행 결과·재작성 표시 + 저장쿼리 실행 액션·이력 + E2E·화면 대조 (spec 008 §10-3).
**계획을 spec 008에 초안으로 쓰고 승인받은 뒤** 착수한다. retrospect 012가 넘긴 제약 2개:

- 상한 3값(`configuredCap`·`effectiveLimit`·`moreRowsExist`)을 **그대로** 보여야 한다 —
  "LIMIT 적용됨" 한 줄로 뭉치면 백엔드에서 쪼갠 이유가 사라진다.
- 감사 화면은 `GET /api/executions`(커서)를 쓴다. 쿼리별 이력만 쓰면 삭제·PREVIEW가 다시 안 보인다.

## 1-a. IR 트리 뷰어 (사용자 요청 2026-07-26 — **스펙 작성 대기**)

에디터 옆에서 IR을 실시간으로 본다. 백엔드에는 이미 `QueryIR.toAsciiTree()`와 `irTree` Gradle 태스크가
있으므로(spec 010 P1 부산물) **화면 쪽 작업**이다.

- 쿼리 에디터 옆에 **IR 트리 실시간 표시** — 탭 분리 또는 에이전트 패널 같은 드로워, 둘 다 가능.
- **SQL의 IR 트리 + 누락된 IR 노드(트리)를 함께** 보여준다. 클릭하면 해당 구문을 에디터에 넣어 주는 것도 검토.
  → 누락 노드는 `ScopeCoverageTest`가 이미 재고 있는 축이다(등록 누락 = 그 스코프를 아무 룰도 보지 못함).
     그 축을 **개발자 화면으로 끌어올리는 것**이므로, 진단 도구이자 판정 사각의 조기 발견 창구가 된다.

## 2. 멀티 벤더 + AI 에이전트 (④)

PostgreSQL·Trino 방언 + 에이전트. M3 이후.

## 3. spec 008 §3.8이 사유와 함께 미룬 것 (스펙 대기)

| 항목 | 왜 지금 아닌가 |
|---|---|
| 컬럼 수준 카탈로그 검증 | 판정 축 추가 → **별도 스펙**. 현 상태도 422 SQL_ERROR로 크게 실패하고 감사에 남는다 |
| `demo_table_map` 정합성 제약·변경 감사 | 매핑 **UI**의 선행 조건. 지금은 seed로만 바뀐다 |
| ~~파서 실행 시간·깊이 상한~~ **해결** | `BoundedMySqlParser` 깊이 상한 100 (spec 010 P1.5). 접수 검사가 문형을 좁혀 폭발 반경이 작다. 길이 상한이 1차 방어 |
| `/api/users`·`/api/directory/*` 전 인증 공개 | spec 007 H3 카브아웃(승인선 편성에 후보 목록 필요) |

## 상태 메모

- 백엔드 **300건 통과**(2026-07-28 실측: 39클래스·실패 0·스킵 0). 이전 기록 266·305는 다른 시점의 값이다 —
  **숫자는 인용하지 말고 다시 센다**(spec 014 §6 규칙 2).
- **푸시·머지 안 됨** — 사용자 명시 없이는 금지.

## spec 013 적대 검토가 남긴 것 (2026-07-27)

두 채널(보안·정합성 / 설계 품질) 결과 중 **이번에 안 고친 것**. 고친 것은 커밋에 있다.

**Q1은 통과했다** — S2(주입 제거)가 fail-open을 만들지 않았음을 순회 축·카탈로그 출처·타입 사슬로
대조 확인했고, 반대 가설 다섯이 기각됐다. 아래는 그와 별개의 발견들이다.

### D-F. 사용자 규칙 채널이 purpose 스코프 밖에서도 강제식 값을 말한다 (MEDIUM)

`DbTableCatalog.resolveConditionPredicate`에 **purpose 필터가 없다**(실측 확인). 그래서:
- 시스템 룰(`requiredPredicates`)은 purpose가 다르면 침묵하는데,
- 사용자 규칙 `requires`의 조각은 그 purpose에서도 `mc.consent_yn = 'Y'`를 실어 보낸다.

커밋의 "새 노출이 아니다"는 **시스템 룰 채널에서만 참**이었다(그쪽은 메시지가 이미 값을 담았다).

**주의**: 그 필터를 `resolveConditionPredicate`에 넣으면 **판정도 좁아져 fail-open**이 된다.
유출만 막으려면 **조각에만** 적용해야 하고, 그러려면 `UserRuleEvaluator.evaluate`가 `LintContext`를
받아야 한다(현재 시그니처에 없다). 착수 시 순서: ⑴ 평가기에 purpose를 넘긴다 ⑵ 조각 생성에만 건다
⑶ 되돌려 실패 — purpose 불일치에서 조각이 사라지고 **판정은 그대로**인지 둘 다 확인.

### D-G. 조각이 스코프를 나르지 않는다 — 지금은 최상위 위반에만 조각을 준다

CTE·파생·UNION 팔 안의 위반은 **조각 없이 문장만** 나간다(`SelectScope.fixable()`).
적용기가 텍스트 조작이라 스코프를 짚을 수 없기 때문이다. 없애려면 조각이 스코프를 나르고
적용기가 그 스코프를 찾아야 하는데, 그것은 **적용기가 파서가 된다**는 뜻이라 별도 결정이 필요하다.
`FixRoundTripTest`의 UNION 시나리오가 `noFixExpected`로 이 현행을 고정하고 있으므로,
조각을 줄 수 있게 되면 그 자리가 빨간불로 알려 준다.

### ~~D-H. `updateDef`가 C2 가드를 지나간다 (LOW→MEDIUM)~~ **해결**(spec 014 L5)

C2를 함수로 뽑아 생성·수정 **양쪽**이 부르게 했다. kind·클래스 변경은 매핑이 있으면 거부한다(`deleteDef`의 대칭 — 요건을 없애려면 매핑을 먼저 풀게 해서 **그 행위가 보이게** 만든다).
`DefUpdateGuardTest` 5건이 고정: 막는 축 둘(유출·과차단) + **과차단하지 않는 축 둘**.
되돌려 실패로 정확히 막는 축 둘만 빨간불임을 확인했다 — 전부 거절해도 첫 축은 만족하므로
**무엇을 통과시켜야 하는지**까지 재야 가드가 가드다.

원래 서술:

C2(판정 불가 형태의 요건 술어 매핑 거부)는 `createMapping`에만 있다. `updateDef`는 파싱·`{col}`만 보고
`requiredForm`도, **기존 매핑 재검증도** 하지 않는다. 대조: `deleteDef`는 매핑이 있으면 거부한다.
- 강제식을 판정 불가 형태로 바꾸면 → 그 테이블 전 쿼리가 "검증할 수 없습니다"(fail-closed DoS)
- **kind를 INTEGRITY → MASK로 바꾸면 요건이 조용히 사라진다**(fail-open)

스튜어드 권한 안이고(어차피 매핑을 지울 수 있다) S1이 만든 구멍도 아니다(FILTER에도 원래 있었다).
다만 커밋의 "등록 검증 C2도 같이 넓혔다"는 **생성 경로에만** 참이다.

### ~~D-I. `GET /api/catalog/purposes`만 steward 게이트가 없다 (LOW)~~ **해결 — 진단이 틀렸다**

같은 컨트롤러의 다른 조회는 전부 `steward(http)`를 먼저 부른다. 인증은 필요하지만 역할은 안 본다.
spec 013과 무관한 선행 결함.

**정정(2026-07-28, spec 014 L10)**: 조이려다 **실측으로 뒤집혔다.** 승인 요청 작성은 **ANALYST의
기능**이고(`ApprovalsPage`의 "새 요청 작성") 요청서에 목적 코드가 필수다. steward로 조이면
분석가가 요청서를 못 만든다 — **통제가 아니라 고장이 된다.** 누락이 아니라 의도적 카브아웃이었고,
그렇게 적혀 있지 않은 것이 결함이었다. 노출되는 것은 목적의 이름·설명뿐이고, 어느 목적에 어떤
제약이 붙었는지는 `/mappings`(steward 전용)에 있다. 인증 요구만 추가하고(예전엔 `http`를 받지도
않았다) 근거를 KDoc에 남겼다.

> **교훈**: "다른 데는 다 있는데 여기만 없다"는 **누락의 증거가 아니다.**
> 조이기 전에 **그 조회를 누가 쓰는지** 본다. 대칭은 근거가 아니라 가설이다.

### D-J. 화면 층의 사본들 (설계 채널)

- 실행 이벤트 표가 2벌(`QueriesPage` / `AuditPage`) — 이미 갈라졌다: 시각 포맷이 한쪽은 날짜만이라
  하루 세 번 실행하면 같은 줄로 보인다. 상한 3값·결말 툴팁은 감사 화면에만 있다.
  → 컬럼 집합을 설정으로 받는 컴포넌트 하나로.
- `FixDto.kind: String` — 같은 파일의 `severity`·`outcome`은 enum이다. `FixKind` enum +
  zod `discriminatedUnion`으로 바꾸면 `fix.ts`의 죽은 가드(`if (!fix.from)`)가 사라지고
  와이어 대조가 이 어휘도 덮는다.
- `src/api/`에 전송(`client.ts`)과 판단(`fix`·`execution`·`runnable`)이 섞였다 → `src/policy/`로.
- `MoreRows`가 한국어 표시 문구를 타입으로 굳혔다 → 코드 + 라벨 맵으로.
- `EditorPage.tsx` 1400줄 중 ~300줄이 AI 패널·추천 팝업 **고정 스텁**이다 → 컴포넌트로 분리하면
  진입 파일에 게이트 줄기만 남는다.

### D-K. 마일스톤을 언급한 주석을 회수하는 절차가 없다

"C3에서 붙인다" 같은 주석이 그 단계가 끝난 뒤에도 남는다(실제로 `ResultFooter` 머리에 유물 KDoc이
얹혀 있었다). 이 저장소는 주석에 결정을 남기는 것이 자산이라 **거짓말하는 주석이 그 자산을 깎는다.**
회고 단계에 한 줄: `grep -rn "에서 붙인다\|아직 .*않았다\|예정" frontend/src backend/src`
