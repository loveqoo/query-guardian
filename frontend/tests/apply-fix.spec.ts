import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { applyFix } from "../src/api/fix";
import type { Fix } from "../src/api/client";

/**
 * **제안 적용의 계약** (spec 013 §3-1 · U2).
 *
 * 서버는 고쳐진 SQL을 만들어 주지 않으므로 `applyFix`가 "적용"의 의미를 정한다. 그 의미가 틀리면
 * 사용자는 우리가 시킨 대로 눌렀는데도 여전히 막힌다 — 제안 모델이 그 자리에서 무너진다.
 *
 * ## 케이스 표는 여기 없다
 *
 * `tests/apply-fix-cases.json`(저장소 루트)을 백엔드 `FixRoundTripTest`와 **함께** 읽는다.
 * 구현은 두 벌일 수밖에 없지만(브라우저에서 Kotlin을 못 돌린다) 케이스까지 두 벌이면 갈라져도
 * 아무도 모른다 — 실제로 갈라져 있었다(설계 검토가 잡았다): 코틀린은 `" WHERE "`를 리터럴 공백으로
 * 찾고 여기서는 `\s`로 찾아서, **줄바꿈이 있는 SQL에서 코틀린만 WHERE를 하나 더 만들었다.**
 * 두 파일의 주석이 서로 "같은 규칙이어야 한다"고 약속하고 있었지만, 그 약속을 지키는 장치가
 * 사람의 기억뿐이었다.
 */

interface Case {
  name: string;
  why?: string;
  sql: string;
  fix: Fix;
  expected: string;
}

const cases: Case[] = JSON.parse(
  readFileSync(new URL("../../tests/apply-fix-cases.json", import.meta.url), "utf8"),
).cases;

test("케이스 표가 비어 있지 않다", () => {
  // 경로가 틀리거나 표가 비면 아래 루프가 0회 돌고 **전부 통과**한다 — 그 착시를 먼저 막는다.
  expect(cases.length, "케이스 표를 못 읽었다 — tests/apply-fix-cases.json 경로를 확인하라").toBeGreaterThan(5);
});

for (const c of cases) {
  test(c.name, () => {
    expect(applyFix(c.sql, c.fix), c.why ?? c.name).toBe(c.expected);
  });
}
