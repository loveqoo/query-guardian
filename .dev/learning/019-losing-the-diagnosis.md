# 019 — 진단을 잃는 네 가지 방법

**맥락**: spec 010 P3(enum·열람 능력·null 정리)에서 넷을 실측으로 만났다. 공통점은 코드가 **동작하는데
이유를 잃는다**는 것이다. 동작이 맞으면 테스트가 초록이라 이 결함은 통과 상태로 오래 산다.

---

## 1. `T?` 하나가 여러 정책을 뜻하면, 버린 것은 이유다

`parsePredicate(sql): Predicate?`의 `null`은 **오직 `catch (e: Exception)`에서만** 나왔다
(`toPredicate`는 non-null을 낸다 — 실측으로 시그니처 확인). 즉 `null`의 뜻은 정확히
**"예외가 났고 그 이유를 버렸다"** 였고, 다섯 호출부가 그것을 각자 다섯 정책으로 해석했다:
등록 거부 · 매핑 거부 · `Raw` 폴백 · fail-closed null · 텍스트 비교 폴백.

`?:` 한 줄은 그 자리에서 읽히므로 **정책이 숨은 것은 아니다**. 숨은 것은 이유다. 그래서 판정 기준은
"호출부가 헷갈리나"가 아니라 **"이 실패가 누군가의 수정 대상인가"** 다. 대상이면 이유를 값으로 만든다.

```kotlin
sealed interface PredicateParse {
    data class Parsed(val predicate: Predicate) : PredicateParse
    data class Unparsed(val reason: String) : PredicateParse
    /** 폴백이 이미 정해져 **이유가 정책을 바꾸지 않는** 자리만 쓴다 */
    val predicateOrNull: Predicate? get() = (this as? Parsed)?.predicate
}
```

`orNull` 편의 접근자를 **두되 용도를 KDoc에 못 박는다**. 두지 않으면 이유가 필요 없는 자리도 `when`을
쓰게 되어 그 `when`이 소음이 되고, 무제한으로 두면 전부 그것을 써서 타입을 만든 이유가 사라진다.

## 2. 같은 입력을 거절하는 두 검사는, **순서가 곧 사용자가 받는 이유다**

강제식 등록 검증의 순서가 이랬다:

```kotlin
require(!parser.predicateContainsSubquery(sample)) { "서브쿼리를 포함할 수 없습니다" }  // 파싱 실패 → true
when (parser.parsePredicate(sample)) { ... }                                          // 도달하지 못한다
```

`predicateContainsSubquery`가 파싱 실패에 fail-closed로 `true`를 내는 것은 **옳다**. 그래서 문법이 깨진
강제식은 "서브쿼리를 포함할 수 없습니다"를 받았고, 파싱 검사는 **죽은 코드**였다. 거절 집합은 정확한데
등록자는 엉뚱한 곳을 고치러 갔다.

규칙: **fail-closed 폴백이 있는 검사는 더 구체적인 진단보다 뒤에 둔다.** fail-closed는 "모르면 거절"이라
어떤 실패든 자기 이름으로 삼킨다. 그리고 같은 실패를 여러 검사가 잡을 수 있으면 **순서가 정책**이다
(spec 010의 "순서는 정책이다"가 게이트 밖에서도 성립한다).

증상 탐지법: 거절 테스트가 **상태 코드만** 단정하고 있으면 이 결함이 안 보인다. 400은 맞고 이유가 틀리다.
거절을 단정할 때 코드와 이유를 함께 단정한다 — 이유가 수정 대상이기 때문이다.

## 3. 단정 하나가 여러 실패를 덮으면, 메시지는 그중 하나의 이름만 댄다

```kotlin
require(predicate != null && requiredForm(predicate) != null) {
    "판정 미지원 형태의 FILTER는 아직 매핑할 수 없습니다 (컬럼 = 리터럴 / IN 단일값만 지원)"
}
```

치환 실패 · 파싱 실패 · 판정 미지원 셋을 덮고 **마지막 하나의 이름만** 말한다. `&&`로 이어진 단정은
메시지가 하나이므로 **조건 수만큼 거짓말할 수 있다**. 조건마다 갈라 각자 자기 이름을 대게 한다.

부수 효과로 `expression == null`을 뭉개고 있던 것도 드러났다 — 메시지에 `null`이 출력될 자리였다.

## 4. 주석이 주장하는 상관관계는, 코드로 만들 수 있으면 만든다

능력 타입에 값이 항상 같은 상수를 둘 뒀다:

```kotlin
/** [seesEveryone]과 오늘 값이 같지만 다른 질문이라 따로 둔다 */
val seesRawErrors: Boolean
// 구현체 둘이 각각 true/true, false/false를 손으로 유지
```

"값이 같다"는 **주석의 주장**이고 독자는 구현체를 교차 대독해 확인해야 한다. 파생으로 바꾸면 그 주장이
실행되는 문장이 된다 — 이름을 따로 둔 이유(다른 질문이므로 갈라질 수 있다)는 그대로 살아 있다:

```kotlin
val seesRawErrors: Boolean get() = seesEveryone   // 갈라지는 날 구현체가 한 줄 override
```

같은 뿌리의 더 나쁜 사례가 같은 단계에 있었다: **파생 프로퍼티를 저장한 것**. Jackson이 규칙 트리의
`targetTable`·`judged` 같은 파생 값을 `tree_json`에 함께 썼고, 읽을 때는 생성자 인자가 아니라
읽을 수 없는 필드였다. 터지지 않은 이유는 Spring Boot 기본값 `FAIL_ON_UNKNOWN_PROPERTIES=false`
**하나** — 우리가 정한 적 없는 남의 기본값이 fail-open을 막고 있었다(켜지면 모든 사용자 규칙이 조용히
평가에서 사라진다). 파생 값은 저장하지 않는다: 원본을 고치고 파생을 안 고치면 두 값이 어긋난 채 남는다.

## 5. 대조 — 갈라야 할 것과 두어야 할 것

`norm(String?): String?`을 `norm(String): String` + `normOrNull(String?): String?`으로 갈랐다.
단정 12개가 사라졌다(이름이 있는 것이 확실한 자리에서 매번 그 사실을 다시 주장하고 있었다).

같은 모양인 `SqlRewriter.normalize`는 **가르지 않았다** — 결과를 전부 `==`로 비교해 갚을 단정이 0곳이다.
기준은 개수가 아니라 **"정의역이 갈렸는가 + 갚을 것이 있는가"** 이고, 갚을 것이 없는 분할은 함수만 늘린다.
`!!` 개수를 목표로 삼으면 `requireNotNull`·`as`로 숫자만 줄이는 길이 열린다(굿하트).

부수: 정의역을 가르면 **컴파일러가 그 경계를 지킨다.**
`Argument type mismatch: actual type is 'String?', but 'String' was expected.`
(단, 자바 플랫폼 타입 `String!`은 통과한다 — 경계는 코틀린이 아는 널가능성에만 걸린다.)
