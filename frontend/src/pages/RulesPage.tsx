import { useEffect, useMemo, useState } from "react";
import { App, Alert, Button, Input, Select, Spin, Tag } from "antd";
import {
  AppstoreOutlined,
  CheckOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  PlusOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { MONO_FONT } from "../theme";
import { opMeta, servers } from "../mock/design";
import { useAuth } from "../auth/AuthContext";
import StewardOnly from "../components/StewardOnly";
import {
  apiErrorMessage,
  createRule,
  deleteRule,
  getRule,
  listDefs,
  listMappings,
  listRules,
  listTables,
  testRule,
  updateRule,
  type CatalogTable,
  type ConstraintDef,
  type ConstraintMapping,
  type Id,
  type RuleDetail,
  type RuleDto,
  type RuleGroup,
  type RuleInput,
  type RuleOp,
  type RuleScope,
  type RuleSeverity,
  type RuleTreeNode,
} from "../api/client";

/**
 * 규칙 관리 (Rule Management) — spec 004 §8 실 연결.
 *
 * spec 003의 로컬 스텁 3단 레이아웃(목록 · 빌더 · IR 트리)을 유지하되 데이터 소스를
 * `/api/rules`로 전환한다. 조건 편집의 제약 select는 `/api/catalog/mappings`(컬럼 필터)
 * 실데이터, joins는 refTable/refColumn select, must_be_within은 "판정 미구현" 배지
 * (must_be_masked는 서버가 판정한다 — 배지를 달면 안 된다. 실제로 달고 있었다).
 * 저장/삭제/추가는 실 API, 테스트 실행은 백엔드 스텁 메시지.
 */

// ---------------------------------------------------------------------------
// Color tokens (dc.html tokens → antd-default hex/rgba). spec 003과 동일.
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
// Editor tree — client-side model with stable ids for React manipulation.
// Maps to/from the backend discriminated tree (node:"group"|"cond").
// ---------------------------------------------------------------------------
interface EditorCond {
  kind: "cond";
  id: string;
  op: RuleOp;
  severity: RuleSeverity;
  table?: string;
  column?: string;
  defId?: Id;
  mappingId?: Id;
  refTable?: string;
  refColumn?: string;
  subject?: string;
  value?: string;
}
interface EditorGroup {
  kind: "group";
  id: string;
  combinator: "all" | "any";
  children: EditorNode[];
}
type EditorNode = EditorCond | EditorGroup;

const isGroup = (n: EditorNode): n is EditorGroup => n.kind === "group";

interface Draft {
  id: Id | null;
  name: string;
  scope: RuleScope;
  server: string | null;
  enabled: boolean;
  tree: EditorGroup;
  corrupt: boolean;
}

type Mode = "basic" | "advanced";
type IrTab = "summary" | "ir";

let UID = 0;
const nid = (p: string): string => `${p}${Date.now()}_${++UID}`;

/**
 * **판정 대상 연산자** — 서버 `RuleCondition.judged`와 같아야 한다.
 *
 * 이 목록은 서버가 주지 않는다(`judged`는 `@JsonIgnore`다 — 파생 값을 내보내면 `tree_json`에
 * 저장돼 나중에 정의를 바꿔도 옛 행이 옛 답을 들고 있게 된다). 그래서 **사본일 수밖에 없고,
 * 사본이라서 갈라졌다**: 서버는 spec 008 M1에서 `must_be_masked`를 미판정 → 판정으로 전환했는데
 * 화면은 따라오지 않아 **"판정 미구현" 배지를 계속 달았다.** 담당자가 "어차피 안 걸린다"고 믿고
 * 등록하면 실제로는 사용자 쿼리가 차단된다 — 화면이 틀린 말을 하고 있었다.
 *
 * 갈라짐을 줄이는 장치 둘:
 * 1. **목록을 하나만 둔다.** 미판정은 판정의 여집합이다 — 예전에는 두 목록이 각자 틀릴 수 있었다.
 * 2. `.dev/tools/wire-contract-check.py`가 이 배열과 서버의 `judged` 정의를 대조한다(CI에서 돈다).
 */
const JUDGED_OPS: RuleOp[] = ["requires", "blocks", "joins", "must_be_masked"];
const isJudgedOp = (op: RuleOp): boolean => JUDGED_OPS.includes(op);
const isDeferredOp = (op: RuleOp): boolean => !isJudgedOp(op);

const clean = (v?: string): string | undefined =>
  v && v.trim() !== "" ? v : undefined;

// backend → editor
function fromApi(node: RuleTreeNode): EditorNode {
  if (node.node === "group") {
    return {
      kind: "group",
      id: nid("g"),
      combinator: node.combinator,
      children: node.children.map(fromApi),
    };
  }
  return {
    kind: "cond",
    id: nid("c"),
    op: node.op,
    severity: node.severity,
    table: node.table ?? undefined,
    column: node.column ?? undefined,
    defId: node.defId ?? undefined,
    mappingId: node.mappingId ?? undefined,
    refTable: node.refTable ?? undefined,
    refColumn: node.refColumn ?? undefined,
    subject: node.subject ?? undefined,
    value: node.value ?? undefined,
  };
}

// editor → backend (drops client ids, empties → undefined)
function toApi(node: EditorNode): RuleTreeNode {
  if (isGroup(node)) {
    return {
      node: "group",
      combinator: node.combinator,
      children: node.children.map(toApi),
    };
  }
  return {
    node: "cond",
    op: node.op,
    severity: node.severity,
    table: clean(node.table),
    column: clean(node.column),
    defId: node.defId ?? undefined,
    mappingId: node.mappingId ?? undefined,
    refTable: clean(node.refTable),
    refColumn: clean(node.refColumn),
    subject: clean(node.subject),
    value: clean(node.value),
  };
}

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

function findNode(node: EditorNode, id: string): EditorNode | null {
  if (node.id === id) return node;
  if (isGroup(node)) {
    for (const c of node.children) {
      const f = findNode(c, id);
      if (f) return f;
    }
  }
  return null;
}
function removeFrom(node: EditorNode, id: string): void {
  if (isGroup(node)) {
    node.children = node.children.filter((c) => c.id !== id);
    node.children.forEach((c) => removeFrom(c, id));
  }
}
function collectConds(node: EditorNode, acc: EditorCond[] = []): EditorCond[] {
  if (isGroup(node)) node.children.forEach((c) => collectConds(c, acc));
  else acc.push(node);
  return acc;
}
const hasGroups = (node: EditorNode): boolean =>
  isGroup(node) && node.children.some((c) => isGroup(c));
function hasEmptyGroup(node: EditorNode): boolean {
  if (isGroup(node)) {
    if (node.children.length === 0) return true;
    return node.children.some((c) => isGroup(c) && hasEmptyGroup(c));
  }
  return false;
}

/** 판정 조건이 재귀적으로 0개면 미강제(표시 전용) — §4.1 C3. */
function isEnforced(node: EditorNode): boolean {
  return collectConds(node).some((c) => isJudgedOp(c.op));
}
/** 표시용 파생 severity(미충족 leaf 최댓값 근사): 판정 조건 중 BLOCK 있으면 BLOCK. */
function derivedSeverity(node: EditorNode): "BLOCK" | "WARN" | "NONE" {
  const judged = collectConds(node).filter((c) => isJudgedOp(c.op));
  if (judged.length === 0) return "NONE";
  return judged.some((c) => c.severity === "BLOCK") ? "BLOCK" : "WARN";
}

const emptyCond = (scope: RuleScope): EditorCond =>
  scope === "GLOBAL"
    ? {
        kind: "cond",
        id: nid("c"),
        op: "must_be_masked",
        severity: "WARN",
        subject: "SELECT statement",
        value: "",
      }
    : {
        kind: "cond",
        id: nid("c"),
        op: "requires",
        severity: "BLOCK",
      };
const starterGroup = (scope: RuleScope): EditorGroup => ({
  kind: "group",
  id: nid("g"),
  combinator: "all",
  children: [emptyCond(scope)],
});

// ---------------------------------------------------------------------------
const SCOPE_LABEL: Record<RuleScope, string> = {
  SINGLE: "단일 테이블",
  MULTI: "다중 테이블 조인",
  GLOBAL: "전역 규칙",
};
const SCOPE_COLOR: Record<RuleScope, string> = {
  SINGLE: "green",
  MULTI: "cyan",
  GLOBAL: "gold",
};
const SCOPE_OPTIONS = (Object.keys(SCOPE_LABEL) as RuleScope[]).map((k) => ({
  label: SCOPE_LABEL[k],
  value: k,
}));
const SEV_DOT: Record<string, string> = {
  BLOCK: C.error,
  WARN: C.gold6,
  NONE: C.textQuaternary,
};
const OP_OPTIONS = (Object.keys(opMeta) as RuleOp[]).map((k) => ({
  label: opMeta[k].label,
  value: k,
}));
const SEVERITY_OPTIONS = [
  { label: "차단 (BLOCK)", value: "BLOCK" },
  { label: "경고 (WARN)", value: "WARN" },
];
const serverOptions = servers.map((s) => ({
  label: s.vendor + " · " + s.key,
  value: s.key,
}));
const serverByKey = (key: string | null) =>
  servers.find((s) => s.key === key) || servers[0];

const fieldLabel: React.CSSProperties = {
  display: "block",
  fontSize: 12,
  color: C.textTertiary,
  marginBottom: 6,
};

// ===========================================================================
export default function RulesPage() {
  const { message } = App.useApp();
  /**
   * 규칙 상세·쓰기와 카탈로그 조회(정의·매핑)는 STEWARD/ADMIN 전용 (spec 007 §5·§6.2).
   * ANALYST는 목록만 볼 수 있고 나머지가 403이라 화면이 성립하지 않으므로 안내 화면으로 대체한다.
   */
  const { isSteward, user } = useAuth();
  const sessionKey = isSteward ? (user?.id ?? "") : "";

  // catalog + rules
  const [loading, setLoading] = useState(true);
  const [rules, setRules] = useState<RuleDto[]>([]);
  const [tables, setTables] = useState<CatalogTable[]>([]);
  const [defs, setDefs] = useState<ConstraintDef[]>([]);
  const [mappings, setMappings] = useState<ConstraintMapping[]>([]);

  // editor
  const [draft, setDraft] = useState<Draft | null>(null);
  const [selectedId, setSelectedId] = useState<Id | null>(null);
  const [ruleMode, setRuleMode] = useState<Mode>("basic");
  const [irTab, setIrTab] = useState<IrTab>("summary");
  const [expandedCond, setExpandedCond] = useState<string | null>(null);
  const [irCollapsed, setIrCollapsed] = useState<Record<string, boolean>>({});
  const [hoverRule, setHoverRule] = useState<string | null>(null);
  const [buildError, setBuildError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const defById = useMemo(() => {
    const m = new Map<string, ConstraintDef>();
    defs.forEach((d) => m.set(String(d.id), d));
    return m;
  }, [defs]);

  // ---- load ---------------------------------------------------------------
  useEffect(() => {
    if (!sessionKey) {
      setLoading(false);
      return;
    }
    let alive = true;
    (async () => {
      try {
        const [rl, tl, dl, ml] = await Promise.all([
          listRules(),
          listTables(),
          listDefs(),
          listMappings(),
        ]);
        if (!alive) return;
        setRules(rl);
        setTables(tl);
        setDefs(dl);
        setMappings(ml);
        if (rl.length) {
          const d = await getRule(rl[0].id);
          if (alive) applyDetail(d);
        } else {
          startDraft();
        }
      } catch (e) {
        if (alive) message.error(apiErrorMessage(e) ?? "규칙을 불러오지 못했습니다");
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionKey]);

  function applyDetail(d: RuleDetail) {
    const tree = d.tree ? (fromApi(d.tree) as EditorGroup) : starterGroup(d.scope);
    setSelectedId(d.id);
    setDraft({
      id: d.id,
      name: d.name,
      scope: d.scope,
      server: d.server ?? null,
      enabled: d.enabled,
      tree,
      corrupt: d.corrupt,
    });
    setBuildError(null);
    setExpandedCond(null);
  }

  function startDraft() {
    setSelectedId(null);
    setDraft({
      id: null,
      name: "새 규칙",
      scope: "SINGLE",
      server: servers[0].key,
      enabled: true,
      tree: starterGroup("SINGLE"),
      corrupt: false,
    });
    setBuildError(null);
    setExpandedCond(null);
  }

  async function openRule(dto: RuleDto) {
    try {
      const d = await getRule(dto.id);
      applyDetail(d);
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "규칙 상세를 불러오지 못했습니다");
    }
  }

  // ---- tree mutation ------------------------------------------------------
  const updateTree = (mut: (t: EditorGroup) => void) =>
    setDraft((prev) => {
      if (!prev) return prev;
      const t = clone(prev.tree);
      mut(t);
      return { ...prev, tree: t };
    });

  const patchDraft = (patch: Partial<Draft>) =>
    setDraft((prev) => (prev ? { ...prev, ...patch } : prev));

  const updateCond = (id: string, patch: Partial<EditorCond>) =>
    updateTree((t) => {
      const n = findNode(t, id);
      if (n && n.kind === "cond") Object.assign(n, patch);
    });

  const setCombinator = (id: string, m: "all" | "any") =>
    updateTree((t) => {
      const n = findNode(t, id);
      if (n && isGroup(n)) n.combinator = m;
    });

  const removeNode = (id: string) => updateTree((t) => removeFrom(t, id));

  const addCond = (groupId: string) => {
    const c = emptyCond(draft?.scope ?? "SINGLE");
    updateTree((t) => {
      const g = findNode(t, groupId);
      if (g && isGroup(g)) g.children.push(c);
    });
    setExpandedCond(c.id);
  };

  const addGroup = (groupId: string) =>
    updateTree((t) => {
      const g = findNode(t, groupId);
      if (g && isGroup(g))
        g.children.push({
          kind: "group",
          id: nid("g"),
          combinator: "any",
          children: [emptyCond(draft?.scope ?? "SINGLE")],
        });
    });

  const setOp = (id: string, op: RuleOp) =>
    updateCond(id, {
      op,
      ...(op === "joins"
        ? { defId: undefined, mappingId: undefined }
        : { refTable: undefined, refColumn: undefined }),
    });

  const toggleIr = (path: string) =>
    setIrCollapsed((c) => ({ ...c, [path]: !c[path] }));

  // ---- catalog helpers ----------------------------------------------------
  const tableOptions = tables.map((t) => ({ label: t.name, value: t.name }));
  const columnsOf = (tableName?: string) =>
    tables.find((t) => t.name === tableName)?.columns ?? [];
  const columnId = (tableName?: string, colName?: string): Id | undefined =>
    columnsOf(tableName).find((c) => c.name === colName)?.id;
  const mappingsForColumn = (
    tableName?: string,
    colName?: string,
  ): ConstraintMapping[] => {
    const cid = columnId(tableName, colName);
    if (cid == null) return [];
    return mappings.filter((m) => String(m.columnId) === String(cid));
  };
  const defName = (defId?: Id): string => {
    if (defId == null) return "";
    return defById.get(String(defId))?.name ?? `defId=${defId}`;
  };

  const condSubjectText = (c: EditorCond): string => {
    if (draft?.scope === "GLOBAL") return c.subject || "";
    return [c.table, c.column].filter(Boolean).join(".");
  };
  const condConstraintLabel = (c: EditorCond): string => {
    if (c.op === "joins")
      return c.refTable && c.refColumn
        ? `${c.refTable}.${c.refColumn}`
        : "(조인 대상 미지정)";
    if (draft?.scope === "GLOBAL") return c.value || "";
    if (c.defId != null) return defName(c.defId);
    return c.value || "";
  };

  // ---- persistence --------------------------------------------------------
  async function save() {
    if (!draft) return;
    if (hasEmptyGroup(draft.tree)) return;
    const input: RuleInput = {
      name: draft.name.trim() || "새 규칙",
      scope: draft.scope,
      server: draft.scope === "GLOBAL" ? null : draft.server,
      enabled: draft.enabled,
      tree: toApi(draft.tree) as RuleGroup,
    };
    setSaving(true);
    setBuildError(null);
    try {
      const saved =
        draft.id == null
          ? await createRule(input)
          : await updateRule(draft.id, input);
      message.success("규칙을 저장했습니다");
      const rl = await listRules();
      setRules(rl);
      const dto = rl.find((r) => String(r.id) === String(saved.id));
      if (dto) await openRule(dto);
      else applyDetail(saved);
    } catch (e) {
      const m = apiErrorMessage(e) ?? "규칙 저장에 실패했습니다";
      setBuildError(m);
      message.error(m);
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!draft || draft.id == null) {
      startDraft();
      return;
    }
    try {
      await deleteRule(draft.id);
      message.success("규칙을 삭제했습니다");
      const rl = await listRules();
      setRules(rl);
      if (rl.length) await openRule(rl[0]);
      else startDraft();
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "규칙 삭제에 실패했습니다");
    }
  }

  async function runTest() {
    if (!draft || draft.id == null) {
      message.info("먼저 규칙을 저장한 뒤 테스트를 실행할 수 있습니다");
      return;
    }
    try {
      const r = await testRule(draft.id);
      message.info(r.message);
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "테스트 실행에 실패했습니다");
    }
  }

  // ---- live IR object (backend tree shape) --------------------------------
  const ruleIrObj = useMemo(() => {
    if (!draft) return {};
    return {
      rule: `${draft.id ?? "new"}:${draft.name}`,
      scope: draft.scope,
      server: draft.scope === "GLOBAL" ? null : draft.server,
      enabled: draft.enabled,
      enforced: isEnforced(draft.tree),
      severity: derivedSeverity(draft.tree),
      ...(toApi(draft.tree) as unknown as Record<string, unknown>),
    };
  }, [draft]);

  // =========================================================================
  // Condition row
  // =========================================================================
  function CondRow(node: EditorCond): React.ReactNode {
    const meta = opMeta[node.op] || { label: node.op, color: "" };
    const open = expandedCond === node.id;
    const global = draft?.scope === "GLOBAL";
    const deferred = isDeferredOp(node.op);
    const subjText = condSubjectText(node);

    const colMappings = mappingsForColumn(node.table, node.column);
    const selMapping =
      node.mappingId != null
        ? colMappings.find((m) => String(m.id) === String(node.mappingId))
        : colMappings.find((m) => String(m.defId) === String(node.defId));
    const noMapping = !!node.column && colMappings.length === 0;

    // --- subject / target editor ---
    const subjectEditor = global ? (
      <div>
        <label style={fieldLabel}>대상 (Subject)</label>
        <Input
          value={node.subject || ""}
          placeholder="예: SELECT statement"
          onChange={(e) => updateCond(node.id, { subject: e.target.value })}
        />
      </div>
    ) : (
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
        <div>
          <label style={fieldLabel}>테이블</label>
          <Select
            style={{ width: "100%" }}
            options={tableOptions}
            value={node.table || undefined}
            placeholder="테이블 선택"
            showSearch
            optionFilterProp="label"
            onChange={(v) =>
              updateCond(node.id, {
                table: v,
                column: undefined,
                defId: undefined,
                mappingId: undefined,
              })
            }
          />
        </div>
        <div>
          <label style={fieldLabel}>컬럼</label>
          <Select
            style={{ width: "100%" }}
            options={columnsOf(node.table).map((c) => ({
              label: c.name + "  ·  " + c.type,
              value: c.name,
            }))}
            value={node.column || undefined}
            placeholder={node.table ? "컬럼 선택" : "먼저 테이블 선택"}
            showSearch
            optionFilterProp="label"
            onChange={(v) =>
              updateCond(node.id, {
                column: v,
                defId: undefined,
                mappingId: undefined,
              })
            }
          />
        </div>
      </div>
    );

    // --- constraint / ref editor ---
    let detailEditor: React.ReactNode;
    if (global) {
      detailEditor = (
        <div>
          <label style={fieldLabel}>제약 조건 (Constraint)</label>
          <Input
            value={node.value || ""}
            placeholder="제약 조건을 입력하세요"
            onChange={(e) => updateCond(node.id, { value: e.target.value })}
          />
        </div>
      );
    } else if (node.op === "joins") {
      detailEditor = (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
          <div>
            <label style={fieldLabel}>조인 대상 테이블 (refTable)</label>
            <Select
              style={{ width: "100%" }}
              options={tableOptions}
              value={node.refTable || undefined}
              placeholder="테이블 선택"
              showSearch
              optionFilterProp="label"
              onChange={(v) =>
                updateCond(node.id, { refTable: v, refColumn: undefined })
              }
            />
          </div>
          <div>
            <label style={fieldLabel}>조인 대상 컬럼 (refColumn)</label>
            <Select
              style={{ width: "100%" }}
              options={columnsOf(node.refTable).map((c) => ({
                label: c.name + "  ·  " + c.type,
                value: c.name,
              }))}
              value={node.refColumn || undefined}
              placeholder={node.refTable ? "컬럼 선택" : "먼저 테이블 선택"}
              showSearch
              optionFilterProp="label"
              onChange={(v) => updateCond(node.id, { refColumn: v })}
            />
          </div>
        </div>
      );
    } else {
      // requires / blocks / must_be_* : mapped-constraint select
      detailEditor = (
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
            제약 조건 (매핑된 제약만)
            {selMapping ? <Tag color="default">{selMapping.defKind}</Tag> : null}
          </label>
          <Select
            style={{ width: "100%" }}
            options={colMappings.map((m) => ({
              value: String(m.id),
              label:
                m.defName +
                (m.purposeCode ? " · " + m.purposeCode : "") +
                "  ·  " +
                m.defKind,
            }))}
            value={selMapping ? String(selMapping.id) : undefined}
            disabled={!node.column || colMappings.length === 0}
            placeholder={
              !node.column
                ? "먼저 컬럼을 선택하세요"
                : noMapping
                  ? "매핑된 제약 없음"
                  : "제약 선택"
            }
            onChange={(v) => {
              const m = colMappings.find((x) => String(x.id) === v);
              if (m) updateCond(node.id, { defId: m.defId, mappingId: m.id });
            }}
          />
          {noMapping ? (
            <div style={{ fontSize: 11, color: C.gold7, marginTop: 6 }}>
              이 컬럼에 매핑된 제약이 없습니다 · 제약 카탈로그 → 컬럼 매핑에서 등록하세요
            </div>
          ) : null}
        </div>
      );
    }

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
          onClick={() =>
            setExpandedCond((cur) => (cur === node.id ? null : node.id))
          }
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            padding: "10px 14px",
            cursor: "pointer",
          }}
        >
          <Tag color={meta.color || undefined}>{meta.label}</Tag>
          <Tag color={node.severity === "BLOCK" ? "red" : "gold"}>
            {node.severity === "BLOCK" ? "차단" : "경고"}
          </Tag>
          {deferred ? (
            <Tag icon={<WarningOutlined />} color="default">
              판정 미구현
            </Tag>
          ) : null}
          <span style={{ fontFamily: MONO_FONT, fontSize: 13, color: C.geekblue7 }}>
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
            {open ? "" : "· " + condConstraintLabel(node)}
          </span>
          <span
            onClick={(e) => {
              e.stopPropagation();
              removeNode(node.id);
            }}
            style={{
              flex: "none",
              cursor: "pointer",
              color: C.textTertiary,
              display: "inline-flex",
            }}
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
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
              <div>
                <label style={fieldLabel}>연산자 (Operator)</label>
                <Select
                  style={{ width: "100%" }}
                  options={OP_OPTIONS}
                  value={node.op}
                  onChange={(v) => setOp(node.id, v as RuleOp)}
                />
              </div>
              <div>
                <label style={fieldLabel}>심각도 (Severity)</label>
                <Select
                  style={{ width: "100%" }}
                  options={SEVERITY_OPTIONS}
                  value={node.severity}
                  onChange={(v) =>
                    updateCond(node.id, { severity: v as RuleSeverity })
                  }
                />
              </div>
            </div>
            {subjectEditor}
            {detailEditor}
            {deferred ? (
              <div style={{ fontSize: 11, color: C.gold7 }}>
                이 조건은 등록·표시만 됩니다 — 이번 버전에서는 판정(강제)되지 않습니다.
              </div>
            ) : null}
          </div>
        )}
      </div>
    );
  }

  // AND / OR join pill between two siblings
  const joinBadge = (key: string, label: string, isOr: boolean) => (
    <div
      key={key}
      style={{ display: "flex", justifyContent: "center", padding: "7px 0" }}
    >
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

  // --- basic mode ----------------------------------------------------------
  function BuildBasic(tree: EditorGroup): React.ReactNode {
    const conds = collectConds(tree);
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
          {conds.map((lf, i) => (
            <div key={lf.id}>
              {i > 0 && joinBadge("j" + i, "AND", false)}
              {CondRow(lf)}
            </div>
          ))}
        </div>
        <div style={{ marginTop: 16 }}>
          <Button
            type="dashed"
            block
            icon={<PlusOutlined />}
            onClick={() => addCond(tree.id)}
          >
            조건 추가
          </Button>
        </div>
      </div>
    );
  }

  // --- advanced mode -------------------------------------------------------
  function Group(node: EditorGroup, depth: number): React.ReactNode {
    const isOr = node.combinator === "any";
    const rail = isOr ? C.purple4 : C.blue4;
    const mixedSeverity =
      isOr &&
      new Set(
        node.children
          .filter((c): c is EditorCond => c.kind === "cond")
          .map((c) => c.severity),
      ).size > 1;
    const combToggle = (
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
            ["all", "AND"],
            ["any", "OR"],
          ] as const
        ).map(([m, lbl]) => {
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
          {mixedSeverity ? (
            <Tag icon={<WarningOutlined />} color="warning">
              OR 그룹에 서로 다른 심각도 혼재
            </Tag>
          ) : null}
          <span style={{ flex: 1 }} />
          <Button
            size="small"
            type="dashed"
            icon={<PlusOutlined />}
            onClick={() => addCond(node.id)}
          >
            조건
          </Button>
          <Button
            size="small"
            type="dashed"
            icon={<PlusOutlined />}
            onClick={() => addGroup(node.id)}
          >
            그룹
          </Button>
          {depth > 0 && (
            <span
              onClick={() => removeNode(node.id)}
              style={{
                cursor: "pointer",
                color: C.textTertiary,
                display: "inline-flex",
              }}
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
  // IR — natural-language summary
  // =========================================================================
  function RuleSummary(d: Draft): React.ReactNode {
    const tree = d.tree;
    const opText = (op: RuleOp) => opMeta[op]?.label || op;
    const condSentence = (node: EditorCond) => (
      <div
        key={node.id}
        style={{
          display: "flex",
          alignItems: "baseline",
          gap: 8,
          padding: "7px 0",
        }}
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
          <span style={{ fontFamily: MONO_FONT, color: C.geekblue7 }}>
            {condSubjectText(node) || "(대상 미지정)"}
          </span>
          <span
            style={{
              margin: "0 6px",
              color: opMeta[node.op]?.color ? C.purple7 : C.textSecondary,
              fontWeight: 500,
            }}
          >
            {opText(node.op)}
          </span>
          <span style={{ color: C.textSecondary }}>
            {condConstraintLabel(node)}
          </span>
          {isDeferredOp(node.op) ? (
            <Tag
              icon={<WarningOutlined />}
              color="default"
              style={{ marginLeft: 6 }}
            >
              판정 미구현
            </Tag>
          ) : null}
        </span>
      </div>
    );
    const groupBlock = (node: EditorNode, depth: number): React.ReactNode => {
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
      new Set(collectConds(tree).filter((n) => n.table).map((n) => n.table as string)),
    );
    const server = serverByKey(d.server);
    const enforced = isEnforced(tree);
    const sev = derivedSeverity(tree);
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
          {chip("적용 범위", SCOPE_LABEL[d.scope])}
          <div style={{ display: "flex", flexDirection: "column", gap: 5 }}>
            <span style={{ fontSize: 11, color: C.textTertiary }}>대상 서버</span>
            {d.scope === "GLOBAL" ? (
              <span style={{ fontSize: 13, color: C.text, fontWeight: 500 }}>
                전체 서버 (전역)
              </span>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                <span
                  style={{ fontSize: 13, display: "flex", alignItems: "center", gap: 6 }}
                >
                  <Tag color={server.vendorColor}>{server.vendor}</Tag>
                  <span style={{ fontFamily: MONO_FONT, color: C.text }}>
                    {server.host}
                  </span>
                </span>
                <span style={{ fontSize: 11, color: C.textTertiary }}>
                  {server.cluster + " · " + server.nodes}
                </span>
              </div>
            )}
          </div>
          <div
            style={{
              display: "flex",
              flexDirection: "column",
              gap: 5,
              gridColumn: "1 / -1",
            }}
          >
            <span style={{ fontSize: 11, color: C.textTertiary }}>대상 테이블</span>
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
                {d.scope === "GLOBAL" ? "해당 없음" : "테이블 미지정"}
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
            color: !enforced
              ? C.textTertiary
              : sev === "BLOCK"
                ? C.error
                : C.gold7,
          }}
        >
          {!enforced ? (
            <>
              <ExclamationCircleOutlined />
              <span>
                {d.scope === "GLOBAL"
                  ? "표시 전용 — 이 규칙은 이번 버전에서 강제되지 않습니다"
                  : "이 규칙은 아무것도 차단/경고하지 않습니다 (판정 조건 없음)"}
              </span>
            </>
          ) : sev === "BLOCK" ? (
            <>
              <CloseCircleOutlined />
              <span>위반 시 쿼리를 차단합니다</span>
            </>
          ) : (
            <>
              <ExclamationCircleOutlined />
              <span>위반 시 경고를 표시합니다</span>
            </>
          )}
        </div>
      </div>
    );
  }

  // =========================================================================
  // IR — JSON tree viewer (dark #0b1220)
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
      if (typeof v === "string")
        return <span style={{ color: J.str }}>{'"' + v + '"'}</span>;
      if (typeof v === "number")
        return <span style={{ color: J.num }}>{String(v)}</span>;
      if (typeof v === "boolean")
        return <span style={{ color: J.bool }}>{String(v)}</span>;
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
    const row = (
      indent: number,
      expandable: boolean,
      path: string,
      kids: React.ReactNode,
    ) => (
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
            <span key="t">{typeBadge((isArr ? "Array" : "Object") + " " + summary)}</span>,
          ]),
        );
        if (openC)
          keys.forEach((kk) =>
            walk(
              isArr
                ? (d as unknown[])[kk as number]
                : (d as Record<string, unknown>)[kk as string],
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
      <div style={{ fontFamily: MONO_FONT, fontSize: 12.5, color: "#e6edf3" }}>
        {rows}
      </div>
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

  if (!isSteward) return <StewardOnly what="규칙 관리" />;

  if (loading) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          height: "100%",
          minHeight: 400,
        }}
      >
        <Spin size="large" />
      </div>
    );
  }

  const tree = draft?.tree;
  const invalid = !tree || hasEmptyGroup(tree);
  const headerSev = draft ? derivedSeverity(draft.tree) : "NONE";
  const headerEnforced = draft ? isEnforced(draft.tree) : false;
  const server = serverByKey(draft?.server ?? null);

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
          <Button
            size="small"
            type="text"
            icon={<PlusOutlined />}
            onClick={startDraft}
          />
        </div>
        <div style={{ flex: 1, overflowY: "auto", padding: 8 }}>
          {rules.length === 0 && (
            <div
              style={{
                padding: 16,
                fontSize: 12,
                color: C.textTertiary,
                textAlign: "center",
              }}
            >
              등록된 규칙이 없습니다
            </div>
          )}
          {rules.map((r) => {
            const key = String(r.id);
            const sel = selectedId != null && String(selectedId) === key;
            const hovered = hoverRule === key && !sel;
            return (
              <div
                key={key}
                onClick={() => openRule(r)}
                onMouseEnter={() => setHoverRule(key)}
                onMouseLeave={() => setHoverRule(null)}
                style={{
                  padding: "10px 12px",
                  borderRadius: 6,
                  cursor: "pointer",
                  marginBottom: 4,
                  border:
                    "1px solid " + (sel ? C.primaryBorder : C.borderSecondary),
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
                      background: SEV_DOT[r.severity] ?? C.textQuaternary,
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
                    gap: 6,
                    marginTop: 8,
                    paddingLeft: 16,
                    flexWrap: "wrap",
                  }}
                >
                  <Tag color={SCOPE_COLOR[r.scope]}>{SCOPE_LABEL[r.scope]}</Tag>
                  {r.corrupt ? <Tag color="error">손상</Tag> : null}
                  {r.scope === "GLOBAL" ? (
                    <Tag color="gold">표시 전용 (이번 버전 미강제)</Tag>
                  ) : !r.enforced && !r.corrupt ? (
                    <Tag color="default">강제 안 함</Tag>
                  ) : null}
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
            <Input
              value={draft?.name ?? ""}
              placeholder="규칙 이름"
              variant="borderless"
              style={{ fontSize: 15, fontWeight: 600, padding: 0, flex: 1 }}
              onChange={(e) => patchDraft({ name: e.target.value })}
            />
            {!headerEnforced ? (
              <Tag color="default">강제 안 함</Tag>
            ) : (
              <Tag color={headerSev === "BLOCK" ? "red" : "gold"}>
                {headerSev === "BLOCK" ? "차단" : "경고"}
              </Tag>
            )}
          </div>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap", alignItems: "flex-start" }}>
            <div className="qg-shrink-mobile" style={{ minWidth: 220 }}>
              <label style={fieldLabel}>적용 범위</label>
              <Select
                style={{ width: "100%" }}
                options={SCOPE_OPTIONS}
                value={draft?.scope}
                onChange={(v) =>
                  patchDraft({
                    scope: v as RuleScope,
                    server: v === "GLOBAL" ? null : (draft?.server ?? servers[0].key),
                  })
                }
              />
            </div>
            <div className="qg-shrink-mobile" style={{ flex: 1, minWidth: 240 }}>
              <label style={fieldLabel}>대상 서버 (클러스터)</label>
              {draft?.scope === "GLOBAL" ? (
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
                    value={draft?.server ?? undefined}
                    onChange={(v) => patchDraft({ server: v })}
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
                </div>
              )}
            </div>
          </div>
        </div>

        {/* builder body */}
        <div style={{ flex: 1, overflowY: "auto", padding: 20 }}>
          {draft?.corrupt ? (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message="손상된 규칙"
              description="저장된 트리를 파싱할 수 없습니다. 아래에서 조건을 다시 구성해 저장하면 복구됩니다."
            />
          ) : null}
          {buildError ? (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message="규칙을 저장할 수 없습니다"
              description={buildError}
              closable
              onClose={() => setBuildError(null)}
            />
          ) : null}
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
          {tree
            ? ruleMode === "advanced"
              ? Group(tree, 0)
              : BuildBasic(tree)
            : null}
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
            {draft?.id != null ? (
              <Button danger icon={<DeleteOutlined />} onClick={remove}>
                삭제
              </Button>
            ) : null}
            <Button onClick={runTest}>테스트 실행</Button>
            <Button
              type="primary"
              icon={<CheckOutlined />}
              disabled={invalid}
              loading={saving}
              onClick={save}
            >
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
                  borderBottom: on
                    ? "2px solid " + C.primary
                    : "2px solid transparent",
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
            {draft ? RuleSummary(draft) : null}
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
