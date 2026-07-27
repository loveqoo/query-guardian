import { test, expect, type Page } from "@playwright/test";

/**
 * 검사할 화면 (src/nav.tsx의 SCREENS와 같은 순서). 로그인 화면은 셸 밖이라 따로 다룬다.
 *
 * 이 목록은 **손으로 유지된다.** 손으로 나열한 목록은 빠뜨리는 쪽으로 조용히 실패하므로
 * (learning 020), 아래 `내비게이션의 모든 화면이 검사 대상이다`가 nav.tsx와 대조해 누락을 깨운다.
 */
const SCREENS = [
  { path: "/databases", name: "databases" },
  { path: "/editor", name: "editor" },
  { path: "/queries", name: "queries" },
  { path: "/approvals", name: "approvals" },
  { path: "/rules", name: "rules" },
  { path: "/catalog", name: "catalog" },
  { path: "/audit", name: "audit" },
  { path: "/admin", name: "admin" },
];

test("내비게이션의 모든 화면이 검사 대상이다", async () => {
  // 화면을 추가하고 이 파일을 안 고치면, 그 화면은 **시각 회귀 검사를 한 번도 받지 않은 채**
  // 통과한다. 검사기가 "없다"고 정직하게 답하는 종류의 실패다 — 그래서 정의를 직접 읽는다.
  const { readFileSync } = await import("node:fs");
  const nav = readFileSync(new URL("../src/nav.tsx", import.meta.url), "utf8");
  const declared = [...nav.matchAll(/path:\s*"(\/[a-z-]+)"/g)].map((m) => m[1]);
  const covered = new Set(SCREENS.map((s) => s.path));
  const missing = declared.filter((p) => !covered.has(p));
  expect(missing, `내비게이션에 있는데 레이아웃 검사가 안 하는 화면: ${missing.join(", ")}`).toEqual([]);
});

/** 세션 로그인 — 폼을 조작하지 않고 API로 쿠키를 얻는다(화면 변경에 영향받지 않는 경로). */
async function login(page: Page) {
  const response = await page.request.post("/api/auth/login", {
    data: { userId: "adm1", password: "qg-demo" },
  });
  expect(response.ok(), "데모 로그인이 실패했다 — 백엔드(8080)와 시드를 확인하라").toBeTruthy();
}

/**
 * **뷰포트를 넘는 내용 측정** — 이 스펙의 객관 지표다.
 *
 * `documentElement.scrollWidth`만 보면 부족하다: 셸에 `overflow: hidden`이 걸려 있으면 내용이 **잘려서**
 * 문서가 넓어지지 않는다(스크롤바보다 나쁘다 — 사용자는 잘린 것을 볼 방법이 없다).
 *
 * 그래서 규칙을 이렇게 정의한다: **뷰포트 오른쪽을 넘는 요소는 가로 스크롤 조상이 있을 때만 허용**한다.
 * 넓은 표·코드 블록은 자기 `overflow-x: auto` 컨테이너 안에서 스크롤하면 되고, 그 밖은 위반이다.
 */
async function expectNoClippedContent(page: Page, label: string) {
  const offenders = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth + 1;
    // **의도 기준 허용목록**: 넓어도 되는 곳은 명시된 곳뿐이다.
    // 계산된 overflow-x로 판단하면 안 된다 — `overflow-y: auto`만 걸어도 CSS 규칙에 따라
    // overflow-x가 auto로 계산되어(visible→auto) 셸 내부 전체가 "스크롤 가능"으로 보인다.
    const ALLOWED = "[data-scroll-x], .ant-table-content, .ant-table-body, .cm-scroller, .ant-tabs-nav-list";
    return Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter((el) => {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return false;
        if (rect.right <= limit) return false;
        return !el.closest(ALLOWED);
      })
      // 부모-자식이 함께 넘치면 부모만 보고한다 — 원인을 찾기 쉽게
      .filter((el, _i, all) => !all.some((other) => other !== el && other.contains(el)))
      .slice(0, 8)
      .map((el) => {
        const rect = el.getBoundingClientRect();
        const cls = el.className ? "." + el.className.toString().split(" ").slice(0, 2).join(".") : "";
        return `${el.tagName.toLowerCase()}${cls} right=${Math.round(rect.right)} w=${Math.round(rect.width)}`;
      });
  });
  expect(
    offenders,
    `${label}: 뷰포트를 넘는 요소 ${offenders.length}개(허용된 가로 스크롤 영역 밖)\n  ${offenders.join("\n  ")}`,
  ).toEqual([]);
}

/**
 * **높이 눌림 측정** — 폭만 재다가 놓친 축이다.
 *
 * 데스크톱은 "뷰포트 높이를 나눠 갖는 패널"이 각자 내부 스크롤한다. 그 구조를 좁은 화면에서 세로로 쌓으면
 * 패널마다 한 조각만 보인다 — 실측으로 높이 79px 컨테이너가 861px 내용을 담고 있었고, 사용자에게는
 * "컬럼이 보이지 않는다"로 나타났다. 스크롤 주체는 페이지(main) 하나여야 한다.
 */
async function expectNoSqueezedPanels(page: Page, label: string) {
  const squeezed = await page.evaluate(() => {
    return Array.from(document.querySelectorAll<HTMLElement>("main div"))
      .filter((el) => {
        const height = el.getBoundingClientRect().height;
        if (!el.children.length || height === 0) return false;
        // 내용이 컨테이너보다 훨씬 큰데 그 안에서 스크롤해야 하는 상태 = 한 조각만 보인다
        return el.scrollHeight > height + 40;
      })
      .slice(0, 5)
      .map((el) => `h=${Math.round(el.getBoundingClientRect().height)}/${el.scrollHeight} "${el.textContent?.trim().slice(0, 30)}"`);
  });
  expect(
    squeezed,
    `${label}: 내용이 눌린 컨테이너 ${squeezed.length}개(내부 세로 스크롤 — 좁은 화면에서는 페이지가 스크롤해야 한다)\n  ${squeezed.join("\n  ")}`,
  ).toEqual([]);
}

test.describe("레이아웃", () => {
  test("로그인 화면", async ({ page }, testInfo) => {
    await page.goto("/login");
    await page.waitForLoadState("networkidle");
    await expectNoClippedContent(page, `login@${testInfo.project.name}`);
    await expect(page).toHaveScreenshot(`login.png`, { fullPage: true });
  });

  for (const screen of SCREENS) {
    test(screen.name, async ({ page }, testInfo) => {
      await login(page);
      await page.goto(screen.path);
      await page.waitForLoadState("networkidle");
      // antd 카드·표가 자리를 잡을 시간 (스냅샷 흔들림 방지)
      await page.waitForTimeout(400);
      await expectNoClippedContent(page, `${screen.name}@${testInfo.project.name}`);
      // 좁은 화면에서만 검사한다 — 데스크톱의 패널 내부 스크롤은 의도된 설계다
      if (testInfo.project.name !== "desktop") {
        await expectNoSqueezedPanels(page, `${screen.name}@${testInfo.project.name}`);
      }
      await expect(page).toHaveScreenshot(`${screen.name}.png`, { fullPage: true });
    });
  }
});
