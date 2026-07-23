import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180, // 5173은 다른 프로젝트(my-agents/admin)가 사용 중
    strictPort: true, // 점유 시 조용히 다른 포트로 넘어가지 말고 실패하게
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
