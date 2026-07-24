import { Layout, Menu } from "antd";
import {
  BellOutlined,
  LockOutlined,
  LogoutOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import type { MenuProps } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { LOGO_GRADIENT, SIDER_BG } from "../theme";
import { NAV_GROUPS, SCREENS, screenByPath } from "../nav";

const { Sider, Header, Content } = Layout;

const menuItems: MenuProps["items"] = NAV_GROUPS.map((g) => ({
  type: "group" as const,
  key: g.group,
  label: g.group,
  children: g.items.map((it) => ({ key: it.key, label: it.title, icon: it.icon })),
}));

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const active = screenByPath(location.pathname) ?? SCREENS[0];

  return (
    <Layout style={{ height: "100vh", overflow: "hidden" }}>
      <Sider
        width={236}
        theme="dark"
        style={{ background: SIDER_BG, display: "flex", flexDirection: "column" }}
      >
        {/* logo header */}
        <div
          style={{
            height: 56,
            flex: "none",
            display: "flex",
            alignItems: "center",
            gap: 10,
            padding: "0 20px",
            borderBottom: "1px solid rgba(255,255,255,.08)",
          }}
        >
          <div
            style={{
              width: 30,
              height: 30,
              flex: "none",
              borderRadius: 7,
              background: LOGO_GRADIENT,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "#fff",
              fontSize: 16,
            }}
          >
            <LockOutlined />
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ color: "#fff", fontSize: 15, fontWeight: 600, lineHeight: 1.2 }}>
              Query Guardian
            </div>
            <div style={{ color: "rgba(255,255,255,.45)", fontSize: 11, lineHeight: 1.2 }}>
              안전한 SQL 거버넌스
            </div>
          </div>
        </div>

        {/* menu */}
        <div style={{ flex: 1, overflowY: "auto", padding: "8px 0" }}>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[active.key]}
            items={menuItems}
            onClick={({ key }) => {
              const screen = SCREENS.find((s) => s.key === key);
              if (screen) navigate(screen.path);
            }}
          />
        </div>

        {/* profile footer */}
        <div
          style={{
            flex: "none",
            padding: "12px 16px",
            borderTop: "1px solid rgba(255,255,255,.08)",
            display: "flex",
            alignItems: "center",
            gap: 10,
          }}
        >
          <div
            style={{
              width: 32,
              height: 32,
              flex: "none",
              borderRadius: "50%",
              background: "#1677ff",
              color: "#fff",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            김
          </div>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div
              style={{
                color: "#fff",
                fontSize: 13,
                lineHeight: 1.3,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
              }}
            >
              김도현
            </div>
            <div style={{ color: "rgba(255,255,255,.45)", fontSize: 11, lineHeight: 1.3 }}>
              데이터 분석가
            </div>
          </div>
          <span style={{ color: "rgba(255,255,255,.45)", cursor: "pointer", display: "inline-flex" }}>
            <LogoutOutlined />
          </span>
        </div>
      </Sider>

      <Layout style={{ minWidth: 0 }}>
        {/* header */}
        <Header
          style={{
            height: 56,
            flex: "none",
            background: "#fff",
            borderBottom: "1px solid #f0f0f0",
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "0 24px",
            gap: 16,
            lineHeight: "normal",
          }}
        >
          <div style={{ minWidth: 0 }}>
            {/* 디자인 dc.html: crumb은 그룹명 단독 (titles[screen][1]) */}
            <div style={{ fontSize: 12, lineHeight: 1.3, color: "rgba(0,0,0,.45)" }}>
              {active.group}
            </div>
            <div
              style={{
                fontSize: 16,
                fontWeight: 600,
                color: "rgba(0,0,0,.88)",
                lineHeight: 1.3,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
              }}
            >
              {active.title}
            </div>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 14, flex: "none" }}>
            <span style={{ color: "rgba(0,0,0,.65)", cursor: "pointer", display: "inline-flex", fontSize: 18 }}>
              <BellOutlined />
            </span>
            <span style={{ color: "rgba(0,0,0,.65)", cursor: "pointer", display: "inline-flex", fontSize: 18 }}>
              <QuestionCircleOutlined />
            </span>
          </div>
        </Header>

        {/* content */}
        <Content style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: 24 }}>
          <div key={active.key} style={{ animation: "qgFade .2s", height: "100%" }}>
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}
