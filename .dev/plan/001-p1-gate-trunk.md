# P1 실행 계획 — 게이트 줄기 (spec 010)

> 이 문서는 **시한부 실행 계획**이라 `.dev/`에 둔다. 스펙(`docs/spec/010`)은 불변식과 수용 기준만
> 들고, 여기는 "이번에 어떤 순서로 어떻게 손대는가"를 든다 — 리뷰 R4의 교훈(구현에서 뽑은 정책은
> 구현을 판정할 수 없다)을 구조로 반영한 것이다. **CLAUDE.md의 "모든 플랜은 `docs/spec/NNN`" 규칙과
> 어긋나므로 사용자 확인이 필요하다**(§7).

## 1. 목표

`QueryExecutionService`의 줄기를 읽히게 만든다. 지금 이 파일은 **알고리즘이 주석에만 있고 본문에는
없으며**, 그 주석이 이미 본문과 어긋났다. 본문은 같은 절차를 두 벌(진입점 둘) 적었고, 세 번째 벌이
`QueryService.gate()`에 있다.

**종료 조건**: spec 010 A2·A3·A5 + `.dev/BACKLOG.md`의 craft 목록 1~7 소멸 + 8은 조립으로 해결.

## 2. 범위 조정 제안 — 파서 자원 유계(A7)를 P1에서 뺀다

spec 010 P1은 **파서 자원 유계**를 함께 묶었다. 분리를 제안한다.

- 게이트 줄기와 **파일도 관심사도 겹치지 않는다**(`DruidMySqlParser`). 같이 넣을 이유가 응집이 아니라
  "둘 다 P1 즈음의 안전 작업"이라는 편의였다.
- spec 010 §7 위험 4가 정확히 이 조합을 지목한다 — *"P1이 파서 격리를 손댄다. 벽시계 가드와 `Error`
  번역을 함께 옮기지 않으면 무기록 500이 생긴다."* 게이트 재작성과 섞이면 **회귀의 근인을 가릴 수 없다.**
- 분리하면 병렬 진행도 가능하다(파서 쪽은 게이트 변경을 기다릴 이유가 없다).

→ A7은 **P1.5**로 뺀다. 나머지(A2·A3·A5)는 게이트 줄기와 한 몸이므로 그대로 둔다.

## 3. 설계 — 발명하지 않고 답습한다

craft 검토가 짚은 대로, 이 문제는 **사용자가 자기 라이브러리에서 이미 풀었다.**

| kraft가 푼 방식 | 여기서 어떻게 |
|---|---|
| 실패는 값, 파이프라인 끝에서 `getOrThrow()` **한 번** | `GateOutcome<T>` + 조합자, 예외로의 번역은 **경계 한 곳** |
| 조합자를 손수 만든다(라이브러리 미도입, `ResultExtensions.kt` 108줄) | arrow 미도입 유지, `then`/`map` 최소 집합만 |
| 공통 절차는 **Delegator에 한 번**, 진입점은 위임만 | 단계 단위를 한 곳에, 진입점은 **설정 차이**만 |
| 실패가 비즈니스 흐름의 일부면 값, 인프라 예외는 throw | 게이트 차단 = 값 / `ExecutionFailure` = 경계에서 값으로 번역 |

`Result<T>`를 그대로 못 쓰는 이유 하나: `Result`는 `Throwable`만 담는다. `GateStop`은 예외가 아니어야
하므로(spec 010 I7) 같은 모양의 자체 타입을 만든다 — **모양은 답습, 타입만 우리 것**이다.

### 3.1 타입 (스케치 — 최종 형태는 구현 자유)

```kotlin
sealed interface GateStop { val code: AuditCode; val status: HttpStatus; val audit: AuditPayload }

sealed interface GateOutcome<out T> {
    data class Cleared<T>(val value: T) : GateOutcome<T>
    data class Stopped(val stop: GateStop) : GateOutcome<Nothing>
}
inline fun <T, R> GateOutcome<T>.then(next: (T) -> GateOutcome<R>): GateOutcome<R> =
    when (this) { is Cleared -> next(value); is Stopped -> this }
```

`when`은 **조합자 안에 한 번** 있고 줄기에는 없다. 지금 호출부에서 손으로 하는 sealed 분해 8개가
여기로 접힌다.

### 3.2 단계 단위

각 단계는 `(GateContext) -> GateOutcome<…>` 한 모양. 줄기는 단계 이름의 나열이 된다:

```kotlin
private fun runGate(ctx: GateContext) = parseOnce(ctx)
    .then(::checkAccess).then(::checkApproval).then(::judgeRules)
    .then(::resolveMapping).then(::planRewrite).then(::rewriteAndVerify)
```

**`GateContext`가 두 진입점의 유일한 차이다** — 실측으로 자유변수는 셋(`queryId`·`requestId`·
`purposeCode`)뿐이다. 저장 게이트(`QueryService`)는 **같은 단위를 자기 순서로** 조립한다(룰 422 선행,
spec 005 H4) — 순서·동작은 불변.

### 3.3 P1이 하지 않는 것 (P2 몫)

**단계 증거 타입(I2)과 증거 토큰(I9)은 P1이 아니다.** P1은 단계를 *이름 있는 단위*로 만들 뿐,
발급 권한을 폐쇄한 타입 그래프는 P2가 세운다. 섞으면 P1이 부풀고 회귀 근인이 흐려진다.

## 4. 작업 순서와 커밋 분리

spec 010 §5: *"호환 변경과 의도적 변경을 커밋에서 분리한다."*

| # | 내용 | 관찰 가능 변경 | 검증 |
|---|---|---|---|
| C1 | `GateStop`·`GateOutcome`·단계 단위 도입, **두 진입점 사본 제거** | **없어야 한다** | A0 24건이 **한 줄도 안 바뀌고** 초록 |
| C2 | 감사 두 등급(`recordOrThrow`/`recordBestEffort`) | 있음 — 감사 실패 시 응답이 달라진다 | A5 주입 테스트 신규 |
| C3 | 오류 바디 `code` 추가 + **재작성 실패 403 → 422** | 있음 | A0의 `bodyCode = null` **9개가 코드로** |
| C4 | 저장 게이트가 단계 단위를 조립 | **없어야 한다** | 저장·lint 계약 테스트 그대로 |
| C5~ | craft 4·5·6·7 (`InspectResult` sealed / `maskedColumns` / `requireOwned` / KDoc 승격) | 없음 | 각각 별도 커밋 |

**C1이 먼저인 이유**: 사본이 남아 있으면 C2~C5의 수정이 매번 두 벌이 된다. 그리고 C1은 **P0 안전망의
첫 시험**이다 — 순수 구조 변경이므로 A0가 한 건이라도 바뀌면 그것은 구조 변경이 동작을 바꿨다는 뜻이다.

**C3의 403 → 422가 안전한 근거(실측)**: 프론트에 `/api/queries/{id}/execute`·`/api/preview-rewrite`·
`/api/executions` **소비자가 없다**(M3 미착수, `grep` 0건). 프론트가 의존하는 것은 저장·lint 경로의
`AccessBlockedDto`·`ApprovalBlockedDto`·`LintReportDto` 셋뿐이고, 그 경로는 C4에서 **동작 불변**이다.

## 5. 검증

- **C1은 A0가 감시자.** 24 시나리오의 기대값을 **손대지 않고** 통과해야 한다.
- **C3은 A0가 변경 목록.** `bodyCode` null → 코드로 바뀌는 9곳마다 커밋 메시지에 *"이 null이 무엇을
  뜻했는가"* 를 적는다(spec 010 §5: 테스트를 고치면 그 테스트가 무엇을 지키고 있었는지 먼저 적는다).
- **되돌려 실패 확인**: 조합자가 `Stopped`를 무시하고 진행하는 변형을 넣어 A0가 깨지는지 본다.
  깨지지 않으면 조합자가 안전망 밖에 있는 것이다.
- **타자 검증 2채널**(축을 갈라서):
  - `craft-reviewer` — 같은 표본 재실행. **craft 목록 1~7이 실제로 사라졌는가.**
  - `deep-reasoner` — 사본 제거가 **게이트 순서를 바꾸지 않았는가**(순서는 정책이다).

## 6. 위험

1. **구조 변경이 조용히 순서를 바꾼다.** 단계를 단위로 뽑는 과정에서 검사 순서가 미끄러지면 보안 의미가
   달라진다 → `deep-reasoner` 채널 + A0의 코드×진입점 축이 감시자.
2. **저장 게이트 회귀.** 저장 경로는 감사가 없어 안전망이 얇다 → C4를 마지막 근처에 두고, 기존
   `ApprovalFlowIntegrationTest`·`QueryFlowIntegrationTest`가 응답 `code` 문자열을 단정하는지 먼저 확인한다.
3. **P1이 부푼다.** §3.3(단계 증거 타입은 P2)과 §2(A7은 P1.5)로 경계를 미리 그었다. 그 경계를 넘는
   변경이 필요해 보이면 **멈추고 재합의**한다.

## 7. 사용자 확인이 필요한 것

1. **A7(파서 자원 유계)을 P1.5로 분리** — §2.
2. **재작성 실패 403 → 422** — 관찰 가능한 계약 변경이나 소비자가 없다(§4).
3. **이 계획 문서의 거처.** CLAUDE.md는 "모든 플랜은 `docs/spec/NNN`"이라 적지만, R4는 바로 그 규칙이
   시한부 계획을 영구 스펙에 밀어 넣는 구조적 원인이라고 지목했다. 규칙을 **"정책·불변식 → `docs/spec`,
   시한부 실행 계획 → `.dev/plan`"** 으로 나누자고 제안한다.
