import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  App,
  Button,
  Input,
  Select,
  Spin,
  Tag,
  Tooltip,
} from "antd";
import {
  BulbOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FireOutlined,
  RobotOutlined,
  SaveOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import CodeMirror from "@uiw/react-codemirror";
import { sql as sqlLang, MySQL } from "@codemirror/lang-sql";
import type { Extension } from "@codemirror/state";
import { MONO_FONT } from "../theme";
import { mockSql } from "../mock/design";
import {
  apiErrorMessage,
  createQuery,
  getQuery,
  getSchemaDict,
  lint,
  listPurposes,
  updateQuery,
  type LintReport,
  type Purpose,
  type SchemaDict,
  type Violation,
} from "../api/client";

// ============================================================================
// Palette (antd v5 css-var equivalents used inline by the design)
// ============================================================================
const C = {
  borderSecondary: "#f0f0f0",
  gray2: "#fafafa",
  split: "rgba(5,5,5,.06)",
  text: "rgba(0,0,0,.88)",
  textSecondary: "rgba(0,0,0,.65)",
  textTertiary: "rgba(0,0,0,.45)",
  textQuaternary: "rgba(0,0,0,.25)",
  primary: "#1677ff",
  green1: "#f6ffed",
  green3: "#b7eb8f",
  green6: "#52c41a",
  green7: "#389e0d",
  gold1: "#fffbe6",
  gold3: "#ffe58f",
  gold6: "#faad14",
  gold7: "#d48806",
  red3: "#ffccc7",
  redBg: "#fff2f0",
  red5: "#ff4d4f",
} as const;

const CARD: React.CSSProperties = {
  background: "#fff",
  border: `1px solid ${C.borderSecondary}`,
  borderRadius: 8,
};

// ============================================================================
// Static (design-faithful) constants
// ============================================================================
const INITIAL_SQL =
  "-- 마케팅 동의 사용자 추출 (Marketing consent users)\n" +
  "SELECT\n  u.id,\n  u.email,\n  u.name,\n  m.consent_at\n" +
  "FROM users AS u\nJOIN marketing_consents AS m\n  ON m.user_id = u.id\n" +
  "WHERE m.is_agreed = TRUE\n  AND u.created_at >= '2025-01-01'\n" +
  "ORDER BY m.consent_at DESC\nLIMIT 100;";

const VENDOR_LABELS: Record<string, string> = {
  mysql: "MySQL 8.0",
  postgresql: "PostgreSQL 16",
  trino: "Trino 440",
};

/** vendor/connection selects — only MySQL / prod-main enabled (gate invariant §4-4). */
const DISABLED_TIP = "후속 지원 예정";
const VENDOR_OPTIONS = [
  { value: "mysql", label: "MySQL", disabled: false },
  { value: "postgresql", label: "PostgreSQL", disabled: true },
  { value: "trino", label: "Trino", disabled: true },
];
const CONN_OPTIONS = [
  { value: "mysql-prod", label: "prod-main", disabled: false },
  { value: "pg-analytics", label: "analytics-dw", disabled: true },
  { value: "trino-lake", label: "data-lake", disabled: true },
];

function gatedOptions(opts: { value: string; label: string; disabled: boolean }[]) {
  return opts.map((o) => ({
    value: o.value,
    disabled: o.disabled,
    label: o.disabled ? (
      <Tooltip title={DISABLED_TIP} placement="right">
        <span style={{ display: "block" }}>{o.label}</span>
      </Tooltip>
    ) : (
      o.label
    ),
  }));
}

/** 추천 팝업 배지 (dc.html 1427–1431). */
const SUGGESTIONS = [
  { kind: "F", text: "marketing_consents", hint: "table", badgeBg: "#e6f4ff", badgeColor: "#1677ff" },
  { kind: "C", text: "is_agreed", hint: "boolean · marketing_consents", badgeBg: "#f9f0ff", badgeColor: "#722ed1" },
  { kind: "ƒ", text: "COUNT(*)", hint: "aggregate function", badgeBg: "#e6fffb", badgeColor: "#08979c" },
  { kind: "K", text: "GROUP BY", hint: "keyword", badgeBg: "#f0f5ff", badgeColor: "#2f54eb" },
];

/** 규칙 검사 결과 예시 카드 (dc.html 1434–1449) — 실 리포트가 없을 때만 노출, "예시" 표시. */
const SAMPLE_RULE_CARDS = [
  { rule: "PII 컬럼 마스킹 필수", status: "pass" as const, note: "email·name 조회는 승인 요청 REQ-1043 요건에 포함됨" },
  { rule: "마케팅 동의 사용자 한정", status: "pass" as const, note: "m.is_agreed = TRUE 조건 확인됨" },
  { rule: "대량 조회 LIMIT 강제", status: "warn" as const, note: "LIMIT 100 — 권장 최대 1,000 이내" },
];

/** 실행 결과 예시 그리드 (dc.html 1453–1458). */
const RESULT_ROWS = [
  { id: "10231", email: "j***@naver.com", name: "김*현", consent: "2026-07-20 09:12" },
  { id: "10244", email: "s***@gmail.com", name: "이*연", consent: "2026-07-19 22:41" },
  { id: "10250", email: "p***@kakao.com", name: "박*준", consent: "2026-07-18 14:03" },
  { id: "10262", email: "h***@daum.net", name: "정*윤", consent: "2026-07-17 11:55" },
];

const PROMPT_CHIPS = [
  { label: "최근 30일 마케팅 동의자", icon: <BulbOutlined /> },
  { label: "주문 금액 상위 100명", icon: <FireOutlined /> },
  { label: "이탈 위험 고객 세그먼트", icon: <ThunderboltOutlined /> },
];

type BottomTab = "rulecheck" | "result" | "messages";
interface AiMessage {
  role: "user" | "assistant";
  text: string;
  sql?: string;
}

// ============================================================================
// Component
// ============================================================================
export default function EditorPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editId = params.get("id");

  const [queryName, setQueryName] = useState("marketing_consent_users");
  const [sql, setSql] = useState(INITIAL_SQL);
  const [editorVendor] = useState("mysql"); // gate: MySQL 고정
  const [connKey] = useState("mysql-prod"); // gate: prod-main 고정
  const [purposeCode, setPurposeCode] = useState<string | undefined>(undefined);
  const [purposes, setPurposes] = useState<Purpose[]>([]);
  const [schema, setSchema] = useState<SchemaDict>({});

  const [showSuggest, setShowSuggest] = useState(false);
  const [bottomTab, setBottomTab] = useState<BottomTab>("rulecheck");
  const [aiOpen, setAiOpen] = useState(true);
  const [aiMessages, setAiMessages] = useState<AiMessage[]>([]);
  const [aiInput, setAiInput] = useState("");

  const [report, setReport] = useState<LintReport | null>(null);
  const [linting, setLinting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadingQuery, setLoadingQuery] = useState(false);

  const lintSeq = useRef(0);

  // ---- edit mode: load existing query --------------------------------------
  useEffect(() => {
    if (!editId) return;
    let alive = true;
    setLoadingQuery(true);
    getQuery(editId)
      .then((q) => {
        if (!alive) return;
        setQueryName(q.name);
        setSql(q.sql);
        setPurposeCode(q.purposeCode ?? undefined);
        if (q.lintReport) setReport(q.lintReport);
      })
      .catch(() => {
        if (alive) message.error("쿼리를 불러오지 못했습니다");
      })
      .finally(() => {
        if (alive) setLoadingQuery(false);
      });
    return () => {
      alive = false;
    };
  }, [editId, message]);

  // ---- catalog: purposes + schema completion -------------------------------
  useEffect(() => {
    listPurposes().then(setPurposes).catch(() => void 0);
    getSchemaDict().then(setSchema).catch(() => void 0);
  }, []);

  // ---- REAL lint (debounced 500ms) -----------------------------------------
  const runLint = useCallback(
    async (immediate = false) => {
      const body = sql.trim();
      if (!body) {
        setReport(null);
        return;
      }
      const seq = ++lintSeq.current;
      setLinting(true);
      try {
        const r = await lint({ dialect: "MYSQL", sql: body, purposeCode });
        if (seq === lintSeq.current) setReport(r);
      } catch {
        if (seq === lintSeq.current && immediate) message.error("규칙 검사에 실패했습니다");
      } finally {
        if (seq === lintSeq.current) setLinting(false);
      }
    },
    [sql, purposeCode, message],
  );

  useEffect(() => {
    if (!sql.trim()) {
      setReport(null);
      return;
    }
    const t = setTimeout(() => void runLint(false), 500);
    return () => clearTimeout(t);
  }, [sql, purposeCode, runLint]);

  // ---- CodeMirror SQL extension (MySQL dialect + schema completion) ---------
  const cmExtensions = useMemo<Extension[]>(
    () => [
      sqlLang({
        dialect: MySQL,
        schema: schema as Record<string, string[]>,
        upperCaseKeywords: true,
      }),
    ],
    [schema],
  );

  // ---- AI (stub, fixed response using mockSql) -----------------------------
  const sendAi = useCallback(
    (raw?: string) => {
      const text = (raw ?? aiInput).trim();
      if (!text) return;
      const generated = mockSql(text);
      setAiMessages((prev) => [
        ...prev,
        { role: "user", text },
        {
          role: "assistant",
          text: "승인된 요건과 규칙을 반영해 아래 쿼리를 제안합니다. 조회 컬럼은 요청서에 포함된 테이블로 제한했어요. (고정 예시 · 스텁)",
          sql: generated,
        },
      ]);
      setAiInput("");
    },
    [aiInput],
  );

  const applyAiSql = useCallback(
    (generated: string) => {
      setSql(generated);
      setBottomTab("rulecheck");
      message.success("에디터에 적용되었습니다");
    },
    [message],
  );

  // ---- REAL save -----------------------------------------------------------
  const handleSave = useCallback(async () => {
    const name = queryName.trim();
    if (!name) {
      message.error("쿼리 이름을 입력하세요");
      return;
    }
    if (name.length > 100) {
      message.error("쿼리 이름은 100자 이하여야 합니다");
      return;
    }
    if (!sql.trim()) {
      message.error("SQL을 입력하세요");
      return;
    }
    setSaving(true);
    try {
      const input = { name, dialect: "MYSQL" as const, sql, purposeCode };
      const result = editId ? await updateQuery(editId, input) : await createQuery(input);
      if (result.ok) {
        message.success(editId ? "쿼리가 수정되었습니다" : "쿼리가 저장되었습니다");
        navigate("/queries");
      } else {
        // 422 — 저장 차단: 위반 리포트를 규칙 검사 결과에 노출
        setReport(result.report);
        setBottomTab("rulecheck");
        message.error("저장이 차단되었습니다");
      }
    } catch (err) {
      message.error(apiErrorMessage(err) ?? "저장에 실패했습니다");
    } finally {
      setSaving(false);
    }
  }, [queryName, sql, purposeCode, editId, navigate, message]);

  const onRuleCheck = useCallback(() => {
    setBottomTab("rulecheck");
    void runLint(true);
  }, [runLint]);

  const onRun = useCallback(() => {
    setBottomTab("result");
    message.info("실행 기능은 다음 단계에서 구현됩니다");
  }, [message]);

  const appendSuggestion = useCallback((text: string) => {
    setSql((p) => p.replace(/;?\s*$/, "") + " " + text);
    setShowSuggest(false);
  }, []);

  const purposeOptions = useMemo(
    () =>
      purposes.map((p) => ({
        value: p.code,
        label: p.description ? `${p.code} · ${p.description}` : p.code,
      })),
    [purposes],
  );

  const vendorLabel = VENDOR_LABELS[editorVendor];

  // ==========================================================================
  return (
    <div style={{ display: "flex", gap: 16, height: "100%", minHeight: 600 }}>
      {/* ============ editor column ============ */}
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 16 }}>
        {/* toolbar */}
        <div
          style={{
            ...CARD,
            padding: "12px 16px",
            display: "flex",
            alignItems: "center",
            gap: 12,
            flexWrap: "wrap",
          }}
        >
          <div style={{ flex: 1, minWidth: 180 }}>
            <Input
              value={queryName}
              onChange={(e) => setQueryName(e.target.value)}
              prefix={<EditOutlined style={{ color: C.textTertiary }} />}
              maxLength={100}
              placeholder="쿼리 이름"
            />
          </div>
          <Tooltip title="현재 MySQL만 지원됩니다">
            <div style={{ width: 150 }}>
              <Select
                style={{ width: "100%" }}
                value={editorVendor}
                options={gatedOptions(VENDOR_OPTIONS)}
                onChange={() => void 0}
              />
            </div>
          </Tooltip>
          <div style={{ width: 170 }}>
            <Select
              style={{ width: "100%" }}
              value={connKey}
              options={gatedOptions(CONN_OPTIONS)}
              onChange={() => void 0}
            />
          </div>
          <div style={{ width: 180 }}>
            <Select
              style={{ width: "100%" }}
              value={purposeCode}
              options={purposeOptions}
              onChange={(v) => setPurposeCode(v)}
              allowClear
              placeholder="목적 (purpose)"
            />
          </div>
          <Button icon={<ThunderboltOutlined />} onClick={() => setShowSuggest((s) => !s)}>
            추천
          </Button>
          <Button icon={<CheckCircleOutlined />} loading={linting} onClick={onRuleCheck}>
            규칙 검사
          </Button>
          <Button icon={<ThunderboltOutlined />} onClick={onRun}>
            실행
          </Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
            {editId ? "수정 저장" : "저장"}
          </Button>
          <Button icon={<RobotOutlined />} onClick={() => setAiOpen((v) => !v)}>
            AI 에이전트
          </Button>
        </div>

        {/* code panel (macOS chrome + CodeMirror) */}
        <div style={{ ...CARD, overflow: "hidden", position: "relative" }}>
          <div
            style={{
              height: 38,
              display: "flex",
              alignItems: "center",
              gap: 8,
              padding: "0 14px",
              borderBottom: `1px solid ${C.borderSecondary}`,
              background: C.gray2,
            }}
          >
            <span style={dot("#ff5f56")} />
            <span style={dot("#ffbd2e")} />
            <span style={dot("#27c93f")} />
            <span style={{ fontSize: 12, color: C.textTertiary, marginLeft: 8, fontFamily: MONO_FONT }}>
              {queryName || "query"}.sql
            </span>
            <span style={{ marginLeft: "auto", fontSize: 12, color: C.textTertiary }}>{vendorLabel}</span>
          </div>
          <Spin spinning={loadingQuery}>
            <CodeMirror
              value={sql}
              height="320px"
              extensions={cmExtensions}
              onChange={setSql}
              basicSetup={{ lineNumbers: true, foldGutter: false, highlightActiveLine: true }}
              style={{ fontSize: 13 }}
            />
          </Spin>

          {/* suggestion popup (stub) */}
          {showSuggest && (
            <div
              style={{
                position: "absolute",
                right: 20,
                top: 120,
                width: 280,
                background: "#fff",
                border: `1px solid ${C.borderSecondary}`,
                borderRadius: 8,
                boxShadow: "0 6px 16px rgba(0,0,0,.12)",
                overflow: "hidden",
                zIndex: 20,
              }}
            >
              <div
                style={{
                  padding: "8px 12px",
                  fontSize: 12,
                  color: C.textTertiary,
                  borderBottom: `1px solid ${C.borderSecondary}`,
                }}
              >
                쿼리 추천 (예시)
              </div>
              {SUGGESTIONS.map((sg) => (
                <div
                  key={sg.text}
                  onClick={() => appendSuggestion(sg.text)}
                  style={{
                    padding: "9px 12px",
                    cursor: "pointer",
                    display: "flex",
                    alignItems: "center",
                    gap: 10,
                    borderBottom: `1px solid ${C.split}`,
                  }}
                >
                  <span
                    style={{
                      width: 20,
                      height: 20,
                      flex: "none",
                      borderRadius: 4,
                      background: sg.badgeBg,
                      color: sg.badgeColor,
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontSize: 11,
                      fontWeight: 700,
                      fontFamily: MONO_FONT,
                    }}
                  >
                    {sg.kind}
                  </span>
                  <span style={{ minWidth: 0 }}>
                    <div
                      style={{
                        fontFamily: MONO_FONT,
                        fontSize: 13,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {sg.text}
                    </div>
                    <div style={{ fontSize: 11, color: C.textTertiary }}>{sg.hint}</div>
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* bottom panel: 3 tabs */}
        <div style={{ ...CARD, overflow: "hidden", flex: "none" }}>
          <div style={{ display: "flex", gap: 2, borderBottom: `1px solid ${C.borderSecondary}`, padding: "0 8px" }}>
            {(
              [
                ["rulecheck", "규칙 검사 결과"],
                ["result", "실행 결과"],
                ["messages", "메시지"],
              ] as [BottomTab, string][]
            ).map(([k, l]) => (
              <div key={k} onClick={() => setBottomTab(k)} style={tabStyle(bottomTab === k)}>
                {l}
              </div>
            ))}
          </div>
          <div style={{ padding: 16, maxHeight: 210, overflowY: "auto" }}>
            {bottomTab === "rulecheck" && <RuleCheckPanel report={report} linting={linting} />}
            {bottomTab === "result" && <ResultPanel />}
            {bottomTab === "messages" && <MessagesPanel />}
          </div>
        </div>
      </div>

      {/* ============ AI panel ============ */}
      {aiOpen && (
        <div
          style={{
            width: 360,
            flex: "none",
            ...CARD,
            display: "flex",
            flexDirection: "column",
            overflow: "hidden",
          }}
        >
          <div
            style={{
              padding: "14px 16px",
              borderBottom: `1px solid ${C.borderSecondary}`,
              display: "flex",
              alignItems: "center",
              gap: 10,
            }}
          >
            <span
              style={{
                width: 28,
                height: 28,
                flex: "none",
                borderRadius: 8,
                background: "rgba(22,119,255,.12)",
                color: C.primary,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <RobotOutlined />
            </span>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>AI 에이전트</div>
              <div style={{ fontSize: 11, color: C.textTertiary }}>승인 요건·규칙 기반 쿼리 생성</div>
            </div>
            <span
              onClick={() => setAiOpen(false)}
              style={{ cursor: "pointer", color: C.textTertiary, display: "inline-flex" }}
            >
              <CloseOutlined />
            </span>
          </div>

          <div style={{ flex: 1, overflowY: "auto", padding: 16, display: "flex", flexDirection: "column", gap: 16 }}>
            {/* Welcome (filled) */}
            <div
              style={{
                background: "rgba(22,119,255,.06)",
                border: `1px solid rgba(22,119,255,.15)`,
                borderRadius: 8,
                padding: "14px 16px",
              }}
            >
              <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 4 }}>AI 에이전트</div>
              <div style={{ fontSize: 12, color: C.textSecondary, lineHeight: 1.6 }}>
                조회할 데이터를 자연어로 설명하면 승인 요건과 규칙에 맞는 SQL을 제안해 드립니다.
                <br />
                <span style={{ color: C.textTertiary }}>* 응답은 고정 예시입니다 (스텁).</span>
              </div>
            </div>

            {/* bubble list */}
            {aiMessages.map((m, i) => (
              <AiBubble key={i} msg={m} onApply={applyAiSql} />
            ))}
          </div>

          <div
            style={{
              flex: "none",
              padding: "12px 16px",
              borderTop: `1px solid ${C.borderSecondary}`,
              display: "flex",
              flexDirection: "column",
              gap: 12,
            }}
          >
            {/* prompts */}
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {PROMPT_CHIPS.map((p) => (
                <Tag
                  key={p.label}
                  icon={p.icon}
                  onClick={() => sendAi(p.label)}
                  style={{ cursor: "pointer", padding: "4px 10px", margin: 0, borderRadius: 16, fontSize: 12 }}
                >
                  {p.label}
                </Tag>
              ))}
            </div>
            {/* sender */}
            <Input.TextArea
              value={aiInput}
              onChange={(e) => setAiInput(e.target.value)}
              onPressEnter={(e) => {
                e.preventDefault();
                sendAi();
              }}
              placeholder="원하는 데이터를 설명하세요"
              autoSize={{ minRows: 1, maxRows: 4 }}
              style={{ resize: "none" }}
            />
            <Button type="primary" icon={<SendOutlined />} block onClick={() => sendAi()}>
              전송
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Sub-components
// ============================================================================
function dot(color: string): React.CSSProperties {
  return { width: 11, height: 11, borderRadius: "50%", background: color, display: "inline-block" };
}

function tabStyle(active: boolean): React.CSSProperties {
  return {
    padding: "10px 14px",
    fontSize: 13,
    cursor: "pointer",
    borderBottom: active ? `2px solid ${C.primary}` : "2px solid transparent",
    color: active ? C.primary : C.textSecondary,
    fontWeight: active ? 500 : 400,
  };
}

/** 규칙 검사 결과 — 실 lint 리포트 우선, 없으면 예시 카드. */
function RuleCheckPanel({ report, linting }: { report: LintReport | null; linting: boolean }) {
  if (report) {
    if (report.violations.length === 0) {
      // 통과 상태 (real)
      return (
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 10,
              padding: "12px 14px",
              border: `1px solid ${C.green3}`,
              background: C.green1,
              borderRadius: 6,
            }}
          >
            <CheckCircleOutlined style={{ color: C.green6, fontSize: 16 }} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 500 }}>규칙 위반이 없습니다</div>
              <div style={{ fontSize: 12, color: C.textSecondary, marginTop: 2 }}>
                모든 규칙을 통과했습니다 · 저장 가능
              </div>
            </div>
            <span style={{ marginLeft: "auto" }}>
              <Tag color="green">통과</Tag>
            </span>
          </div>
        </div>
      );
    }
    // 위반 카드 (real)
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {report.violations.map((v, i) => (
          <ViolationCard key={`${v.ruleId}-${i}`} v={v} />
        ))}
      </div>
    );
  }

  // 실 리포트 없음 → 예시 카드 (명확히 구분)
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <div style={{ fontSize: 12, color: C.textTertiary, display: "flex", alignItems: "center", gap: 6 }}>
        {linting ? <Spin size="small" /> : <Tag color="default">예시</Tag>}
        <span>실제 검사 전 표시되는 예시입니다. 타이핑 후 0.5초 또는 "규칙 검사"로 실제 검사가 실행됩니다.</span>
      </div>
      {SAMPLE_RULE_CARDS.map((r) => {
        const isPass = r.status === "pass";
        return (
          <div
            key={r.rule}
            style={{
              display: "flex",
              alignItems: "flex-start",
              gap: 10,
              padding: "10px 12px",
              border: `1px solid ${isPass ? C.green3 : C.gold3}`,
              background: isPass ? C.green1 : C.gold1,
              borderRadius: 6,
              opacity: 0.75,
            }}
          >
            <span style={{ color: isPass ? C.green6 : C.gold6, display: "inline-flex", fontSize: 16, marginTop: 1 }}>
              {isPass ? <CheckCircleOutlined /> : <ExclamationCircleOutlined />}
            </span>
            <span>
              <div style={{ fontSize: 13, fontWeight: 500 }}>{r.rule}</div>
              <div style={{ fontSize: 12, color: C.textSecondary, marginTop: 2 }}>{r.note}</div>
            </span>
            <span style={{ marginLeft: "auto", flex: "none" }}>
              <Tag color={isPass ? "green" : "gold"}>{isPass ? "통과" : "경고"}</Tag>
            </span>
          </div>
        );
      })}
    </div>
  );
}

function ViolationCard({ v }: { v: Violation }) {
  const block = v.severity === "BLOCK";
  return (
    <div
      style={{
        display: "flex",
        alignItems: "flex-start",
        gap: 10,
        padding: "10px 12px",
        border: `1px solid ${block ? C.red3 : C.gold3}`,
        background: block ? C.redBg : C.gold1,
        borderRadius: 6,
      }}
    >
      <span style={{ color: block ? C.red5 : C.gold6, display: "inline-flex", fontSize: 16, marginTop: 1 }}>
        {block ? <CloseCircleOutlined /> : <ExclamationCircleOutlined />}
      </span>
      <span style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 500 }}>{v.ruleId}</div>
        <div style={{ fontSize: 12, color: C.textSecondary, marginTop: 2 }}>{v.message}</div>
      </span>
      <span style={{ marginLeft: "auto", flex: "none" }}>
        <Tag color={block ? "red" : "gold"}>{block ? "차단 (오류)" : "경고"}</Tag>
      </span>
    </div>
  );
}

/** 실행 결과 — 스텁 마스킹 그리드. */
function ResultPanel() {
  const cell: React.CSSProperties = {
    padding: "8px 10px",
    borderBottom: `1px solid ${C.split}`,
  };
  const head: React.CSSProperties = {
    padding: "8px 10px",
    background: C.gray2,
    color: C.textTertiary,
    borderBottom: `1px solid ${C.borderSecondary}`,
  };
  return (
    <div style={{ overflowX: "auto" }}>
      <div style={{ marginBottom: 10 }}>
        <Tag color="default">예시 데이터</Tag>
      </div>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "60px 1fr 100px 160px",
          fontFamily: MONO_FONT,
          fontSize: 12,
          minWidth: 480,
        }}
      >
        <div style={head}>id</div>
        <div style={head}>email</div>
        <div style={head}>name</div>
        <div style={head}>consent_at</div>
        {RESULT_ROWS.map((row) => (
          <Fragment key={row.id}>
            <div style={cell}>{row.id}</div>
            <div style={cell}>{row.email}</div>
            <div style={cell}>{row.name}</div>
            <div style={cell}>{row.consent}</div>
          </Fragment>
        ))}
      </div>
      <div style={{ fontSize: 12, color: C.textTertiary, marginTop: 10 }}>4 rows · 0.08s · LIMIT 100 적용됨</div>
    </div>
  );
}

/** 메시지 — 디자인 4줄 샘플. */
function MessagesPanel() {
  return (
    <div style={{ fontFamily: MONO_FONT, fontSize: 12, lineHeight: 1.9, color: C.textSecondary }}>
      <div>
        <span style={{ color: C.green7 }}>✓</span> 승인 요청 REQ-1043 연결됨 (마케팅 캠페인)
      </div>
      <div>
        <span style={{ color: C.green7 }}>✓</span> 규칙 2건 통과 · 1건 경고
      </div>
      <div>
        <span style={{ color: C.gold7 }}>⚠</span> LIMIT 100 — 권장 최대 1,000 이내
      </div>
      <div>
        <span style={{ color: C.textTertiary }}>·</span> 자동 저장됨 2026-07-24 14:32
      </div>
    </div>
  );
}

/** AI 버블 — assistant는 좌측 회색, user는 우측 파랑. dark SQL 블록 + 에디터 적용. */
function AiBubble({ msg, onApply }: { msg: AiMessage; onApply: (sql: string) => void }) {
  const isA = msg.role === "assistant";
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: isA ? "flex-start" : "flex-end", gap: 8 }}>
      <div
        style={{
          maxWidth: "90%",
          padding: "10px 14px",
          fontSize: 13,
          lineHeight: 1.6,
          background: isA ? "#f5f5f5" : C.primary,
          color: isA ? C.text : "#fff",
          borderRadius: isA ? "12px 12px 12px 4px" : "12px 12px 4px 12px",
        }}
      >
        {msg.text}
      </div>
      {msg.sql && (
        <div style={{ alignSelf: "flex-start", maxWidth: "90%", background: "#0b1220", borderRadius: 8, overflow: "hidden" }}>
          <pre
            style={{
              margin: 0,
              padding: "12px 14px",
              fontFamily: MONO_FONT,
              fontSize: 12,
              lineHeight: 1.6,
              color: "#e6edf3",
              whiteSpace: "pre-wrap",
              overflowWrap: "break-word",
            }}
          >
            {msg.sql}
          </pre>
          <div style={{ padding: "8px 12px", background: "rgba(255,255,255,.04)", display: "flex", justifyContent: "flex-end" }}>
            <Button type="primary" size="small" icon={<CheckOutlined />} onClick={() => onApply(msg.sql!)}>
              에디터에 적용
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
