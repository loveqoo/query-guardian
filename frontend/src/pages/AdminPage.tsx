import { useRef, useState } from "react";
import { Alert, App, Checkbox, Switch, Tag, theme } from "antd";
import {
  buildDefaultPerms,
  databases,
  tablesByDb,
  users,
  type PermsMap,
} from "../mock/design";

/**
 * 접근 권한 관리 (Access Control) — STUB screen.
 * Source: docs/design/query-guardian-design/Query Guardian.dc.html lines 642–688 (2-pane),
 * render logic lines 1498–1517, toggle helpers 1141–1155, buildDefaultPerms 1125–1140.
 * All state is local — no backend. Ports toggleDbPerm/toggleTablePerm to local hooks.
 */

const DEMO_NOTE =
  "접근 권한은 데모 상태이며 다음 단계에서 백엔드와 연결됩니다";

function Avatar({
  initial,
  color,
  size,
  font,
}: {
  initial: string;
  color: string;
  size: number;
  font: number;
}) {
  return (
    <span
      style={{
        width: size,
        height: size,
        flex: "none",
        borderRadius: "50%",
        background: color,
        color: "#fff",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: font,
        fontWeight: 600,
      }}
    >
      {initial}
    </span>
  );
}

export default function AdminPage() {
  const { token } = theme.useToken();
  const { message } = App.useApp();

  const [perms, setPerms] = useState<PermsMap>(() => buildDefaultPerms());
  const [adminUser, setAdminUser] = useState<string>(users[0].id);
  const [hovered, setHovered] = useState<string | null>(null);
  const demoShown = useRef(false);

  const notifyDemo = () => {
    if (demoShown.current) return;
    demoShown.current = true;
    message.info(DEMO_NOTE);
  };

  // toggleDbPerm (dc.html 1141–1147) — immutable local port.
  const toggleDbPerm = (uid: string, dbKey: string) => {
    setPerms((prev) => {
      const cur = prev[uid];
      return {
        ...prev,
        [uid]: { ...cur, dbs: { ...cur.dbs, [dbKey]: !cur.dbs[dbKey] } },
      };
    });
    notifyDemo();
  };

  // toggleTablePerm (dc.html 1148–1155) — immutable local port.
  const toggleTablePerm = (uid: string, dbKey: string, table: string) => {
    const k = dbKey + "/" + table;
    setPerms((prev) => {
      const cur = prev[uid];
      return {
        ...prev,
        [uid]: { ...cur, tables: { ...cur.tables, [k]: !cur.tables[k] } },
      };
    });
    notifyDemo();
  };

  const selected = users.find((u) => u.id === adminUser) ?? users[0];
  const userPerms = perms[adminUser];

  const cardStyle: React.CSSProperties = {
    background: token.colorBgContainer,
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: 8,
    display: "flex",
    flexDirection: "column",
    overflow: "hidden",
  };

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        gap: 12,
        height: "100%",
        minHeight: 580,
      }}
    >
      <Alert
        type="info"
        showIcon
        banner
        message={DEMO_NOTE}
        style={{ flex: "none", borderRadius: 8 }}
      />

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "280px 1fr",
          gap: 16,
          flex: 1,
          minHeight: 0,
        }}
      >
        {/* ── 사용자 목록 (left pane) ── */}
        <div style={cardStyle}>
          <div
            style={{
              padding: "14px 16px",
              borderBottom: `1px solid ${token.colorBorderSecondary}`,
              fontWeight: 600,
              fontSize: 14,
            }}
          >
            사용자
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
            {users.map((u) => {
              const active = u.id === adminUser;
              const isHover = hovered === u.id && !active;
              return (
                <div
                  key={u.id}
                  onClick={() => setAdminUser(u.id)}
                  onMouseEnter={() => setHovered(u.id)}
                  onMouseLeave={() => setHovered(null)}
                  style={{
                    padding: "10px 12px",
                    borderRadius: 6,
                    cursor: "pointer",
                    marginBottom: 4,
                    border: `1px solid ${
                      active ? token.colorPrimaryBorder : "transparent"
                    }`,
                    background: active
                      ? token.colorPrimaryBg
                      : isHover
                        ? token.colorFillTertiary
                        : token.colorBgContainer,
                  }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <Avatar initial={u.initial} color={u.color} size={32} font={13} />
                    <span style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 14, fontWeight: 500 }}>{u.name}</div>
                      <div style={{ fontSize: 12, color: token.colorTextTertiary }}>
                        {u.role}
                      </div>
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── 우측 상세 (detail pane) ── */}
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
            <Avatar
              initial={selected.initial}
              color={selected.color}
              size={38}
              font={15}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 600 }}>{selected.name}</div>
              <div style={{ fontSize: 12, color: token.colorTextTertiary }}>
                {selected.role} · 접근 가능한 데이터베이스·테이블을 설정하세요
              </div>
            </div>
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
            {databases.map((d) => {
              const dbOn = !!userPerms.dbs[d.key];
              const tbls = tablesByDb[d.key] ?? [];
              const allowed = dbOn
                ? tbls.filter((t) => userPerms.tables[d.key + "/" + t.name]).length
                : 0;
              return (
                <div
                  key={d.key}
                  style={{
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: 8,
                    overflow: "hidden",
                  }}
                >
                  {/* db header */}
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
                    <Tag color={d.vendorColor} style={{ marginInlineEnd: 0 }}>
                      {d.vendor}
                    </Tag>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>{d.name}</span>
                    <span
                      style={{
                        fontSize: 12,
                        color: token.colorTextTertiary,
                        fontFamily:
                          "ui-monospace,SFMono-Regular,Consolas,Menlo,monospace",
                      }}
                    >
                      {d.host}
                    </span>
                    <span
                      style={{
                        marginLeft: "auto",
                        display: "flex",
                        alignItems: "center",
                        gap: 12,
                      }}
                    >
                      <span style={{ fontSize: 12, color: token.colorTextTertiary }}>
                        {allowed}/{tbls.length} 테이블 허용
                      </span>
                      <Switch
                        checked={dbOn}
                        onChange={() => toggleDbPerm(adminUser, d.key)}
                      />
                    </span>
                  </div>

                  {/* table checkboxes */}
                  <div
                    style={{
                      padding: "10px 12px",
                      display: "grid",
                      gridTemplateColumns: "1fr 1fr 1fr",
                      gap: 2,
                    }}
                  >
                    {tbls.map((t) => (
                      <div
                        key={t.name}
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: 8,
                          padding: "7px 10px",
                          borderRadius: 6,
                          opacity: dbOn ? 1 : 0.45,
                        }}
                      >
                        <Checkbox
                          checked={!!userPerms.tables[d.key + "/" + t.name]}
                          disabled={!dbOn}
                          onChange={() =>
                            toggleTablePerm(adminUser, d.key, t.name)
                          }
                        />
                        <span
                          style={{
                            fontFamily:
                              "ui-monospace,SFMono-Regular,Consolas,Menlo,monospace",
                            fontSize: 13,
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {t.name}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
