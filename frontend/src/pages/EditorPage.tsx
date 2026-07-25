import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  Alert,
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
import { useAuth } from "../auth/AuthContext";
import {
  apiErrorMessage,
  createQuery,
  getQuery,
  getSchemaDict,
  lint,
  listUsableApprovals,
  updateQuery,
  type AccessBlocked,
  type ApprovalBlocked,
  type ApprovalSummary,
  type Id,
  type LintReport,
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

/** 403 ApprovalBlockedDto.code → 한국어 라벨 (spec 005 §7 H5). */
const APPROVAL_BLOCK_LABEL: Record<string, string> = {
  NO_REQUEST: "승인 요청 미지정",
  NOT_APPROVED: "승인되지 않은 요청",
  REQUESTER_MISMATCH: "요청자 불일치",
  TABLES_NOT_COVERED: "승인 범위 밖 테이블",
};

/** 403 AccessBlockedDto.code → 한국어 라벨 (spec 007 §6.5). 승인 차단과 **별도 영역**에 노출한다 (§8). */
const ACCESS_BLOCK_LABEL: Record<AccessBlocked["code"], string> = {
  TABLES_NOT_PERMITTED: "권한 없는 테이블",
  TABLES_UNKNOWN: "카탈로그에 없는 테이블",
  REQUESTER_MISMATCH: "본인 요청만 사용 가능",
};

/** 코드별 안내 문구 — 오타(미등록)와 권한 부족을 구분해 보여준다 (M6). */
function accessBlockDescription(err: AccessBlocked): string {
  const list = err.deniedTables.join(", ");
  switch (err.code) {
    case "TABLES_NOT_PERMITTED":
      return list ? `권한 없는 테이블: ${list}` : err.message;
    case "TABLES_UNKNOWN":
      return list ? `카탈로그에 없는 테이블: ${list}` : err.message;
    case "REQUESTER_MISMATCH":
      return "본인이 요청한 승인만 사용할 수 있습니다 — 승인 요청을 다시 선택하세요.";
  }
}

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

  const { user } = useAuth();
  /** 사용자별 캐시 키 — 로그인/로그아웃 시 자동완성 사전·usable 목록을 다시 받는다 (§8 M5). */
  const sessionKey = user?.id ?? "";

  const [queryName, setQueryName] = useState("marketing_consent_users");
  const [sql, setSql] = useState(INITIAL_SQL);
  const [editorVendor] = useState("mysql"); // gate: MySQL 고정
  const [connKey] = useState("mysql-prod"); // gate: prod-main 고정
  /** 승인된 요청 선택 (spec 005 §8) — purposeCode는 여기서 서버가 승계한다 (C1). */
  const [requestId, setRequestId] = useState<Id | undefined>(undefined);
  const [usable, setUsable] = useState<ApprovalSummary[]>([]);
  const [usableLoading, setUsableLoading] = useState(true);
  const [approvalBlock, setApprovalBlock] = useState<ApprovalBlocked | null>(null);
  /** 데이터 권한 차단(403) — 규칙 위반·승인 차단과 분리된 자체 영역 (spec 007 §8). */
  const [accessBlock, setAccessBlock] = useState<AccessBlocked | null>(null);
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
        setRequestId(q.requestId);
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

  // ---- catalog: schema completion (허용 테이블만 · 사용자별) -------------------
  useEffect(() => {
    if (!sessionKey) return;
    let alive = true;
    getSchemaDict()
      .then((d) => {
        if (alive) setSchema(d);
      })
      .catch(() => void 0);
    return () => {
      alive = false;
    };
  }, [sessionKey]);

  // ---- 사용 가능한 승인 요청 (세션 사용자 기준) --------------------------------
  useEffect(() => {
    if (!sessionKey) return;
    let alive = true;
    setUsableLoading(true);
    listUsableApprovals()
      .then((list) => {
        if (!alive) return;
        setUsable(list);
        // 현재 선택이 이 사용자의 승인 목록에 없으면(사용자 전환 등) 첫 항목으로 재설정
        setRequestId((prev) =>
          prev != null && list.some((r) => String(r.id) === String(prev))
            ? prev
            : (list[0]?.id ?? undefined),
        );
      })
      .catch(() => {
        if (alive) setUsable([]);
      })
      .finally(() => {
        if (alive) setUsableLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [sessionKey]);

  const selectedRequest = useMemo(
    () => usable.find((r) => String(r.id) === String(requestId)) ?? null,
    [usable, requestId],
  );
  const noUsable = !usableLoading && usable.length === 0;

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
        // purposeCode는 보내지 않는다 — 서버가 requestId에서 주입한다 (C1).
        const r = await lint({ dialect: "MYSQL", sql: body, requestId });
        if (seq !== lintSeq.current) return;
        if (r.ok) {
          // 권한 통과 → 룰 결과 표시. 이전 권한 차단은 해소된 것으로 본다.
          setReport(r.report);
          setAccessBlock(null);
        } else {
          // 403 — 데이터 권한 차단이 룰보다 앞선다 (§6.0). 위반 리포트는 주지 않는다.
          setAccessBlock(r.error);
          setReport(null);
        }
      } catch {
        if (seq === lintSeq.current && immediate) message.error("규칙 검사에 실패했습니다");
      } finally {
        if (seq === lintSeq.current) setLinting(false);
      }
    },
    [sql, requestId, message],
  );

  useEffect(() => {
    if (!sql.trim()) {
      setReport(null);
      return;
    }
    const t = setTimeout(() => void runLint(false), 500);
    return () => clearTimeout(t);
  }, [sql, requestId, runLint]);

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
    if (requestId == null) {
      message.error("승인된 요청을 선택하세요 — 먼저 승인을 받으세요");
      return;
    }
    setSaving(true);
    setApprovalBlock(null);
    setAccessBlock(null);
    try {
      const input = { name, dialect: "MYSQL" as const, sql, requestId };
      const result = editId ? await updateQuery(editId, input) : await createQuery(input);
      if (result.ok) {
        message.success(editId ? "쿼리가 수정되었습니다" : "쿼리가 저장되었습니다");
        navigate("/queries");
      } else if (result.kind === "RULES") {
        // 422 — 룰 게이트 차단: 위반 리포트를 규칙 검사 결과에 노출
        setReport(result.report);
        setBottomTab("rulecheck");
        message.error("규칙 위반으로 저장이 차단되었습니다");
      } else if (result.kind === "ACCESS") {
        // 403 — 데이터 권한 차단: 규칙 위반·승인 차단과 **또 다른 영역**에 노출 (spec 007 §8)
        setAccessBlock(result.error);
        setReport(null);
        message.error("데이터 접근 권한이 없어 저장이 차단되었습니다");
      } else {
        // 403 — 승인 게이트 차단: 규칙 위반과 **별도 영역**에 노출 (§8)
        setApprovalBlock(result.error);
        message.error("승인 범위를 벗어나 저장이 차단되었습니다");
      }
    } catch (err) {
      message.error(apiErrorMessage(err) ?? "저장에 실패했습니다");
    } finally {
      setSaving(false);
    }
  }, [queryName, sql, requestId, editId, navigate, message]);

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

  const requestOptions = useMemo(
    () =>
      usable.map((r) => ({
        value: String(r.id),
        label: `REQ-${String(r.id)} · ${r.purposeTitle}`,
      })),
    [usable],
  );

  const vendorLabel = VENDOR_LABELS[editorVendor];

  // ==========================================================================
  return (
    <div className="qg-stack-mobile" style={{ display: "flex", gap: 16, height: "100%", minHeight: 600 }}>
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
          <div className="qg-shrink-mobile" style={{ flex: 1, minWidth: 180 }}>
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
          <Tooltip title="쿼리는 승인된 요청 범위 안에서만 저장됩니다 (spec 005 §4)">
            <div style={{ width: 250 }}>
              <Select
                style={{ width: "100%" }}
                value={requestId != null ? String(requestId) : undefined}
                options={requestOptions}
                onChange={(v) => {
                  setRequestId(v);
                  setApprovalBlock(null);
                  setAccessBlock(null);
                }}
                loading={usableLoading}
                disabled={noUsable}
                placeholder={noUsable ? "승인된 요청 없음" : "승인 요청 선택"}
              />
            </div>
          </Tooltip>
          <Button icon={<ThunderboltOutlined />} onClick={() => setShowSuggest((s) => !s)}>
            추천
          </Button>
          <Button icon={<CheckCircleOutlined />} loading={linting} onClick={onRuleCheck}>
            규칙 검사
          </Button>
          <Button icon={<ThunderboltOutlined />} onClick={onRun}>
            실행
          </Button>
          <Tooltip title={noUsable ? "먼저 승인을 받으세요" : ""}>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={noUsable || requestId == null}
              onClick={handleSave}
            >
              {editId ? "수정 저장" : "저장"}
            </Button>
          </Tooltip>
          <Button icon={<RobotOutlined />} onClick={() => setAiOpen((v) => !v)}>
            AI 에이전트
          </Button>
        </div>

        {/* 데이터 권한 차단(403) — 규칙 위반·승인 차단과 별도 영역 (spec 007 §8) */}
        {accessBlock && (
          <Alert
            type="error"
            showIcon
            closable
            onClose={() => setAccessBlock(null)}
            message={`데이터 권한 차단 · ${ACCESS_BLOCK_LABEL[accessBlock.code]}`}
            description={
              <div style={{ fontSize: 13 }}>
                <div style={{ fontFamily: accessBlock.deniedTables.length ? MONO_FONT : undefined }}>
                  {accessBlockDescription(accessBlock)}
                </div>
                {accessBlock.code === "TABLES_UNKNOWN" ? (
                  <div style={{ marginTop: 6, color: C.textSecondary }}>
                    이름 오타이거나 카탈로그에 등록되지 않은 테이블입니다 — 등록 후 다시 시도하세요.
                  </div>
                ) : accessBlock.code === "TABLES_NOT_PERMITTED" ? (
                  <div style={{ marginTop: 6, color: C.textSecondary }}>
                    접근 권한 관리 화면에서 권한을 요청하세요. 권한이 없는 동안은 규칙 검사 결과도
                    제공되지 않습니다.
                  </div>
                ) : null}
              </div>
            }
          />
        )}

        {/* 승인 요청 컨텍스트 — 읽기 전용 (§8) */}
        {noUsable ? (
          <Alert
            type="warning"
            showIcon
            message="먼저 승인을 받으세요"
            description="현재 사용자에게 승인 완료된 요청이 없습니다. '승인 요청' 화면에서 요청서를 제출하고 승인을 받은 뒤 쿼리를 저장할 수 있습니다."
          />
        ) : (
          selectedRequest && (
            <div
              style={{
                ...CARD,
                padding: "12px 16px",
                display: "flex",
                gap: 28,
                alignItems: "flex-start",
                flexWrap: "wrap",
                background: C.gray2,
              }}
            >
              <div>
                <div style={{ fontSize: 11, color: C.textTertiary, marginBottom: 4 }}>목적 (승인됨)</div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Tag color="blue" style={{ margin: 0 }}>
                    {selectedRequest.purposeCode}
                  </Tag>
                  <span style={{ fontSize: 13 }}>{selectedRequest.purposeTitle}</span>
                </div>
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11, color: C.textTertiary, marginBottom: 4 }}>승인 범위 테이블</div>
                <div style={{ fontFamily: MONO_FONT, fontSize: 12, color: C.textSecondary }}>
                  {selectedRequest.tables.join(", ") || "—"}
                </div>
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11, color: C.textTertiary, marginBottom: 4 }}>비즈니스 요건</div>
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                  {selectedRequest.businessReqs.map((r) => (
                    <Tag key={r} color="geekblue" style={{ margin: 0 }}>
                      {r}
                    </Tag>
                  ))}
                  {selectedRequest.businessReqs.length === 0 && (
                    <span style={{ fontSize: 12, color: C.textTertiary }}>—</span>
                  )}
                </div>
              </div>
            </div>
          )
        )}

        {/* 승인 게이트 차단(403) — 규칙 위반과 별도 영역 (§8) */}
        {approvalBlock && (
          <Alert
            type="error"
            showIcon
            closable
            onClose={() => setApprovalBlock(null)}
            message={`승인 게이트 차단 · ${APPROVAL_BLOCK_LABEL[approvalBlock.code] ?? approvalBlock.code}`}
            description={
              <div style={{ fontSize: 13 }}>
                <div>{approvalBlock.message}</div>
                {approvalBlock.uncoveredTables.length > 0 && (
                  <div style={{ marginTop: 6, fontFamily: MONO_FONT }}>
                    승인 범위에 없는 테이블: {approvalBlock.uncoveredTables.join(", ")}
                  </div>
                )}
                {approvalBlock.requestStatus && (
                  <div style={{ marginTop: 6 }}>
                    요청 상태: {approvalBlock.requestStatus}
                    {approvalBlock.requestId != null ? ` (REQ-${String(approvalBlock.requestId)})` : ""}
                  </div>
                )}
              </div>
            }
          />
        )}

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
            {bottomTab === "rulecheck" && (
              <RuleCheckPanel report={report} linting={linting} accessBlocked={!!accessBlock} />
            )}
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
function RuleCheckPanel({
  report,
  linting,
  accessBlocked,
}: {
  report: LintReport | null;
  linting: boolean;
  accessBlocked: boolean;
}) {
  // 권한 게이트가 룰보다 앞이므로(§6.0) 권한 차단 시 위반 목록 자체가 존재하지 않는다.
  if (accessBlocked && !report) {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: C.textSecondary }}>
        <CloseCircleOutlined style={{ color: C.red5 }} />
        데이터 접근 권한이 없어 규칙 검사 결과를 제공하지 않습니다 — 위 권한 차단 안내를 확인하세요.
      </div>
    );
  }
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
    // data-scroll-x: 결과 표는 좁은 화면에서 **한 열로 접지 않고** 이 컨테이너 안에서 가로 스크롤한다
    // (4열 데이터 표를 세로로 접으면 어느 값이 어느 컬럼인지 알 수 없게 된다).
    <div data-scroll-x style={{ overflowX: "auto" }}>
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
