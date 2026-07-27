package com.loveqoo.queryguardian.ir

/**
 * 강제식의 `{col}` 자리표시자와 **허용 형태 렌더링** — 공용 어휘(`ir`)에 둔다.
 *
 * 이 토큰과 치환이 예전에는 **세 곳**에 흩어져 있었다: `catalog.Expressions`,
 * `RewriteVerifier`의 마스킹 검증(⑷), 그리고 spec 012 P0가 추가한 판정 쪽.
 * 한 곳에서 규칙이 바뀌면 나머지가 조용히 갈라진다(learning 011: 식별자 비교 한 곳의 누락이
 * 금지 목록 전체를 무효화). 어휘는 아무 계층도 의존하지 않으므로 여기가 유일하게 셋 다 볼 수 있는 자리다.
 */
const val COL_PLACEHOLDER = "{col}"

/**
 * 강제식 하나가 허용하는 **표기 형태들**.
 *
 * `mask_email({col})` → `mask_email(email)`, `mask_email(u.email)`.
 * 한정자 유무를 둘 다 내주는 이유: 사용자가 한정자를 붙여 쓰는 것이 자연스럽고, 비교하는 쪽에서
 * 한정자를 벗기면 `other.email`까지 통과하기 때문이다. **여기서 다 내주는 것이 안전한 쪽이다.**
 *
 * `{col}`이 없으면 빈 집합 — 무엇도 정답으로 인정하지 않는다(fail-closed).
 */
fun forcedExpressionForms(template: String, instanceKey: String, column: String): Set<String> =
    setOfNotNull(
        forcedExpressionForm(template, qualifier = null, column = column),
        forcedExpressionForm(template, qualifier = instanceKey, column = column),
    )

/**
 * **한 형태만** 렌더링한다 — 제안에 쓴다.
 *
 * 판정은 여러 형태를 인정해야 하지만(위), 제안은 **하나를 골라 줘야** 한다. 목록을 주면 사용자가
 * 고르는 일이 남고, 그것은 추천이 아니라 문제의 재출제다.
 *
 * `{col}`이 없으면 null — 정답을 만들 수 없다는 사실을 숨기지 않는다(fail-closed).
 */
fun forcedExpressionForm(template: String, qualifier: String?, column: String): String? =
    if (!template.contains(COL_PLACEHOLDER)) null
    else template.replace(COL_PLACEHOLDER, if (qualifier == null) column else "$qualifier.$column")
