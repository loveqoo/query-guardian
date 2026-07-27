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

test("검토가 안 끝났으면 막힌다", () => {
  expect(runDeniedReason({ review: "PENDING_REVIEW", owner: "u1" }, "u1")).toContain("검토 승인");
  expect(runDeniedReason({ review: "REJECTED", owner: "u1" }, "u1")).toContain("검토 승인");
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
