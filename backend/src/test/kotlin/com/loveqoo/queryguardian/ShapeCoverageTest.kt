package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.lint.LintService
import com.loveqoo.queryguardian.rules.InMemoryTableCatalog
import com.loveqoo.queryguardian.rules.RequiredForm
import com.loveqoo.queryguardian.rules.RuleCondition
import com.loveqoo.queryguardian.rules.RuleEngine
import com.loveqoo.queryguardian.rules.RuleGroup
import com.loveqoo.queryguardian.rules.RuleOp
import com.loveqoo.queryguardian.rules.RuleScope
import com.loveqoo.queryguardian.rules.Severity
import com.loveqoo.queryguardian.rules.UserRule
import com.loveqoo.queryguardian.rules.UserRuleEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **표현 형태 커버리지** — 같은 의도를 여러 형태로 쓸 때 판정이 일관된가.
 *
 * ## 왜 이 파일이 필요한가
 *
 * SQL은 같은 뜻을 여러 형태로 쓸 수 있다. 그래서 룰은 **형태를 열거**하는 대신 **의도를 봐야** 하는데,
 * 이 엔진의 두 절반이 서로 다른 방식으로 만들어져 있다(실측):
 *
 * | 절반 | 판정 방식 | 표현이 낯설면 |
 * |---|---|---|
 * | **금지**(`no-blocked-column`·`must-be-masked`) | `scope.columnRefs` — IR이 **정규화한 참조 집합** | 놓치면 **데이터가 나간다** |
 * | **요건**(`require-partition-key`·`require-predicate`) | `scope.whereConjuncts`에서 **모양 맞추기**(`EQ`/`InList`/`Between`) | 못 찾으면 **과차단** |
 *
 * 실패 방향이 반대라서, 금지 쪽은 정규화로 단단해졌고 요건 쪽은 열거로 남았다. 이 파일은 그 차이를
 * **숫자로** 만든다. 형태만 적으면 현재 동작을 정답으로 축복하게 되므로, 각 형태에 **"옳은 판정"과
 * 그 근거**를 적는다.
 *
 * ## 결과를 세 갈래로 가른다
 *
 * - **일치**: 기대와 같다.
 * - **과차단**(기대 PASS / 실제 BLOCK): 정당한 쿼리를 막는다. 방향은 안전하나 사용자는 "허용되는
 *   표현"을 추측해야 하고, 그 추측은 룰을 우회하는 법을 익히는 것과 같은 학습이다.
 * - **누락차단**(기대 BLOCK / 실제 PASS): **위험**. 하나라도 나오면 이 테스트는 실패한다.
 *
 * 과차단은 [KNOWN_OVERBLOCK]으로 고정한다 — 새로 생기면 실패하고, 고치면 목록에서 빼야 통과한다
 * (learning 015의 양방향 안전망: 구조 변경엔 감시자, 계약 변경엔 변경 목록).
 *
 * ## 범위와 한계 (부풀리지 않는다)
 *
 * - 시스템 룰 7종은 [Fixtures]가, 사용자 정의 규칙 4종(`joins`·`requires`·`blocks`·`must_be_masked`)은
 *   축 K가 태운다. **형태 하나에 규칙 하나만** 태운다 — 넷을 함께 태웠더니 서로 간섭해 무엇이 막았는지
 *   알 수 없었다(실측).
 * - 픽스처 카탈로그: `users.ssn` 차단 · `users.email` 마스킹 · `user_events.event_date` 파티션 키 ·
 *   `user_events`는 purpose=marketing일 때 `consent_yn = 'Y'` 필수. 아는 테이블은 두 개뿐이므로
 *   다른 이름을 쓰면 `unknown-table`이 먼저 걸린다.
 * - `SELECT *`는 `no-select-star`가 먼저 잡는다 — 별 확장을 통한 컬럼 추적은 이 파일로 알 수 없다.
 * - `LIMIT`이 없으면 `require-limit`이 걸리므로 모든 형태에 붙였다.
 */
class ShapeCoverageTest {

    enum class Verdict { PASS, BLOCK }

    // ── 사용자 정의 규칙 픽스처 ─────────────────────────────────────────────────
    //
    // [Fixtures]는 시스템 룰 7종만 태운다. 사용자 규칙(`joins`·`requires`·`blocks`·`must_be_masked`)은
    // **다른 평가기**(`UserRuleEvaluator`)를 지나므로 그 축은 위 형태들이 재지 못했다(retrospect 019가
    // 남긴 구멍). 여기서 채운다.
    //
    // **시스템 룰과 겹치지 않는 컬럼을 쓴다** — `users.phone`·`users.name`·`users.created_at`.
    // 겹치면 어느 룰이 막았는지 알 수 없어 측정이 무의미해진다.
    private val ruleCatalog = InMemoryTableCatalog(
        partitionKeys = mapOf("user_events" to listOf("event_date")),
        blocked = mapOf("users" to setOf("ssn")),
        masked = mapOf("users" to setOf("email")),
        tables = setOf("user_events", "users"),
        // requires 판정용 정규형: `users.created_at = '2026-01-01'`
        conditionPredicates = mapOf(901L to RequiredForm("created_at", "2026-01-01")),
    )

    private val userRules = listOf(
        UserRule(
            id = 1, name = "조인 필수", scope = RuleScope.MULTI, enabled = true,
            tree = RuleGroup(RuleGroup.Combinator.ALL, listOf(
                RuleCondition(RuleOp.JOINS, Severity.BLOCK, table = "users", column = "id",
                    refTable = "user_events", refColumn = "id"),
            )),
        ),
        UserRule(
            id = 2, name = "생성일 조건 필수", scope = RuleScope.SINGLE, enabled = true,
            tree = RuleGroup(RuleGroup.Combinator.ALL, listOf(
                RuleCondition(RuleOp.REQUIRES, Severity.BLOCK, table = "users", column = "created_at", defId = 901L),
            )),
        ),
        UserRule(
            id = 3, name = "전화번호 조회 금지", scope = RuleScope.SINGLE, enabled = true,
            tree = RuleGroup(RuleGroup.Combinator.ALL, listOf(
                RuleCondition(RuleOp.BLOCKS, Severity.BLOCK, table = "users", column = "phone"),
            )),
        ),
        UserRule(
            id = 4, name = "이름 마스킹 필수", scope = RuleScope.SINGLE, enabled = true,
            tree = RuleGroup(RuleGroup.Combinator.ALL, listOf(
                RuleCondition(RuleOp.MUST_BE_MASKED, Severity.BLOCK, table = "users", column = "name"),
            )),
        ),
    )

    /**
     * **규칙 하나만 태운다.** 넷을 함께 태웠더니 서로 간섭했다 — `users`만 조회해도 조인 규칙이 발화해서
     * "이 형태가 무엇 때문에 막혔는지" 알 수 없었다(실측). 형태 하나가 재는 것은 규칙 하나여야 한다.
     */
    private fun serviceFor(ruleId: Int) = LintService(
        Fixtures.parser,
        RuleEngine.withDefaultRules(userRuleEvaluator = UserRuleEvaluator { userRules.filter { r -> r.id == ruleId.toLong() } }),
        ruleCatalog,
    )

    /**
     * @param intent 이 쿼리가 **하려는 일**. 같은 의도의 여러 형태가 다르게 판정되면 그것이 결함이다.
     * @param because 기대 판정이 **옳은 이유**. 이 칸이 비면 현재 동작을 축복하는 것이다.
     * @param debatable 판정이 갈릴 수 있는 형태 — 사람의 결정이 필요하다.
     */
    data class Shape(
        val id: String,
        val axis: String,
        val intent: String,
        val sql: String,
        val expect: Verdict,
        val because: String,
        val purpose: String? = null,
        val debatable: Boolean = false,
        /** 태울 사용자 정의 규칙 id(축 K). null이면 시스템 룰만. **하나만** 태운다 — 아래 [serviceFor] 참조. */
        val rule: Int? = null,
    )

    private val shapes: List<Shape> = listOf(
        // ── A. 별명 사슬 — 금지 컬럼이 겹마다 이름을 바꾼다 ────────────────────────
        Shape("A1", "별명 사슬", "차단 컬럼을 CTE에서 개명해 반출",
            "WITH a AS (SELECT ssn AS s1 FROM users) SELECT s1 FROM a LIMIT 10",
            Verdict.BLOCK, "CTE 겹의 columnRefs에 users.ssn이 있다 — 개명은 참조를 없애지 못한다"),
        Shape("A2", "별명 사슬", "2단 개명으로 추적 끊기",
            "WITH a AS (SELECT ssn AS s1 FROM users), b AS (SELECT s1 AS s2 FROM a) SELECT s2 FROM b LIMIT 10",
            Verdict.BLOCK, "가장 안쪽 겹에 원본 참조가 남는다 — 룰이 전 스코프를 순회하므로 개명 깊이와 무관"),
        Shape("A3", "별명 사슬", "파생 테이블에서 개명",
            "SELECT x.s FROM (SELECT ssn AS s FROM users) x LIMIT 10",
            Verdict.BLOCK, "A1과 같은 이유 — 파생 테이블도 자식 스코프다"),
        Shape("A4", "별명 사슬", "표현식으로 감싸며 개명",
            "SELECT CONCAT(ssn, '') AS s FROM users LIMIT 10",
            Verdict.BLOCK, "함수 인자도 컬럼 참조다"),
        Shape("A5", "별명 사슬", "개명 후 바깥에서 조건으로만 사용(오라클)",
            "WITH a AS (SELECT ssn AS s FROM users) SELECT 1 FROM a WHERE s LIKE '8%' LIMIT 10",
            Verdict.BLOCK, "투영하지 않아도 행 존재로 값을 캐낼 수 있다"),

        // ── B. 자기 조인 — 인스턴스별로 요건이 독립인가 ───────────────────────────
        Shape("B1", "자기 조인", "한 별칭에만 파티션 조건",
            "SELECT a.id FROM user_events a JOIN user_events b ON a.id = b.id " +
                "WHERE a.event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "b 인스턴스는 조건이 없어 전체 스캔이다 — §6.4가 인스턴스 키로 판정하는 이유"),
        Shape("B2", "자기 조인", "양 별칭에 파티션 조건",
            "SELECT a.id FROM user_events a JOIN user_events b ON a.id = b.id " +
                "WHERE a.event_date = '2026-01-01' AND b.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "두 인스턴스 모두 파티션이 고정됐다"),

        // ── C. UNION — 팔마다 출처가 다르다 ───────────────────────────────────────
        Shape("C1", "UNION", "한 팔에만 차단 컬럼",
            "SELECT id FROM users UNION SELECT ssn FROM users LIMIT 10",
            Verdict.BLOCK, "어느 팔에서든 나가면 나간 것이다"),
        Shape("C2", "UNION", "한 팔만 파티션 충족",
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' " +
                "UNION SELECT id FROM user_events LIMIT 10",
            Verdict.BLOCK, "조건 없는 팔이 전체 스캔이다"),
        Shape("C3", "UNION", "양 팔 파티션 충족",
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' " +
                "UNION SELECT id FROM user_events WHERE event_date = '2026-01-02' LIMIT 10",
            Verdict.PASS, "두 팔 모두 파티션이 고정됐다"),

        // ── D. 서브쿼리 — 상관·스칼라 ─────────────────────────────────────────────
        Shape("D1", "서브쿼리", "EXISTS 안에서 차단 컬럼 사용",
            "SELECT e.id FROM user_events e WHERE EXISTS (SELECT 1 FROM users u WHERE u.ssn = 'x') " +
                "AND e.event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "서브쿼리도 스코프다"),
        Shape("D2", "서브쿼리", "상관 조건 + 파티션 충족(정상)",
            "SELECT e.id FROM user_events e WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = e.id) " +
                "AND e.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "차단 컬럼 없고 파티션이 고정됐다"),
        Shape("D3", "서브쿼리", "SELECT 절 스칼라 서브쿼리로 반출",
            "SELECT (SELECT ssn FROM users LIMIT 1) AS s FROM user_events " +
                "WHERE event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "투영 위치가 서브쿼리 안이어도 반출이다"),

        // ── E. 마스킹 — 투영 위치와 비투영 위치 ───────────────────────────────────
        Shape("E1", "마스킹", "마스킹 대상을 그대로 투영",
            "SELECT email FROM users LIMIT 10",
            Verdict.PASS, "투영 위치는 실행 시 자동 재작성된다 — WARN이고 차단은 아니다(결정 9)"),
        Shape("E2", "마스킹", "마스킹 대상을 개명해 투영",
            "SELECT email AS e FROM users LIMIT 10",
            Verdict.PASS, "개명은 투영 위치를 바꾸지 않는다 — E1과 같은 판정이어야 한다"),
        Shape("E3", "마스킹", "마스킹 대상을 함수로 감싸기",
            "SELECT CONCAT(email, '') FROM users LIMIT 10",
            Verdict.BLOCK, "재작성이 표현식 안을 안전하게 바꿀 수 없다 — 비투영 위치는 BLOCK(결정 9)"),
        Shape("E4", "마스킹", "CTE에서 투영하고 바깥에서 그대로 넘기기",
            "WITH a AS (SELECT email FROM users) SELECT email FROM a LIMIT 10",
            Verdict.PASS, "안쪽이 투영 위치이므로 재작성 대상이다 — 겹을 늘려도 E1과 같아야 한다"),
        Shape("E5", "마스킹", "WHERE에서만 사용(오라클)",
            "SELECT id FROM users WHERE email LIKE 'a%' LIMIT 10",
            Verdict.BLOCK, "조건에 쓰면 재작성해도 원본으로 걸러진다 — 비투영 위치"),

        // ── F. 요건 사실의 위치 — 조건이 어느 겹·어느 절에 있는가 ─────────────────
        Shape("F1", "요건 위치", "파티션 조건을 INNER JOIN의 ON에 두기",
            "SELECT e.id FROM user_events e JOIN users u ON u.id = e.id AND e.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "INNER JOIN의 ON은 WHERE와 의미가 같다 — 파티션은 실제로 고정된다"),
        Shape("F2", "요건 위치", "테이블은 CTE 안, 조건은 바깥",
            "WITH a AS (SELECT id, event_date FROM user_events) " +
                "SELECT id FROM a WHERE event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "CTE는 인라인되므로 파티션이 고정된다 — 읽기 좋게 쪼갠 것이 막혀선 안 된다"),
        Shape("F3", "요건 위치", "테이블과 조건 모두 CTE 안",
            "WITH a AS (SELECT id FROM user_events WHERE event_date = '2026-01-01') SELECT id FROM a LIMIT 10",
            Verdict.PASS, "같은 겹에서 충족된다"),
        Shape("F4", "요건 위치", "HAVING에만 조건",
            "SELECT id FROM user_events GROUP BY id HAVING MAX(event_date) = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "HAVING은 집계 **후**라 스캔을 줄이지 못한다 — 파티션 요건의 목적을 만족하지 않는다"),

        // ── G. 조건 조합 — 여러 조각이 하나의 사실을 만든다 ───────────────────────
        Shape("G1", "조건 조합", "경계 있는 범위를 부등호 두 개로",
            "SELECT id FROM user_events WHERE event_date >= '2026-01-01' AND event_date < '2026-02-01' LIMIT 10",
            Verdict.PASS, "BETWEEN과 의미가 같고 오히려 더 좁다 — 상·하한이 모두 있으면 파티션은 고정된다"),
        Shape("G2", "조건 조합", "하한만(끝이 없는 범위)",
            "SELECT id FROM user_events WHERE event_date >= '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "상한이 없으면 사실상 전체 스캔이다 — 막는 것이 옳다"),
        Shape("G3", "조건 조합", "BETWEEN(넓은 범위)",
            "SELECT id FROM user_events WHERE event_date BETWEEN '2026-01-01' AND '2026-12-31' LIMIT 10",
            Verdict.PASS, "상·하한이 있다. G1보다 12배 넓은데 통과하는 것이 현재 동작"),
        Shape("G4", "조건 조합", "IN 목록",
            "SELECT id FROM user_events WHERE event_date IN ('2026-01-01','2026-01-02') LIMIT 10",
            Verdict.PASS, "열거된 값으로 고정된다"),
        Shape("G5", "조건 조합", "OR로 분배됐지만 파티션은 고정",
            "SELECT id FROM user_events WHERE (event_date = '2026-01-01' AND id > 0) " +
                "OR (event_date = '2026-01-01' AND id < 0) LIMIT 10",
            Verdict.PASS, "두 분기 모두 같은 날짜로 고정된다 — 다만 OR 안을 신뢰하는 것이 옳은지는 판단이 필요",
            debatable = true),
        Shape("G6", "조건 조합", "같은 조건 중복",
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "중복은 의미를 바꾸지 않는다"),

        // ── H. 필수 술어 — 닫힌 동치 목록(§6.5) ───────────────────────────────────
        Shape("H1", "필수 술어", "술어를 그대로 씀",
            "SELECT id FROM user_events WHERE consent_yn = 'Y' AND event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "요구된 술어가 최상위 conjunct로 있다", purpose = "marketing"),
        Shape("H2", "필수 술어", "OR로 무력화",
            "SELECT id FROM user_events WHERE (consent_yn = 'Y' OR 1 = 1) AND event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "형태는 있으나 의미가 없다 — 최상위 conjunct가 아니다", purpose = "marketing"),
        Shape("H3", "필수 술어", "대소문자·공백 변형",
            "SELECT id FROM user_events WHERE CONSENT_YN='Y' AND event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "§6.5가 케이스 정규화를 동치로 정했다", purpose = "marketing"),
        Shape("H4", "필수 술어", "IN 단일값으로",
            "SELECT id FROM user_events WHERE consent_yn IN ('Y') AND event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "§6.5가 IN 단일값 ≡ = 를 동치로 정했다", purpose = "marketing"),
        Shape("H5", "필수 술어", "술어는 CTE 안, 테이블도 CTE 안",
            "WITH a AS (SELECT id FROM user_events WHERE consent_yn = 'Y' AND event_date = '2026-01-01') " +
                "SELECT id FROM a LIMIT 10",
            Verdict.PASS, "같은 겹에서 충족된다", purpose = "marketing"),
        Shape("H6", "필수 술어", "테이블은 CTE 안, 술어는 바깥",
            "WITH a AS (SELECT id, consent_yn, event_date FROM user_events) " +
                "SELECT id FROM a WHERE consent_yn = 'Y' AND event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "인라인되면 같은 술어다 — F2와 같은 축", purpose = "marketing"),

        // ── J. 절 누락 — 검사 대상 절 목록에 없는 문법 (실측으로 뚫린 자리) ───────
        //
        // 컬럼 참조 수집은 `refExprs`에 **손으로 나열한 6개 절**(select·where·groupBy·having·orderBy·on)만
        // 훑는다. MySQL 문법이 컬럼을 참조할 수 있는 자리는 그보다 넓고, 아래 셋이 목록 밖에 있었다.
        // 셋 다 **같은 원인**이며, 껍질(UNION·파생·CTE)은 이것을 그대로 실어 나른다.
        Shape("J1", "절 누락", "USING으로 차단 컬럼을 조인 키로 사용",
            "SELECT a.id FROM users a JOIN users b USING (ssn) LIMIT 10",
            Verdict.BLOCK, "`ON a.ssn = b.ssn`과 의미가 같고 그쪽은 차단된다 — 같은 사용이 표기법으로 갈리면 안 된다"),
        Shape("J2", "절 누락", "NATURAL JOIN의 암묵 조인 키",
            "SELECT a.id FROM users a NATURAL JOIN users b LIMIT 10",
            Verdict.BLOCK, "공통 컬럼 전부가 조인 키이므로 ssn이 포함된다. 어느 컬럼인지 SQL만으로 알 수 없으니 " +
                "추측하지 말고 검증 불가로 거부해야 한다"),
        Shape("J3", "절 누락", "named window로 차단 컬럼 정렬 순위 얻기",
            "SELECT id, ROW_NUMBER() OVER w AS rn FROM users WINDOW w AS (ORDER BY ssn) LIMIT 10",
            Verdict.BLOCK, "`ORDER BY ssn`은 이미 차단한다. 이 형태는 그 순서를 **결과 컬럼으로** 돌려주므로 더 샌다"),
        Shape("J4", "절 누락", "named window로 값 분포 얻기",
            "SELECT id, COUNT(*) OVER w AS c FROM users WINDOW w AS (PARTITION BY ssn) LIMIT 10",
            Verdict.BLOCK, "동일 ssn 그룹 크기가 나온다 — 값을 보지 않고 분포를 얻는다"),
        Shape("J5", "절 누락", "UNION 팔 안에 숨긴 named window",
            "SELECT x.v FROM (SELECT ROW_NUMBER() OVER w AS v FROM users WINDOW w AS (ORDER BY ssn) " +
                "UNION ALL SELECT id AS v FROM users) x LIMIT 10",
            Verdict.BLOCK, "껍질은 판정을 바꾸지 못해야 한다 — 인라인 OVER 버전은 차단된다(대조군 J6)"),
        Shape("J6", "절 누락", "대조군 — 같은 UNION 껍질에 인라인 OVER",
            "SELECT x.v FROM (SELECT ROW_NUMBER() OVER (ORDER BY ssn) AS v FROM users " +
                "UNION ALL SELECT id AS v FROM users) x LIMIT 10",
            Verdict.BLOCK, "인라인은 select 목록 안이라 방문자가 훑는다 — J5와 결과가 달라지면 그 차이가 결함이다"),

        // ── K. 사용자 정의 규칙 — 다른 평가기, 같은 질문 ──────────────────────────
        //
        // 분모: 룰 어휘 **op 4종**(`joins`·`requires`·`blocks`·`must_be_masked`) × **배치 위치**
        // (한 겹 / CTE 안 / 겹을 가로질러 / 표기 변형). 상상으로 고른 것이 아니라 어휘를 전수로 훑었다.
        // 규칙은 시스템 룰과 겹치지 않는 컬럼을 쓴다(`phone`·`name`·`created_at`) — 겹치면 어느 쪽이
        // 막았는지 알 수 없다.
        Shape("K1", "사용자 규칙", "joins 충족(한 겹)",
            "SELECT u.id FROM users u JOIN user_events e ON u.id = e.id WHERE e.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "요구된 조인 등식이 그대로 있다", rule = 1),
        Shape("K2", "사용자 규칙", "joins 미충족",
            "SELECT u.id FROM users u JOIN user_events e ON u.id = e.event_date " +
                "WHERE e.event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "요구된 등식(users.id ↔ user_events.id)이 없다", rule = 1),
        Shape("K3", "사용자 규칙", "joins가 CTE 안에서 충족",
            "WITH t AS (SELECT u.id FROM users u JOIN user_events e ON u.id = e.id " +
                "WHERE e.event_date = '2026-01-01') SELECT id FROM t LIMIT 10",
            Verdict.PASS, "같은 겹에서 충족된다", rule = 1),
        Shape("K4", "사용자 규칙", "테이블은 CTE 안, 조인은 바깥",
            "WITH t AS (SELECT id FROM users) SELECT t.id FROM t JOIN user_events e ON t.id = e.id " +
                "WHERE e.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "인라인되면 같은 조인이다 — F2·H6과 같은 축(겹 경계에서 멈춤)", rule = 1),
        Shape("K5", "사용자 규칙", "joins를 USING으로 표기",
            "SELECT u.id FROM users u JOIN user_events e USING (id) WHERE e.event_date = '2026-01-01' LIMIT 10",
            Verdict.PASS, "`ON u.id = e.id`와 같은 조인이다 — 표기가 판정을 바꾸면 안 된다", rule = 1),
        Shape("K6", "사용자 규칙", "requires 충족",
            "SELECT id FROM users WHERE created_at = '2026-01-01' LIMIT 10",
            Verdict.PASS, "요구된 술어가 최상위 conjunct다", rule = 2),
        Shape("K7", "사용자 규칙", "requires 미충족",
            "SELECT id FROM users LIMIT 10",
            Verdict.BLOCK, "술어가 없다", rule = 2),
        Shape("K8", "사용자 규칙", "requires를 OR로 무력화",
            "SELECT id FROM users WHERE created_at = '2026-01-01' OR 1 = 1 LIMIT 10",
            Verdict.BLOCK, "형태는 있으나 최상위 conjunct가 아니다", rule = 2),
        Shape("K9", "사용자 규칙", "requires를 IN 단일값으로",
            "SELECT id FROM users WHERE created_at IN ('2026-01-01') LIMIT 10",
            Verdict.PASS, "§6.5가 IN 단일값 ≡ = 를 동치로 정했다", rule = 2),
        Shape("K10", "사용자 규칙", "blocks — 컬럼을 조회",
            "SELECT phone FROM users WHERE created_at = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "금지 컬럼을 참조했다", rule = 3),
        Shape("K11", "사용자 규칙", "blocks — 함수로 감싸기",
            "SELECT CONCAT(phone, '') FROM users WHERE created_at = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "columnRefs 기반이므로 껍질과 무관해야 한다", rule = 3),
        Shape("K12", "사용자 규칙", "blocks — CTE 안에서 참조",
            "WITH t AS (SELECT phone AS p FROM users WHERE created_at = '2026-01-01') SELECT p FROM t LIMIT 10",
            Verdict.BLOCK, "안쪽 겹에 참조가 남는다", rule = 3),
        Shape("K13", "사용자 규칙", "must_be_masked — 투영만",
            "SELECT name FROM users WHERE created_at = '2026-01-01' LIMIT 10",
            Verdict.PASS, "투영 위치는 실행 시 재작성된다(결정 9)", rule = 4),
        Shape("K14", "사용자 규칙", "must_be_masked — 비투영(WHERE)",
            "SELECT id FROM users WHERE name = 'x' AND created_at = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "조건에 쓰면 재작성해도 원본으로 걸러진다", rule = 4),

        // ── I. 귀속 — 한정자가 없을 때 ────────────────────────────────────────────
        Shape("I1", "귀속", "조인 쿼리에서 한정자 없이 차단 컬럼",
            "SELECT ssn FROM users u JOIN user_events e ON u.id = e.id WHERE e.event_date = '2026-01-01' LIMIT 10",
            Verdict.BLOCK, "귀속이 불명하면 동명 차단 컬럼이 있는 테이블 기준으로 fail-closed(§6.4)"),
    )

    /**
     * 실측으로 확인된 과차단. **줄지도 늘지도 않아야** 통과한다.
     *
     * ## 첫 측정 결과 (2026-07-27, 형태 35개)
     *
     * **누락차단 0건.** 막아야 할 17개 형태가 전부 막혔다 — 개명 사슬 2단, 파생 테이블,
     * UNION 한 팔, EXISTS 안, SELECT 절 스칼라 서브쿼리, 한정자 없는 참조까지.
     * 금지 쪽이 `columnRefs` 정규화 위에 서 있다는 것이 여기서 확인된다.
     *
     * 과차단 4건은 **두 뿌리로 모인다**:
     *
     * | 뿌리 | 형태 | 무엇을 못 보는가 |
     * |---|---|---|
     * | 겹을 이어 보지 않는다 | `F2` `H6` | CTE 안의 테이블과 바깥의 조건이 같은 쿼리라는 사실 |
     * | 조각을 합쳐 보지 않는다 | `G1` `G5` | 두 부등호가 하나의 경계를, OR 두 분기가 같은 값을 만드는 사실 |
     *
     * **예측이 틀린 것 하나**: `F1`(INNER JOIN의 ON에 파티션 조건)은 과차단일 것이라 예상했으나
     * **통과한다** — 엔진이 이미 INNER JOIN의 ON을 WHERE와 동등하게 본다. 열거가 생각보다 넓은
     * 자리도 있다는 뜻이고, 그래서 추측이 아니라 이 표가 근거여야 한다.
     *
     * ## 2차 측정 (2026-07-27, 축 K 추가 — 형태 55개)
     *
     * 사용자 정의 규칙 축 14형태: **누락차단 0**, 과차단 2. 뿌리가 하나 늘어 셋이 됐다:
     *
     * | 뿌리 | 형태 | 무엇을 못 보는가 |
     * |---|---|---|
     * | 겹을 이어 보지 않는다 | `F2` `H6` **`K4`** | CTE 안 테이블과 바깥 조건이 같은 쿼리라는 사실 |
     * | 조각을 합쳐 보지 않는다 | `G1` `G5` | 부등호 둘이 하나의 경계를, OR 두 분기가 같은 값을 만드는 사실 |
     * | **표기를 알아보지 못한다** | **`K5`** | `JOIN ... USING (id)`가 `ON a.id = b.id`와 같은 조인이라는 사실 |
     *
     * `K5`는 `USING`을 `columnRefs`에 넣은 수정(`b29047b`)의 **바로 옆**에서 나왔다 —
     * 조인 등식(`joinEqualities`)은 여전히 `ON`에서만 만들어진다. 같은 문법이 금지 쪽에는 보이고
     * 요건 쪽에는 안 보인다.
     */
    private val KNOWN_OVERBLOCK: Set<String> = setOf("F2", "G1", "G5", "H6", "K4", "K5")

    // ── 실행 ─────────────────────────────────────────────────────────────────────

    private data class Outcome(val shape: Shape, val actual: Verdict, val codes: List<String>)

    private fun run(): List<Outcome> = shapes.map { s ->
        val report = s.rule?.let { serviceFor(it).lint(s.sql, s.purpose) } ?: Fixtures.lint(s.sql, s.purpose)
        val actual = if (report.blocked) Verdict.BLOCK else Verdict.PASS
        Outcome(s, actual, report.violations.filter { it.severity == Severity.BLOCK }.map { it.ruleId }.distinct())
    }

    private fun table(rows: List<Outcome>): String = rows.joinToString("\n") { o ->
        val mark = when {
            o.actual == o.shape.expect -> "일치  "
            o.shape.expect == Verdict.BLOCK -> "누락차단"
            else -> "과차단 "
        }
        "  ${o.shape.id.padEnd(3)} ${o.shape.axis.padEnd(9)} $mark 기대=${o.shape.expect} 실제=${o.actual} " +
            "${o.codes.joinToString(",")} — ${o.shape.intent}"
    }

    /**
     * **누락차단은 하나도 없어야 한다.** 막아야 할 형태가 통과하면 데이터가 나간다.
     * 여기 실패가 뜨면 그 형태는 즉시 백로그가 아니라 결함이다.
     */
    @Test
    fun `막아야 할 형태가 통과하지 않는다`() {
        val rows = run()
        val leaks = rows.filter { it.shape.expect == Verdict.BLOCK && it.actual == Verdict.PASS }
        assertTrue(
            leaks.isEmpty(),
            "막아야 할 형태가 통과했다(누락차단):\n" + table(leaks) + "\n\n전체:\n" + table(rows),
        )
    }

    /**
     * **과차단 집합은 고정이다.** 새로 생기면 회귀이고, 사라지면 고쳐진 것이므로 목록에서 빼야 한다.
     * "고쳤다"를 목록으로 증명하게 만든다 — 숫자만 세면 넣고 빼기를 통과한다(learning 015).
     */
    @Test
    fun `과차단은 알려진 목록과 정확히 같다`() {
        val rows = run()
        val over = rows.filter { it.shape.expect == Verdict.PASS && it.actual == Verdict.BLOCK }
            .map { it.shape.id }.toSet()
        assertEquals(
            KNOWN_OVERBLOCK, over,
            "과차단 집합이 달라졌다. 새로 생겼으면 회귀, 사라졌으면 KNOWN_OVERBLOCK에서 지워라.\n" + table(rows),
        )
    }

    /**
     * **대조군** — 목록이 비거나 축이 한쪽으로 쏠리면 위 두 테스트는 공허하다.
     * 축마다 최소 하나, 그리고 PASS·BLOCK 기대가 둘 다 있어야 한다.
     */
    @Test
    fun `형태 목록이 공허하지 않다`() {
        assertTrue(shapes.size >= 30, "형태가 너무 적다: ${shapes.size}")
        val axes = shapes.groupBy { it.axis }
        assertTrue(axes.size >= 8, "축이 너무 적다: ${axes.keys}")
        axes.forEach { (axis, list) -> assertTrue(list.isNotEmpty(), "빈 축: $axis") }
        assertTrue(shapes.any { it.expect == Verdict.PASS }, "통과 기대가 하나도 없다 — 과차단을 볼 수 없다")
        assertTrue(shapes.any { it.expect == Verdict.BLOCK }, "차단 기대가 하나도 없다")
        assertTrue(shapes.all { it.because.isNotBlank() }, "근거가 빈 형태가 있다 — 현재 동작을 축복하는 것이다")
    }
}
