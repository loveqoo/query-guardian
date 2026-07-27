import { test, expect, type Page } from "@playwright/test";

/**
 * **판정 → 제안 → 실행 → 감사** 한 바퀴 (spec 013 §5 수용 기준 U1~U6).
 *
 * ## 왜 E2E여야 하는가
 *
 * 여기까지의 검사는 조각을 따로 쟀다 — 백엔드는 "제안대로 고치면 통과한다"를, 프론트는 "적용기가
 * 규칙을 지킨다"를. **둘을 잇는 자리는 아무도 안 봤다.** 서버가 주는 조각을 화면이 제대로 읽는지,
 * 읽은 것을 에디터에 넣는지, 넣은 결과를 다시 판정에 태우는지는 실제로 눌러 봐야 안다.
 *
 * ## 선행 조건
 *
 * 백엔드(8080) + 데모 시드 + **저장·승인된 쿼리 1건**이 필요하다. 없으면 실행·이력·감사가 전부
 * 빈 화면이라 "통과했지만 아무것도 안 잰" 상태가 된다 — 그래서 준비물이 없으면 **건너뛰지 않고
 * 실패**시킨다(조용한 스킵은 커버리지 착시를 만든다).
 *
 * ## 데스크톱만 돈다
 *
 * 좁은 화면의 레이아웃은 `layout.spec.ts`가 이미 재고 있고, 여기서 재는 것은 **동작**이다.
 * 네 뷰포트로 같은 동작을 네 번 돌리면 실패 하나가 네 줄로 늘어날 뿐이다.
 */

const DEMO_PASSWORD = "qg-demo";

async function loginAs(page: Page, userId: string) {
  const res = await page.request.post("/api/auth/login", {
    data: { userId, password: DEMO_PASSWORD },
  });
  expect(res.ok(), `데모 로그인 실패(${userId}) — 백엔드(8080)와 시드를 확인하라`).toBeTruthy();
}

/** 준비물: 승인된 저장 쿼리 1건. 없으면 이 파일이 재는 것이 사라지므로 실패로 알린다. */
async function approvedQueryId(page: Page): Promise<string> {
  const res = await page.request.get("/api/queries");
  expect(res.ok(), "저장 쿼리 목록을 읽지 못했다").toBeTruthy();
  const rows = (await res.json()) as { id: number | string; reviewStatus: string; owner?: string }[];
  const approved = rows.find((q) => q.reviewStatus === "APPROVED");
  expect(
    approved,
    "검토 승인된 저장 쿼리가 없다 — 실행·결과·이력·감사를 하나도 잴 수 없다. 준비물을 만들고 다시 돌려라",
  ).toBeTruthy();
  return String(approved!.id);
}

test.describe.configure({ mode: "serial" });

// 좁은 화면 레이아웃은 layout.spec.ts가 재고, 여기서 재는 것은 **동작**이다.
// 네 뷰포트로 같은 동작을 네 번 돌리면 실패 하나가 네 줄로 늘어날 뿐이다.
test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "동작 검사는 데스크톱에서만");
});

test("U1·U2 — 막히면 조각이 보이고, 적용하면 통과한다", async ({ page }) => {
  await loginAs(page, "u1");
  await page.goto("/editor");
  await page.waitForLoadState("networkidle");

  // 승인 요청을 골라야 서버가 purposeCode를 주입한다(저장 게이트와 같은 조건).
  const requestPicker = page.locator(".ant-select").first();
  await requestPicker.click();
  await page.locator(".ant-select-item-option").first().click();

  const editor = page.locator(".cm-content");
  await editor.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("SELECT email FROM users LIMIT 10");

  await page.getByRole("button", { name: "규칙 검사" }).click();

  // U1: 차단되고 **조각이 실린다**
  const applyButton = page.getByRole("button", { name: "적용" }).first();
  await expect(applyButton, "막혔는데 [적용]이 없다 — 사용자는 여기서 멈춘다").toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("mask_email(email)").first()).toBeVisible();

  // U2: 적용 → **서버를 부르지 않고** 에디터가 바뀐다 → 자동 재판정 → 통과
  await applyButton.click();
  await expect(editor, "적용했는데 에디터가 안 바뀌었다").toContainText("mask_email(email)");
  await expect(
    page.getByText("규칙 위반이 없습니다"),
    "제안대로 고쳤는데 여전히 막힌다 — 제안과 판정이 갈라졌다(spec 012 I3)",
  ).toBeVisible({ timeout: 10_000 });
});

test("U4 — 미저장 쿼리는 실행 버튼이 꺼져 있고 이유가 보인다", async ({ page }) => {
  await loginAs(page, "u1");
  await page.goto("/editor");
  await page.waitForLoadState("networkidle");

  const run = page.getByRole("button", { name: "실행" });
  await expect(run, "미저장 상태인데 실행이 활성이다 — 눌러 보면 403이고 사용자는 이유를 모른다").toBeDisabled();

  // 이유는 **서버 어휘**로 뜬다. 비활성 버튼은 이벤트를 안 받으므로 래퍼에 호버한다.
  await run.locator("xpath=..").hover();
  await expect(page.getByText("저장한 뒤에 실행할 수 있습니다")).toBeVisible({ timeout: 5_000 });
});

test("U3·U5 — 실행 결과와 상한 세 값, 그리고 결과가 저장소에 남지 않는다", async ({ page }) => {
  await loginAs(page, "u1");
  const id = await approvedQueryId(page);

  await page.goto(`/editor?id=${id}&run=1`);
  await page.waitForLoadState("networkidle");

  // U3: 결과 푸터 — `N rows · X.XXs`
  await expect(
    page.getByText(/\d+ rows · \d+\.\d\ds/),
    "실행 결과 푸터가 없다 — 실행이 실패했거나 결과가 그려지지 않았다",
  ).toBeVisible({ timeout: 20_000 });

  // 실행된 SQL은 **기본 접힘**이다 — 펼쳐 두면 사용자가 원본과 혼동한다
  const sqlToggle = page.getByRole("button", { name: "실제 실행된 SQL 보기" });
  await expect(sqlToggle).toBeVisible();
  await sqlToggle.click();
  // 서버가 바꾸는 것은 **이름과 양**뿐이다(spec 012 I1) — 물리 테이블명이 보여야 한다
  await expect(page.locator("pre").filter({ hasText: "demo_" }).first()).toBeVisible();

  /**
   * U5(F1): **결과 행이 브라우저 저장소에 없다.**
   *
   * 컬럼 이름이 아니라 **값**을 찾는다 — 이름은 스키마 사전에 정상적으로 캐시될 수 있지만
   * 값은 어디에도 남으면 안 된다(spec 008 §6).
   */
  const firstCell = await page.locator(".ant-tabs-tabpane, [data-scroll-x]").last().innerText();
  const sample = firstCell
    .split("\n")
    .map((s) => s.trim())
    .find((s) => s.includes("@") || /^\d{2,}$/.test(s));
  expect(sample, "결과에서 검사할 값을 못 골랐다 — 이 단정이 아무것도 안 재고 있다").toBeTruthy();

  const leaked = await page.evaluate((needle: string) => {
    const found: string[] = [];
    for (const store of [localStorage, sessionStorage]) {
      for (let i = 0; i < store.length; i++) {
        const key = store.key(i)!;
        if ((store.getItem(key) ?? "").includes(needle)) found.push(key);
      }
    }
    return found;
  }, sample!);
  expect(leaked, `결과 값이 브라우저 저장소에 남았다: ${leaked.join(", ")}`).toEqual([]);
});

test("U6 — 실행 감사가 차단·미리보기까지 보인다 (스튜어드만)", async ({ page }) => {
  // 분석가에게는 화면 자체가 막힌다
  await loginAs(page, "u1");
  await page.goto("/audit");
  await page.waitForLoadState("networkidle");
  await expect(page.getByText(/STEWARD 이상만 조회할 수 있습니다/)).toBeVisible();

  await loginAs(page, "u4");
  await page.goto("/audit");
  await page.waitForLoadState("networkidle");

  // 전체 건수는 **서버 헤더**에서 온다 — 받은 목록 길이로 세면 "200건이 전부"로 읽힌다
  await expect(page.getByText(/전체 \d+건/)).toBeVisible({ timeout: 10_000 });
  // 미리보기 기록이 보인다 — query_id가 null이라 쿼리별 이력에서는 안 보이는 종류다
  await expect(page.locator(".ant-table-row").first()).toBeVisible();
});

/**
 * **에디터에서도** 대행 실행이 막히는가 (설계 검토가 잡은 구멍).
 *
 * 목록 화면만 막고 에디터를 안 막으면 `에디터에서 열기`로 들어가 실행할 수 있다 —
 * 서버는 403을 주지만 화면이 그때까지 거짓 기대를 준다. 두 진입점이 **같은 판정 함수**를
 * 쓰는지 여기서 잰다.
 */
test("대행 실행 금지 — 에디터로 열어도 막힌다", async ({ page }) => {
  await loginAs(page, "u1");
  const id = await approvedQueryId(page); // u1 소유의 승인된 쿼리

  await loginAs(page, "u4"); // 스튜어드로 갈아탄다 — 볼 수는 있다
  await page.goto(`/editor?id=${id}`);
  await page.waitForLoadState("networkidle");

  const run = page.getByRole("button", { name: "실행" });
  await expect(
    run,
    "스튜어드가 남의 쿼리를 에디터로 열었는데 실행이 활성이다 — 목록만 막고 에디터를 안 막았다",
  ).toBeDisabled();
  await run.locator("xpath=..").hover();
  await expect(page.getByText(/대행 실행 불허/)).toBeVisible({ timeout: 5_000 });
});

test("대행 실행 금지 — 스튜어드는 남의 쿼리를 보되 실행하지 못한다", async ({ page }) => {
  await loginAs(page, "u4");
  await page.goto("/queries");
  await page.waitForLoadState("networkidle");

  const rows = page.locator(".ant-table-row");
  await expect(rows.first()).toBeVisible({ timeout: 10_000 });

  // 첫 행의 실행 버튼(재생 아이콘)이 비활성이어야 한다 — u4가 만든 쿼리가 아니다.
  const runIcon = rows.first().locator("button:has(.anticon-play-circle)");
  await expect(
    runIcon,
    "스튜어드에게 남의 쿼리 실행이 활성이다 — 서버는 403을 준다(결정 14), 화면이 거짓 기대를 준 것이다",
  ).toBeDisabled();
});
