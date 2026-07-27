import { test, expect } from "@playwright/test";
import { limitStatus } from "../src/api/execution";

/**
 * **상한 세 값의 계약** (spec 013 F2).
 *
 * 백엔드가 `effectiveLimit`·`configuredCap`·`moreRowsExist`를 따로 준 이유는 실측으로 얻은 것이다:
 * 한 값으로 뭉쳤더니 사용자가 `LIMIT 2`로 스스로 좁힌 실행이 "상한 2가 걸려 잘렸다"로 남았다
 * (spec 008 적대 검토 D5). 화면이 다시 뭉치면 그 발견이 화면에서 되살아난다.
 */

const of = (effectiveLimit: number | null, configuredCap: number | null, moreRowsExist: boolean | null) =>
  limitStatus({ effectiveLimit, configuredCap, moreRowsExist });

test("적용 상한 == 설정 상한이면 거버넌스가 자른 것이다", () => {
  const s = of(120, 120, true);
  expect(s.truncatedByGovernance).toBe(true);
  expect(s.moreRows).toBe("있음");
});

test("사용자가 스스로 좁힌 것은 자른 것이 아니다", () => {
  // `LIMIT 5`를 쓴 실행. 설정 상한 1000은 걸리지도 않았다 — 경고하면 거짓말이다.
  expect(of(5, 1000, false).truncatedByGovernance).toBe(false);
});

test("`더 있는지`는 세 상태다 — null은 없음이 아니다", () => {
  expect(of(120, 120, true).moreRows).toBe("있음");
  expect(of(120, 120, false).moreRows).toBe("없음");
  // 상한 0이면 초과 탐지용 1행조차 조회하지 않는다 → 확인 자체를 안 했다.
  expect(of(0, 0, null).moreRows).toBe("확인하지 않음");
});

test("서버가 상한을 말하지 않으면 자른 것으로 읽지 않는다", () => {
  // 둘 중 하나라도 없으면 "같다"를 판정할 수 없다. 모를 때 경고하면 그것도 거짓말이다.
  expect(of(null, 1000, null).truncatedByGovernance).toBe(false);
  expect(of(1000, null, null).truncatedByGovernance).toBe(false);
});
