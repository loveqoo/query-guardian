package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.Fixtures.assertBlockedBy
import com.loveqoo.queryguardian.rules.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/** spec 001 §12 우회 시도 스위트 — 전부 차단되어야 한다. 하나라도 통과하면 게이트가 아니라 제안함이다. */
class BypassSuiteTest {

    @Test
    fun `OR 가지 세탁 - 필수 술어`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND (consent_yn = 'Y' OR 1 = 1) LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `OR 가지 세탁 - 파티션 키`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' OR user_id > 0 LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `파생 테이블에 숨긴 SELECT star와 거버넌스 테이블`() {
        assertBlockedBy("SELECT id FROM (SELECT * FROM user_events) t LIMIT 10", "no-select-star")
        assertBlockedBy("SELECT id FROM (SELECT id FROM user_events) t LIMIT 10", "require-partition-key")
    }

    @Test
    fun `CTE에 숨긴 위반`() {
        assertBlockedBy("WITH x AS (SELECT * FROM user_events) SELECT id FROM x LIMIT 10", "no-select-star")
        assertBlockedBy("WITH x AS (SELECT id FROM user_events) SELECT id FROM x LIMIT 10", "require-partition-key")
    }

    @Test
    fun `IN 서브쿼리에 숨긴 거버넌스 테이블`() {
        assertBlockedBy(
            "SELECT id FROM orders WHERE id IN (SELECT id FROM user_events) LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `UNION 더러운 팔 세탁`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' UNION ALL SELECT * FROM user_events",
            "no-select-star",
        )
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' UNION ALL SELECT id FROM user_events",
            "require-partition-key",
        )
    }

    @Test
    fun `부정형은 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND NOT (consent_yn = 'Y') LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND consent_yn <> 'Y' LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `함수로 감싼 파티션 키는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE DATE(event_date) = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }

    @Test
    fun `Raw로 떨어지는 술어는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' AND COALESCE(consent_yn, 'N') = 'Y' LIMIT 10",
            "require-predicate", purpose = "marketing",
        )
    }

    @Test
    fun `주석 삽입은 술어가 아니다`() {
        assertBlockedBy(
            "SELECT id FROM user_events WHERE event_date = '2026-01-01' -- AND consent_yn = 'Y'",
            "require-predicate", purpose = "marketing",
        )
    }

    /**
     * CTE 이름을 물리 테이블과 **같게** 지어도 그 본문의 물리 테이블 참조는 숨겨지지 않는다.
     *
     * MySQL 실측: `WITH demo_users AS (SELECT id, ssn FROM demo_users) SELECT ... FROM demo_users`는
     * 본문의 참조를 **물리 테이블로 해석해 실제 ssn을 반환**한다(비재귀 CTE는 자기 이름을 가리지 않는다).
     * IR이 본문에서도 그 이름을 CTE로 취급하면 카탈로그 조회가 건너뛰어져 BLOCK 룰이 발화하지 않는다 (§6.2).
     */
    @Test
    fun `CTE에 물리 테이블과 같은 이름을 붙여도 숨길 수 없다`() {
        assertBlockedBy(
            "WITH users AS (SELECT id, ssn FROM users) SELECT id FROM users LIMIT 10",
            "no-blocked-column",
        )
    }

    @Test
    fun `OUTER JOIN ON의 술어는 충족이 아니다`() {
        assertBlockedBy(
            "SELECT o.id FROM orders o LEFT JOIN user_events e ON e.event_date = '2026-01-01' LIMIT 10",
            "require-partition-key",
        )
    }

    /**
     * 파생 테이블의 본문이 **UNION**이면 그 스코프가 IR에서 통째로 사라졌다(collectTables의 else 분기).
     * "미수집이면 귀속 불가라 fail-closed"라고 적혀 있었지만, 스코프가 사라지면 그 안의 BLOCK 컬럼도
     * 함께 사라진다 — spec 001 §6.2 스코프 은닉의 정확한 사례다.
     */
    @Test
    fun `파생 테이블 본문이 UNION이어도 스코프는 숨겨지지 않는다`() {
        assertBlockedBy(
            "SELECT d.c FROM (SELECT ssn AS c FROM users UNION ALL SELECT ssn AS c FROM users) d LIMIT 10",
            "no-blocked-column",
        )
        // 바깥에 물리 테이블이 따로 있으면 0-테이블 검사도 발화하지 않아 완전히 조용히 통과했다
        assertBlockedBy(
            "SELECT u.id FROM users u JOIN (SELECT ssn AS c FROM users UNION ALL SELECT ssn AS c FROM users) d " +
                "ON u.id = d.c LIMIT 10",
            "no-blocked-column",
        )
    }

    /**
     * 적대 검토 CRITICAL 1(실측): `LATERAL (...)`은 Druid에서 `SQLExprTableSource`로 오는데,
     * 예전의 `else -> expr.toString()` 폴백이 그것을 **이름이 `LATERAL(…)`인 물리 테이블**로 만들어
     * 안쪽의 `u.ssn` 참조가 IR에서 사라졌다 → MySQL에서 실제 주민번호가 반환됐다.
     * 스코프가 없으면 BLOCK·마스킹·권한·매핑 허용목록이 **동시에** 무발화한다.
     */
    @Test
    fun `LATERAL 파생 테이블은 물리 테이블로 위장할 수 없다`() {
        for (sql in listOf(
            "SELECT u.id, d.s FROM users u JOIN LATERAL (SELECT u.ssn AS s) d ON TRUE LIMIT 10",
            "SELECT u.id, d.s FROM users u, LATERAL (SELECT u.ssn AS s) d LIMIT 10",
        )) {
            val report = Fixtures.lint(sql)
            assertTrue(report.blocked, "차단되어야 함: $sql\n$report")
        }
    }

    /**
     * 적대 검토 CRITICAL 2(실측): FROM에 인스턴스가 둘 이상이면 IR이 컬럼 귀속을 포기하는데(table=null),
     * 마스킹 판정이 인스턴스 키로만 세어 **ABSENT**로 떨어졌다 → 평문 이메일이 반환됐다.
     * `no-blocked-column`은 같은 상황에 fail-closed 폴백이 있었으므로 ssn은 막히고 email만 뚫렸다.
     */
    @Test
    fun `다중 테이블 FROM의 비한정 마스킹 컬럼은 통과하지 못한다`() {
        for (sql in listOf(
            "SELECT email FROM users, user_events LIMIT 10",
            "SELECT u.id, email FROM users u JOIN user_events e ON e.id = u.id LIMIT 10",
        )) {
            val report = Fixtures.lint(sql)
            assertTrue(
                report.violations.any { it.ruleId == "must-be-masked" && it.severity == Severity.BLOCK },
                "귀속 불가한 마스킹 컬럼은 표현 불가로 차단되어야 함: $sql\n$report",
            )
        }
    }

    /**
     * 적대 검토 CRITICAL 4(실측): ORDER BY/GROUP BY 안의 서브쿼리가 스코프로 등록되지 않아
     * `ORDER BY (SELECT u.ssn LIKE '90%')`가 **주민번호 불리언 오라클**이 됐다(정렬 순서로 값을 복원).
     * 첫 쿼리는 테이블 자체가 IR에 안 나타나 권한·필수조건·매핑 허용목록이 전부 무발화했다.
     */
    @Test
    fun `정렬 그룹 절의 서브쿼리도 스코프로 검사한다`() {
        val oracle = Fixtures.lint("SELECT u.id FROM users u ORDER BY (SELECT u.ssn LIKE '90%') DESC LIMIT 10")
        assertTrue(
            oracle.violations.any { it.ruleId.startsWith("no-blocked-column") },
            "ORDER BY 서브쿼리의 BLOCK 컬럼이 잡혀야 함: $oracle",
        )
        val hidden = Fixtures.lint("SELECT u.id FROM users u ORDER BY (SELECT COUNT(*) FROM user_events) LIMIT 10")
        assertTrue(
            hidden.violations.any { it.ruleId.startsWith("require-partition-key") },
            "ORDER BY 서브쿼리의 거버넌스 테이블이 판정 대상이어야 함: $hidden",
        )
    }
}
