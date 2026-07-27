package com.loveqoo.queryguardian.audit

/**
 * 감사 어휘 (spec 010 P0 · I13).
 *
 * **이 패키지는 아무 것도 의존하지 않는다.** `ir`이 SQL의 공용 어휘인 것처럼 여기는 *결말*의 공용 어휘다.
 * 게이트(`query`)·권한(`auth`)·승인(`approval`)·실행(`exec`)이 모두 같은 코드를 말해야 하는데,
 * 어느 한쪽에 두면 나머지가 그쪽을 의존하게 되고 그 방향이 곧 순환으로 되돌아온다(ArchUnit이 고정한다).
 */

/**
 * 실행 시도의 결말. **차단·오류도 기록한다** — 시도 자체가 감사 대상이다(spec 008 §6).
 * [PREVIEW]는 실행 없이 재작성만 보여준 경우다 — 데이터는 나가지 않지만 **어떤 강제식이 적용되는지**가
 * 노출되므로(카탈로그 오라클) 누가 무엇을 미리 봤는지는 남긴다.
 */
enum class ExecutionOutcome { SUCCESS, BLOCKED, ERROR, PREVIEW }

/**
 * 게이트 차단 분류의 공용 어휘. `execution_event.error_code`에 남는 값은 이 집합에서 나온다.
 *
 * 문자열 리터럴이 아닌 이유는 스타일이 아니다. 코드가 문자열이면 ⑴ 오타가 조용히 새 코드를 만들고
 * ⑵ "이 코드가 실제로 발생하는가"를 물을 대상이 없어져 감사가 **무엇을 놓치고 있는지 셀 수 없다**.
 *
 * **어디까지 닫히는지 정확히 적는다.** [com.loveqoo.queryguardian.exec.ExecutionAudit.record]가 이 타입만
 * 받으므로 *그 함수를 지나는* 분류는 닫힌다. 그러나 `ExecutionEvent`와 그 리포지토리는 여전히 public이고
 * `errorCode`는 `String?`이라, 리포지토리를 직접 쓰면 임의 문자열이 들어간다 — 지금 그런 호출자가 없다는
 * 것은 **규약이지 컴파일 보장이 아니다**(`.dev/BACKLOG.md` D-D와 같은 뿌리: 감사의 폐쇄가 애플리케이션
 * 관례에 얹혀 있다). 반대 방향도 1:1이 아니다: 저장 게이트와 lint는 감사 행 **없이** 같은 값을 응답
 * 코드로 내보낸다(`LintController`·`QueryService` 경유). 이 타입의 정확한 정의는
 * **"게이트 차단 분류의 공용 어휘"** 이고, `error_code`는 그중 감사를 지나는 부분집합이다.
 *
 * 이름은 **32자 이하**여야 한다 — `error_code VARCHAR(32)`. 테스트가 고정한다.
 *
 * ⚠️ 값을 추가하면 `AuditCodeCoverageTest`가 즉시 실패한다(대응 시나리오가 없으므로). 그것이 의도다 —
 * 새 차단 사유를 만들면서 "그 사유가 실제로 발생하고 기록되는가"를 확인하지 않고 지나갈 수 없다.
 */
enum class AuditCode {
    // ── 열람 ──────────────────────────────────────────────────────────
    /** 남의 저장 쿼리 id로 실행을 시도했다. 열거 시도 자체가 감사 대상이라 본문 없이 기록한다. */
    FORBIDDEN_READ,

    // ── 승인 (approval) ───────────────────────────────────────────────
    NO_REQUEST,
    NOT_APPROVED,
    REQUESTER_MISMATCH,
    TABLES_NOT_COVERED,

    /** 검토(review) 승인 전 실행 시도. 승인(approval)과 다른 축이다. */
    NOT_REVIEWED,

    // ── 데이터 권한 (auth) ────────────────────────────────────────────
    TABLES_UNKNOWN,
    TABLES_NOT_PERMITTED,

    // ── 접수·판정 ─────────────────────────────────────────────────────
    PARSE_FAILED,
    RULE_BLOCKED,

    // ── 실행 대상 매핑 ────────────────────────────────────────────────
    NO_DEMO_MAPPING,
    INVALID_PHYSICAL_NAME,

    // ── 재작성 (RewriteRefusal과 1:1) ─────────────────────────────────
    REWRITE_MASK_NOT_EXPRESSIBLE,
    REWRITE_EXPRESSION_NOT_USABLE,
    REWRITE_SCOPE_NOT_FOUND,
    REWRITE_VERIFY_FAILED,

    /** 계획에 상한이 없다 = 재작성이 LIMIT을 넣지 않았다. 상한 없는 실행은 허용하지 않는다(fail-closed). */
    REWRITE_NO_LIMIT,

    // ── 실행 인프라 (ExecutionFailure.Kind와 1:1) ─────────────────────
    TIMEOUT,
    SQL_ERROR,
    CONNECTION,
    ;

    companion object {
        /** `error_code VARCHAR(32)` — 넘치면 감사가 잘려 사후 분류가 불가능해진다. */
        const val MAX_LENGTH = 32
    }
}
