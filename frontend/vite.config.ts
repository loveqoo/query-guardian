import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// allowedHosts: 기본은 로컬(localhost)만 — Tailscale MagicDNS 호스트명으로 접속하면
// Vite host-check(5.4.12+)에 걸리므로 VITE_ALLOWED_HOSTS로만 연다. "true"=호스트 검사 해제
// (노출 경계는 `tailscale serve`가 보장하므로 안전), 그 외엔 쉼표구분 호스트 목록.
// 형제 프로젝트(my-agents/admin)와 같은 방식 — 바인딩을 넓히지(host: true) 않는 것이 요점이다:
// 그러면 지금 붙어 있는 모든 네트워크(카페 wifi 포함)에 열리고, 이 앱은 로그인이 데모 계정 하나다.
const _ah = (process.env.VITE_ALLOWED_HOSTS ?? "").trim();
const allowedHosts =
  _ah === "true" ? true : _ah ? _ah.split(",").map((s: string) => s.trim()).filter(Boolean) : undefined;

export default defineConfig({
  plugins: [react()],
  server: {
    // IPv4 루프백에 고정(노출 범위는 그대로 로컬). 기본값 "localhost"는 macOS에서 ::1로 풀려
    // [::1]에만 바인딩되는데, `tailscale serve`는 tcp://127.0.0.1로 넘기므로 대상을 못 찾는다.
    host: "127.0.0.1",
    port: 5180, // 5173은 다른 프로젝트(my-agents/admin)가 사용 중
    strictPort: true, // 점유 시 조용히 다른 포트로 넘어가지 말고 실패하게
    allowedHosts,
    // same-origin 프록시: 브라우저는 /api로만 부르고 여기서 백엔드로 넘긴다.
    // 타깃을 127.0.0.1로 고정하는 이유: 백엔드를 루프백으로 좁혀도(server.address) 그대로 동작해야 한다
    // — "localhost"는 ::1로 풀릴 수 있어 IPv4에만 바인딩된 서버를 못 찾는다.
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
});
