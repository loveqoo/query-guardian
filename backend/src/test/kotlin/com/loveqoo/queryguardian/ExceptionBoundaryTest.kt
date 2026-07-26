package com.loveqoo.queryguardian

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 P1 · 수용 기준 A3 — **게이트에서 예외 취급은 이름이 지정된 경계에만 있다** (불변식 I7).
 *
 * 게이트의 실패는 값이다(`GateStop`). 예외를 잡아 값으로 **번역하는 지점**만 예외를 알아도 되고,
 * 그 지점은 손에 꼽을 수 있어야 한다. 예전에는 같은 개념이 한 함수 안에서 네 문법으로 흩어져 있었고
 * (try/catch·지역 `blocked()`·5인자 헬퍼·`runCatching`) **그 다양성이 감사 누락을 숨겼다** —
 * "403인데 감사 0건"이 실제로 일어났고 코드 주석이 그 자리에 묘비로 남아 있다.
 *
 * ## 왜 소스 스캔인가
 *
 * ArchUnit은 바이트코드를 보는데 `try`/`catch`의 위치를 함수 단위로 묻는 API가 없고, `runCatching`은
 * 인라인되어 원래 모양이 사라진다. 그래서 **소스 규율 테스트**로 만든다. 휴리스틱임을 숨기지 않는다:
 * 주석·KDoc 줄은 걸러내고, 발견 위치의 **직전 `fun` 선언**을 감싸는 함수로 본다. 이 저장소의 포맷에서는
 * 결정적으로 동작하며, 아래 [`발견기가 실제로 함수를 짚는지`] 테스트가 그 전제를 스스로 검사한다.
 *
 * ## 패키지 이동으로 통과할 수 없다
 *
 * 허용 목록의 키는 **`파일.함수`** 다. 클래스를 다른 패키지로 옮겨도 검사는 따라가고, 허용된 파일에
 * 새 함수를 만들어 거기서 예외를 잡는 것도 통과하지 못한다.
 */
class ExceptionBoundaryTest {

    /**
     * 예외 취급으로 세는 문법. **`throw`가 들어 있는 것이 핵심이다** — I7은 "만들지도 잡지도 않는다"인데
     * 처음에는 `catch` 쪽만 봤다. 정작 이 저장소에서 사고를 낸 것은 *만든* 쪽이다(예외가 게이트를 그대로
     * 빠져나가 "403인데 감사 0건"). 잡는 것만 세면 불변식의 절반만 지킨다.
     *
     * 나머지 — `fold`·`getOrElse`는 `Result`에 쓰이면 예외 취급이지만 컬렉션에도 있다 —
     * 오탐 가능성을 알면서 넣는다. `query` 패키지에서 컬렉션 fold가 필요하면 그때 이 결정을 다시 본다
     * (오탐은 눈에 띄고, 누락은 안 띈다).
     *
     * **`orElseThrow`는 나중에 추가했다.** `\bthrow\b`는 대소문자를 구분하므로 `orElseThrow`의 `Throw`를
     * 놓쳤다. 그동안 발화한 이유는 그 표현이 있던 함수들에 *명시적* `throw`가 따로 있었던 **우연**이었고,
     * P3에서 404 계약을 `QueryService.load`로 뽑아내자 그 우연이 걷히며 사각지대가 드러났다 —
     * 게이트 패키지에서 예외를 **만드는** 문법 하나가 검사 밖에 있었다는 뜻이다(실측: 이 패키지에 1곳).
     */
    private val exceptionSyntax = Regex(
        """\btry\s*\{|\bcatch\s*\(|\bthrow\b|\borElseThrow\b|\brunCatching\b|""" +
            """\brecoverCatching\b|\bgetOrElse\b|\.fold\(""",
    )

    private val functionDecl = Regex(
        """^\s*(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|protected\s+)?(?:inline\s+)?(?:suspend\s+)?""" +
            """fun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(\w+)\s*\(""",
    )

    /**
     * 게이트에서 예외를 값으로 **번역해도 되는** 지점. 전부 "협력자가 던지는 것을 받아 `GateStop`으로
     * 바꾸는" 한 가지 일만 한다.
     */
    private val allowedBoundaries = mapOf(
        "GateStop.raise" to "GateStop → 예외로의 유일한 번역점. 경계(orRaise/orThrowWithoutAudit)에서만 불린다",
        "QueryService.ownedBy" to "소유자만 — 게이트 진입 전 조회 계약(ForbiddenException). " +
            "`visible`이 능력에 따라 여기로 갈라진다(P3: privileged → Viewer)",
        "QueryService.load" to "404 계약 — 없는 id 하나를 모든 조회가 같은 문장으로 거절한다",
        "QueryService.update" to "소유권·request_id 교체 거부 — 저장 계약(게이트 아님)",
        "QueryService.review" to "검토 결정의 사전조건 — 저장 계약(게이트 아님)",
        "GateSteps.checkAccess" to "AccessControl이 던지는 권한 차단을 값으로",
        "GateSteps.approvalOf" to "ApprovalGate가 던지는 승인 차단을 값으로 — 두 게이트가 승인을 " +
            "다른 위치에서 검사하므로(checkApproval/requireApproval) 번역점만 하나로 모았다",
        "QueryExecutionService.requireOwnExecution" to "ApprovalGate.requireOwned가 던지는 소유권 차단을 값으로",
        "QueryExecutionService.runQuery" to "JDBC 인프라 실패(ExecutionFailure)를 값으로 — 실행 경계",
        "QueryExecutionService.recordStop" to "감사 best-effort — 기록 실패가 원래 사유를 덮지 않게(I5)",
        "QueryExecutionService.visibleOrRecord" to "열람 차단을 값으로 — 시도 자체가 감사 대상이라 여기서 받는다",
    )

    private val gateSources: List<Path>
        get() = @Suppress("DEPRECATION")
        Path.of("src/main/kotlin/com/loveqoo/queryguardian/query").walk()
            .filter { it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `게이트의 예외 취급은 허용된 경계에만 있다`() {
        val found = scanGate()
        val unexpected = found.filterNot { it.key in allowedBoundaries }

        assertTrue(
            unexpected.isEmpty(),
            "게이트에 새 예외 취급이 생겼다. 실패는 값이어야 한다(GateStop) — 여기서 잡아야 할 이유가 " +
                "있다면 허용 목록에 **이유와 함께** 올려라:\n" +
                unexpected.entries.joinToString("\n") { (where, lines) -> "  $where → ${lines.joinToString(", ")}" },
        )
    }

    /**
     * **죽은 허용 항목은 지운다.** 남겨 두면 나중에 같은 이름의 함수가 생겼을 때 아무도 모르게 통과한다 —
     * 허용 목록이 스스로 구멍이 되는 방식이다.
     */
    @Test
    fun `허용 목록에 죽은 항목이 없다`() {
        val found = scanGate().keys
        val dead = allowedBoundaries.keys - found
        assertTrue(dead.isEmpty(), "허용 목록에 더 이상 존재하지 않는 항목이 있다: $dead")
    }

    /**
     * **이 테스트가 없으면 위 두 개는 공허하다.** 발견기가 함수를 못 짚으면(정규식이 어긋나면)
     * 모든 발견이 `?` 같은 이름으로 뭉쳐 허용 목록과 대조가 무의미해진다.
     * 그래서 알려진 자리 하나를 이름까지 확인한다 — 통과하는 이유를 고정하는 대조군이다.
     */
    @Test
    fun `발견기가 실제로 함수를 짚는지`() {
        val found = scanGate()
        assertTrue(
            "GateSteps.checkAccess" in found,
            "발견기가 알려진 경계를 못 짚었다 — 정규식이 어긋났다면 다른 테스트도 공허하다. 발견: ${found.keys}",
        )
        assertEquals(
            emptySet(), found.keys.filter { it.endsWith(".?") }.toSet(),
            "함수를 특정하지 못한 발견이 있다 — 그 자리는 검사되지 않는다",
        )
    }

    // ---- 스캐너 ------------------------------------------------------------

    /** `파일.함수` → 발견된 줄 번호들. */
    private fun scanGate(): Map<String, List<Int>> {
        val hits = mutableMapOf<String, MutableList<Int>>()
        for (path in gateSources) {
            val file = path.name.removeSuffix(".kt")
            var currentFun = "?"
            var inBlockComment = false
            path.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                // KDoc·주석은 문법이 아니다 — `runCatching`을 설명하는 문장이 위반으로 잡히면 안 된다.
                if (line.startsWith("/*")) inBlockComment = true
                val isComment = inBlockComment || line.startsWith("//") || line.startsWith("*")
                if (line.endsWith("*/")) inBlockComment = false
                if (isComment) return@forEachIndexed

                functionDecl.find(raw)?.let { currentFun = it.groupValues[1] }
                if (exceptionSyntax.containsMatchIn(raw)) {
                    hits.getOrPut("$file.$currentFun") { mutableListOf() } += i + 1
                }
            }
        }
        return hits
    }
}
