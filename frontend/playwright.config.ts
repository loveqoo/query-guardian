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
  // 스냅샷 비교는 렌더링 편차를 약간 허용한다 — 폰트 앤티에일리어싱 차이로 실패하면 신호가 죽는다
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0.01 } },
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
    // 폭이 정책의 축이다: 390(휴대폰) / 768(경계) / 1280(데스크톱 — 원본 디자인의 기준)
    { name: "mobile", use: { ...devices["Desktop Chrome"], viewport: { width: 390, height: 844 } } },
    { name: "tablet", use: { ...devices["Desktop Chrome"], viewport: { width: 768, height: 1024 } } },
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 900 } } },
  ],
});
