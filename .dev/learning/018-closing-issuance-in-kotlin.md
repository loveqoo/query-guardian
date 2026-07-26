# 018 — Kotlin에서 "이 타입은 여기서만 만든다"를 실제로 닫는 법 (spec 010 P2)

게이트 단계 증거의 발급 권한을 닫으면서 얻은 것. 다음에 **"이 값은 검사를 통과해야만 존재한다"**
를 타입으로 표현할 때 그대로 쓴다.

## 1. 폐쇄 장치 셋은 **범위가 다르다** — 섞어 쓰면 착각한다

| 장치 | 막는 것 | **실제 범위** |
|---|---|---|
| `sealed` | 밖에서 **구현** | **패키지** + 모듈 (Kotlin 1.5+. 파일이 아니다) |
| `private` 최상위 클래스 | 밖에서 **생성** | **파일** |
| `internal` | 둘 다 | **모듈** |

셋을 "밖에서 못 한다"로 뭉뚱그리면 경계가 실제보다 좁다고 착각한다. 나는 `sealed`도 파일 단위인 줄 알고
계약과 구현을 한 파일에 묶어 놓고 그것을 "폐쇄의 조건"이라고 적었다 — **가짜 제약이 그 안의 나쁜
구조까지 정당화했다.**

확인 비용은 스크래치 파일 하나다:

```kotlin
// 같은 패키지, 다른 파일
private class Forged(...) : SealedType { ... }   // 컴파일되면 sealed는 파일 단위가 아니다
```

### 1-a. 이 논거를 다시 쓰려 할 때 (P3에서 **재발했다**)

위 문단을 쓴 다음 단계(P3)에서 **같은 논거를 계약 배치에 다시 썼다.** 열람 능력 `Viewer`를
발급자 파일(`AuthService.kt`)에 얹고 "발급자와 같은 파일이어야 한다"를 근거로 삼았다. 그 근거는
**구현체에만** 성립한다 — 계약은 옮겨도 폐쇄가 1비트도 줄지 않는다. 더 나쁜 것은 그 파일의 KDoc이
위 표를 **옳게 싣고 있으면서 결론만 어긋났다**는 점이다.

그래서 규칙을 문장으로 못 박는다. **"같은 파일이어야 한다"는 오직 `private` 구현체에만 쓴다.**
계약(sealed 인터페이스)에 그 말을 쓰고 있으면 그것은 가짜 제약이다.

배치의 기준은 폐쇄가 아니라 **파일 이름이 약속한 것**이다: `GateEvidence.kt`(계약) ↔ `GateSteps.kt`
(발급+구현체)가 그 선례이고, `Viewer.kt` ↔ `AuthService.kt`도 같은 배치로 맞췄다.
`AuthService.kt` 129줄의 42%가 값 타입 에세이여서, 세션 인증을 읽으러 온 사람이 그것을 먼저 통과해야 했다.

## 2. friend 가시성이 없을 때의 조립법

Kotlin에는 C++ friend·Java 패키지 private에 해당하는 "이 파일/클래스에만 열기"가 없다. 그래서 **겹쳐 쓴다**:

```
전용 패키지 (예: query.gate)
├── Contracts.kt   sealed interface …          ← 밖에서 구현 불가 (패키지 경계)
└── Issuer.kt      private class …Impl         ← 밖에서 생성 불가 (파일 경계)
                   class Issuer { fun step(): Contract = …Impl(...) }
```

**전용 패키지가 핵심이다.** 같은 패키지에 다른 협력자(서비스·컨트롤러)가 함께 살면 `sealed`가
그들에게 열려 있으므로 폐쇄가 처음부터 샌다. 패키지를 좁히는 것이 `internal`보다 정확하다 —
`internal`은 모듈 전체다.

## 3. "구조로 불가능"의 **층위를 정확히 적어라**

`private` 구현체는 JVM에서 **package-private 클래스 + public 생성자**로 컴파일된다(`javap`로 확인).
모듈 시스템을 안 쓰면 `setAccessible(true)` 리플렉션은 뚫는다.

그러니 정확한 서술은 이것이다: **"평범한 Kotlin 코드가 실수로 건너뛰는 것은 컴파일 오류다."**
리플렉션 가능한 코드는 이미 무엇이든 할 수 있으므로 이 경계의 목표가 아니다. 목표를 적어 두지 않으면
나중에 "리플렉션으로 뚫린다"는 지적에 방어할 근거가 없다.

## 4. ArchUnit 필드 규칙은 **제네릭 소거에 눈이 멀다**

`noFields().should().haveRawType(X::class.java)`는 JVM 필드 디스크립터를 본다. 그래서
`List<X>`·`GateOutcome<X>?`·`Map<K, List<X>>`를 **전부 통과시킨다** — 그리고 값을 보관하는 가장
자연스러운 모양이 하필 그것들이다. 사각지대가 위협과 정확히 겹친다.

타입 인자를 재귀로 훑어야 한다. **와일드카드를 빠뜨리면 절반만 잡는다**:

```kotlin
private fun mentions(type: JavaType, fqn: String): Boolean = when {
    type.name == fqn -> true
    type is JavaParameterizedType -> type.actualTypeArguments.any { mentions(it, fqn) }
    type is JavaWildcardType -> (type.upperBounds + type.lowerBounds).any { mentions(it, fqn) }
    else -> false
}
```

`Box<out T>`처럼 **공변 선언**이면 필드 시그니처가 `Box<? extends X>`가 되어 타입 인자가 클래스가
아니라 와일드카드다. 첫 판이 `List<X>`는 잡고 `Box<X>?`는 놓친 이유가 그것이었다.

## 5. ArchUnit 규칙이 **자기 자신을 위반**할 수 있다

"패키지 밖은 이 타입을 의존하지 마라"를 클래스 리터럴(`X::class.java`)로 쓰면, 그 규칙을 담은
**테스트 클래스가 곧 위반**이다(리터럴이 곧 의존이다). 회피책 둘:

- 문자열 FQN — **이름이 어긋나면 조용히 아무것도 안 잡는다**(리팩터링에 취약). 쓰지 마라.
- `@AnalyzeClasses(importOptions = [ImportOption.DoNotIncludeTests::class])` — 대상을 프로덕션으로
  좁히면 리터럴을 쓸 수 있고 **컴파일러가 이름을 지킨다**. 이쪽이 맞다.

규칙 대상이 프로덕션뿐인 것들은 **별도 테스트 클래스**로 모으는 편이 낫다.

## 6. 순서가 정책이라 갈릴 때 — **타입 파라미터가 "무엇을 통과했는지" 기억한다**

두 진입점이 같은 검사를 **다른 순서**로 한다면 사슬 하나로 못 합친다. 그렇다고 순서 강제를 포기할
필요는 없다:

```kotlin
sealed interface Authorized : Parsed
sealed interface ApprovalCovered : Authorized          // 승인 검사는 권한 검사를 포함한다
sealed interface Judged<out Prior : Authorized> { val prior: Prior }

fun <Prior : Authorized> judge(prior: Prior): Judged<Prior>   // 둘 다 받는다
fun resolveMapping(judged: Judged<ApprovalCovered>)           // 승인 통과분만 받는다
```

한쪽 게이트는 `Judged<Authorized>`를, 다른 쪽은 `Judged<ApprovalCovered>`를 얻고, **뒷단계가 요구하는
쪽만** 진행한다. 런타임 플래그로 같은 것을 하려면 검사가 실행 시점으로 내려간다.

**타입 파라미터 이름을 필드명과 맞춰라**(`Prior`/`prior`) — `P` 한 글자면 KDoc을 읽어야 뜻이 산다.

## 7. CI 유무가 **"테스트로 지킴 vs 컴파일러로 지킴"의 선택을 바꾼다**

ArchUnit·리플렉션 규칙은 **누군가 테스트를 돌릴 때만** 발화한다. CI가 없으면 그 "누군가"가 보장되지
않으므로, 컴파일러로 올릴 수 있는 것은 전부 올리는 편이 값이 크다(전용 패키지·sealed·타입 파라미터).
남는 규칙은 **컴파일러가 표현할 수 없는 성질**뿐이어야 한다.

착수 전에 `.github/workflows` 유무를 한 번 보는 것이 설계 결정을 바꾼다.

## 8. 감시자는 **하나하나** 되돌려 실패시켜라

"이번 작업에서 되돌려 실패를 세 번 했다"는 안전을 주지 않는다. 안 해 본 하나가 하필 뚫려 있다.
새로 만든 감시자마다 **그것이 잡아야 할 것을 심어 보고** 실패를 확인한다 — 새로 쓴 검사 로직 자체도
틀릴 수 있다(위 4의 와일드카드가 그 사례다).

### 8-a. 그것도 부족했다 — **감시자가 약속한 범위의 경계 밖에 심어라** (P3)

P3에서 위 규율을 지켰다. 새 감시자 네 개를 하나씩 되돌려 넷 다 실패를 확인했다. **그런데도 뚫렸다.**

발급 경로 감시자를 `AuthService::class.declaredMemberFunctions`로 만들고 KDoc에는 *"그 파일의 선언만
훑으면 발급 경로 전수 검사"* 라고 적었다. `declaredMemberFunctions`가 보지 못하는 것이 넷이다 —
**companion 함수 · 최상위 함수 · 확장 함수 · 프로퍼티**. 그리고 그 클래스에는 이미 companion이 있었다.
심어서 재 봤더니 컴파일되고 **네 테스트가 전부 통과**했다:

```kotlin
companion object {
    const val SESSION_KEY = "qg.userId"
    fun viewerOf(user: AppUser): Viewer =            // 공개 data class를 받는다 = 세션 없이 특권
        if (user.role == Role.ANALYST) SelfViewer(user.id) else GovernanceViewer(user.id)
}
```

즉 **되돌려 실패는 감시자가 *존재*하는지만 잰다. 감시자가 *약속한 범위*는 재지 않는다.**
그래서 두 단계로 쓴다:

1. 감시자가 잡는다고 한 것을 되돌려 실패시킨다.
2. **KDoc이 약속한 범위의 경계 밖에** 심어 본다. 통과하면 KDoc을 좁히거나 감시자를 넓힌다.

리플렉션으로 "형태 열거"를 하면 이 실패가 반복된다 — 열거는 빠뜨리는 쪽으로 실패한다. 대신 **참 불변식**을
찾아 그것을 재라. 여기서는 *어떤 모양의 발급자든 구현체를 **생성**해야 한다*였고, 구현체가 file-private이니
그 파일의 **생성 지점을 소스로 세면** 형태와 무관하게 전수가 된다:

```kotlin
// 구현체 이름으로 생성 지점을 찾고, 감싸는 함수가 유일한 발급자인지 본다
private val construction = Regex("""\b(${implNames.joinToString("|")})\s*\(""")
// 파일 레벨 상수(`private val SYSTEM = GovernanceViewer("system")`)도 함께 잡힌다
```

두 형태(companion 함수·파일 레벨 상수)로 되돌려 실패 확인. 소스 스캔 관용구가 이미 집에 있었는데
(`ExceptionBoundaryTest`) 리플렉션을 먼저 잡은 것이 실수였다 — **선례 탐색이 리플렉션 API 학습보다 싸다.**

부수로 알게 된 제약: `sealed`는 **모듈**도 요구하므로 테스트 소스에서 구현이 막힌다
(`Extending sealed classes or interfaces from a different module is prohibited.`).
그래서 가짜 능력을 심어 정책을 단위 테스트할 수 없다 — **정책 값은 HTTP 경계에서 재고, 폐쇄만 여기서 잰다.**
이 제약을 먼저 알면 테스트 전략이 달라지므로, 능력 타입을 만들 때 이 프로브를 먼저 돌려라.
