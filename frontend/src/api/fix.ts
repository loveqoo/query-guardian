import type { Fix } from "./client";

/**
 * **제안 조각을 SQL에 적용한다** (spec 013 §3-1 · F4).
 *
 * 서버는 고쳐진 SQL을 만들어 주지 않는다(spec 012 §9 — 조각만 준다). 그러므로 **"적용"의 의미를
 * 정하는 것은 이 함수다.** UI에서 떼어 둔 이유가 그것이다: 화면 렌더링과 무관한 계약이고,
 * 계약은 재어져야 한다(`tests/apply-fix.spec.ts`).
 *
 * 백엔드 `FixRoundTripTest.applyFix`와 **같은 규칙**이어야 한다. 그쪽이 "적용하면 판정을 통과한다"를
 * 재고 있으므로, 두 적용기가 갈라지면 서버 테스트는 초록인데 화면에서만 왕복이 깨진다.
 * 한쪽을 고치면 반드시 다른 쪽도 본다.
 *
 * 적용할 자리를 못 찾으면 **원본을 그대로 돌려준다** — 부분 적용된 SQL을 만들지 않는다.
 * 호출부는 `next === prev`로 그 사실을 알 수 있고, 사용자에게 직접 고치라고 말해야 한다.
 */
export function applyFix(sql: string, fix: Fix): string {
  if (fix.kind === "REPLACE_PROJECTION") {
    if (!fix.from) return sql;
    // 단어 경계로 끊는다. 셋 다 필요하다(실측):
    //  - 앞이 식별자/점이면 안 된다 → `email_verified`, `u.email`을 건드리지 않는다
    //  - 앞이 **여는 괄호여도 안 된다** → 이미 `mask_email(email)`인 것을 또 감싸는 것을 막는다.
    //    대가로 `SELECT (email)` 같은 괄호 친 맨몸 참조는 못 고친다 — 자리를 못 찾은 것으로 처리되어
    //    사용자가 직접 고치게 된다. 조용히 이중 마스킹을 만드는 쪽보다 안전하다.
    //  - 뒤가 식별자/여는 괄호면 안 된다 → `email_verified`, 함수명 `email(...)`을 건드리지 않는다
    const token = fix.from.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return sql.replace(new RegExp(`(?<![\\w.(])${token}(?![\\w(])`), fix.to);
  }
  // 최상위 WHERE에 AND로 잇는다. 기존 조건 **앞**에 넣는다 — 뒤에 붙이면 원본이 OR일 때
  // `a OR b AND new`가 되어 우선순위가 무너진다(`AND`가 `OR`보다 강하다).
  const where = /\sWHERE\s/i.exec(sql);
  if (where) {
    return sql.slice(0, where.index) + ` WHERE ${fix.to} AND ` + sql.slice(where.index + where[0].length);
  }
  // WHERE가 없으면 다음 절 앞에 새로 만든다. 절이 없으면 끝에 붙인다.
  const tail = /\s(LIMIT|GROUP\s+BY|ORDER\s+BY|HAVING)\s/i.exec(sql);
  if (!tail) return `${sql} WHERE ${fix.to}`;
  return sql.slice(0, tail.index) + ` WHERE ${fix.to}` + sql.slice(tail.index);
}
