import { App, Drawer, Grid, Layout, Menu, Tag, Tooltip } from "antd";
import {
  BellOutlined,
  LockOutlined,
  LogoutOutlined,
  MenuOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import type { MenuProps } from "antd";
import { useEffect, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { LOGO_GRADIENT, SIDER_BG } from "../theme";
import { NAV_GROUPS, SCREENS, screenByPath } from "../nav";
import { useAuth } from "../auth/AuthContext";
import { ROLE_COLOR, ROLE_LABEL } from "../auth/demoUsers";

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
  const { user, logout } = useAuth();
  const { message } = App.useApp();

  // spec 009: **992px(antd lg) 미만**을 좁은 화면으로 본다.
  // 처음에 768(md)로 잡았다가 실측으로 고쳤다 — 768px에서도 사이드바 236px를 빼면 내용이 532px뿐이어서
  // `300px 300px 1fr` 같은 그리드가 그대로 넘쳤다(태블릿 3화면 실패). 기준은 화면 폭이 아니라 **남는 내용 폭**이다.
  const screens = Grid.useBreakpoint();
  const isMobile = screens.lg === false;
  const [navOpen, setNavOpen] = useState(false);

  // 화면을 옮기면 서랍을 닫는다 — 모바일에서 내비를 열어둔 채 이동하면 내용이 가려진다
  useEffect(() => setNavOpen(false), [location.pathname]);

  /** 로그아웃 → 세션 무효화 후 로그인 화면. 뒤로가기로 화면이 복원되지 않도록 replace (L6). */
  async function handleLogout() {
    await logout();
    message.success("로그아웃되었습니다");
    navigate("/login", { replace: true });
  }

  /** 로고 헤더 — 사이드바와 모바일 서랍이 공유한다(디자인 원본의 높이·간격 유지). */
  const logo = (
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
  );

  const nav = (
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
  );

  const profile = (
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
        {(user?.displayName ?? "?").slice(0, 1)}
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div
          style={{
            color: "#fff",
            fontSize: 13,
            lineHeight: 1.3,
            display: "flex",
            alignItems: "center",
            gap: 6,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {user?.displayName ?? "—"}
          {user && (
            <Tag color={ROLE_COLOR[user.role]} style={{ margin: 0, fontSize: 10, lineHeight: "16px" }}>
              {ROLE_LABEL[user.role]}
            </Tag>
          )}
        </div>
        <div style={{ color: "rgba(255,255,255,.45)", fontSize: 11, lineHeight: 1.3 }}>
          {user?.title ?? ""}
        </div>
      </div>
      <Tooltip title="로그아웃" placement="right">
        <span
          onClick={() => void handleLogout()}
          style={{ color: "rgba(255,255,255,.45)", cursor: "pointer", display: "inline-flex" }}
        >
          <LogoutOutlined />
        </span>
      </Tooltip>
    </div>
  );

  return (
    // 100dvh: iOS 주소창이 100vh를 실제 보이는 높이보다 크게 만들어 하단이 잘린다
    <Layout style={{ height: "100dvh", overflow: "hidden" }}>
      {isMobile ? (
        <Drawer
          placement="left"
          open={navOpen}
          onClose={() => setNavOpen(false)}
          closable={false}
          width={236}
          styles={{ body: { padding: 0, background: SIDER_BG, display: "flex", flexDirection: "column" } }}
        >
          {logo}
          {nav}
          {profile}
        </Drawer>
      ) : (
        <Sider
          width={236}
          theme="dark"
          style={{ background: SIDER_BG, display: "flex", flexDirection: "column" }}
        >
          {logo}
          {nav}
          {profile}
        </Sider>
      )}

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
            padding: isMobile ? "0 12px" : "0 24px",
            gap: isMobile ? 8 : 16,
            lineHeight: "normal",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0 }}>
            {isMobile && (
              <span
                onClick={() => setNavOpen(true)}
                aria-label="메뉴 열기"
                style={{ color: "rgba(0,0,0,.75)", cursor: "pointer", display: "inline-flex", fontSize: 18 }}
              >
                <MenuOutlined />
              </span>
            )}
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
        <Content style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: isMobile ? 12 : 24 }}>
          <div key={active.key} style={{ animation: "qgFade .2s", height: "100%" }}>
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}
