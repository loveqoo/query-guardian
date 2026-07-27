import { test, expect } from "@playwright/test";
import { applyFix } from "../src/api/fix";
import type { Fix } from "../src/api/client";

/**
 * **제안 적용의 계약** (spec 013 §3-1 · U2).
 *
 * 서버는 고쳐진 SQL을 만들어 주지 않으므로 `applyFix`가 "적용"의 의미를 정한다. 그 의미가 틀리면
 * 사용자는 우리가 시킨 대로 눌렀는데도 여전히 막힌다 — 제안 모델이 그 자리에서 무너진다.
 *
 * **백엔드 `FixRoundTripTest`와 짝이다.** 그쪽은 "이 규칙대로 적용하면 판정을 통과한다"를 실제 판정기로
 * 재고, 여기서는 "화면의 적용기가 그 규칙을 지키는가"를 잰다. 둘 중 하나만 있으면 갈라진 채로 초록이다.
 *
 * 브라우저가 필요 없는 순수 함수라 Playwright의 러너만 빌려 쓴다(프로젝트에 단위 러너가 없다).
 */

const replace = (from: string, to: string): Fix => ({
  kind: "REPLACE_PROJECTION",
  table: "users",
  column: from,
  from,
  to,
});

const add = (predicate: string): Fix => ({
  kind: "ADD_PREDICATE",
  table: "marketing_consents",
  column: "consent_yn",
  from: null,
  to: predicate,
});

test.describe("투영 치환", () => {
  test("맨몸 컬럼을 강제식으로 바꾼다", () => {
    expect(applyFix("SELECT email FROM users LIMIT 10", replace("email", "mask_email(email)"))).toBe(
      "SELECT mask_email(email) FROM users LIMIT 10",
    );
  });

  test("이름이 겹치는 다른 컬럼은 건드리지 않는다", () => {
    // `email_verified`가 먼저 나와도 그것을 바꾸면 안 된다 — 단어 경계가 없으면 여기서 깨진다.
    expect(
      applyFix("SELECT email_verified, email FROM users LIMIT 10", replace("email", "mask_email(email)")),
    ).toBe("SELECT email_verified, mask_email(email) FROM users LIMIT 10");
  });

  test("이미 감싼 것을 다시 감싸지 않는다", () => {
    // `mask_email(email)`의 `email`은 여는 괄호 뒤에 오므로 대상이 아니다. 이중 마스킹은 사용자 의도를 바꾼다.
    const sql = "SELECT mask_email(email) FROM users LIMIT 10";
    expect(applyFix(sql, replace("email", "mask_email(email)"))).toBe(sql);
  });

  test("한정된 참조(u.email)는 대상이 아니다", () => {
    // 서버는 한정자 없는 형태를 제안한다. 한정 참조까지 바꾸면 `u.mask_email(email)`이 되어 문법이 깨진다.
    const sql = "SELECT u.email FROM users u LIMIT 10";
    expect(applyFix(sql, replace("email", "mask_email(email)"))).toBe(sql);
  });

  test("자리를 못 찾으면 원본 그대로 — 부분 적용을 만들지 않는다", () => {
    const sql = "SELECT id FROM users LIMIT 10";
    expect(applyFix(sql, replace("email", "mask_email(email)"))).toBe(sql);
  });
});

test.describe("조건 추가", () => {
  test("WHERE가 없으면 다음 절 앞에 만든다", () => {
    expect(applyFix("SELECT id FROM marketing_consents LIMIT 10", add("mc.consent_yn = 'Y'"))).toBe(
      "SELECT id FROM marketing_consents WHERE mc.consent_yn = 'Y' LIMIT 10",
    );
  });

  test("절이 하나도 없으면 끝에 붙인다", () => {
    expect(applyFix("SELECT id FROM marketing_consents", add("mc.consent_yn = 'Y'"))).toBe(
      "SELECT id FROM marketing_consents WHERE mc.consent_yn = 'Y'",
    );
  });

  test("WHERE가 있으면 기존 조건 **앞**에 AND로 잇는다", () => {
    expect(applyFix("SELECT id FROM marketing_consents WHERE id > 0 LIMIT 10", add("mc.consent_yn = 'Y'"))).toBe(
      "SELECT id FROM marketing_consents WHERE mc.consent_yn = 'Y' AND id > 0 LIMIT 10",
    );
  });

  /**
   * 원본이 최상위 `OR`일 때가 이 함수의 가장 위험한 자리다. 뒤에 이으면 `a OR b AND new`가 되고
   * `AND`가 더 강하게 묶여 **조건이 무력화**된다(`a`인 행이 그대로 통과한다).
   * 앞에 넣으면 `new AND a OR b`인데 이것도 `(new AND a) OR b`다 — 그래서 앞·뒤 어느 쪽도
   * 괄호 없이는 안전하지 않다. 아래 단정이 현재 동작을 고정하고, 그것이 왜 아직 위험한지 남긴다.
   */
  test("최상위 OR에서는 우선순위가 보장되지 않는다 — 현행 동작 고정", () => {
    const out = applyFix("SELECT id FROM t WHERE a = 1 OR b = 2 LIMIT 10", add("c = 'Y'"));
    expect(out).toBe("SELECT id FROM t WHERE c = 'Y' AND a = 1 OR b = 2 LIMIT 10");
    // 알려진 한계: `(c='Y' AND a=1) OR b=2`로 해석된다. 판정이 이 결과를 **미충족으로 본다** —
    // require-predicate는 최상위 AND conjunct만 인정하므로, 사용자는 여기서 통과하지 못하고
    // 직접 괄호를 넣어야 한다. 조용히 통과시키는 것보다 안전한 방향이라 지금은 이대로 둔다.
  });
});
