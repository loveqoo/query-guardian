import { Spin } from "antd";
import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import AppShell from "./components/AppShell";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import DatabasesPage from "./pages/DatabasesPage";
import EditorPage from "./pages/EditorPage";
import QueriesPage from "./pages/QueriesPage";
import ApprovalsPage from "./pages/ApprovalsPage";
import RulesPage from "./pages/RulesPage";
import CatalogPage from "./pages/CatalogPage";
import AdminPage from "./pages/AdminPage";
import LoginPage from "./pages/LoginPage";

/** 부트스트랩(me()) 대기 화면 — 로그인 여부를 알기 전에는 라우팅을 결정하지 않는다. */
function Booting() {
  return (
    <div
      style={{
        height: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Spin size="large" />
    </div>
  );
}

/** 미인증이면 전 라우트를 `/login`으로 (spec 007 §8). 로그인 후 원래 경로로 되돌린다. */
function RequireAuth() {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) return <Booting />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}

/** 이미 로그인된 상태에서 `/login`에 오면 앱으로 되돌린다. */
function LoginRoute() {
  const { user, ready } = useAuth();
  if (!ready) return <Booting />;
  if (user) return <Navigate to="/editor" replace />;
  return <LoginPage />;
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        {/* 로그인은 셸 없이 단독 렌더 */}
        <Route path="/login" element={<LoginRoute />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route path="/" element={<Navigate to="/editor" replace />} />
            <Route path="/databases" element={<DatabasesPage />} />
            <Route path="/editor" element={<EditorPage />} />
            <Route path="/queries" element={<QueriesPage />} />
            <Route path="/approvals" element={<ApprovalsPage />} />
            <Route path="/rules" element={<RulesPage />} />
            <Route path="/catalog" element={<CatalogPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="*" element={<Navigate to="/editor" replace />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  );
}
