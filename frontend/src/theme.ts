import type { ThemeConfig } from "antd";

/**
 * antd theme + design tokens for Query Guardian.
 * Ported from docs/design/query-guardian-design/Query Guardian.dc.html.
 */

/** Monospace font stack used for code / identifiers (dc.html reference token). */
export const MONO_FONT =
  "ui-monospace,SFMono-Regular,Consolas,Menlo,monospace";

/** Sider background (dark). */
export const SIDER_BG = "#001529";

/** Logo gradient. */
export const LOGO_GRADIENT = "linear-gradient(135deg,#1677ff,#0958d9)";

export const theme: ThemeConfig = {
  token: {
    colorPrimary: "#1677ff",
    borderRadius: 8,
  },
};

/** antd Tag `color` value type (preset name or hex). */
export type TagColor = string;

/** DB vendor → Tag color. */
export const VENDOR_COLOR: Record<string, TagColor> = {
  MySQL: "blue",
  PostgreSQL: "geekblue",
  Trino: "purple",
};

/** Constraint kind → Tag color. */
export const KIND_COLOR: Record<string, TagColor> = {
  mask: "purple",
  filter: "blue",
  block: "red",
  join: "cyan",
  integrity: "geekblue",
  partition: "orange",
};

/** Constraint kind → Korean label. */
export const KIND_LABEL: Record<string, string> = {
  mask: "마스킹",
  filter: "필터",
  block: "차단",
  join: "조인",
  integrity: "무결성",
  partition: "파티션",
};

/** Rule operator → Tag color. */
export const OP_COLOR: Record<string, TagColor> = {
  requires: "blue",
  joins: "cyan",
  must_be_within: "gold",
  must_be_masked: "purple",
  blocks: "red",
};

/** Rule operator → Korean label. */
export const OP_LABEL: Record<string, string> = {
  requires: "요건 필요",
  joins: "조인 강제",
  must_be_within: "기간 이내",
  must_be_masked: "마스킹 필수",
  blocks: "차단",
};

/** Approval status → Korean label. */
export const STATUS_LABEL: Record<string, string> = {
  pending: "승인 대기",
  approved: "승인됨",
  rejected: "반려됨",
  cancelled: "요청 취소됨",
  draft: "초안",
};

/** Approval status → Tag color. Matches design dc.html (pending=gold, draft/cancelled=default). */
export const STATUS_COLOR: Record<string, TagColor> = {
  pending: "gold",
  approved: "green",
  rejected: "red",
  cancelled: "default",
  draft: "default",
};

/** Rule severity → label + Tag color. */
export const SEVERITY: Record<string, { label: string; color: TagColor }> = {
  error: { label: "차단 (오류)", color: "red" },
  warning: { label: "경고", color: "gold" },
};

/**
 * Read-only SQL syntax highlighter — ports dc.html highlight() (lines 1179–1198).
 * Returns an HTML string (colored <span>s) for injection into a <pre>.
 * Color rules: comment #8c8c8c, string #c41d7f, number #d46b08,
 * keyword #0958d9 (bold), function #08979c.
 */
export function sqlHighlight(sql: string): string {
  const KW = new Set([
    "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
    "FULL", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS",
    "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "INSERT",
    "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "WITH",
    "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "DESC", "ASC",
    "UNION", "ALL", "EXISTS", "TRUE", "FALSE",
  ]);
  const FN = new Set([
    "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NOW", "DATE", "LOWER",
    "UPPER", "CAST", "ROUND", "CONCAT",
  ]);
  const esc = (s: string): string =>
    String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const re =
    /(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:[^']|'')*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)|(\s+)|([^\s])/g;
  const span = (c: string, t: string, w?: boolean): string =>
    `<span style="color:${c}${w ? ";font-weight:600" : ""}">${esc(t)}</span>`;
  let out = "";
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql))) {
    if (m[1]) out += span("#8c8c8c", m[1]);
    else if (m[2]) out += span("#c41d7f", m[2]);
    else if (m[3]) out += span("#d46b08", m[3]);
    else if (m[4]) {
      const up = m[4].toUpperCase();
      if (KW.has(up)) out += span("#0958d9", m[4], true);
      else if (FN.has(up)) out += span("#08979c", m[4]);
      else out += esc(m[4]);
    } else if (m[5]) out += esc(m[5]);
    else out += esc(m[6]);
  }
  return out + "\n";
}
