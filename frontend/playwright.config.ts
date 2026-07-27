import { defineConfig, devices } from "@playwright/test";

/**
 * 시각 회귀 하네스 (spec 009).
 *
 * 목적 두 개:
 * 1. **데스크톱 픽셀 불변을 증명한다** — 모바일 대응이 데스크톱을 건드리지 않았음을 스냅샷으로 막는다.
 *    코드 diff로는 증명할 수 없다(전역 CSS 한 줄이 전 화면을 바꾼다).
 * 2. **가로 넘침을 측정한다** — "모바일에서 보기 어렵다"를 객관 지표(scrollWidth ≤ clientWidth)로 바꾼다.
 *
 * 개발 서버는 이미 떠 있으면 재사용한다(5180은 strictPort이므로 중복 기동이 조용히 다른 포트로 가지 않는다).
 */
export default defineConfig({
  testDir: "./tests",
  /**
   * **허용 오차 0** — 짐작이 아니라 실측이다.
   *
   * 예전 값은 `maxDiffPixelRatio: 0.01`이었고 근거는 "폰트 앤티에일리어싱 차이로 실패하면 신호가
   * 죽는다"였다. 그럴듯하지만 재 본 적이 없는 방어막이었고, **실제로 신호를 죽였다**:
   * spec 013에서 내비게이션 항목 하나를 추가하자 사이드바가 모든 화면에서 한 칸씩 밀렸는데,
   * 7개 중 1개(admin, 18659px = 0.02)만 실패하고 나머지는 조용히 통과했다
   * (databases 7829px · approvals 4054px — 전부 1% 아래). 1280×900에서 1%는 **약 11,500픽셀**이고,
   * 그 정도면 레이아웃이 통째로 움직여도 통과한다.
   *
   * 실측(2026-07-27, 이 기계): 기준선 재생성 후 오차 0으로 **4회 연속 40/40 통과**. 잡음 바닥이 0이다.
   *
   * 나중에 이것이 흔들리면(OS·폰트 갱신 등) **넓히기 전에 다시 재라.** 폭을 짐작으로 늘리면
   * 그 순간 이 하네스는 다시 "통과하지만 아무것도 지키지 않는" 상태로 돌아간다.
   */
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0 } },
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:5180",
    // 애니메이션은 스냅샷을 흔든다 (antd 전환 효과)
    launchOptions: { args: ["--force-prefers-reduced-motion"] },
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5180",
    reuseExistingServer: true,
    timeout: 60_000,
  },
  projects: [
    /**
     * **폭과 무관한 계약 테스트** — 브라우저는 실행기일 뿐 뷰포트가 축이 아니다.
     *
     * 뷰포트 프로젝트에서 떼어 낸 이유 둘:
     * 1. 같은 단정이 **4번 돌고 있었다**(폭이 답을 바꾸지 않는데).
     * 2. **CI에서 이것만 돌릴 수 있어야 한다.** 시각 회귀 기준선은 이 기계(macOS)에서 만들어져
     *    폰트 렌더링이 다른 리눅스 러너에서는 전부 실패하고, E2E는 백엔드·DB가 떠 있어야 한다.
     *    섞어 두면 CI에 넣을 수 있는 부분까지 못 넣는다.
     */
    { name: "logic", testMatch: /(apply-fix|limit-status|runnable)\.spec\.ts$/ },

    // 폭이 정책의 축이다: 390(휴대폰) / 768(경계) / 1280(데스크톱 — 원본 디자인의 기준)
    ...(
      [
        { name: "mobile", width: 390, height: 844 },
        { name: "tablet", width: 768, height: 1024 },
        { name: "desktop", width: 1280, height: 900 },
        // 긴 뷰포트: 앱이 main 안에서 스크롤하므로 `fullPage`가 첫 화면만 담는다.
        // 이 프로젝트가 화면 **아래쪽**(표·이력 등)까지 담는다 — 그 사각에서 실제로 결함을 놓쳤다.
        { name: "mobile-tall", width: 390, height: 2000 },
      ] as const
    ).map(({ name, width, height }) => ({
      name,
      testIgnore: /(apply-fix|limit-status|runnable)\.spec\.ts$/,
      use: { ...devices["Desktop Chrome"], viewport: { width, height } },
    })),
  ],
});
