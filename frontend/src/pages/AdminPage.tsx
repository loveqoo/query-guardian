import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Spin,
  Switch,
  Tag,
  Tooltip,
  theme,
} from "antd";
import { LockOutlined, ReloadOutlined, SaveOutlined } from "@ant-design/icons";
import { MONO_FONT } from "../theme";
import { useAuth } from "../auth/AuthContext";
import { ROLE_COLOR, ROLE_LABEL } from "../auth/demoUsers";
import { useUsers } from "../auth/useUsers";
import {
  ApiError,
  apiErrorMessage,
  getPermissions,
  listPermissionHistory,
  savePermissions,
  type AppUser,
  type PermissionEvent,
  type Permissions,
  type TablePerm,
} from "../api/client";

/**
 * 접근 권한 관리 (spec 007 §8) — 실 API 연결.
 *
 * - 목록: `GET /api/users` (전 인증 사용자 열람 가능)
 * - 권한: `GET /api/users/{id}/permissions` (**본인 또는 ADMIN**), `PUT`(ADMIN 전용)
 * - 이력: `GET /api/users/{id}/permissions/history` (ADMIN 전용, append-only 감사)
 *
 * 규칙 두 가지를 화면으로 못박는다:
 * 1) **ADMIN이 아니면 읽기 전용** — 토글·체크박스 비활성 + 안내.
 * 2) **자기 자신의 권한은 편집 금지** — 저장 시 403 `CANNOT_EDIT_OWN_PERMISSION` (자기상향 방지, M2).
 */

/** 단일 서버 전제 (spec 007 §3.1 C3) — 멀티 서버·DB 차원은 ④로 이연. */
const SERVER_KEY = "mysql-prod";
const SERVER_LABEL = "prod-main";
const SERVER_VENDOR = "MySQL";

const AVATAR_COLORS = ["#1677ff", "#722ed1", "#08979c", "#d46b08", "#eb2f96"];

function avatarColor(id: string): string {
  let sum = 0;
  for (let i = 0; i < id.length; i += 1) sum += id.charCodeAt(i);
  return AVATAR_COLORS[sum % AVATAR_COLORS.length];
}

function Avatar({ user, size, font }: { user: AppUser; size: number; font: number }) {
  return (
    <span
      style={{
        width: size,
        height: size,
        flex: "none",
        borderRadius: "50%",
        background: avatarColor(user.id),
        color: "#fff",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: font,
        fontWeight: 600,
      }}
    >
      {user.displayName.slice(0, 1)}
    </span>
  );
}

function fmtDateTime(iso: string): string {
  return iso.length >= 16 ? iso.slice(0, 16).replace("T", " ") : iso;
}

export default function AdminPage() {
  const { token } = theme.useToken();
  const { message } = App.useApp();
  const { user: me, isAdmin } = useAuth();
  const { users } = useUsers();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [perms, setPerms] = useState<Permissions | null>(null);
  const [draft, setDraft] = useState<PermDraft | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [history, setHistory] = useState<PermissionEvent[]>([]);
  const [hovered, setHovered] = useState<string | null>(null);

  // 기본 선택: ADMIN은 첫 사용자, 그 외는 본인(다른 사용자는 403)
  useEffect(() => {
    if (selectedId || users.length === 0) return;
    setSelectedId(isAdmin ? users[0].id : (me?.id ?? users[0].id));
  }, [users, isAdmin, me, selectedId]);

  const selected = users.find((u) => u.id === selectedId) ?? null;
  /** 자기 자신의 권한은 ADMIN도 편집 불가 (403 CANNOT_EDIT_OWN_PERMISSION). */
  const isSelf = !!selected && selected.id === me?.id;
  const canEdit = isAdmin && !isSelf;

  const load = useCallback(
    async (userId: string) => {
      setLoading(true);
      setLoadError(null);
      setHistory([]);
      try {
        const p = await getPermissions(userId);
        setPerms(p);
        setDraft(toDraft(p));
      } catch (e) {
        setPerms(null);
        setDraft(null);
        setLoadError(
          e instanceof ApiError && e.status === 403
            ? "본인 또는 ADMIN만 권한을 조회할 수 있습니다"
            : (apiErrorMessage(e) ?? "권한을 불러오지 못했습니다"),
        );
      } finally {
        setLoading(false);
      }
      if (isAdmin) {
        try {
          setHistory(await listPermissionHistory(userId));
        } catch {
          setHistory([]);
        }
      }
    },
    [isAdmin],
  );

  useEffect(() => {
    if (!selectedId) return;
    void load(selectedId);
  }, [selectedId, load]);

  const dirty = useMemo(() => {
    if (!perms || !draft) return false;
    if (perms.serverAllowed !== draft.serverAllowed) return true;
    return perms.tables.some((t) => draft.tables[t.tableName] !== t.allowed);
  }, [perms, draft]);

  const allowedCount = useMemo(() => {
    if (!perms || !draft) return 0;
    if (!draft.serverAllowed) return 0;
    return perms.tables.filter((t) => draft.tables[t.tableName]).length;
  }, [perms, draft]);

  async function handleSave() {
    if (!selected || !perms || !draft) return;
    const body = {
      serverAllowed: draft.serverAllowed,
      tables: perms.tables.map<TablePerm>((t) => ({
        tableName: t.tableName,
        allowed: !!draft.tables[t.tableName],
      })),
    };
    setSaving(true);
    try {
      const next = await savePermissions(selected.id, body);
      setPerms(next);
      setDraft(toDraft(next));
      message.success(`${selected.displayName} 님의 권한을 저장했습니다`);
      if (isAdmin) {
        try {
          setHistory(await listPermissionHistory(selected.id));
        } catch {
          /* 이력 실패는 저장 결과에 영향 없음 */
        }
      }
    } catch (e) {
      // 403: ADMIN 아님 · **자기 자신의 권한 편집** (CANNOT_EDIT_OWN_PERMISSION)
      message.error(apiErrorMessage(e) ?? "권한 저장에 실패했습니다");
    } finally {
      setSaving(false);
    }
  }

  const cardStyle: React.CSSProperties = {
    background: token.colorBgContainer,
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: 8,
    display: "flex",
    flexDirection: "column",
    overflow: "hidden",
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, height: "100%", minHeight: 580 }}>
      {isAdmin ? (
        <Alert
          type="info"
          showIcon
          banner
          style={{ flex: "none", borderRadius: 8 }}
          message="행이 없는 테이블은 '허용'입니다 (default-allow) — 새로 등록된 테이블은 즉시 열람 가능하니 주의하세요."
          description="체크를 해제한 테이블만 명시적 차단 행으로 저장되며, 모든 변경은 감사 이벤트로 남습니다. 본인의 권한은 편집할 수 없습니다."
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          banner
          style={{ flex: "none", borderRadius: 8 }}
          message="권한 변경은 ADMIN만 가능합니다 — 현재 화면은 읽기 전용입니다"
          description="본인의 권한만 조회할 수 있습니다. 변경이 필요하면 관리자에게 요청하세요."
        />
      )}

      <div className="qg-stack-mobile"
        style={{
          display: "grid",
          gridTemplateColumns: "280px 1fr",
          gap: 16,
          flex: 1,
          minHeight: 0,
        }}
      >
        {/* ── 사용자 목록 ── */}
        <div style={cardStyle}>
          <div
            style={{
              padding: "14px 16px",
              borderBottom: `1px solid ${token.colorBorderSecondary}`,
              fontWeight: 600,
              fontSize: 14,
              display: "flex",
              justifyContent: "space-between",
            }}
          >
            <span>사용자</span>
            <span style={{ fontSize: 12, color: token.colorTextTertiary, fontWeight: 400 }}>
              {users.length}명
            </span>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
            {users.length === 0 && (
              <div style={{ padding: 16 }}>
                <Empty description="사용자를 불러오지 못했습니다" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            )}
            {users.map((u) => {
              const active = u.id === selectedId;
              const readable = isAdmin || u.id === me?.id;
              const isHover = hovered === u.id && !active;
              const row = (
                <div
                  key={u.id}
                  onClick={() => {
                    if (readable) setSelectedId(u.id);
                  }}
                  onMouseEnter={() => setHovered(u.id)}
                  onMouseLeave={() => setHovered(null)}
                  style={{
                    padding: "10px 12px",
                    borderRadius: 6,
                    cursor: readable ? "pointer" : "not-allowed",
                    marginBottom: 4,
                    opacity: readable ? 1 : 0.5,
                    border: `1px solid ${active ? token.colorPrimaryBorder : "transparent"}`,
                    background: active
                      ? token.colorPrimaryBg
                      : isHover && readable
                        ? token.colorFillTertiary
                        : token.colorBgContainer,
                  }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <Avatar user={u} size={32} font={13} />
                    <span style={{ minWidth: 0, flex: 1 }}>
                      <div
                        style={{
                          fontSize: 14,
                          fontWeight: 500,
                          display: "flex",
                          alignItems: "center",
                          gap: 6,
                        }}
                      >
                        {u.displayName}
                        {u.id === me?.id && <Tag style={{ margin: 0 }}>본인</Tag>}
                        {!u.enabled && <Tag color="default">비활성</Tag>}
                      </div>
                      <div style={{ fontSize: 12, color: token.colorTextTertiary }}>{u.title}</div>
                    </span>
                    <Tag color={ROLE_COLOR[u.role]} style={{ margin: 0 }}>
                      {ROLE_LABEL[u.role]}
                    </Tag>
                  </div>
                </div>
              );
              return readable ? (
                row
              ) : (
                <Tooltip key={u.id} title="ADMIN만 다른 사용자의 권한을 조회할 수 있습니다" placement="right">
                  {row}
                </Tooltip>
              );
            })}
          </div>
        </div>

        {/* ── 권한 상세 ── */}
        <div style={{ ...cardStyle, minWidth: 0 }}>
          <div
            style={{
              padding: "16px 20px",
              borderBottom: `1px solid ${token.colorBorderSecondary}`,
              display: "flex",
              alignItems: "center",
              gap: 12,
              flex: "none",
            }}
          >
            {selected && <Avatar user={selected} size={38} font={15} />}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 600 }}>
                {selected?.displayName ?? "사용자를 선택하세요"}
              </div>
              <div style={{ fontSize: 12, color: token.colorTextTertiary }}>
                {selected
                  ? `${selected.title} · ${canEdit ? "접근 가능한 서버·테이블을 설정하세요" : isSelf ? "본인의 권한은 편집할 수 없습니다 (자기상향 방지)" : "읽기 전용"}`
                  : ""}
              </div>
            </div>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => selectedId && void load(selectedId)}
              disabled={!selectedId || loading}
            >
              새로고침
            </Button>
            <Tooltip
              title={
                canEdit
                  ? ""
                  : isSelf
                    ? "자기 자신의 권한은 편집할 수 없습니다 (403 CANNOT_EDIT_OWN_PERMISSION)"
                    : "권한 변경은 ADMIN만 가능합니다"
              }
            >
              <Button
                type="primary"
                icon={canEdit ? <SaveOutlined /> : <LockOutlined />}
                loading={saving}
                disabled={!canEdit || !dirty}
                onClick={() => void handleSave()}
              >
                권한 저장
              </Button>
            </Tooltip>
          </div>

          <div
            style={{
              flex: 1,
              overflowY: "auto",
              padding: 20,
              display: "flex",
              flexDirection: "column",
              gap: 16,
            }}
          >
            {loadError && <Alert type="error" showIcon message={loadError} />}
            {loading && (
              <div style={{ padding: 40, textAlign: "center" }}>
                <Spin />
              </div>
            )}

            {!loading && perms && draft && (
              <>
                <div
                  style={{
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: 8,
                    overflow: "hidden",
                  }}
                >
                  {/* 서버 토글 */}
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 10,
                      padding: "12px 16px",
                      background: token.colorFillAlter,
                      borderBottom: `1px solid ${token.colorBorderSecondary}`,
                    }}
                  >
                    <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                      {SERVER_VENDOR}
                    </Tag>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>{SERVER_LABEL}</span>
                    <span style={{ fontSize: 12, color: token.colorTextTertiary, fontFamily: MONO_FONT }}>
                      {SERVER_KEY}
                    </span>
                    <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 12 }}>
                      <span style={{ fontSize: 12, color: token.colorTextTertiary }}>
                        {allowedCount}/{perms.tables.length} 테이블 허용
                      </span>
                      <Tooltip title={canEdit ? "" : "읽기 전용"}>
                        <Switch
                          checked={draft.serverAllowed}
                          disabled={!canEdit}
                          onChange={(v) =>
                            setDraft((d) => (d ? { ...d, serverAllowed: v } : d))
                          }
                        />
                      </Tooltip>
                    </span>
                  </div>

                  {/* 테이블 체크박스 */}
                  {perms.tables.length === 0 ? (
                    <div style={{ padding: 32 }}>
                      <Empty
                        description="카탈로그에 등록된 테이블이 없습니다"
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                      />
                    </div>
                  ) : (
                    <div
                      style={{
                        padding: "10px 12px",
                        display: "grid",
                        gridTemplateColumns: "1fr 1fr 1fr",
                        gap: 2,
                      }}
                    >
                      {perms.tables.map((t) => (
                        <div
                          key={t.tableName}
                          style={{
                            display: "flex",
                            alignItems: "center",
                            gap: 8,
                            padding: "7px 10px",
                            borderRadius: 6,
                            opacity: draft.serverAllowed ? 1 : 0.45,
                          }}
                        >
                          <Checkbox
                            checked={!!draft.tables[t.tableName]}
                            disabled={!canEdit || !draft.serverAllowed}
                            onChange={(e) =>
                              setDraft((d) =>
                                d
                                  ? { ...d, tables: { ...d.tables, [t.tableName]: e.target.checked } }
                                  : d,
                              )
                            }
                          />
                          <span
                            style={{
                              fontFamily: MONO_FONT,
                              fontSize: 13,
                              overflow: "hidden",
                              textOverflow: "ellipsis",
                              whiteSpace: "nowrap",
                            }}
                          >
                            {t.tableName}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {!draft.serverAllowed && (
                  <Alert
                    type="warning"
                    showIcon
                    message="서버 접근이 차단되어 이 서버의 모든 테이블이 차단됩니다"
                  />
                )}

                {/* 변경 이력 (ADMIN) */}
                {isAdmin && (
                  <div
                    style={{
                      border: `1px solid ${token.colorBorderSecondary}`,
                      borderRadius: 8,
                      overflow: "hidden",
                    }}
                  >
                    <div
                      style={{
                        padding: "10px 16px",
                        borderBottom: `1px solid ${token.colorBorderSecondary}`,
                        fontSize: 13,
                        fontWeight: 600,
                        display: "flex",
                        gap: 8,
                        alignItems: "center",
                      }}
                    >
                      권한 변경 이력
                      <span style={{ fontSize: 11, color: token.colorTextTertiary, fontWeight: 400 }}>
                        append-only 감사 이벤트
                      </span>
                    </div>
                    <div style={{ padding: history.length ? "8px 12px" : 20 }}>
                      {history.length === 0 ? (
                        <div style={{ fontSize: 12, color: token.colorTextTertiary }}>
                          변경 이력이 없습니다.
                        </div>
                      ) : (
                        history.map((h, i) => (
                          <div
                            key={i}
                            style={{
                              display: "flex",
                              alignItems: "center",
                              gap: 10,
                              fontSize: 12,
                              padding: "6px 6px",
                              borderLeft: `2px solid ${token.colorSplit}`,
                            }}
                          >
                            <span style={{ fontFamily: MONO_FONT, color: token.colorTextTertiary, width: 120 }}>
                              {fmtDateTime(h.at)}
                            </span>
                            <Tag style={{ margin: 0 }}>{h.scope === "SERVER" ? "서버" : "테이블"}</Tag>
                            <span style={{ fontFamily: MONO_FONT }}>{h.target}</span>
                            <Tag color={h.afterAllowed ? "green" : "red"} style={{ margin: 0 }}>
                              {h.beforeAllowed === false ? "차단" : "허용"} →{" "}
                              {h.afterAllowed ? "허용" : "차단"}
                            </Tag>
                            <span style={{ color: token.colorTextSecondary }}>by {h.actor}</span>
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------

interface PermDraft {
  serverAllowed: boolean;
  tables: Record<string, boolean>;
}

function toDraft(p: Permissions): PermDraft {
  const tables: Record<string, boolean> = {};
  p.tables.forEach((t) => {
    tables[t.tableName] = t.allowed;
  });
  return { serverAllowed: p.serverAllowed, tables };
}
