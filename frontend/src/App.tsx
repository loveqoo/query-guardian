import { Layout, Menu, Typography } from "antd";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import EditorPage from "./pages/EditorPage";
import QueriesPage from "./pages/QueriesPage";
import CatalogPage from "./pages/CatalogPage";

const MENU_ITEMS = [
  { key: "/editor", label: "쿼리 에디터" },
  { key: "/queries", label: "쿼리 목록" },
  { key: "/catalog", label: "카탈로그" },
];

function Shell() {
  const location = useLocation();
  const navigate = useNavigate();
  const selectedKey =
    MENU_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? "/editor";

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Layout.Sider theme="dark" width={220}>
        <div style={{ padding: "16px 20px" }}>
          <Typography.Text strong style={{ color: "#fff", fontSize: 16 }}>
            Query Guardian
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Content style={{ padding: 24, background: "#f5f5f5" }}>
          <div
            style={{
              background: "#fff",
              borderRadius: 8,
              padding: 24,
              minHeight: "calc(100vh - 48px)",
            }}
          >
            <Routes>
              <Route path="/" element={<Navigate to="/editor" replace />} />
              <Route path="/editor" element={<EditorPage />} />
              <Route path="/queries" element={<QueriesPage />} />
              <Route path="/catalog" element={<CatalogPage />} />
              <Route path="*" element={<Navigate to="/editor" replace />} />
            </Routes>
          </div>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Shell />
    </BrowserRouter>
  );
}
