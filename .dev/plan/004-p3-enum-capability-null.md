# P3 실행 계획 — enum · 열람 능력 · null 정리 (spec 010 §4.8 · §5 P3 / A8)

> spec 010의 마지막 단계. **보안 경계를 만지는 유일한 단계**다(§7 위험 2: 플래그 → 능력).

## 0. 실측 (2026-07-26, P2 완료 시점)

스펙에 적힌 숫자는 P0 기준이라 다시 셌다.

| 항목 | 스펙(P0) | 지금 |
|---|---|---|
| `!!` | 35 | **36** (엔티티 id ~21은 §4.8이 범위 밖으로 명시) |
| `norm(...)!!` | 12 | **12** |
| `privileged: Boolean` 등장 | — | **29** (프로덕션). **테스트에는 0** — 전부 HTTP로 들어간다 |
| 소문자 enum | 2종 | `RuleOp`(5값) · `RuleGroup.Combinator`(2값) |
| enum `.name` 문자열 비교 | — | **5곳** (`QueryService` 4 · `QueryExecutionService` 1) |
| `parsePredicate(): Predicate?` 호출부 | — | **6곳** (`requireNotNull` 1 · `?: Raw` 1 · `?: return null` 1 · 기타) |

**영속 경계에서 enum 규약이 엔티티마다 다르다**(R1의 더 깊은 층):
`ApprovalRequest.status: RequestStatus`(enum) vs `SavedQuery.reviewStatus: String` vs `ExecutionEvent.outcome: String`.
`.name` 비교 5곳은 **그 불일치의 증상**이고, 그중 `QueryService:117`의 `it.severity.name == "BLOCK"`은
enum 비교로 쓸 수 있는데도 문자열로 쓴 것이다.

**와이어 포맷은 바꿀 수 없다(실측).** 프론트가 소문자 문자열을 **타입 유니온과 테마 맵의 키**로 쓴다
(`frontend/src/theme.ts:59-69`, `mock/design.ts:21-24`). 그리고 그 값은 `tree_json`에 **저장되어 있다**.
그러므로 R1은 "이름을 대문자로 바꾸기"가 아니라 **"상수 이름과 와이어 표현을 분리하기"** 다.

## 1. C1 — enum은 도메인 안에서 enum이다 (I13)

- `SavedQuery.reviewStatus: String` → `ReviewStatus`
- `ExecutionEvent.outcome: String` → `ExecutionOutcome`
- `.name` 비교 5곳 소멸. `ReviewStatus.entries.firstOrNull { it.name == … }` 도 파싱 헬퍼 하나로
- DTO도 enum을 싣는다 — Jackson이 이름 문자열로 직렬화하므로 **JSON은 한 글자도 바뀌지 않는다**

선례가 집 안에 있다: `ApprovalRequest.status: RequestStatus`가 이미 이 규약이고 Spring Data JDBC에서
동작한다. 즉 **새 기술을 도입하는 것이 아니라 세 엔티티를 한 규약으로 맞추는 것**이다.

**위험**: Spring Data JDBC의 enum ↔ VARCHAR 변환이 세 엔티티에서 다 동작하는지. `ApprovalRequest`가
증거지만 **읽기 경로까지 확인**한다(`findTop200ByOutcomeAndIdLessThanOrderByIdDesc(outcome: String, …)`
같은 파생 쿼리 시그니처가 enum을 받아야 한다).

## 2. C2 — 소문자 enum: 상수 이름과 와이어 표현 분리 (R1)

```kotlin
enum class RuleOp(@JsonValue val wire: String) {
    REQUIRES("requires"), BLOCKS("blocks"), JOINS("joins"),
    MUST_BE_WITHIN("must_be_within"), MUST_BE_MASKED("must_be_masked"),
}
```

**가장 위험한 커밋이다** — 이 값은 `tree_json`에 **이미 저장되어 있고** 프론트가 키로 쓴다.
그래서 완료 조건이 "컴파일된다"가 아니라 **"기존 와이어 형태로 왕복한다"** 다:

- 역직렬화: `{"op":"requires"}` → `RuleOp.REQUIRES` (Jackson의 enum `@JsonValue` 역방향이 실제로 되는지
  **확인**하고, 안 되면 `@JsonCreator`를 명시한다 — 되는 줄 알고 넘기지 않는다)
- 직렬화: `RuleOp.REQUIRES` → `"requires"`
- **기존 저장 데이터**: 시드로 들어간 규칙 트리가 그대로 읽히는지
- `RuleFlowIntegrationTest`가 이미 **생짜 맵으로 와이어 형태를 보낸다**(`"op" to "joins"`,
  `"combinator" to "all"`) — 이것이 그대로 감시자다. 손대지 않는다.

## 3. C3 — 열람 능력 (§7 위험 2: 플래그 → 능력)

`privileged: Boolean`이 컨트롤러 → 서비스로 29곳을 흐른다. 문제 셋:

1. **의미가 이름에 없다.** `visible(id, actor, true)`가 무엇을 허용하는지 호출부에서 안 보인다.
2. **정책이 두 벌**이다 — `QueryController.privileged(me)`와 `ApprovalController.privileged(me)`가
   각자 같은 판정을 적는다.
3. **누구나 `true`를 넘길 수 있다.** 권한이 인자이면 그 인자를 만드는 사람이 권한을 정한다.

→ `auth`에 **발급이 폐쇄된 능력 타입**을 둔다(P2의 패턴을 그대로 답습 — learning 018):

```kotlin
sealed interface Viewer {          // 밖에서 구현 불가(패키지 경계)
    val actor: String
    val seesEveryone: Boolean
}
// 구현체는 발급자와 같은 파일의 private class → 밖에서 생성 불가
fun viewerOf(user: AppUser): Viewer   // 유일한 발급 경로
```

이러면 **"권한을 스스로 선언할 수 없다"** 가 컴파일 시점 사실이 된다. `update`가 `privileged`를 받지
않는 결정(적대 검토 D7)도 시그니처에 드러난다 — `Viewer`를 받지 않으면 대행 여지가 없다.

**이름은 `Viewer`로 한다**: `privileged`가 실제로 통제하는 것은 **열람 스코프 하나**다(실측: 전 호출이
`visible()`·`list()`로 귀결). 능력을 넓게 잡아 `Actor`라고 부르면 나중에 실행·수정 권한까지 이 타입에
얹으려는 유혹이 생기고, 그것이 결정 14(대행 실행 불허)를 조용히 뒤집는 길이다.

**테스트 부담이 0에 가깝다**(실측: 테스트의 `privileged` 사용 0건) — 전부 HTTP 경로로 들어간다.

## 4. C4 — null 정리 (§4.8)

| 대상 | 조치 | 갚는 `!!` |
|---|---|---|
| `norm(String?): String?` | `norm(String): String` + `normOrNull(String?): String?` | **12** |
| `parsePredicate(): Predicate?` | 실패를 값으로. 호출부 3곳이 서로 **다른 정책**을 갖고 있고(등록 거부 / `Raw` 폴백 / null 반환) 지금은 그 차이가 `?:` 뒤에 숨어 있다 | 0(대신 이유가 살아난다) |

엔티티 id `!!` 21개는 **손대지 않는다** — §4.8이 별도 스펙으로 분리했고, 5개 패키지에 흩어져 있어
P3에 넣으면 이 단계가 무엇을 했는지 흐려진다. **증가만 막는다**(현재 36).

## 5. 커밋 계획

| # | 커밋 | 종료 조건 |
|---|---|---|
| C1 | enum이 영속 경계에서 enum | `.name` 비교 0 · JSON 응답 불변(회귀) · 296건 유지 |
| C2 | 소문자 enum ↔ 와이어 분리 | **기존 와이어 형태 왕복**(직렬화·역직렬화·저장 데이터) |
| C3 | 열람 능력 타입 | 발급 폐쇄가 컴파일 사실임을 **되돌려 실패로** 확인 |
| C4 | `norm` 두 정의역 + `parsePredicate` 타입 실패 | `norm(...)!!` 12 → 0, 전체 `!!` 감소 |

## 6. 검증 (A8)

**감시자마다 되돌려 실패한다** — 개수가 아니라 하나하나(retrospect 017 반성 2, memory 규율).

| 축 | 방법 |
|---|---|
| C1 응답 불변 | 기존 통합 테스트의 JSON 단정이 그대로 통과. 추가로 `reviewStatus`·`outcome` 문자열 값을 명시 단정 |
| C2 와이어 왕복 | 저장된 `tree_json` 형태를 그대로 역직렬화 → 직렬화 후 **문자열 동등** |
| C2 되돌려 실패 | `@JsonValue`를 떼면 그 왕복 테스트가 깨지는지 |
| C3 발급 폐쇄 | 밖에서 `Viewer` 구현·생성을 시도해 **컴파일 오류 메시지 확보** |
| C3 정책 단일 | 두 컨트롤러가 같은 발급 함수를 쓴다는 것을 구조로(중복 정의 0) |
| C4 정의역 분리 | `norm`이 두 정의역으로 갈렸고 호출부에 `!!`이 남지 않았는지 스캔 |
| 전체 | 296건 + 미사용 import 0 |

## 7. 위험

1. **C2가 저장 데이터를 깬다** — 가장 큰 위험. 왕복 테스트를 **먼저** 쓰고 나서 enum을 고친다.
2. **C1이 파생 쿼리 시그니처를 건드린다** — `findTop200ByOutcome…(outcome: String)`이 enum을 받아야 한다.
   Spring Data JDBC 변환이 파라미터 방향에서도 되는지 확인 후 진행.
3. **C3가 보안 경계다** — 능력을 넓게 잡으면 결정 14를 뒤집는 길이 생긴다. 열람 스코프로 좁혀 둔다.
4. **`!!` 개수를 목표로 삼는 굿하트** — §3의 A4 주석이 이미 경고한 것이다. `requireNotNull`·`as`로
   숫자만 줄이지 않는다. C4의 기준은 "정의역이 갈렸는가"이고 개수는 부산물이다.
