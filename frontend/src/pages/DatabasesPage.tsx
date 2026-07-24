import { useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { App, Button, Input, Tag } from "antd";
import {
  AppstoreOutlined,
  CodeOutlined,
  DownOutlined,
  EditOutlined,
  LockOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import {
  buildDefaultPerms,
  colConstraints,
  columnsFor,
  databases,
  defById,
  genericIndexes,
  genericTableMeta,
  indexesByTable,
  tableComments,
  tableMetaByName,
  tablesByDb,
} from "../mock/design";
import { MONO_FONT } from "../theme";

/**
 * Database Explorer (/databases) — 3-pane catalog browser.
 * Faithful conversion of dc.html lines 79–237 + x-dc renderVals (1259–1329).
 * All data is the design SAMPLE from src/mock/design.ts. Locks/perms are a
 * UI-only sample (NOT real access control).
 */

// ── design tokens (CSS vars → concrete values) ─────────────────────────────
const T = {
  border: "#f0f0f0",
  split: "rgba(5,5,5,0.06)",
  text: "rgba(0,0,0,0.88)",
  textSec: "rgba(0,0,0,0.65)",
  textTer: "rgba(0,0,0,0.45)",
  textQua: "rgba(0,0,0,0.25)",
  primary: "#1677ff",
  primaryBg: "#e6f4ff",
  primaryBorder: "#91caff",
  fillQuat: "rgba(0,0,0,0.02)",
  gray2: "#fafafa",
  gray3: "#f5f5f5",
  gold1: "#fffbe6",
  gold3: "#ffe58f",
  gold7: "#d48806",
  red1: "#fff1f0",
  red3: "#ffa39e",
  red6: "#f5222d",
} as const;

const CURRENT_USER_ID = "u1"; // 김도현 — perms sample uses u1
const PERMS = buildDefaultPerms();

// column-key Tag colors (dc.html 1292)
const KEY_COLOR: Record<string, string> = {
  PK: "gold",
  FK: "geekblue",
  UK: "cyan",
  PARTITION: "purple",
  IDX: "blue",
  CHECK: "green",
};

// index-type text colors (dc.html 1323)
const IDX_TEXT: Record<string, string> = {
  PRIMARY: T.gold7,
  UNIQUE: "#08979c",
  INDEX: "#0958d9",
  FOREIGN: "#10239e",
  PARTITION: "#531dab",
};

// ── hover-aware clickable row ───────────────────────────────────────────────
function Row({
  style,
  selected,
  hoverBg = T.fillQuat,
  onClick,
  children,
}: {
  style: CSSProperties;
  selected: boolean;
  hoverBg?: string;
  onClick?: () => void;
  children: ReactNode;
}) {
  const [hover, setHover] = useState(false);
  const merged: CSSProperties =
    hover && !selected ? { ...style, background: hoverBg } : style;
  return (
    <div
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={merged}
    >
      {children}
    </div>
  );
}

// ── collapsible accordion section (caret + header + body) ───────────────────
function Section({
  title,
  right,
  open,
  onToggle,
  children,
  last,
}: {
  title: string;
  right?: ReactNode;
  open: boolean;
  onToggle: () => void;
  children: ReactNode;
  last?: boolean;
}) {
  return (
    <div style={{ borderBottom: last ? "none" : `1px solid ${T.border}` }}>
      <Row
        selected={false}
        onClick={onToggle}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          padding: "12px 16px",
          cursor: "pointer",
        }}
      >
        <span
          style={{
            display: "inline-flex",
            color: T.textTer,
            transition: "transform .15s",
            transform: open ? "rotate(180deg)" : "none",
            fontSize: 12,
          }}
        >
          <DownOutlined />
        </span>
        <span style={{ fontSize: 13, fontWeight: 600 }}>{title}</span>
        {right != null && (
          <span style={{ marginLeft: "auto" }}>{right}</span>
        )}
      </Row>
      {open && <div style={{ padding: "0 16px 16px 36px" }}>{children}</div>}
    </div>
  );
}

const cardStyle: CSSProperties = {
  background: "#fff",
  border: `1px solid ${T.border}`,
  borderRadius: 8,
  display: "flex",
  flexDirection: "column",
  overflow: "hidden",
  minHeight: 0,
};

export default function DatabasesPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const up = PERMS[CURRENT_USER_ID];

  const [dbKey, setDbKey] = useState<string>(databases[0].key);
  const firstTable = (tablesByDb[databases[0].key] || [])[0]?.name ?? "";
  const [tableName, setTableName] = useState<string>(firstTable);
  const [selColName, setSelColName] = useState<string>("");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState({
    index: true,
    comment: true,
    pii: true,
    constraint: true,
  });

  const curDb = databases.find((d) => d.key === dbKey);
  const dbNameForCols = curDb ? curDb.name : "";
  const tables = tablesByDb[dbKey] || [];

  const selectDb = (key: string) => {
    const list = tablesByDb[key] || [];
    const first =
      list.find((t) => up.tables[key + "/" + t.name]) || list[0];
    setDbKey(key);
    setTableName(first ? first.name : "");
    setSelColName("");
    setSearch("");
  };

  const cols = columnsFor(tableName);
  const effSelCol =
    selColName && cols.some((c) => c.name === selColName)
      ? selColName
      : cols[0]?.name ?? "";
  const selColMeta = cols.find((c) => c.name === effSelCol);

  const currentTableAccessible = !!(
    up.dbs[dbKey] && up.tables[dbKey + "/" + tableName]
  );
  const cannotEdit = !currentTableAccessible;

  const filteredTables = tables.filter((t) =>
    t.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  const tableComment = tableComments[tableName] || "";
  const metaEntries = Object.entries(
    tableMetaByName[tableName] || genericTableMeta,
  );
  const indexes = indexesByTable[tableName] || genericIndexes;
  const piiCols = cols.filter((c) => c.isPii);

  const selKey = dbNameForCols + "/" + tableName + "/" + effSelCol;
  const selConstraintIds = colConstraints[selKey] || [];
  const selConstraints = selConstraintIds
    .map((id) => defById(id))
    .filter((d): d is NonNullable<typeof d> => Boolean(d));

  const onMapEdit = () => {
    message.info("제약 매핑은 카탈로그 화면에서 관리합니다");
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, height: "100%" }}>
      {/* permission banner */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          flex: "none",
          fontSize: 12,
          color: T.textSec,
          background: T.gray2,
          border: `1px solid ${T.border}`,
          borderRadius: 8,
          padding: "8px 14px",
        }}
      >
        <LockOutlined style={{ color: T.textTer }} />
        김도현 님 권한 기준 · 잠금 항목은 접근 불가
      </div>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "300px 300px 1fr",
          gap: 16,
          flex: 1,
          minHeight: 0,
        }}
      >
        {/* ── PANE 1: connections ── */}
        <div style={cardStyle}>
          <div
            style={{
              padding: "14px 16px",
              borderBottom: `1px solid ${T.border}`,
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
            }}
          >
            <span style={{ fontWeight: 600, fontSize: 14 }}>연결 목록</span>
            <span style={{ fontSize: 12, color: T.textTer }}>
              {databases.length}개
            </span>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
            {databases.map((d) => {
              const accessible = up.dbs[d.key];
              const locked = !accessible;
              const selected = d.key === dbKey;
              const base: CSSProperties = {
                padding: "10px 12px",
                borderRadius: 6,
                cursor: "pointer",
                marginBottom: 4,
                border: "1px solid transparent",
              };
              const style: CSSProperties = locked
                ? selected
                  ? { ...base, background: T.primaryBg, borderColor: T.primaryBorder, opacity: 0.8 }
                  : { ...base, background: T.gray2, borderColor: T.border, opacity: 0.72 }
                : selected
                  ? { ...base, background: T.primaryBg, borderColor: T.primaryBorder }
                  : { ...base, background: "#fff", borderColor: T.border };
              return (
                <Row key={d.key} selected={selected} style={style} onClick={() => selectDb(d.key)}>
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      gap: 8,
                    }}
                  >
                    <span style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
                      <span style={{ color: T.textTer, display: "inline-flex" }}>
                        <AppstoreOutlined style={{ fontSize: 15 }} />
                      </span>
                      <span
                        style={{
                          fontWeight: 500,
                          fontSize: 14,
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                        }}
                      >
                        {d.name}
                      </span>
                    </span>
                    <span style={{ display: "flex", alignItems: "center", gap: 6, flex: "none" }}>
                      {locked && (
                        <span style={{ color: T.textQua, display: "inline-flex" }}>
                          <LockOutlined style={{ fontSize: 13 }} />
                        </span>
                      )}
                      <Tag color={d.vendorColor} style={{ marginInlineEnd: 0 }}>
                        {d.vendor}
                      </Tag>
                    </span>
                  </div>
                  <div
                    style={{
                      fontSize: 12,
                      color: T.textTer,
                      marginTop: 6,
                      paddingLeft: 23,
                    }}
                  >
                    {d.host} · {d.tables}개 테이블
                  </div>
                </Row>
              );
            })}
          </div>
        </div>

        {/* ── PANE 2: tables ── */}
        <div style={cardStyle}>
          <div style={{ padding: "10px 12px", borderBottom: `1px solid ${T.border}` }}>
            <div style={{ fontWeight: 600, fontSize: 14, margin: "4px 4px 10px" }}>
              {curDb ? curDb.name : "—"} · 테이블
            </div>
            <Input
              size="small"
              placeholder="테이블 검색"
              prefix={<SearchOutlined style={{ color: T.textQua }} />}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              allowClear
            />
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
            {filteredTables.map((t) => {
              const accessible = up.dbs[dbKey] && up.tables[dbKey + "/" + t.name];
              const locked = !accessible;
              const selected = t.name === tableName;
              const base: CSSProperties = {
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 8,
                padding: "8px 10px",
                borderRadius: 6,
                cursor: "pointer",
                marginBottom: 2,
              };
              const style: CSSProperties = locked
                ? selected
                  ? { ...base, background: T.primaryBg, opacity: 0.8 }
                  : { ...base, background: "transparent", opacity: 0.65 }
                : selected
                  ? { ...base, background: T.primaryBg }
                  : { ...base, background: "transparent" };
              return (
                <Row
                  key={t.name}
                  selected={selected}
                  style={style}
                  onClick={() => {
                    setTableName(t.name);
                    setSelColName("");
                  }}
                >
                  <span style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
                    <span
                      style={{
                        color: selected ? T.primary : T.textQua,
                        display: "inline-flex",
                      }}
                    >
                      <AppstoreOutlined style={{ fontSize: 14 }} />
                    </span>
                    <span
                      style={{
                        fontSize: 13,
                        fontFamily: MONO_FONT,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {t.name}
                    </span>
                  </span>
                  <span style={{ flex: "none", display: "flex", alignItems: "center", gap: 4 }}>
                    {locked && (
                      <span style={{ color: T.textQua, display: "inline-flex" }}>
                        <LockOutlined style={{ fontSize: 12 }} />
                      </span>
                    )}
                    <span style={{ fontSize: 11, color: T.textTer }}>{t.rows}</span>
                  </span>
                </Row>
              );
            })}
          </div>
        </div>

        {/* ── PANE 3: column detail ── */}
        <div style={cardStyle}>
          {/* header */}
          <div style={{ padding: "14px 16px", borderBottom: `1px solid ${T.border}` }}>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 8,
              }}
            >
              <span style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 0 }}>
                <span style={{ fontFamily: MONO_FONT, fontWeight: 600, fontSize: 14 }}>
                  {tableName || "—"}
                </span>
                <span style={{ fontSize: 12, color: T.textTer }}>· {cols.length} columns</span>
              </span>
              <Button
                size="small"
                icon={<CodeOutlined />}
                disabled={cannotEdit}
                onClick={() => navigate("/editor")}
              >
                이 테이블로 쿼리
              </Button>
            </div>

            {cannotEdit && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                  marginTop: 8,
                  fontSize: 12,
                  color: T.gold7,
                  background: T.gold1,
                  border: `1px solid ${T.gold3}`,
                  borderRadius: 6,
                  padding: "6px 10px",
                }}
              >
                <LockOutlined style={{ fontSize: 13 }} />
                접근 권한 없음 · 열람 전용 (편집·쿼리 불가)
              </div>
            )}

            {tableComment && (
              <div style={{ fontSize: 12, color: T.textSec, marginTop: 8 }}>{tableComment}</div>
            )}

            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
              {metaEntries.map(([k, v]) => (
                <span
                  key={k}
                  style={{
                    fontSize: 11,
                    background: T.gray2,
                    border: `1px solid ${T.border}`,
                    borderRadius: 4,
                    padding: "2px 8px",
                    color: T.textTer,
                  }}
                >
                  {k}{" "}
                  <span style={{ color: T.textSec, fontFamily: MONO_FONT }}>{v}</span>
                </span>
              ))}
            </div>
          </div>

          {/* scroll body */}
          <div style={{ flex: 1, overflowY: "auto", minHeight: 0 }}>
            {/* column grid header */}
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "1.2fr 1fr 0.8fr 1fr",
                gap: 0,
                padding: "10px 16px",
                borderBottom: `1px solid ${T.border}`,
                fontSize: 12,
                color: T.textTer,
                fontWeight: 500,
                position: "sticky",
                top: 0,
                background: "#fff",
                zIndex: 1,
              }}
            >
              <span>컬럼</span>
              <span>타입</span>
              <span>NULL</span>
              <span>기본값</span>
            </div>

            {/* column rows */}
            {cols.map((c) => {
              const selected = c.name === effSelCol;
              const rowStyle: CSSProperties = {
                display: "grid",
                gridTemplateColumns: "1.2fr 1fr 0.8fr 1fr",
                gap: 0,
                padding: "11px 16px",
                borderBottom: `1px solid ${T.split}`,
                alignItems: "center",
                fontSize: 13,
                cursor: "pointer",
                background: selected ? T.primaryBg : "transparent",
                boxShadow: selected ? `inset 3px 0 0 ${T.primary}` : "none",
              };
              return (
                <Row
                  key={c.name}
                  selected={selected}
                  style={rowStyle}
                  onClick={() => {
                    setSelColName(c.name);
                    setOpen((o) => ({ ...o, constraint: true }));
                  }}
                >
                  <span
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 6,
                      minWidth: 0,
                      flexWrap: "wrap",
                    }}
                  >
                    <span
                      style={{
                        fontFamily: MONO_FONT,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {c.name}
                    </span>
                    {(c.keys || []).map((k) => (
                      <Tag
                        key={k}
                        color={KEY_COLOR[k] || "default"}
                        style={{
                          marginInlineEnd: 0,
                          fontSize: 10,
                          lineHeight: "16px",
                          padding: "0 5px",
                        }}
                      >
                        {k}
                      </Tag>
                    ))}
                  </span>
                  <span
                    style={{
                      fontFamily: MONO_FONT,
                      color: T.textSec,
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {c.type}
                  </span>
                  <span
                    style={{
                      fontSize: 11,
                      color: c.nullable ? T.textQua : T.textSec,
                    }}
                  >
                    {c.nullable ? "NULL" : "NOT NULL"}
                  </span>
                  <span
                    style={{
                      fontFamily: MONO_FONT,
                      fontSize: 11,
                      color: T.textTer,
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {c.def === null || c.def === undefined ? "—" : c.def}
                  </span>
                </Row>
              );
            })}

            {/* ── 4 accordion sections ── */}
            <div style={{ borderTop: `8px solid ${T.gray3}` }}>
              {/* 1) 인덱스 */}
              <Section
                title="인덱스"
                open={open.index}
                onToggle={() => setOpen((o) => ({ ...o, index: !o.index }))}
                right={<span style={{ fontSize: 12, color: T.textTer }}>{indexes.length}개</span>}
              >
                <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                  {indexes.map((ix) => (
                    <div
                      key={ix.name}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 10,
                        flexWrap: "wrap",
                      }}
                    >
                      <span style={{ width: 74, flex: "none" }}>
                        <span
                          style={{
                            fontSize: 11,
                            fontWeight: 600,
                            padding: "1px 7px",
                            borderRadius: 4,
                            background: T.gray3,
                            border: `1px solid ${T.border}`,
                            color: IDX_TEXT[ix.type] || T.textSec,
                          }}
                        >
                          {ix.type}
                        </span>
                      </span>
                      <span style={{ fontFamily: MONO_FONT, fontSize: 12, color: T.text }}>
                        {ix.name}
                      </span>
                      <span
                        style={{
                          display: "flex",
                          gap: 4,
                          flexWrap: "wrap",
                          alignItems: "center",
                        }}
                      >
                        {ix.columns.map((ic) => (
                          <span
                            key={ic}
                            style={{
                              fontFamily: MONO_FONT,
                              fontSize: 11,
                              background: T.gray3,
                              border: `1px solid ${T.border}`,
                              borderRadius: 4,
                              padding: "1px 7px",
                              color: T.textSec,
                            }}
                          >
                            {ic}
                          </span>
                        ))}
                      </span>
                      {ix.note && (
                        <span style={{ fontSize: 11, color: T.textTer }}>{ix.note}</span>
                      )}
                    </div>
                  ))}
                </div>
              </Section>

              {/* 2) 코멘트 */}
              <Section
                title="코멘트"
                open={open.comment}
                onToggle={() => setOpen((o) => ({ ...o, comment: !o.comment }))}
                right={
                  <span style={{ fontSize: 12, color: T.textTer }}>
                    · <span style={{ fontFamily: MONO_FONT }}>{effSelCol || "—"}</span>
                  </span>
                }
              >
                <div
                  style={{
                    fontSize: 13,
                    color: T.text,
                    lineHeight: 1.6,
                    overflowWrap: "break-word",
                    wordBreak: "break-word",
                  }}
                >
                  {selColMeta && selColMeta.comment ? selColMeta.comment : "—"}
                </div>
              </Section>

              {/* 3) 개인정보 컬럼 */}
              <Section
                title="개인정보 컬럼"
                open={open.pii}
                onToggle={() => setOpen((o) => ({ ...o, pii: !o.pii }))}
                right={
                  <span
                    style={{
                      fontSize: 12,
                      color: piiCols.length ? T.red6 : T.textQua,
                    }}
                  >
                    {piiCols.length ? piiCols.length + "개" : "없음"}
                  </span>
                }
              >
                {piiCols.length ? (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                    {piiCols.map((pc) => (
                      <span
                        key={pc.name}
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          padding: "2px 8px",
                          background: T.red1,
                          border: `1px solid ${T.red3}`,
                          borderRadius: 4,
                        }}
                      >
                        <span style={{ fontFamily: MONO_FONT, fontSize: 12, color: T.text }}>
                          {pc.name}
                        </span>
                      </span>
                    ))}
                  </div>
                ) : (
                  <div style={{ fontSize: 12, color: T.textTer }}>
                    개인정보로 분류된 컬럼이 없습니다.
                  </div>
                )}
              </Section>

              {/* 4) 쿼리 제약 */}
              <Section
                title="쿼리 제약"
                open={open.constraint}
                onToggle={() => setOpen((o) => ({ ...o, constraint: !o.constraint }))}
                last
                right={
                  <span
                    style={{
                      fontSize: 12,
                      color: selConstraints.length ? T.primary : T.textQua,
                    }}
                  >
                    · <span style={{ fontFamily: MONO_FONT }}>{effSelCol || "—"}</span>
                  </span>
                }
              >
                <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 10 }}>
                  <Button
                    size="small"
                    type="dashed"
                    icon={<EditOutlined />}
                    disabled={cannotEdit}
                    onClick={onMapEdit}
                  >
                    매핑 편집
                  </Button>
                </div>
                {selConstraints.length ? (
                  <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                    {selConstraints.map((mc) => (
                      <div
                        key={mc.id}
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: 10,
                          padding: "8px 12px",
                          background: "#fff",
                          border: `1px solid ${T.border}`,
                          borderRadius: 6,
                        }}
                      >
                        <span
                          style={{
                            width: 6,
                            height: 6,
                            borderRadius: "50%",
                            background: T.primary,
                            flex: "none",
                          }}
                        />
                        <span style={{ minWidth: 0 }}>
                          <div style={{ fontSize: 13, fontWeight: 500 }}>{mc.name}</div>
                          <div style={{ fontSize: 12, color: T.textTer, marginTop: 1 }}>
                            {mc.desc}
                          </div>
                        </span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div style={{ fontSize: 12, color: T.textTer }}>
                    이 컬럼에 매핑된 제약이 없습니다. "매핑 편집"으로 추가하세요.
                  </div>
                )}
              </Section>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
