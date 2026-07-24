import { Navigate, Route, Routes } from "react-router-dom";
import AppShell from "./components/AppShell";
import DatabasesPage from "./pages/DatabasesPage";
import EditorPage from "./pages/EditorPage";
import QueriesPage from "./pages/QueriesPage";
import ApprovalsPage from "./pages/ApprovalsPage";
import RulesPage from "./pages/RulesPage";
import CatalogPage from "./pages/CatalogPage";
import AdminPage from "./pages/AdminPage";

export default function App() {
  return (
    <Routes>
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
    </Routes>
  );
}
