import { test, expect } from "@playwright/test";
import { runDeniedReason } from "../src/api/runnable";

/**
 * **실행 자격 판정의 계약** (spec 013 §3-3).
 *
 * 서버 정책(spec 008 §7 · 결정 14)을 화면이 그대로 반영하는지 잰다. 여기가 느슨하면
 * 사용자는 눌러 보고 403을 받고, 빡빡하면 자기 쿼리를 못 돌린다.
 */

const approved = (owner: string | null) => ({ review: "APPROVED", owner });

test("본인의 승인된 쿼리는 실행할 수 있다", () => {
  expect(runDeniedReason(approved("u1"), "u1")).toBeNull();
});

/**
 * 검토 상태마다 **다음에 할 일이 다르다** — 대기 중이면 기다리는 것이고, 반려면 고쳐서 다시 올리는 것이다.
 * 한 문장으로 뭉치면 사용자가 그 차이를 화면에서 알 수 없다.
 */
test("검토가 안 끝났으면 막히고, 이유가 상태마다 다르다", () => {
  expect(runDeniedReason({ review: "PENDING_REVIEW", owner: "u1" }, "u1")).toContain("검토 대기");
  expect(runDeniedReason({ review: "REJECTED", owner: "u1" }, "u1")).toContain("반려");
  // 아직 못 읽어 온 상태(빈 문자열)도 **막는** 쪽이다 — 모를 때 여는 것은 fail-open이다
  expect(runDeniedReason({ review: "", owner: "u1" }, "u1")).toContain("확인하는 중");
});

/** 이 프로젝트에서 화면에 단서가 하나도 없는 규칙 — 스튜어드는 **보되 실행하지 못한다**. */
test("스튜어드라도 남의 쿼리는 실행할 수 없다", () => {
  const reason = runDeniedReason(approved("u1"), "u4");
  expect(reason).toContain("대행 실행 불허");
});

test("근거 승인 요청이 사라지면 아무도 실행하지 못한다", () => {
  expect(runDeniedReason(approved(null), "u1")).toContain("근거 승인 요청");
  expect(runDeniedReason(approved(undefined), "u1")).toContain("근거 승인 요청");
});

test("로그인 정보를 모르면 막는다 — 모를 때는 여는 쪽이 아니다", () => {
  expect(runDeniedReason(approved("u1"), undefined)).not.toBeNull();
});

test("예시 행은 실행 대상이 아니다", () => {
  expect(runDeniedReason({ isSample: true, review: "APPROVED", owner: "u1" }, "u1")).toBe("예시 행입니다");
});
