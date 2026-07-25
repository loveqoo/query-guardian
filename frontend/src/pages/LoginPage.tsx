import { useState } from "react";
import { Alert, App, Button, Input, Select, Tag } from "antd";
import { LockOutlined, LoginOutlined, UserOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { ApiError, apiErrorMessage } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { DEMO_PASSWORD, DEMO_USERS, ROLE_COLOR, ROLE_LABEL } from "../auth/demoUsers";
import { LOGO_GRADIENT, SIDER_BG } from "../theme";

/**
 * 로그인 화면 (spec 007 §8). 셸 없이 단독 렌더된다.
 * 사용자 목록은 **프론트 상수**(공개 API 추가 금지, L4). 실패는 사유 구분 없는 동일 401 메시지.
 */
export default function LoginPage() {
  const { login } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [userId, setUserId] = useState(DEMO_USERS[0].id);
  const [password, setPassword] = useState(DEMO_PASSWORD);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const selected = DEMO_USERS.find((u) => u.id === userId) ?? DEMO_USERS[0];

  async function submit() {
    if (!password) {
      setError("비밀번호를 입력하세요");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login(userId, password);
      message.success(`${selected.name} 님으로 로그인했습니다`);
      navigate("/editor", { replace: true });
    } catch (err) {
      const server = apiErrorMessage(err);
      if (err instanceof ApiError && err.status === 401) {
        setError(server ?? "아이디 또는 비밀번호가 올바르지 않습니다");
      } else {
        setError(server ?? "로그인에 실패했습니다");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
        background: `linear-gradient(160deg, ${SIDER_BG} 0%, #10182b 55%, #0b1220 100%)`,
      }}
    >
      <div
        style={{
          width: 400,
          maxWidth: "100%",
          background: "#fff",
          borderRadius: 12,
          boxShadow: "0 12px 40px rgba(0,0,0,.28)",
          padding: "32px 32px 28px",
        }}
      >
        {/* 로고 */}
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
          <div
            style={{
              width: 40,
              height: 40,
              flex: "none",
              borderRadius: 9,
              background: LOGO_GRADIENT,
              color: "#fff",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 20,
            }}
          >
            <LockOutlined />
          </div>
          <div>
            <div style={{ fontSize: 18, fontWeight: 600, lineHeight: 1.2 }}>Query Guardian</div>
            <div style={{ fontSize: 12, color: "rgba(0,0,0,.45)", lineHeight: 1.3 }}>
              안전한 SQL 거버넌스
            </div>
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: "block", fontSize: 13, fontWeight: 500, marginBottom: 8 }}>
            사용자
          </label>
          <Select
            style={{ width: "100%" }}
            value={userId}
            onChange={(v) => {
              setUserId(v);
              setError(null);
            }}
            suffixIcon={<UserOutlined />}
            options={DEMO_USERS.map((u) => ({
              value: u.id,
              label: `${u.name} · ${u.title} (${ROLE_LABEL[u.role]})`,
            }))}
          />
          <div style={{ marginTop: 8, display: "flex", alignItems: "center", gap: 8 }}>
            <Tag color={ROLE_COLOR[selected.role]} style={{ margin: 0 }}>
              {ROLE_LABEL[selected.role]}
            </Tag>
            <span style={{ fontSize: 12, color: "rgba(0,0,0,.45)", fontFamily: "monospace" }}>
              {selected.id}
            </span>
          </div>
        </div>

        <div style={{ marginBottom: 20 }}>
          <label style={{ display: "block", fontSize: 13, fontWeight: 500, marginBottom: 8 }}>
            비밀번호
          </label>
          <Input.Password
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setError(null);
            }}
            onPressEnter={() => void submit()}
            prefix={<LockOutlined style={{ color: "rgba(0,0,0,.25)" }} />}
            placeholder="비밀번호"
          />
        </div>

        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}

        <Button
          type="primary"
          block
          size="large"
          icon={<LoginOutlined />}
          loading={busy}
          onClick={() => void submit()}
        >
          로그인
        </Button>

        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 20 }}
          message={`데모 공통 비밀번호 "${DEMO_PASSWORD}"`}
          description="모든 계정이 같은 비밀번호를 쓰는 데모 환경입니다 — 운영 반입 금지 (spec 007 H9)."
        />
      </div>
    </div>
  );
}
