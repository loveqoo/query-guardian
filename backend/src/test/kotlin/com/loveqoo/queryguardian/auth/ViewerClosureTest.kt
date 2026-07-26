package com.loveqoo.queryguardian.auth

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * spec 010 **P3 · §7 위험 2** — 열람 능력([Viewer])의 **발급이 실제로 닫혀 있는가**.
 *
 * ## 컴파일러가 이미 막은 것은 여기서 검사하지 않는다
 *
 * 세 경로는 컴파일 오류다(실측 — 프로브 파일을 심어 메시지를 받고 삭제했다):
 *
 * | 시도 | 컴파일러의 말 |
 * |---|---|
 * | `auth` 밖에서 `Viewer` 구현 | `A class can only extend a sealed class or interface declared in the same package.` |
 * | 구현체 직접 생성 | `Cannot access 'data class GovernanceViewer : Viewer': it is private in file.` |
 * | 옛 `list(actor, true)` 호출 | `Argument type mismatch: actual type is 'String', but 'Viewer' was expected.` |
 * | **테스트 모듈**에서 `Viewer` 구현 | `Extending sealed classes or interfaces from a different module is prohibited.` |
 *
 * 마지막 줄이 이 파일의 검사 방식을 정한다: 테스트도 능력을 만들 수 없으므로 **가짜 능력을 심어
 * 정책을 단위 테스트할 수 없다**. 능력의 *값*이 옳은지는 HTTP 경계가 잰다
 * (`ExecutionFlowIntegrationTest`의 오류 원문·스코프 테스트, `TestDebtIntegrationTest`의 목록 스코프).
 * 여기서 재는 것은 **발급 경로의 폐쇄**뿐이다.
 *
 * ## 왜 리플렉션이 아니라 소스 스캔인가 (검토 2채널이 독립적으로 짚은 구멍)
 *
 * 처음에는 `AuthService::class.declaredMemberFunctions`에서 `Viewer`를 돌려주는 함수가 하나인지 봤다.
 * **뚫린다.** `AuthService`에는 이미 companion object가 있고(`SESSION_KEY`), companion 함수는 같은
 * 파일의 file-private 구현체를 볼 수 있는데 `declaredMemberFunctions`에는 나타나지 않는다.
 * 실측으로 심어 확인했다 — 아래 함수를 companion에 넣으면 **네 테스트가 전부 통과했다**:
 *
 * ```
 * fun viewerOf(user: AppUser): Viewer =
 *     if (user.role == Role.ANALYST) SelfViewer(user.id) else GovernanceViewer(user.id)
 * ```
 *
 * `AppUser`는 공개 data class이므로 그 뒤는 `viewerOf(AppUser(..., role = ADMIN))` — 세션 없이 특권이다.
 * KDoc이 "이름만 바꿔 되살아난다"고 경고한 바로 그 구멍이 감시자를 지나갔다.
 * 리플렉션으로 세려면 형태를 다 열거해야 한다(멤버·companion·최상위·확장·프로퍼티·타입 추론 생략).
 * 열거는 빠뜨리는 쪽으로 실패한다.
 *
 * 그래서 **참 불변식**을 잰다: *어떤 모양의 발급자든 구현체를 **생성**해야 한다.* 구현체는 file-private이니
 * 생성은 `AuthService.kt` 안에서만 가능하고, 그 파일의 생성 지점을 세면 형태와 무관하게 전수가 된다.
 * 소스 스캔 관용구는 이 집에 이미 있다(`ExceptionBoundaryTest`).
 *
 * ## 되돌려 실패 (A8)
 *
 * `sealed`를 떼면 첫째가, 구현체의 `private`를 떼면 둘째가, 이름을 바꾸면 셋째가,
 * `currentViewer` 밖(companion·최상위·프로퍼티 어디든)에서 구현체를 만들면 넷째가 실패한다.
 */
class ViewerClosureTest {

    /** 유일한 발급 함수. 구현체 생성은 이 안에서만 일어나야 한다. */
    private val soleIssuer = "currentViewer"

    /** 구현체가 사는 파일 = 생성이 가능한 유일한 파일(file-private이므로). */
    private val issuerFile = Path.of("src/main/kotlin/com/loveqoo/queryguardian/auth/AuthService.kt")

    private fun leavesOf(type: KClass<*>): List<KClass<*>> =
        if (type.isSealed) type.sealedSubclasses.flatMap { leavesOf(it) } else listOf(type)

    private val impls = leavesOf(Viewer::class)

    @Test
    fun `능력 타입은 봉인돼 있다`() {
        assertEquals(
            true, Viewer::class.isSealed,
            "Viewer의 봉인이 풀렸다 — 어느 패키지에서든 seesEveryone = true를 스스로 선언할 수 있다",
        )
    }

    @Test
    fun `모든 구현체는 발급자 파일 밖에서 생성할 수 없다`() {
        val leaked = impls.filter { it.visibility != KVisibility.PRIVATE }
            .map { "${it.simpleName}(${it.visibility})" }
        assertEquals(
            emptyList(), leaked,
            "구현체가 private이 아니다 — auth 패키지 안에서 발급 함수를 거치지 않고 능력을 만들 수 있다",
        )
    }

    /**
     * 대조군 — 위 둘이 "빈 목록이라 통과"가 아니라는 것. **능력의 값은 여기서 재지 않는다**
     * (테스트 모듈은 능력을 만들 수 없다). 값은 HTTP 경계가 잰다.
     */
    @Test
    fun `발견기가 실제 구현체를 짚는다`() {
        assertEquals(
            setOf("GovernanceViewer", "SelfViewer"),
            impls.mapNotNull { it.simpleName }.toSet(),
            "구현체 집합이 바뀌었다 — 새 능력 조합을 만들었다면 그것이 의도인지 확인하라",
        )
    }

    /**
     * **발급 경로가 하나다.** 구현체를 생성하는 줄이 [soleIssuer] 밖에 있으면 그것이 두 번째 발급자다 —
     * 그 함수의 입력이 새 신뢰 근거가 되고, 세션이 아닌 무언가로 특권을 얻는 길이 열린다.
     *
     * 파일 레벨 상수(`private val SYSTEM = GovernanceViewer("system")`)도 여기서 잡힌다 —
     * 감싸는 함수가 없으므로 [soleIssuer]와 일치하지 않는다.
     */
    @Test
    fun `구현체를 생성하는 곳은 발급 함수 하나다`() {
        val sites = constructionSites()
        assertEquals(
            setOf(soleIssuer), sites.keys,
            "구현체 생성이 $soleIssuer 밖에서 일어난다 — 능력은 인증된 요청에서만 나와야 한다. 발견: $sites",
        )
        // 대조군: 스캐너가 실제로 생성 지점을 짚었는가. 0건이면 위 단정은 공허하다.
        assertEquals(
            impls.size, sites.getValue(soleIssuer).size,
            "생성 지점 수가 구현체 수와 다르다 — 스캐너가 어긋났거나 발급 분기가 바뀌었다: $sites",
        )
    }

    // ---- 스캐너 ------------------------------------------------------------

    /** `함수명` → 구현체를 생성하는 줄 번호들. 감싸는 함수는 직전 `fun` 선언으로 본다. */
    private fun constructionSites(): Map<String, List<Int>> {
        val names = impls.mapNotNull { it.simpleName }
        val construction = Regex("""\b(${names.joinToString("|")})\s*\(""")
        val functionDecl = Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(\w+)\s*\(""")

        val hits = mutableMapOf<String, MutableList<Int>>()
        var currentFun = "<파일 레벨>"
        var inBlockComment = false
        issuerFile.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            // 주석은 코드가 아니다 — KDoc이 구현체 이름을 언급해도 생성이 아니다.
            if (line.startsWith("/*")) inBlockComment = true
            val isComment = inBlockComment || line.startsWith("//") || line.startsWith("*")
            if (line.endsWith("*/")) inBlockComment = false
            if (isComment) return@forEachIndexed

            functionDecl.find(raw)?.let { currentFun = it.groupValues[1] }
            // 선언(`private data class GovernanceViewer(`)은 생성이 아니다.
            if (Regex("""\bclass\s+\w""").containsMatchIn(raw)) return@forEachIndexed
            if (construction.containsMatchIn(raw)) {
                hits.getOrPut(currentFun) { mutableListOf() } += i + 1
            }
        }
        return hits
    }
}
