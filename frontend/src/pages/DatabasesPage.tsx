import { useEffect, useMemo, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { Alert, App, Button, Empty, Input, Spin, Tag, Tooltip } from "antd";
import {
  AppstoreOutlined,
  CodeOutlined,
  DownOutlined,
  LockOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import {
  columnsByTable,
  databases,
  genericIndexes,
  genericTableMeta,
  indexesByTable,
  tableComments,
  tableMetaByName,
} from "../mock/design";
import { MONO_FONT } from "../theme";
import { useAuth } from "../auth/AuthContext";
import {
  listMappings,
  myTables,
  type CatalogColumn,
  type ConstraintMapping,
  type MyTable,
} from "../api/client";
import { CLASS_LABEL } from "./catalog/meta";

/**
 * 데이터베이스 탐색기 (/databases) — 3분할 카탈로그 브라우저.
 * 화면 골격은 디자인 원본(dc.html 79–237)을 유지하고, **테이블·컬럼·잠금은 실 API**로 교체했다:
 * `GET /api/my/tables` = 전 테이블 + `accessible`(비허용은 컬럼 생략, spec 007 §6.3).
 *
 * 연결 목록은 단일 서버(mysql-prod) 전제라 나머지 벤더는 비활성이다(spec 007 §3.1 C3 — 멀티 벤더는 ④).
 * 인덱스·코멘트는 카탈로그 API가 제공하지 않으므로 디자인 표본이 있는 테이블에서만 "예시"로 표시한다.
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

/** 백엔드가 붙어 있는 유일한 연결 (spec 007 §3.1 — 권한 키는 테이블명 단독, 단일 서버 전제). */
const ACTIVE_DB_KEY = "mysql-prod";
const OTHER_VENDOR_TIP = "후속 지원 예정 — 현재 MySQL(prod-main)만 연결됩니다";

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
        {right != null && <span style={{ marginLeft: "auto" }}>{right}</span>}
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

/** 디자인 표본에만 있는 컬럼 코멘트 — 실 카탈로그에는 없다. */
function sampleComment(table: string, column: string): string | null {
  return columnsByTable[table]?.find((c) => c.name === column)?.comment || null;
}

export default function DatabasesPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const { user, isSteward } = useAuth();
  const sessionKey = user?.id ?? "";

  const [tables, setTables] = useState<MyTable[]>([]);
  const [loading, setLoading] = useState(true);
  const [tableName, setTableName] = useState<string>("");
  const [selColName, setSelColName] = useState<string>("");
  const [search, setSearch] = useState("");
  const [mappings, setMappings] = useState<ConstraintMapping[]>([]);
  const [open, setOpen] = useState({
    index: true,
    comment: true,
    pii: true,
    constraint: true,
  });

  // ---- 실 테이블 목록 (사용자 권한 반영) ------------------------------------
  useEffect(() => {
    if (!sessionKey) return;
    let alive = true;
    setLoading(true);
    myTables()
      .then((list) => {
        if (!alive) return;
        setTables(list);
        setTableName((prev) =>
          prev && list.some((t) => t.name === prev)
            ? prev
            : (list.find((t) => t.accessible)?.name ?? list[0]?.name ?? ""),
        );
      })
      .catch(() => {
        if (alive) setTables([]);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [sessionKey]);

  const table = tables.find((t) => t.name === tableName) ?? null;
  const accessible = !!table?.accessible;
  const cols: CatalogColumn[] = table?.columns ?? [];

  // ---- 매핑(쿼리 제약) — STEWARD 이상만 조회 가능 (spec 007 §6.2) -------------
  useEffect(() => {
    if (!isSteward || !table?.id || !accessible) {
      setMappings([]);
      return;
    }
    let alive = true;
    listMappings({ tableId: table.id })
      .then((m) => {
        if (alive) setMappings(m);
      })
      .catch(() => {
        if (alive) setMappings([]);
      });
    return () => {
      alive = false;
    };
  }, [isSteward, table?.id, accessible, sessionKey]);

  const effSelCol =
    selColName && cols.some((c) => c.name === selColName)
      ? selColName
      : (cols[0]?.name ?? "");
  const selColMeta = cols.find((c) => c.name === effSelCol) ?? null;

  const filteredTables = useMemo(
    () => tables.filter((t) => t.name.toLowerCase().includes(search.trim().toLowerCase())),
    [tables, search],
  );

  const tableComment = table?.description || tableComments[tableName] || "";
  const metaEntries = Object.entries(tableMetaByName[tableName] || genericTableMeta);
  const sampleIndexes = indexesByTable[tableName];
  const piiCols = cols.filter((c) => c.isPii);
  const selMappings = selColMeta
    ? mappings.filter((m) => String(m.columnId) === String(selColMeta.id))
    : [];

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
        {user?.displayName ?? "—"} 님 권한 기준 · 잠금 항목은 접근 불가 (컬럼은 노출되지 않습니다)
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
            <span style={{ fontSize: 12, color: T.textTer }}>{databases.length}개</span>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
            {databases.map((d) => {
              const active = d.key === ACTIVE_DB_KEY;
              const base: CSSProperties = {
                padding: "10px 12px",
                borderRadius: 6,
                cursor: active ? "pointer" : "not-allowed",
                marginBottom: 4,
                border: "1px solid transparent",
              };
              const style: CSSProperties = active
                ? { ...base, background: T.primaryBg, borderColor: T.primaryBorder }
                : { ...base, background: T.gray2, borderColor: T.border, opacity: 0.6 };
              const body = (
                <Row key={d.key} selected={active} style={style}>
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
                      {!active && (
                        <span style={{ color: T.textQua, display: "inline-flex" }}>
                          <LockOutlined style={{ fontSize: 13 }} />
                        </span>
                      )}
                      <Tag color={d.vendorColor} style={{ marginInlineEnd: 0 }}>
                        {d.vendor}
                      </Tag>
                    </span>
                  </div>
                  <div style={{ fontSize: 12, color: T.textTer, marginTop: 6, paddingLeft: 23 }}>
                    {active ? `${d.host} · ${tables.length}개 테이블` : `${d.host} · 미연결`}
                  </div>
                </Row>
              );
              return active ? (
                body
              ) : (
                <Tooltip key={d.key} title={OTHER_VENDOR_TIP} placement="right">
                  {body}
                </Tooltip>
              );
            })}
          </div>
        </div>

        {/* ── PANE 2: tables ── */}
        <div style={cardStyle}>
          <div style={{ padding: "10px 12px", borderBottom: `1px solid ${T.border}` }}>
            <div style={{ fontWeight: 600, fontSize: 14, margin: "4px 4px 10px" }}>
              prod-main · 테이블
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
            {loading && (
              <div style={{ padding: 24, textAlign: "center" }}>
                <Spin />
              </div>
            )}
            {!loading && filteredTables.length === 0 && (
              <div style={{ padding: 16 }}>
                <Empty
                  description="카탈로그에 등록된 테이블이 없습니다"
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                />
              </div>
            )}
            {filteredTables.map((t) => {
              const locked = !t.accessible;
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
                      style={{ color: selected ? T.primary : T.textQua, display: "inline-flex" }}
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
                    {locked ? (
                      <Tooltip title="접근 권한 없음 · 열람 전용">
                        <span style={{ color: T.textQua, display: "inline-flex" }}>
                          <LockOutlined style={{ fontSize: 12 }} />
                        </span>
                      </Tooltip>
                    ) : (
                      <span style={{ fontSize: 11, color: T.textTer }}>
                        {t.columns.length} cols
                      </span>
                    )}
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
                <span style={{ fontSize: 12, color: T.textTer }}>
                  · {accessible ? `${cols.length} columns` : "컬럼 비공개"}
                </span>
              </span>
              <Tooltip title={accessible ? "" : "접근 권한이 없는 테이블은 쿼리할 수 없습니다"}>
                <Button
                  size="small"
                  icon={<CodeOutlined />}
                  disabled={!accessible}
                  onClick={() => navigate("/editor")}
                >
                  이 테이블로 쿼리
                </Button>
              </Tooltip>
            </div>

            {!accessible && tableName && (
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
                접근 권한 없음 · 열람 전용 (컬럼·제약 비공개 · 쿼리 불가)
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
                  {k} <span style={{ color: T.textSec, fontFamily: MONO_FONT }}>{v}</span>
                </span>
              ))}
              <Tag color="default" style={{ margin: 0, fontSize: 10 }}>
                예시 메타
              </Tag>
            </div>
          </div>

          {/* scroll body */}
          <div style={{ flex: 1, overflowY: "auto", minHeight: 0 }}>
            {/* column grid header — 카탈로그 API가 주는 필드(타입·클래스·PII)로 구성 */}
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "1.2fr 1fr 0.9fr 0.6fr",
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
              <span>클래스</span>
              <span>개인정보</span>
            </div>

            {/* 비허용 테이블은 컬럼을 내려주지 않는다 (spec 007 §6.3) */}
            {!accessible ? (
              <div style={{ padding: 32 }}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="접근 권한이 없어 컬럼을 표시하지 않습니다"
                />
              </div>
            ) : cols.length === 0 ? (
              <div style={{ padding: 32 }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="등록된 컬럼이 없습니다" />
              </div>
            ) : (
              cols.map((c) => {
                const selected = c.name === effSelCol;
                const rowStyle: CSSProperties = {
                  display: "grid",
                  gridTemplateColumns: "1.2fr 1fr 0.9fr 0.6fr",
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
                    key={String(c.id)}
                    selected={selected}
                    style={rowStyle}
                    onClick={() => {
                      setSelColName(c.name);
                      setOpen((o) => ({ ...o, constraint: true }));
                    }}
                  >
                    <span
                      style={{
                        fontFamily: MONO_FONT,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                        minWidth: 0,
                      }}
                    >
                      {c.name}
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
                    <span style={{ fontSize: 11, color: T.textSec }}>{CLASS_LABEL[c.cls]}</span>
                    <span>
                      {c.isPii ? (
                        <Tag color="red" style={{ margin: 0, fontSize: 10 }}>
                          PII
                        </Tag>
                      ) : (
                        <span style={{ fontSize: 11, color: T.textQua }}>—</span>
                      )}
                    </span>
                  </Row>
                );
              })
            )}

            {/* ── 4 accordion sections ── */}
            <div style={{ borderTop: `8px solid ${T.gray3}` }}>
              {/* 1) 인덱스 — 카탈로그 API 미제공, 디자인 표본이 있을 때만 예시로 노출 */}
              <Section
                title="인덱스"
                open={open.index}
                onToggle={() => setOpen((o) => ({ ...o, index: !o.index }))}
                right={
                  <span style={{ fontSize: 12, color: T.textTer }}>
                    {sampleIndexes ? `${sampleIndexes.length}개 (예시)` : "정보 없음"}
                  </span>
                }
              >
                {!accessible ? (
                  <div style={{ fontSize: 12, color: T.textTer }}>접근 권한이 없습니다.</div>
                ) : !sampleIndexes ? (
                  <div style={{ fontSize: 12, color: T.textTer }}>
                    카탈로그는 인덱스 정보를 보관하지 않습니다 (후속 스펙).
                  </div>
                ) : (
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    <Tag color="default" style={{ alignSelf: "flex-start" }}>
                      예시 데이터
                    </Tag>
                    {(sampleIndexes ?? genericIndexes).map((ix) => (
                      <div
                        key={ix.name}
                        style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}
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
                          style={{ display: "flex", gap: 4, flexWrap: "wrap", alignItems: "center" }}
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
                        {ix.note && <span style={{ fontSize: 11, color: T.textTer }}>{ix.note}</span>}
                      </div>
                    ))}
                  </div>
                )}
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
                  {accessible ? (sampleComment(tableName, effSelCol) ?? "—") : "접근 권한이 없습니다."}
                </div>
              </Section>

              {/* 3) 개인정보 컬럼 — 실 카탈로그의 is_pii */}
              <Section
                title="개인정보 컬럼"
                open={open.pii}
                onToggle={() => setOpen((o) => ({ ...o, pii: !o.pii }))}
                right={
                  <span
                    style={{ fontSize: 12, color: piiCols.length ? T.red6 : T.textQua }}
                  >
                    {accessible ? (piiCols.length ? `${piiCols.length}개` : "없음") : "비공개"}
                  </span>
                }
              >
                {!accessible ? (
                  <div style={{ fontSize: 12, color: T.textTer }}>접근 권한이 없습니다.</div>
                ) : piiCols.length ? (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                    {piiCols.map((pc) => (
                      <span
                        key={String(pc.id)}
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

              {/* 4) 쿼리 제약 — 실 매핑(STEWARD 이상만 조회 가능, §6.2) */}
              <Section
                title="쿼리 제약"
                open={open.constraint}
                onToggle={() => setOpen((o) => ({ ...o, constraint: !o.constraint }))}
                last
                right={
                  <span
                    style={{ fontSize: 12, color: selMappings.length ? T.primary : T.textQua }}
                  >
                    · <span style={{ fontFamily: MONO_FONT }}>{effSelCol || "—"}</span>
                  </span>
                }
              >
                {!accessible ? (
                  <div style={{ fontSize: 12, color: T.textTer }}>접근 권한이 없습니다.</div>
                ) : !isSteward ? (
                  <Alert
                    type="info"
                    showIcon
                    message="컬럼 제약 매핑은 STEWARD 이상만 조회할 수 있습니다"
                  />
                ) : (
                  <>
                    <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 10 }}>
                      <Button size="small" type="dashed" onClick={onMapEdit}>
                        매핑 편집
                      </Button>
                    </div>
                    {selMappings.length ? (
                      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                        {selMappings.map((m) => (
                          <div
                            key={String(m.id)}
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
                            <span style={{ minWidth: 0, flex: 1 }}>
                              <div style={{ fontSize: 13, fontWeight: 500 }}>{m.defName}</div>
                              <div style={{ fontSize: 12, color: T.textTer, marginTop: 1 }}>
                                {m.defKind}
                                {m.purposeCode ? ` · purpose=${m.purposeCode}` : ""}
                              </div>
                            </span>
                            {m.clsMismatch && <Tag color="warning">클래스 불일치</Tag>}
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div style={{ fontSize: 12, color: T.textTer }}>
                        이 컬럼에 매핑된 제약이 없습니다. "매핑 편집"으로 추가하세요.
                      </div>
                    )}
                  </>
                )}
              </Section>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
