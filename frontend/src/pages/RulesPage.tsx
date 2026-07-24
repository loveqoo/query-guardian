import { useMemo, useState } from "react";
import { App, Button, Input, Select, Tag } from "antd";
import {
  AppstoreOutlined,
  CheckOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import { MONO_FONT } from "../theme";
import {
  columnsFor,
  condValueLabel,
  constraintOptionsFor,
  opMeta,
  ruleTrees as seedRuleTrees,
  rulesMeta,
  servers,
} from "../mock/design";
import type { RuleCondNode, RuleTree, Server } from "../mock/design";

/**
 * 규칙 관리 (Rule Management) — antd port of dc.html lines 558–640 (3-pane layout)
 * + condition rows / inline edit form (~1900–1960) + AND/OR tree + advanced rail
 * (~1857–2014) + IR summary (~1796–1855) + JSON tree (~1209–1255).
 *
 * STUB screen: everything is local React state seeded from src/mock/design.ts.
 * Save / test / add / delete only raise an antd message — no backend (spec 004).
 */

// ---------------------------------------------------------------------------
// Color tokens — the design HTML uses antd CSS variables (var(--color-*),
// var(--purple-7), …) that this app does not define, so they are resolved to
// their antd-default hex/rgba values here (dc.html tokens + .dev reference).
// ---------------------------------------------------------------------------
const C = {
  text: "rgba(0,0,0,0.88)",
  textSecondary: "rgba(0,0,0,0.65)",
  textTertiary: "rgba(0,0,0,0.45)",
  textQuaternary: "rgba(0,0,0,0.25)",
  border: "#d9d9d9",
  borderSecondary: "#f0f0f0",
  split: "rgba(5,5,5,0.06)",
  fillTertiary: "rgba(0,0,0,0.04)",
  error: "#ff4d4f",
  primary: "#1677ff",
  primaryBg: "#e6f4ff",
  primaryBorder: "#91caff",
  geekblue7: "#1d39c4",
  purple1: "#f9f0ff",
  purple3: "#d3adf7",
  purple4: "#b37feb",
  purple6: "#722ed1",
  purple7: "#531dab",
  blue3: "#91caff",
  blue4: "#69b1ff",
  gold6: "#faad14",
  gold7: "#d48806",
  gray2: "#fafafa",
  gray3: "#f5f5f5",
};

// ---------------------------------------------------------------------------
// Tree model — condition leaves + AND/OR groups (groups may nest groups).
// ---------------------------------------------------------------------------
type CondNode = RuleCondNode;
interface GroupNode {
  id: string;
  combinator: "all" | "any";
  children: TreeNode[];
}
type TreeNode = CondNode | GroupNode;

const isGroup = (n: TreeNode): n is GroupNode =>
  (n as GroupNode).children !== undefined;

type Scope = "single" | "multi" | "global";
type Mode = "basic" | "advanced";
type IrTab = "summary" | "ir";

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

function findNode(node: TreeNode, id: string): TreeNode | null {
  if (node.id === id) return node;
  if (isGroup(node)) {
    for (const c of node.children) {
      const f = findNode(c, id);
      if (f) return f;
    }
  }
  return null;
}

function removeFrom(node: TreeNode, id: string): void {
  if (isGroup(node)) {
    node.children = node.children.filter((c) => c.id !== id);
    node.children.forEach((c) => removeFrom(c, id));
  }
}

function collectLeaves(node: TreeNode, acc: CondNode[]): CondNode[] {
  if (isGroup(node)) node.children.forEach((c) => collectLeaves(c, acc));
  else acc.push(node);
  return acc;
}

const hasGroups = (node: TreeNode): boolean =>
  isGroup(node) && node.children.some((c) => isGroup(c));

function hasEmptyGroup(node: TreeNode): boolean {
  if (isGroup(node)) {
    if (node.children.length === 0) return true;
    return node.children.some((c) => isGroup(c) && hasEmptyGroup(c));
  }
  return false;
}

function condSubject(node: CondNode): string {
  if (node.db || node.table)
    return [node.db, node.table, node.column].filter(Boolean).join(".");
  return node.subject || "";
}

const serverByKey = (key: string): Server =>
  servers.find((s) => s.key === key) || servers[0];

// scope → [label, Tag color] for the rule list (dc.html line 1475)
const scopeTag: Record<Scope, [string, string]> = {
  single: ["단일 테이블", "green"],
  multi: ["다중 테이블 조인", "cyan"],
  global: ["전역 규칙", "gold"],
};
const scopeName: Record<Scope, string> = {
  single: "단일 테이블",
  multi: "다중 테이블 조인",
  global: "전역 규칙",
};

const scopeOptions = [
  { label: "단일 테이블", value: "single" },
  { label: "다중 테이블 조인", value: "multi" },
  { label: "전역 규칙", value: "global" },
];
const serverOptions = servers.map((s) => ({
  label: s.vendor + " · " + s.key,
  value: s.key,
}));
const opOptions = (Object.keys(opMeta) as (keyof typeof opMeta)[]).map((k) => ({
  label: opMeta[k].label,
  value: k,
}));

const STUB_MSG = "규칙 관리는 다음 단계(spec 004)에서 백엔드와 연결됩니다";

const fieldLabel: React.CSSProperties = {
  display: "block",
  fontSize: 12,
  color: C.textTertiary,
  marginBottom: 6,
};

// ---------------------------------------------------------------------------
export default function RulesPage() {
  const { message } = App.useApp();
  const stub = () => message.info(STUB_MSG);

  const [trees, setTrees] = useState<Record<string, GroupNode>>(() =>
    clone(seedRuleTrees as Record<string, RuleTree>) as Record<string, GroupNode>,
  );
  const [ruleKey, setRuleKey] = useState("r2");
  const [ruleScope, setRuleScope] = useState<Scope>("multi");
  const [ruleServer, setRuleServer] = useState("mysql-prod");
  const [ruleMode, setRuleMode] = useState<Mode>("basic");
  const [irTab, setIrTab] = useState<IrTab>("summary");
  const [expandedCond, setExpandedCond] = useState<string | null>("c1");
  const [irCollapsed, setIrCollapsed] = useState<Record<string, boolean>>({});
  const [hoverRule, setHoverRule] = useState<string | null>(null);

  const curRule = rulesMeta.find((r) => r.key === ruleKey);
  const tree = trees[ruleKey];
  const server = serverByKey(ruleServer);
  const invalid = hasEmptyGroup(tree);

  // --- tree mutation --------------------------------------------------------
  const updateTree = (mut: (t: GroupNode) => void) =>
    setTrees((prev) => {
      const t = clone(prev[ruleKey]);
      mut(t);
      return { ...prev, [ruleKey]: t };
    });

  const setCombinator = (id: string, m: "all" | "any") =>
    updateTree((t) => {
      const n = findNode(t, id);
      if (n && isGroup(n)) n.combinator = m;
    });

  const setCondField = (id: string, field: keyof CondNode, val: string) =>
    updateTree((t) => {
      const n = findNode(t, id);
      if (n && !isGroup(n)) (n as unknown as Record<string, unknown>)[field] = val;
    });

  const setCondPart = (id: string, part: "db" | "table" | "column", val: string) =>
    updateTree((t) => {
      const n = findNode(t, id);
      if (!n || isGroup(n)) return;
      n[part] = val;
      if (part === "db") {
        n.table = "";
        n.column = "";
        n.value = "";
      }
      if (part === "table") {
        n.column = "";
        n.value = "";
      }
      if (part === "column") n.value = "";
    });

  const removeNode = (id: string) => updateTree((t) => removeFrom(t, id));

  const addCond = (groupId: string) => {
    const nid = "c" + Date.now();
    const firstDb = Object.keys(server.databases)[0];
    const structured = ruleScope !== "global";
    updateTree((t) => {
      const g = findNode(t, groupId);
      if (g && isGroup(g))
        g.children.push(
          structured
            ? { type: "cond", id: nid, op: "requires", db: firstDb, table: "", column: "", value: "" }
            : { type: "cond", id: nid, op: "requires", subject: "SELECT statement", value: "제약 조건을 입력하세요" },
        );
    });
    setExpandedCond(nid);
  };

  const addGroup = (groupId: string) => {
    const firstDb = Object.keys(server.databases)[0];
    updateTree((t) => {
      const g = findNode(t, groupId);
      if (g && isGroup(g))
        g.children.push({
          id: "g" + Date.now(),
          combinator: "any",
          children: [
            { type: "cond", id: "c" + (Date.now() + 1), op: "requires", db: firstDb, table: "", column: "", value: "" },
          ],
        });
    });
  };

  const toggleIr = (path: string) =>
    setIrCollapsed((c) => ({ ...c, [path]: !c[path] }));

  const selectRule = (r: (typeof rulesMeta)[number]) => {
    setRuleKey(r.key);
    setRuleScope(r.scope);
    setRuleServer(r.server || "mysql-prod");
    setExpandedCond(null);
  };

  // --- IR object (dc.html lines 1486–1496) ----------------------------------
  const ruleIrObj = useMemo(() => {
    const irFromNode = (node: TreeNode): unknown =>
      isGroup(node)
        ? { match: node.combinator, conditions: node.children.map(irFromNode) }
        : { op: node.op, subject: condSubject(node), constraint: condValueLabel(node) };
    return {
      rule: ruleKey + ":" + (curRule ? curRule.name : ""),
      scope:
        ruleScope === "single" ? "single_table" : ruleScope === "multi" ? "multi_table" : "global",
      server: ruleScope === "global" ? null : ruleServer,
      ...(irFromNode(tree) as Record<string, unknown>),
      severity: curRule ? curRule.severity : "error",
      on_violation: curRule && curRule.severity === "error" ? "block" : "warn",
    };
  }, [tree, ruleKey, ruleScope, ruleServer, curRule]);

  // =========================================================================
  // Condition row (dc.html _condRow, lines 1906–1953)
  // =========================================================================
  function CondRow(node: CondNode) {
    const meta = opMeta[node.op] || { label: node.op, color: "" };
    const open = expandedCond === node.id;
    const structured = !!(node.db || node.table) || ruleScope !== "global";
    const dbOptions = Object.keys(server.databases).map((d) => ({ label: d, value: d }));
    const tableOptions = (server.databases[node.db || ""] || []).map((tb) => ({
      label: tb,
      value: tb,
    }));
    const columnList = node.table ? columnsFor(node.table) : [];
    const columnOptions = columnList.map((c) => ({
      label: c.name + "  ·  " + c.type,
      value: c.name,
    }));
    const cInfo = constraintOptionsFor(node.db || "", node.table || "", node.column || "");
    const constraintDisabled = !node.column || cInfo.options.length === 0;
    const noMapping = !!node.column && cInfo.options.length === 0;
    const subjText = condSubject(node);

    const subjectEditor = structured ? (
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <div>
          <label style={fieldLabel}>데이터베이스</label>
          <Select
            style={{ width: "100%" }}
            options={dbOptions}
            value={node.db || undefined}
            placeholder="선택"
            onChange={(v) => setCondPart(node.id, "db", v)}
          />
        </div>
        <div>
          <label style={fieldLabel}>테이블</label>
          <Select
            style={{ width: "100%" }}
            options={tableOptions}
            value={node.table || undefined}
            placeholder={node.db ? "선택" : "먼저 DB 선택"}
            onChange={(v) => setCondPart(node.id, "table", v)}
          />
        </div>
        <div style={{ gridColumn: "1 / -1" }}>
          <label style={fieldLabel}>컬럼</label>
          <Select
            style={{ width: "100%" }}
            options={columnOptions}
            value={node.column || undefined}
            placeholder={node.table ? "컬럼 선택" : "먼저 테이블 선택"}
            onChange={(v) => setCondPart(node.id, "column", v)}
          />
        </div>
      </div>
    ) : (
      <div>
        <label style={fieldLabel}>대상 (Subject)</label>
        <Input
          value={node.subject || ""}
          placeholder="예: SELECT statement"
          onChange={(e) => setCondField(node.id, "subject", e.target.value)}
        />
      </div>
    );

    const constraintEditor = structured ? (
      <div>
        <label
          style={{
            display: "flex",
            alignItems: "center",
            gap: 6,
            fontSize: 12,
            color: C.textTertiary,
            marginBottom: 6,
          }}
        >
          제약 조건
          {node.column && cInfo.type ? <Tag color="default">{cInfo.type}</Tag> : null}
        </label>
        <Select
          style={{ width: "100%" }}
          options={cInfo.options}
          value={node.value || undefined}
          disabled={constraintDisabled}
          placeholder={!node.column ? "먼저 컬럼을 선택하세요" : noMapping ? "매핑된 제약 없음" : "제약 선택"}
          onChange={(v) => setCondField(node.id, "value", v)}
        />
        {noMapping ? (
          <div style={{ fontSize: 11, color: C.gold7, marginTop: 6 }}>
            이 컬럼에 매핑된 제약이 없습니다 · 제약 카탈로그 → 컬럼 매핑에서 등록하세요
          </div>
        ) : null}
      </div>
    ) : (
      <div>
        <label style={fieldLabel}>제약 조건 (Constraint)</label>
        <Input
          value={node.value}
          onChange={(e) => setCondField(node.id, "value", e.target.value)}
        />
      </div>
    );

    return (
      <div
        key={node.id}
        style={{
          background: C.gray2,
          border: "1px solid " + (open ? C.primaryBorder : C.borderSecondary),
          borderRadius: 8,
          overflow: open ? "visible" : "hidden",
          position: "relative",
          zIndex: open ? 5 : 1,
        }}
      >
        <div
          onClick={() => setExpandedCond((cur) => (cur === node.id ? null : node.id))}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            padding: "10px 14px",
            cursor: "pointer",
          }}
        >
          <Tag color={meta.color || undefined}>{meta.label}</Tag>
          <span
            style={{ fontFamily: MONO_FONT, fontSize: 13, color: C.geekblue7 }}
          >
            {subjText || "(대상 미지정)"}
          </span>
          <span
            style={{
              flex: 1,
              minWidth: 0,
              fontSize: 12,
              color: C.textTertiary,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {open ? "" : "· " + condValueLabel(node)}
          </span>
          <span
            onClick={(e) => {
              e.stopPropagation();
              removeNode(node.id);
            }}
            style={{ flex: "none", cursor: "pointer", color: C.textTertiary, display: "inline-flex" }}
          >
            <DeleteOutlined />
          </span>
          <span
            style={{
              flex: "none",
              display: "inline-flex",
              color: C.textTertiary,
              transition: "transform .15s",
              transform: open ? "rotate(180deg)" : "none",
            }}
          >
            <DownOutlined />
          </span>
        </div>
        {open && (
          <div
            style={{
              padding: 14,
              borderTop: "1px solid " + C.borderSecondary,
              background: "#fff",
              display: "flex",
              flexDirection: "column",
              gap: 12,
            }}
          >
            <div>
              <label style={fieldLabel}>연산자 (Operator)</label>
              <Select
                style={{ width: "100%" }}
                options={opOptions}
                value={node.op}
                onChange={(v) => setCondField(node.id, "op", v)}
              />
            </div>
            {subjectEditor}
            {constraintEditor}
          </div>
        )}
      </div>
    );
  }

  // AND / OR join pill between two siblings (dc.html lines 1975 & 1996)
  const joinBadge = (key: string, label: string, isOr: boolean) => (
    <div key={key} style={{ display: "flex", justifyContent: "center", padding: "7px 0" }}>
      <span
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: isOr ? C.purple7 : C.textTertiary,
          background: isOr ? C.purple1 : C.gray3,
          border: "1px solid " + (isOr ? C.purple3 : C.borderSecondary),
          borderRadius: 100,
          padding: "2px 12px",
        }}
      >
        {label}
      </span>
    </div>
  );

  // --- basic mode (dc.html buildBasic 1969–1986) ----------------------------
  function BuildBasic() {
    const leaves = collectLeaves(tree, []);
    return (
      <div>
        {hasGroups(tree) && (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              padding: "10px 14px",
              marginBottom: 14,
              background: C.purple1,
              border: "1px solid " + C.purple3,
              borderRadius: 8,
              fontSize: 12,
              color: C.purple7,
            }}
          >
            <ExclamationCircleOutlined />
            <span>OR 그룹은 어드밴스드 모드에서 편집하세요</span>
          </div>
        )}
        <div style={{ display: "flex", flexDirection: "column" }}>
          {leaves.map((lf, i) => (
            <div key={lf.id}>
              {i > 0 && joinBadge("j" + i, "AND", false)}
              {CondRow(lf)}
            </div>
          ))}
        </div>
        <div style={{ marginTop: 16 }}>
          <Button type="dashed" block icon={<PlusOutlined />} onClick={() => addCond(tree.id)}>
            조건 추가
          </Button>
        </div>
      </div>
    );
  }

  // --- advanced mode (dc.html buildAdvanced 1987–2015) ----------------------
  function Group(node: GroupNode, depth: number): React.ReactNode {
    const isOr = node.combinator === "any";
    const rail = isOr ? C.purple4 : C.blue4;
    const combToggle = (
      <div
        style={{
          display: "flex",
          border: "1px solid " + C.border,
          borderRadius: 6,
          overflow: "hidden",
        }}
      >
        {([["all", "AND"], ["any", "OR"]] as const).map(([m, lbl]) => {
          const on = node.combinator === m;
          return (
            <span
              key={m}
              onClick={() => setCombinator(node.id, m)}
              style={{
                padding: "3px 11px",
                fontSize: 11,
                fontWeight: on ? 600 : 400,
                cursor: "pointer",
                background: on ? (m === "any" ? C.purple6 : C.primary) : "#fff",
                color: on ? "#fff" : C.textSecondary,
              }}
            >
              {lbl}
            </span>
          );
        })}
      </div>
    );
    return (
      <div
        key={node.id}
        style={{
          borderLeft: "3px solid " + rail,
          borderRadius: "2px 8px 8px 2px",
          background: depth === 0 ? "transparent" : "rgba(0,0,0,.015)",
          padding: depth === 0 ? "2px 0 2px 14px" : "12px 12px 12px 14px",
          marginBottom: depth === 0 ? 0 : 2,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            marginBottom: 12,
            flexWrap: "wrap",
          }}
        >
          <span
            style={{
              fontSize: 12,
              fontWeight: 500,
              color: isOr ? C.purple7 : C.textSecondary,
            }}
          >
            {depth === 0 ? "루트 그룹" : isOr ? "OR 그룹" : "AND 그룹"}
          </span>
          {combToggle}
          <span style={{ flex: 1 }} />
          <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => addCond(node.id)}>
            조건
          </Button>
          <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => addGroup(node.id)}>
            그룹
          </Button>
          {depth > 0 && (
            <span
              onClick={() => removeNode(node.id)}
              style={{ cursor: "pointer", color: C.textTertiary, display: "inline-flex" }}
            >
              <DeleteOutlined />
            </span>
          )}
        </div>
        <div style={{ display: "flex", flexDirection: "column" }}>
          {node.children.map((ch, i) => (
            <div key={ch.id}>
              {i > 0 && joinBadge("j" + i, isOr ? "OR" : "AND", isOr)}
              {isGroup(ch) ? Group(ch, depth + 1) : CondRow(ch)}
            </div>
          ))}
        </div>
      </div>
    );
  }

  // =========================================================================
  // IR — natural-language summary (dc.html buildRuleSummary 1796–1855)
  // =========================================================================
  function RuleSummary() {
    const opText = (op: string) => (opMeta[op as keyof typeof opMeta]?.label || op).split(" · ").pop();
    const condSentence = (node: CondNode) => (
      <div
        key={node.id}
        style={{ display: "flex", alignItems: "baseline", gap: 8, padding: "7px 0" }}
      >
        <span
          style={{
            flex: "none",
            width: 5,
            height: 5,
            borderRadius: "50%",
            background: C.textQuaternary,
            transform: "translateY(-2px)",
          }}
        />
        <span style={{ fontSize: 13, lineHeight: 1.6, color: C.text }}>
          <span style={{ fontFamily: MONO_FONT, color: C.geekblue7 }}>{condSubject(node)}</span>
          <span
            style={{
              margin: "0 6px",
              color: opMeta[node.op]?.color ? C.purple7 : C.textSecondary,
              fontWeight: 500,
            }}
          >
            {opText(node.op)}
          </span>
          <span style={{ color: C.textSecondary }}>{condValueLabel(node)}</span>
        </span>
      </div>
    );
    const groupBlock = (node: TreeNode, depth: number): React.ReactNode => {
      if (!isGroup(node)) return condSentence(node);
      const isOr = node.combinator === "any";
      const lead =
        depth === 0
          ? isOr
            ? "아래 조건 중 하나라도 만족하면"
            : "아래 조건을 모두 만족해야"
          : isOr
            ? "다음 중 하나라도 (OR)"
            : "다음을 모두 (AND)";
      return (
        <div
          key={node.id}
          style={
            depth === 0
              ? {}
              : {
                  borderLeft: "2px solid " + (isOr ? C.purple3 : C.blue3),
                  paddingLeft: 12,
                  margin: "6px 0 6px 4px",
                }
          }
        >
          <div
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: isOr ? C.purple7 : C.textSecondary,
              marginBottom: 4,
            }}
          >
            {lead + (depth === 0 ? " 합니다:" : ":")}
          </div>
          <div>{node.children.map((ch) => groupBlock(ch, depth + 1))}</div>
        </div>
      );
    };
    const chip = (label: string, val: string) => (
      <div key={label} style={{ display: "flex", flexDirection: "column", gap: 3 }}>
        <span style={{ fontSize: 11, color: C.textTertiary }}>{label}</span>
        <span style={{ fontSize: 13, color: C.text, fontWeight: 500 }}>{val}</span>
      </div>
    );
    const usedTables = Array.from(
      new Set(
        collectLeaves(tree, [])
          .filter((n) => n.table)
          .map((n) => n.db + "." + n.table),
      ),
    );
    const isError = !!curRule && curRule.severity === "error";
    return (
      <div>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "1fr 1fr",
            gap: 14,
            paddingBottom: 16,
            marginBottom: 16,
            borderBottom: "1px solid " + C.split,
          }}
        >
          {chip("적용 범위", scopeName[ruleScope] || "—")}
          {/* server */}
          <div style={{ display: "flex", flexDirection: "column", gap: 5 }}>
            <span style={{ fontSize: 11, color: C.textTertiary }}>대상 서버</span>
            {ruleScope === "global" ? (
              <span style={{ fontSize: 13, color: C.text, fontWeight: 500 }}>전체 서버 (전역)</span>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                <span style={{ fontSize: 13, display: "flex", alignItems: "center", gap: 6 }}>
                  <Tag color={server.vendorColor}>{server.vendor}</Tag>
                  <span style={{ fontFamily: MONO_FONT, color: C.text }}>{server.host}</span>
                </span>
                <span style={{ fontSize: 11, color: C.textTertiary }}>
                  {server.cluster + " · " + server.nodes}
                </span>
              </div>
            )}
          </div>
          {/* tables */}
          <div
            style={{ display: "flex", flexDirection: "column", gap: 5, gridColumn: "1 / -1" }}
          >
            <span style={{ fontSize: 11, color: C.textTertiary }}>조인 대상 테이블</span>
            {usedTables.length ? (
              <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                {usedTables.map((q, i) => (
                  <span
                    key={i}
                    style={{
                      fontFamily: MONO_FONT,
                      fontSize: 12,
                      background: C.gray3,
                      borderRadius: 4,
                      padding: "2px 8px",
                      color: C.text,
                    }}
                  >
                    {q}
                  </span>
                ))}
              </div>
            ) : (
              <span style={{ fontSize: 12, color: C.textTertiary }}>
                {ruleScope === "global" ? "해당 없음" : "테이블 미지정"}
              </span>
            )}
          </div>
        </div>
        <div>{groupBlock(tree, 0)}</div>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            marginTop: 16,
            paddingTop: 14,
            borderTop: "1px solid " + C.split,
            fontSize: 12,
            color: isError ? C.error : C.gold7,
          }}
        >
          {isError ? <CloseCircleOutlined /> : <ExclamationCircleOutlined />}
          <span>{isError ? "위반 시 쿼리를 차단합니다" : "위반 시 경고를 표시합니다"}</span>
        </div>
      </div>
    );
  }

  // =========================================================================
  // IR — JSON tree viewer (dc.html jsonTree 1209–1255)
  // =========================================================================
  function JsonTree({ data }: { data: unknown }) {
    const J = {
      key: "#c9d1d9",
      str: "#a5d6a4",
      num: "#f0a878",
      bool: "#79b8ff",
      type: "#8b98a5",
    };
    const rows: React.ReactNode[] = [];
    const leaf = (v: unknown) => {
      if (typeof v === "string") return <span style={{ color: J.str }}>{'"' + v + '"'}</span>;
      if (typeof v === "number") return <span style={{ color: J.num }}>{String(v)}</span>;
      if (typeof v === "boolean") return <span style={{ color: J.bool }}>{String(v)}</span>;
      if (v === null) return <span style={{ color: J.type }}>null</span>;
      return <span>{String(v)}</span>;
    };
    const caret = (openC: boolean, onClick: (e: React.MouseEvent) => void) => (
      <span
        onClick={onClick}
        style={{
          display: "inline-flex",
          width: 14,
          height: 18,
          alignItems: "center",
          justifyContent: "center",
          cursor: "pointer",
          color: J.type,
          flex: "none",
          transition: "transform .15s",
          transform: openC ? "none" : "rotate(-90deg)",
        }}
      >
        <svg width={9} height={9} viewBox="0 0 10 10">
          <path
            d="M1 3l4 4 4-4"
            stroke="currentColor"
            strokeWidth={1.4}
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
    );
    const keyLabel = (k: string | null) =>
      k != null ? <span style={{ color: J.key }}>{k}</span> : null;
    const typeBadge = (t: string) => (
      <span style={{ color: J.type, fontSize: 11, marginLeft: 6 }}>{t}</span>
    );
    const row = (indent: number, expandable: boolean, path: string, kids: React.ReactNode) => (
      <div
        key={"r" + path}
        style={{
          display: "flex",
          alignItems: "center",
          minHeight: 22,
          lineHeight: 1.6,
          paddingLeft: indent * 16 + (expandable ? 0 : 14),
          borderRadius: 4,
        }}
      >
        {kids}
      </div>
    );
    const walk = (d: unknown, indent: number, k: string | null, path: string) => {
      const isArr = Array.isArray(d);
      const isObj = d != null && typeof d === "object";
      if (isArr || isObj) {
        const openC = !irCollapsed[path];
        const keys = isArr
          ? (d as unknown[]).map((_, i) => i)
          : Object.keys(d as Record<string, unknown>);
        const count = keys.length;
        const summary = isArr ? "[ " + count + " ]" : "{ " + count + " }";
        rows.push(
          row(indent, true, path, [
            <span key="c">
              {caret(openC, (e) => {
                e.stopPropagation();
                toggleIr(path);
              })}
            </span>,
            <span key="k">{keyLabel(k)}</span>,
            <span key="t">
              {k != null
                ? typeBadge((isArr ? "Array" : "Object") + summary)
                : typeBadge((isArr ? "Array" : "Object") + " " + summary)}
            </span>,
          ]),
        );
        if (openC)
          keys.forEach((kk) =>
            walk(
              isArr ? (d as unknown[])[kk as number] : (d as Record<string, unknown>)[kk as string],
              indent + 1,
              String(kk),
              path + "." + kk,
            ),
          );
      } else {
        rows.push(
          row(indent, false, path, [
            <span key="k">{keyLabel(k)}</span>,
            k != null ? (
              <span key="s" style={{ color: J.type, margin: "0 6px" }}>
                :
              </span>
            ) : null,
            <span key="v">{leaf(d)}</span>,
          ]),
        );
      }
    };
    walk(data, 0, null, "$");
    return (
      <div style={{ fontFamily: MONO_FONT, fontSize: 12.5, color: "#e6edf3" }}>{rows}</div>
    );
  }

  // =========================================================================
  const cardStyle: React.CSSProperties = {
    background: "#fff",
    border: "1px solid " + C.borderSecondary,
    borderRadius: 8,
    display: "flex",
    flexDirection: "column",
    overflow: "hidden",
  };

  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "280px 1fr 320px",
        gap: 16,
        height: "100%",
        minHeight: 580,
      }}
    >
      {/* ============ rule list ============ */}
      <div style={cardStyle}>
        <div
          style={{
            padding: "14px 16px",
            borderBottom: "1px solid " + C.borderSecondary,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <span style={{ fontWeight: 600, fontSize: 14 }}>규칙 목록</span>
          <Button size="small" type="text" icon={<PlusOutlined />} onClick={stub} />
        </div>
        <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
          {rulesMeta.map((r) => {
            const sel = r.key === ruleKey;
            const hovered = hoverRule === r.key && !sel;
            const [scopeLabel, scopeColor] = scopeTag[r.scope];
            return (
              <div
                key={r.key}
                onClick={() => selectRule(r)}
                onMouseEnter={() => setHoverRule(r.key)}
                onMouseLeave={() => setHoverRule(null)}
                style={{
                  padding: "10px 12px",
                  borderRadius: 6,
                  cursor: "pointer",
                  marginBottom: 4,
                  border: "1px solid " + (sel ? C.primaryBorder : C.borderSecondary),
                  background: sel ? C.primaryBg : hovered ? C.fillTertiary : "#fff",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: "50%",
                      flex: "none",
                      background: r.severity === "error" ? C.error : C.gold6,
                    }}
                  />
                  <span
                    style={{
                      fontSize: 14,
                      fontWeight: 500,
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {r.name}
                  </span>
                </div>
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 8,
                    marginTop: 8,
                    paddingLeft: 16,
                  }}
                >
                  <Tag color={scopeColor}>{scopeLabel}</Tag>
                  <span style={{ fontSize: 11, color: C.textTertiary }}>
                    {r.hits}회 위반 감지
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* ============ builder ============ */}
      <div style={cardStyle}>
        {/* builder header */}
        <div style={{ padding: "16px 20px", borderBottom: "1px solid " + C.borderSecondary }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 12,
              marginBottom: 14,
            }}
          >
            <span style={{ fontSize: 15, fontWeight: 600 }}>{curRule ? curRule.name : ""}</span>
            <Tag color={curRule && curRule.severity === "error" ? "red" : "gold"}>
              {curRule && curRule.severity === "error" ? "차단 (오류)" : "경고"}
            </Tag>
          </div>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap", alignItems: "flex-start" }}>
            <div style={{ minWidth: 220 }}>
              <label style={fieldLabel}>적용 범위</label>
              <Select
                style={{ width: "100%" }}
                options={scopeOptions}
                value={ruleScope}
                onChange={(v) => setRuleScope(v as Scope)}
              />
            </div>
            <div style={{ flex: 1, minWidth: 240 }}>
              <label style={fieldLabel}>대상 서버 (클러스터)</label>
              {ruleScope === "global" ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 6,
                    minHeight: 32,
                    fontSize: 13,
                    color: C.textTertiary,
                  }}
                >
                  <Tag color="gold">전체 서버</Tag>
                  전역 규칙 · 모든 서버에 적용
                </div>
              ) : (
                <div>
                  <Select
                    style={{ width: "100%" }}
                    options={serverOptions}
                    value={ruleServer}
                    onChange={(v) => setRuleServer(v)}
                  />
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 8,
                      marginTop: 8,
                      fontSize: 12,
                      color: C.textTertiary,
                    }}
                  >
                    <span style={{ display: "inline-flex" }}>
                      <AppstoreOutlined />
                    </span>
                    <span style={{ fontFamily: MONO_FONT }}>{server.host}</span>
                    <span>·</span>
                    <span>{server.cluster}</span>
                    <span>·</span>
                    <span>{server.nodes}</span>
                  </div>
                  <div style={{ fontSize: 11, color: C.textQuaternary, marginTop: 6 }}>
                    테이블은 각 조건의 <b>대상</b>에서 이 서버 내 데이터베이스·테이블로 선택합니다.
                    서버 간 조인은 지원하지 않습니다.
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* builder body */}
        <div style={{ flex: 1, overflowY: "auto", padding: 20 }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 12,
              marginBottom: 16,
            }}
          >
            <span style={{ fontSize: 13, fontWeight: 500, color: C.textSecondary }}>
              {ruleMode === "advanced" ? "조건 트리" : "조건 목록"}
            </span>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ fontSize: 12, color: C.textTertiary }}>편집 모드</span>
              <div
                style={{
                  display: "flex",
                  border: "1px solid " + C.border,
                  borderRadius: 6,
                  overflow: "hidden",
                }}
              >
                {(
                  [
                    ["basic", "기본"],
                    ["advanced", "어드밴스드"],
                  ] as const
                ).map(([m, lbl]) => {
                  const on = ruleMode === m;
                  return (
                    <span
                      key={m}
                      onClick={() => setRuleMode(m)}
                      style={{
                        padding: "5px 14px",
                        fontSize: 12,
                        cursor: "pointer",
                        fontWeight: on ? 600 : 400,
                        background: on ? C.primary : "#fff",
                        color: on ? "#fff" : C.textSecondary,
                      }}
                    >
                      {lbl}
                    </span>
                  );
                })}
              </div>
            </div>
          </div>
          {ruleMode === "advanced" ? Group(tree, 0) : <BuildBasic />}
        </div>

        {/* builder footer */}
        <div
          style={{
            flex: "none",
            padding: "14px 20px",
            borderTop: "1px solid " + C.borderSecondary,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 10,
          }}
        >
          {invalid ? (
            <span
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                fontSize: 12,
                color: C.error,
              }}
            >
              <ExclamationCircleOutlined />
              조건이 하나도 없는 그룹이 있습니다
            </span>
          ) : (
            <span />
          )}
          <div style={{ display: "flex", gap: 10 }}>
            <Button onClick={stub}>테스트 실행</Button>
            <Button type="primary" icon={<CheckOutlined />} disabled={invalid} onClick={stub}>
              규칙 저장
            </Button>
          </div>
        </div>
      </div>

      {/* ============ IR preview ============ */}
      <div style={cardStyle}>
        <div
          style={{
            padding: "10px 16px 0",
            borderBottom: "1px solid " + C.borderSecondary,
            display: "flex",
            alignItems: "center",
            gap: 2,
          }}
        >
          {(
            [
              ["summary", "규칙 설정"],
              ["ir", "규칙 트리"],
            ] as const
          ).map(([k, lbl]) => {
            const on = irTab === k;
            return (
              <div
                key={k}
                onClick={() => setIrTab(k)}
                style={{
                  padding: "10px 14px",
                  fontSize: 13,
                  cursor: "pointer",
                  borderBottom: on ? "2px solid " + C.primary : "2px solid transparent",
                  color: on ? C.primary : C.textSecondary,
                  fontWeight: on ? 500 : 400,
                }}
              >
                {lbl}
              </div>
            );
          })}
        </div>
        {irTab === "summary" ? (
          <div style={{ flex: 1, overflow: "auto", padding: 18, background: "#fff" }}>
            <RuleSummary />
          </div>
        ) : (
          <div style={{ flex: 1, overflow: "auto", background: "#0b1220", padding: 16 }}>
            <JsonTree data={ruleIrObj} />
          </div>
        )}
      </div>
    </div>
  );
}
