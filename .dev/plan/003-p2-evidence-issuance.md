# P2 실행 계획 — 발급 권한 폐쇄 (spec 010 I2·I8·I9·I10 / A1·A4·A6)

> P1은 단계에 **이름**을 줬다. P2는 그 이름을 **위조할 수 없게** 만든다.
> retrospect 013이 못 박은 문장이 이 단계의 전부다: *"'타입으로 막는다'의 본체는 필드가 아니라 발급 권한 폐쇄다."*

## 0. 지금 무엇이 열려 있는가 (실측)

| 사실 | 위치 | 결과 |
|---|---|---|
| 모든 단계 타입이 `data class` — 생성자 공개 | `GatePipeline.kt:76-118` | `Ready(...)`를 손으로 조립해 게이트 전체를 건너뛸 수 있다 |
| `data class`라 `copy()`도 공개 | 〃 | 통과한 증거의 `request.actor`만 바꿔 재사용할 수 있다 |
| `InspectResult`가 곱 타입 | `DialectParser.kt:41-45` | `parse=Success ∧ statement=null`이 표현 가능 — `parseOnce`가 규율로 막는다 |
| 상태 타입과 발급자가 다른 파일 | `GatePipeline.kt` vs `GateSteps.kt` | 같은 파일이어야 `private`이 성립. `internal`은 `query` 전체(=`QueryService`)에 열린다 |
| `QueryExecutor`가 public 빈, 인자 3개 | `exec/QueryExecutor.kt:73,105` | 아무나 주입해 임의 SQL·임의 상한으로 실행 가능(I10 미이행, 백로그 D-E) |
| 저장 게이트가 세 문법 혼재 | `QueryService.kt:176-196` | `require`(400) · `orThrowWithoutAudit`(GateStop) · 생짜 `approvalGate.check`(403) |

**"발급 권한 폐쇄"의 정확한 뜻**(I2): ⑴ 밖에서 **생성**할 수 없고 ⑵ 밖에서 **구현**할 수 없으며
⑶ **전 단계의 전이 함수만** 다음 타입을 발급한다. 셋 중 하나라도 열리면 나머지는 문서상의 주장이다.

## 1. 폐쇄 수단 — 왜 이 방법인가

Kotlin에는 friend 가시성이 없다. 후보를 실제로 재어 본다:

| 수단 | 되는가 | 왜 아닌가 |
|---|---|---|
| `internal` | 아니오 | 모듈 전체 = `query` 패키지 전체. `QueryService`가 그대로 조립할 수 있어 **폐쇄가 처음부터 샌다** |
| 별도 Gradle 모듈 + `internal` | 예 | 게이트 하나 때문에 모듈을 가르는 것은 비용이 크다. 지금 범위 밖 |
| 중첩 클래스 + `private constructor` | 불확실 | Kotlin은 외부 클래스가 중첩 클래스의 private 멤버에 접근하지 못한다(Java와 다름). 검증 비용만 든다 |
| **`sealed interface` + 같은 파일 `private` 구현체** | **예** | ⑵는 `sealed`가, ⑴은 `private` 구현체가, ⑶은 "발급 함수가 같은 파일에 있다"가 준다 |

→ **채택: 단계 타입은 공개 `sealed interface`, 구현체는 `private data class`, 발급 함수(`GateSteps`)가 같은 파일.**
`private data class`이므로 `copy()`도 함께 닫힌다.

**대가를 먼저 적는다**: `GateStages.kt` 한 파일이 ~350줄이 된다(현 `GatePipeline` 119 + `GateSteps` 113 + 인터페이스).
파일 크기는 폐쇄의 **필요 비용**이지 취향이 아니다 — 가를 수 있는 축이 있으면 craft 검토에서 받는다.
`GateOutcome`·`then`·`GateRequest`는 증거가 아니므로 `GatePipeline.kt`에 남긴다.

## 2. 순서를 타입으로 — 어디까지 갈 수 있나

I8이 요구하는 것: *"권한 재확인 없는 판정, 계획 없는 실행, 핸들 없는 재작성"* 이 **표현 불가**할 것.
두 게이트의 순서가 다르므로 사슬 하나로는 안 된다:

```
실행:  Parsed → Authorized → Covered → Judged<Covered>   → Mapped → Planned → Ready → Executable
저장:  Parsed → Authorized →           Judged<Authorized> → Approved
```

- `Covered : Authorized`(승인 커버 확인은 권한 확인을 **포함**한다) → `judgeRules`가 둘 다 받는다.
- `Judged<out P : Authorized>`가 **무엇을 통과하고 판정됐는지 기억**한다.
- `resolveMapping(judged: Judged<Covered>)` → **승인 검사 없는 매핑·계획·실행이 컴파일되지 않는다.**
- 저장 게이트는 `Judged<Authorized>`만 얻으므로 실행 쪽 사슬로 넘어갈 수 없다.

타입 파라미터 하나로 I8의 첫 항목을 컴파일 시점으로 올린다. **이것이 P2에서 유일하게 영리한 부분이므로
craft·deep 두 채널에 명시적으로 물어볼 항목이다** — 읽히지 않는 안전은 이 저장소의 기준을 통과하지 못한다.

## 3. 증거 토큰(I9)에 대한 판단 — **스펙과 다르게 간다**

I9는 *"실행은 증거 토큰 소지로만 가능하고 토큰은 단회성"* 을 요구하고, A6은 4건 거부를 요구한다.
**제안: 런타임 토큰을 만들지 않고, 같은 4건을 구조로 불가능하게 만든다.**

근거 — retrospect 016 반성 1의 물음("무엇을 없애면 이 문제가 존재하지 않는가")을 A6의 4항목에 각각 적용:

| A6 시나리오 | 런타임 토큰이 막는 방식 | 구조가 막는 방식 |
|---|---|---|
| 재사용 | 소비 플래그 | `Executable`은 `private` 구현체이고 **어떤 공개 API도 반환하지 않는다** — 참조가 `execute`의 스택 밖으로 나가지 못한다 |
| 다른 대상에 재결합 | 대상 해시 비교 | 실행기가 `sql`·`maxRows`·`cap`을 **한 값으로** 받는다(3인자 → 1인자). A의 SQL에 B의 상한을 붙일 자리가 없다 |
| 승인 상태 변경 후 사용 | 상태 스냅샷 비교 | 캐시 경로가 없다. 매 호출이 `checkApproval`을 새로 탄다 |
| 다른 actor에게 전달 | actor 비교 | `actor`는 `GateRequest` 안에 있고 사슬 밖에서 바꿀 수 없다(`copy()`도 닫힌다) |

**증명 부담을 진다**(retrospect 016 반성 2 — "할 수 없다"에도 같은 증명이 필요하다). 주장이 아니라 감시자로 고정한다:

- **A6-구조 테스트**: 리플렉션으로 `query` 패키지의 모든 공개·`internal` 선언을 훑어 **단계 타입이 반환형·파라미터·프로퍼티에 등장하지 않음**을 단정한다. 등장하면 참조가 새는 것이므로 실패.
- **A6-되돌려 실패**: 단계 타입 하나를 `data class`로 되돌리면 위 테스트가 깨지는지 확인(A8).
- **ArchUnit**: `exec.QueryExecutor`를 의존할 수 있는 클래스는 게이트 하나뿐(I10, 백로그 D-E).

**토큰이 값을 갖게 되는 시점을 함께 적는다**: 게이트 통과와 실행이 **다른 호출로 갈라질 때**(배치·에이전트·비동기 실행 큐). 그때는 증거가 프로세스 경계를 넘으므로 구조가 못 막고 단회성 토큰이 필요하다. 지금 만들면 쓰이지 않는 소비 플래그가 남고, 그것이 "이유 없는 코드"다.

> **이 항목은 승인 없이는 진행하지 않는다.** 승인된 스펙의 수용 기준을 바꾸는 제안이기 때문이다.
> 반대 결정이면 C4에서 단회성 토큰까지 만든다(작업량 +1일 규모, A6는 원안대로 4건 거부 테스트).

## 4. 커밋 계획

craft 검토가 "P2 앞에 둘 것"으로 넘긴 4건은 **C1~C3에 전부 들어간다**(빠뜨린 항목 없음 — retrospect 015 반성 1).

| # | 커밋 | 내용 | 검토 넘긴 것 | 종료 조건 |
|---|---|---|---|---|
| C1 | `InspectResult`를 sealed로 | `Parsed(ir, statement, intake)` / `Unparsed(failure, intake)`. `LintService`·`RewriteVerifier`·`DruidMySqlParser`·`GateSteps`·테스트 4곳 갱신 | ① | A4 · 285건 유지 |
| C2 | 발급 권한 폐쇄 | `GateStages.kt` 신설(sealed + private impl + `GateSteps`), `Authorized`·`Covered` 추가, `Judged<P>` 제네릭, `requireRowCap` 이관, `Blocked` 단일화 | ②③ | A1 · A6-구조 |
| C3 | 저장 게이트 완전 전환 | `require(name)`을 게이트 밖(API 경계)으로, `approvalGate.check` → `requireApproval(judged): Approved`. 줄기가 한 문법 | ④ | 저장 경로 동작 불변 |
| C4 | 실행기 접근 폐쇄 | `exec.ExecutionOrder` 포트(1인자), `QueryExecutor.execute(order)`, ArchUnit 규칙 | 백로그 D-E | I10 · A6-구조 |

**C1이 먼저인 이유**: `Parsed`가 `InspectResult`를 통째로 들고 다닌다. 원본이 곱 타입인 채로 생성자만 닫으면
"세 필드가 서로 모순되지 않는다"는 여전히 `parseOnce`의 **규율**이지 타입의 성질이 아니다(검토 원문: *"증거 타입이 증거가 되려면 이것부터"*).

**C3의 `require`를 옮기는 이유**: 이름 길이는 게이트 검사가 아니라 요청 검증이다. `GateStop`으로 바꾸면
새 `AuditCode`가 생기고 A0 전수 검증이 따라온다(learning 015) — 없앨 수 있는 것에 감사 어휘를 늘리지 않는다.

## 5. 검증

| 축 | 방법 | 채널 |
|---|---|---|
| 발급 폐쇄가 실재하는가 | **되돌려 실패**: `private data class` → `data class`로 되돌리면 A6-구조가 깨지는지 | 메인 |
| 순서 강제가 실재하는가 | `resolveMapping(Judged<Authorized>)`를 호출하는 코드가 **컴파일 실패**하는지 실측(컴파일러 출력 첨부) | 메인 |
| 동작 불변 | 285건 전부. 특히 `AuditCodeCoverageTest`(A0)·`AuditFailureContractTest`(A5)·`ExceptionBoundaryTest`(A3) | 메인 |
| 보안·정합성 | 제네릭 증거가 우회 가능한가, `ExecutionOrder` 포트가 위조 가능한가, 저장 게이트 순서가 실제로 안 바뀌었나 | `deep-reasoner` |
| 설계 품질 | `Judged<P>`가 읽히는가, 350줄 파일을 가를 축이 있는가, `Blocked` 단일화가 두 계약을 뭉갰는가 | `craft-reviewer` |

두 채널은 **축을 갈라** 붙인다(retrospect 014). 브리프에 "내 단정을 반증하라"를 상설로 넣는다(retrospect 015 반성 2)
— 특히 **§3의 "구조가 막는다" 4항목을 반증 대상으로 명시**한다.

## 6. 위험

1. **제네릭이 산만함이 된다.** `Judged<Covered>`가 읽히지 않으면 I8을 얻고 가독성을 잃는다.
   → craft 채널이 반대하면 되돌리고, 승인 증거를 `Judged`의 필드가 아니라 **`resolveMapping`이 `Covered`를 별도로 받는 형태**로 내린다(순서 강제는 약해지고 그 사실을 스펙에 적는다).
2. **파일 하나가 비대해진다.** 폐쇄의 필요 비용이나, 350줄이 상한이라고 스스로 정한 적은 없다. 검토가 축을 주면 가른다.
3. **저장 게이트 동작이 조용히 바뀐다** — spec 010 §6이 금지한 것이다. `require` 이관은 **응답 코드가 400으로 유지**되는지 테스트로 고정한다.
4. **`Blocked` 단일화가 두 DTO 계약을 뭉갠다.** `AccessBlockedDto`(deniedTables)와 `ApprovalBlockedDto`는 정의역이 다르다 — 공통 상위는 `code`+`message`까지만이고 바디는 각자 원형을 유지해야 한다.
