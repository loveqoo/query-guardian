package com.loveqoo.queryguardian.auth

/**
 * **열람 능력** (spec 010 P3 · §7 위험 2) — "누구의 것을 볼 수 있는가"를 나르는 값.
 *
 * 예전에는 `privileged: Boolean`이 컨트롤러에서 서비스로 흘렀다. 셋이 문제였다:
 * ⑴ 이름에 의미가 없어 `visible(id, actor, true)`가 무엇을 허용하는지 호출부에서 보이지 않았다
 * ⑵ 같은 역할 판정이 세 곳에 복제되어 있었다(두 컨트롤러 + 아무도 쓰지 않던 `isSteward`)
 * ⑶ **누구나 `true`를 넘길 수 있었다** — 권한이 인자이면 그 인자를 만드는 사람이 권한을 정한다
 *
 * 폐쇄는 세 겹의 겹침이다: `sealed`(패키지) + file-private 구현체(파일) + 공개 발급 경로 하나.
 * 각 장치의 **범위와 한계**는 learning 018에 있다 — 특히 이 셋을 하나로 뭉쳐 말하면 가짜 제약이 된다.
 * 합치면 **능력은 인증된 요청에서만 나온다**([AuthService.currentViewer])가 컴파일 시점 사실이 된다.
 *
 * `AppUser`를 받는 발급 함수를 노출하지 않는 이유가 여기 있다 — `AppUser`는 공개 data class이므로
 * `role = ADMIN`으로 손수 만들 수 있고, 그것을 받아 능력을 주면 ⑶번 구멍이 이름만 바꿔 되살아난다.
 *
 * **`actor`를 함께 싣는다.** 예전에는 `(actor, privileged)` 두 인자가 따로 흘러서 A의 행위자와 B의
 * 특권을 짝지어 넘길 여지가 있었다. 한 값이면 그 불일치가 표현 불가능해진다.
 *
 * 이름을 `Actor`가 아니라 `Viewer`로 둔 것도 결정이다: 이 능력이 통제하는 것은 **열람 스코프**다.
 * 넓게 부르면 나중에 실행·수정 권한을 여기 얹으려는 유혹이 생기고, 그것이 결정 14(대행 실행 불허)를
 * 조용히 뒤집는 길이다. (`delete`가 이 능력을 받는 것은 그 경계의 예외이며 미결 항목이다 —
 * [AuthService.currentViewer] 아래 `QueryService.delete` 주석과 백로그를 보라.)
 *
 * 계약을 발급자 파일에서 뗀 이유: `sealed`는 **패키지** 단위이므로 여기로 옮겨도 폐쇄가 줄지 않는다.
 * 게이트 선례(`GateEvidence.kt` ↔ `GateSteps.kt`)와 같은 배치다 — P2에서 "같은 파일이어야 한다"고
 * 적었던 것이 가짜 제약이었고, learning 018 §1이 그 정정이다.
 */
sealed interface Viewer {
    /** 행위자 id — 감사와 소유권 판정의 주체. */
    val actor: String

    /** 남이 저장한 것까지 본다. STEWARD/ADMIN에게만 — 검토가 그들의 직무다(결정 15). */
    val seesEveryone: Boolean

    /**
     * 오류 **원문**을 본다. MySQL 오류는 데이터 값을 에코하므로 일반 사용자에게는 분류 코드까지만(spec 008 §6).
     *
     * 이름을 따로 두는 이유는 **다른 질문**이기 때문이다 — 하나로 합치면 "원문도 보여주자"는 변경이
     * 행 열람 범위까지 조용히 넓힌다. 다만 오늘은 같은 축이라 **파생시킨다**: 상관관계를 주석으로
     * 주장하면 독자가 구현체를 교차 대독해 확인해야 하고, 그 주장은 손으로 유지된다.
     * 갈라지는 날 구현체가 이 한 줄을 override하며, 그때 `seesEveryone`은 손대지 않는다.
     */
    val seesRawErrors: Boolean get() = seesEveryone
}
