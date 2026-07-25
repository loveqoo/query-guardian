import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  theme,
} from "antd";
import {
  CheckOutlined,
  DeleteOutlined,
  DownOutlined,
  LockOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UploadOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { MONO_FONT, STATUS_COLOR, STATUS_LABEL } from "../theme";
import { useAuth } from "../auth/AuthContext";
import { userLabel, useUsers } from "../auth/useUsers";
import {
  apiErrorMessage,
  approveApproval,
  cancelApproval,
  createApproval,
  getApproval,
  listApprovals,
  listBusinessReqs,
  listPurposes,
  listRules,
  myTables,
  rejectApproval,
  type ApprovalDetail,
  type ApprovalStatus,
  type ApprovalSummary,
  type BusinessReq,
  type MyTable,
  type Purpose,
  type RuleDto,
} from "../api/client";

/**
 * 승인 요청 화면 (spec 005 §8) — 실 API 연결.
 * 화면 골격은 디자인 원본(dc.html 418–556)을 유지하고 데이터·액션만 실 백엔드로 교체했다.
 */

/** §6 필수 카피 (H3) — 문구 변경 금지. */
const RULE_AUDIT_COPY =
  "체크한 규칙은 감사 기록용입니다. 실제 판정에는 활성 규칙 전체가 항상 적용되며, 체크를 해제해도 면제되지 않습니다.";

/** 백엔드 상태(대문자) → theme STATUS_* 키(소문자). */
function statusKey(s: string): string {
  return s.toLowerCase();
}

const DECISION_LABEL: Record<string, string> = {
  PENDING: "대기",
  APPROVED: "승인",
  REJECTED: "반려",
};
const DECISION_COLOR: Record<string, string> = {
  PENDING: "default",
  APPROVED: "green",
  REJECTED: "red",
};

const ACTION_LABEL: Record<string, string> = {
  SUBMIT: "제출",
  APPROVE: "승인",
  REJECT: "반려",
  CANCEL: "취소",
};

const AVATAR_COLORS = ["#722ed1", "#1677ff", "#13c2c2", "#fa8c16", "#eb2f96"];

/** ANALYST는 승인·반려 권한이 없다 (spec 007 §5 — 시도 시 403). */
const DECIDE_DENIED_TIP = "승인·반려는 STEWARD 이상만 가능합니다";

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return iso.length >= 16 ? iso.slice(0, 16).replace("T", " ") : iso;
}

function fmtDate(iso: string | null | undefined): string {
  return iso && iso.length >= 10 ? iso.slice(0, 10) : "—";
}

export default function ApprovalsPage() {
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const GRAY2 = "#fafafa";
  const label12 = { fontSize: 12, color: token.colorTextTertiary };

  const { user, isSteward } = useAuth();
  const { users, approvers: allApprovers } = useUsers();
  /** 요청자는 세션 사용자 — 행위자 선택 UI는 제거됐다 (spec 007 §8). */
  const actor = user?.id ?? "";
  const sessionKey = actor;
  /** 자가 승인 금지 (C1) — 본인은 승인자 후보에서 뺀다(서버도 400 `REQUESTER_IS_APPROVER`). */
  const approverPool = useMemo(
    () => allApprovers.filter((a) => a.id !== actor),
    [allApprovers, actor],
  );

  const [tab, setTab] = useState<"list" | "new">("list");

  // ---- 목록 -----------------------------------------------------------------
  const [items, setItems] = useState<ApprovalSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<"all" | ApprovalStatus>("all");
  const [requester, setRequester] = useState("all");
  const [approverFilter, setApproverFilter] = useState("all");

  // ---- 참조 데이터 ----------------------------------------------------------
  const [purposes, setPurposes] = useState<Purpose[]>([]);
  /** 요청 피커는 `/api/my/tables` — 전 테이블 + accessible (spec 007 §6.3). 비허용은 담을 수 없다(400). */
  const [pickerTables, setPickerTables] = useState<MyTable[]>([]);
  const [rules, setRules] = useState<RuleDto[]>([]);
  const [businessReqs, setBusinessReqs] = useState<BusinessReq[]>([]);

  // ---- 새 요청 작성 폼 -------------------------------------------------------
  const [purposeTitle, setPurposeTitle] = useState("");
  const [purposeCode, setPurposeCode] = useState<string | undefined>(undefined);
  const [formTables, setFormTables] = useState<string[]>([]);
  const [formRules, setFormRules] = useState<string[]>([]);
  const [formReqs, setFormReqs] = useState<string[]>([]);
  const [formApprovers, setFormApprovers] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  // ---- 상세 -----------------------------------------------------------------
  const [detail, setDetail] = useState<ApprovalDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // ---- 반려 사유 모달 --------------------------------------------------------
  const [rejectTarget, setRejectTarget] = useState<ApprovalSummary | null>(null);
  const [rejectNote, setRejectNote] = useState("");
  const [acting, setActing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await listApprovals({
        status: status === "all" ? undefined : status,
        requester: requester === "all" ? undefined : requester,
      });
      setItems(list);
    } catch {
      message.error("승인 요청 목록을 불러오지 못했습니다");
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [status, requester, message]);

  useEffect(() => {
    if (!sessionKey) return;
    void load();
  }, [load, sessionKey]);

  // 참조 데이터는 사용자별로 다시 받는다(허용 테이블이 사용자마다 다름).
  useEffect(() => {
    if (!sessionKey) return;
    listPurposes().then(setPurposes).catch(() => void 0);
    myTables().then(setPickerTables).catch(() => void 0);
    listRules().then(setRules).catch(() => void 0);
    listBusinessReqs().then(setBusinessReqs).catch(() => void 0);
  }, [sessionKey]);

  // 승인자 풀이 로드되면 기본 2단계 라인을 세팅한다 (디자인 원본과 동일한 초기 상태).
  // 사용자 전환 시 풀에서 사라진 승인자(=본인)는 라인에서 제거한다.
  useEffect(() => {
    if (approverPool.length === 0) return;
    setFormApprovers((prev) => {
      const kept = prev.filter((id) => approverPool.some((a) => a.id === id));
      return kept.length > 0 ? kept : approverPool.slice(0, 2).map((a) => a.id);
    });
  }, [approverPool]);

  const reqLabel = useMemo(() => {
    const m: Record<string, string> = {};
    businessReqs.forEach((b) => (m[b.code] = b.label));
    return m;
  }, [businessReqs]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return items.filter(
      (a) =>
        (!q || `REQ-${a.id} ${a.purposeTitle} ${a.purposeCode}`.toLowerCase().includes(q)) &&
        (approverFilter === "all" || a.approvers.some((x) => x.approverId === approverFilter)),
    );
  }, [items, search, approverFilter]);

  // ---- 액션 -----------------------------------------------------------------
  /** 성공하면 true. 409(순서 아닌 actor·재결정·이미 결정됨)는 서버 메시지를 그대로 노출한다. */
  async function runAction(fn: () => Promise<unknown>, okMsg: string): Promise<boolean> {
    setActing(true);
    try {
      await fn();
      message.success(okMsg);
      await load();
      return true;
    } catch (err) {
      message.error(apiErrorMessage(err) ?? "처리에 실패했습니다");
      return false;
    } finally {
      setActing(false);
    }
  }

  function openDetail(id: ApprovalSummary["id"]) {
    setDetailLoading(true);
    setDetail(null);
    getApproval(id)
      .then(setDetail)
      .catch(() => message.error("요청 상세를 불러오지 못했습니다"))
      .finally(() => setDetailLoading(false));
  }

  async function submitRequest() {
    if (!purposeTitle.trim()) {
      message.error("쿼리 목적을 입력하세요");
      return;
    }
    if (!purposeCode) {
      message.error("목적 코드(purpose)를 선택하세요");
      return;
    }
    if (formTables.length === 0) {
      message.error("조회 대상 테이블을 1개 이상 선택하세요");
      return;
    }
    const line = formApprovers.filter(Boolean);
    if (line.length === 0) {
      message.error("승인자를 1명 이상 지정하세요");
      return;
    }
    if (new Set(line).size !== line.length) {
      message.error("같은 승인자를 여러 단계에 지정할 수 없습니다");
      return;
    }
    // 자가 승인 금지 (C1) — 서버도 400 REQUESTER_IS_APPROVER로 거부한다.
    if (line.includes(actor)) {
      message.error("본인을 자신의 승인 라인에 지정할 수 없습니다 (REQUESTER_IS_APPROVER)");
      return;
    }
    setSubmitting(true);
    try {
      await createApproval({
        purposeTitle: purposeTitle.trim(),
        purposeCode,
        tables: formTables.map((tableName) => ({ tableName })),
        ruleIds: formRules.map((r) => Number(r)).filter((n) => !Number.isNaN(n)),
        businessReqs: formReqs,
        approvers: line.map((approverId, i) => ({ step: i + 1, approverId })),
      });
      message.success("승인 요청을 제출했습니다");
      setPurposeTitle("");
      setFormTables([]);
      setFormRules([]);
      setFormReqs([]);
      setTab("list");
      await load();
    } catch (err) {
      message.error(apiErrorMessage(err) ?? "승인 요청 제출에 실패했습니다");
    } finally {
      setSubmitting(false);
    }
  }

  // 미선택 강제(BLOCK) 규칙 — 실시간 (§6)
  const unselectedBlockRules = useMemo(
    () => rules.filter((r) => r.enabled && r.severity === "BLOCK" && !formRules.includes(String(r.id))),
    [rules, formRules],
  );

  const toggle = (setter: (fn: (prev: string[]) => string[]) => void, key: string) =>
    setter((prev) => (prev.includes(key) ? prev.filter((x) => x !== key) : [...prev, key]));

  // ==========================================================================
  // 요청 목록
  // ==========================================================================
  const listView = (
    <>
      <div
        style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 16 }}
      >
        <Input
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
          placeholder="요청번호·목적 검색"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          allowClear
        />
        <Select
          style={{ width: 140 }}
          value={status}
          onChange={(v) => setStatus(v)}
          options={[
            { label: "상태 전체", value: "all" },
            { label: "승인 대기", value: "PENDING" },
            { label: "승인됨", value: "APPROVED" },
            { label: "반려됨", value: "REJECTED" },
            { label: "요청 취소됨", value: "CANCELLED" },
          ]}
        />
        <Select
          style={{ width: 160 }}
          value={requester}
          onChange={setRequester}
          options={[
            { label: "요청자 전체", value: "all" },
            ...users.map((u) => ({ label: `${u.displayName} · ${u.title}`, value: u.id })),
          ]}
        />
        <Select
          style={{ width: 220 }}
          value={approverFilter}
          onChange={setApproverFilter}
          options={[
            { label: "승인자 전체", value: "all" },
            ...allApprovers.map((a) => ({
              label: `${a.displayName} · ${a.title}`,
              value: a.id,
            })),
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          새로고침
        </Button>
      </div>

      <Spin spinning={loading}>
        <div style={{ display: "flex", flexDirection: "column", gap: 14, minHeight: 120 }}>
          {filtered.length === 0 && !loading && (
            <Empty description="승인 요청이 없습니다. '새 요청 작성'에서 제출하세요." />
          )}
          {filtered.map((a) => {
            const pending = a.status === "PENDING";
            const currentApprover = a.approvers.find((x) => x.step === a.currentStep);
            const isCurrentApprover = pending && currentApprover?.approverId === actor;
            const isRequester = a.requester === actor;
            return (
              <div
                key={String(a.id)}
                style={{
                  background: "#fff",
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: 8,
                  padding: "18px 20px",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                    gap: 12,
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 4 }}>
                      <a
                        onClick={() => openDetail(a.id)}
                        style={{ fontFamily: MONO_FONT, fontSize: 12, color: token.colorLink }}
                      >
                        REQ-{String(a.id)}
                      </a>
                      <Tag color={STATUS_COLOR[statusKey(a.status)] ?? "default"}>
                        {STATUS_LABEL[statusKey(a.status)] ?? a.status}
                      </Tag>
                      <Tag color="blue">{a.purposeCode}</Tag>
                      {pending && (
                        <span style={{ fontSize: 11, color: token.colorTextTertiary }}>
                          {a.currentStep}차 승인 대기
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: 15, fontWeight: 600, color: token.colorTextHeading }}>
                      {a.purposeTitle}
                    </div>
                  </div>
                  <div style={{ textAlign: "right", flex: "none" }}>
                    <div style={label12}>요청일</div>
                    <div style={{ fontSize: 13 }}>{fmtDate(a.submittedAt)}</div>
                  </div>
                </div>

                <div style={{ display: "flex", gap: 32, marginTop: 14, flexWrap: "wrap" }}>
                  <div>
                    <div style={{ ...label12, marginBottom: 4 }}>요청자</div>
                    <div style={{ fontSize: 13 }}>{userLabel(users, a.requester)}</div>
                  </div>
                  <div>
                    <div style={{ ...label12, marginBottom: 4 }}>승인 라인 (순차)</div>
                    <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                      {a.approvers.map((ap) => (
                        <Tag
                          key={ap.step}
                          color={DECISION_COLOR[ap.decision] ?? "default"}
                          style={{ margin: 0 }}
                        >
                          {ap.step}. {ap.name} · {DECISION_LABEL[ap.decision] ?? ap.decision}
                        </Tag>
                      ))}
                    </div>
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ ...label12, marginBottom: 4 }}>조회 테이블</div>
                    <div
                      style={{
                        fontFamily: MONO_FONT,
                        fontSize: 12,
                        color: token.colorTextSecondary,
                      }}
                    >
                      {a.tables.join(", ") || "—"}
                    </div>
                  </div>
                </div>

                <div style={{ display: "flex", gap: 6, marginTop: 14, flexWrap: "wrap" }}>
                  {a.businessReqs.map((r) => (
                    <Tag key={r} color="geekblue">
                      {reqLabel[r] ?? r}
                    </Tag>
                  ))}
                </div>

                {pending && (
                  <div
                    style={{
                      display: "flex",
                      gap: 8,
                      marginTop: 16,
                      paddingTop: 14,
                      borderTop: `1px solid ${token.colorSplit}`,
                      justifyContent: "flex-end",
                      alignItems: "center",
                    }}
                  >
                    <span style={{ marginRight: "auto", fontSize: 11, color: token.colorTextTertiary }}>
                      현재 사용자: {userLabel(users, actor)}
                      {isCurrentApprover
                        ? " · 이 단계의 승인자입니다"
                        : isRequester
                          ? " · 요청자 (취소 가능)"
                          : !isSteward
                            ? " · 승인 권한 없음 (열람 전용)"
                            : " · 이 단계의 승인자가 아닙니다 (시도 시 409)"}
                    </span>
                    {/* 취소는 요청자 본인만 (spec 007 §5) */}
                    {isRequester && (
                      <Popconfirm
                        title="요청 취소"
                        description="이 승인 요청을 취소하시겠습니까?"
                        okText="취소하기"
                        cancelText="닫기"
                        onConfirm={() => runAction(() => cancelApproval(a.id), "요청을 취소했습니다")}
                      >
                        <Button size="small" loading={acting}>
                          요청 취소
                        </Button>
                      </Popconfirm>
                    )}
                    {/* 승인·반려는 STEWARD 이상 — ANALYST에게는 비활성 + 툴팁 (§8) */}
                    <Tooltip title={isSteward ? "" : DECIDE_DENIED_TIP}>
                      <Button
                        size="small"
                        danger
                        disabled={!isSteward}
                        icon={isSteward ? undefined : <LockOutlined />}
                        onClick={() => {
                          setRejectTarget(a);
                          setRejectNote("");
                        }}
                      >
                        반려
                      </Button>
                    </Tooltip>
                    <Tooltip title={isSteward ? "" : DECIDE_DENIED_TIP}>
                      <Button
                        size="small"
                        type="primary"
                        icon={isSteward ? <CheckOutlined /> : <LockOutlined />}
                        loading={acting}
                        disabled={!isSteward}
                        onClick={() => runAction(() => approveApproval(a.id), "승인했습니다")}
                      >
                        승인
                      </Button>
                    </Tooltip>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </Spin>
    </>
  );

  // ==========================================================================
  // 새 요청 작성 — 쿼리 작성 요청서
  // ==========================================================================
  const newView = (
    <div
      style={{
        background: "#fff",
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: 8,
        padding: "28px 32px",
      }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 6 }}>쿼리 작성 요청서</div>
          <div style={{ fontSize: 13, color: token.colorTextSecondary }}>
            요건을 제출하면 지정된 상위 조직장의 순차 승인 후 쿼리 작성이 가능합니다.
          </div>
        </div>
        {/* 요청자는 로그인 사용자로 고정 — 선택 불가 (spec 007 §4) */}
        <div style={{ textAlign: "right", flex: "none" }}>
          <div style={label12}>요청자</div>
          <div style={{ fontSize: 13, fontWeight: 500 }}>{user?.displayName ?? "—"}</div>
          <div style={{ fontSize: 11, color: token.colorTextTertiary }}>{user?.title ?? ""}</div>
        </div>
      </div>

      {/* 1. 쿼리 목적 */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          쿼리 목적 <span style={{ color: token.colorError }}>*</span>
        </label>
        <Input.TextArea
          rows={2}
          maxLength={200}
          showCount
          placeholder="예: Q3 마케팅 캠페인 대상자 추출"
          value={purposeTitle}
          onChange={(e) => setPurposeTitle(e.target.value)}
        />
      </div>

      {/* 2. 목적 코드 (관리형) */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          목적 코드 (purpose) <span style={{ color: token.colorError }}>*</span>
        </label>
        <div style={{ ...label12, marginBottom: 8 }}>
          판정에 쓰이는 purpose는 이 요청서에서 승인된 값이 사용됩니다 — 에디터에서 임의로 바꿀 수 없습니다.
        </div>
        <Select
          style={{ width: 320 }}
          value={purposeCode}
          onChange={setPurposeCode}
          placeholder="목적 코드 선택"
          options={purposes.map((p) => ({
            value: p.code,
            label: p.description ? `${p.code} · ${p.description}` : p.code,
          }))}
        />
      </div>

      {/* 3. 조회 대상 테이블 (카탈로그 선택만) */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          조회 대상 테이블 <span style={{ color: token.colorError }}>*</span>
        </label>
        <div style={{ ...label12, marginBottom: 10 }}>
          카탈로그에 등록된 테이블만 선택할 수 있습니다(자유 입력 불가). 저장 시 쿼리가 참조하는 모든
          테이블이 이 집합 안에 있어야 합니다. <b>접근 권한이 없는 테이블은 담을 수 없습니다</b>(요청 시 400).
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {pickerTables.map((t) => {
            const selected = formTables.includes(t.name);
            const locked = !t.accessible;
            return (
              <div
                key={t.name}
                onClick={() => {
                  if (!locked) toggle(setFormTables, t.name);
                }}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  padding: "10px 14px",
                  borderRadius: 8,
                  cursor: locked ? "not-allowed" : "pointer",
                  opacity: locked ? 0.55 : 1,
                  border: `1px solid ${selected ? token.colorPrimary : token.colorBorderSecondary}`,
                  background: selected ? token.colorPrimaryBg : locked ? "#fafafa" : "#fff",
                }}
              >
                <Checkbox
                  checked={selected}
                  disabled={locked}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggle(setFormTables, t.name)}
                />
                <span style={{ display: "flex", flexDirection: "column", minWidth: 0, flex: 1 }}>
                  <span
                    style={{
                      fontFamily: MONO_FONT,
                      fontSize: 13,
                      color: token.colorText,
                      display: "flex",
                      alignItems: "center",
                      gap: 6,
                    }}
                  >
                    {t.name}
                    {locked && (
                      <Tooltip title="접근 권한 없음 — 이 테이블은 요청서에 담을 수 없습니다">
                        <LockOutlined style={{ color: token.colorTextQuaternary, fontSize: 12 }} />
                      </Tooltip>
                    )}
                  </span>
                  <span style={{ fontSize: 11, color: token.colorTextTertiary }}>
                    {locked
                      ? "접근 권한 없음 · 열람 전용"
                      : t.description || `${t.columns.length}개 컬럼`}
                  </span>
                </span>
                {selected && (
                  <span style={{ display: "inline-flex", color: token.colorPrimary }}>
                    <CheckOutlined />
                  </span>
                )}
              </div>
            );
          })}
          {pickerTables.length === 0 && (
            <Empty description="카탈로그에 등록된 테이블이 없습니다" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </div>
      </div>

      {/* 4. 적용 규칙 (감사 기록용) */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          적용 규칙 (감사 기록용)
        </label>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={RULE_AUDIT_COPY} />
        {unselectedBlockRules.length > 0 && (
          <Alert
            type="info"
            showIcon
            icon={<WarningOutlined />}
            style={{ marginBottom: 12 }}
            message={`미선택 강제(BLOCK) 규칙 ${unselectedBlockRules.length}건 — 선택 여부와 무관하게 항상 적용됩니다`}
            description={
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 4 }}>
                {unselectedBlockRules.map((r) => (
                  <Tag key={String(r.id)} color="red" style={{ margin: 0 }}>
                    {r.name}
                  </Tag>
                ))}
              </div>
            }
          />
        )}
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {rules.map((r) => {
            const key = String(r.id);
            const checked = formRules.includes(key);
            return (
              <div
                key={key}
                onClick={() => toggle(setFormRules, key)}
                style={{
                  display: "flex",
                  gap: 10,
                  alignItems: "center",
                  padding: "9px 12px",
                  borderRadius: 6,
                  cursor: "pointer",
                  border: `1px solid ${checked ? token.colorPrimaryBorder : token.colorBorderSecondary}`,
                  background: checked ? token.colorPrimaryBg : "#fff",
                }}
              >
                <Checkbox
                  checked={checked}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggle(setFormRules, key)}
                />
                <span style={{ flex: 1, minWidth: 0, fontSize: 13, fontWeight: 500 }}>{r.name}</span>
                {r.scope === "GLOBAL" && <Tag color="gold">전역</Tag>}
                {!r.enabled && <Tag color="default">비활성</Tag>}
                <Tag color={r.severity === "BLOCK" ? "red" : r.severity === "WARN" ? "gold" : "default"}>
                  {r.severity === "BLOCK" ? "차단" : r.severity === "WARN" ? "경고" : "판정없음"}
                </Tag>
              </div>
            );
          })}
          {rules.length === 0 && (
            <div style={{ fontSize: 12, color: token.colorTextTertiary }}>
              등록된 규칙이 없습니다.
            </div>
          )}
        </div>
      </div>

      {/* 5. 비즈니스 요건 */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 12 }}>
          비즈니스 요건 (해당 항목 체크)
        </label>
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          {businessReqs.map((b) => {
            const checked = formReqs.includes(b.code);
            return (
              <div
                key={b.code}
                onClick={() => toggle(setFormReqs, b.code)}
                style={{
                  display: "flex",
                  gap: 12,
                  alignItems: "flex-start",
                  padding: "12px 14px",
                  borderRadius: 8,
                  cursor: "pointer",
                  border: `1px solid ${checked ? token.colorPrimaryBorder : token.colorBorderSecondary}`,
                  background: checked ? token.colorPrimaryBg : "#fff",
                }}
              >
                <Checkbox
                  checked={checked}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggle(setFormReqs, b.code)}
                />
                <span style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500 }}>{b.label}</div>
                  <div style={{ fontSize: 12, color: token.colorTextTertiary, marginTop: 2 }}>
                    {b.description}
                  </div>
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {/* 6. 승인 라인 (순차 승인) */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          승인 라인 (순차 승인) <span style={{ color: token.colorError }}>*</span>
        </label>
        <div style={{ ...label12, marginBottom: 12 }}>
          위에서부터 순서대로 승인이 진행됩니다 · 같은 사람을 두 단계에 넣을 수 없습니다 (최대 10단계) ·
          승인자는 <b>STEWARD 이상</b>만 지정할 수 있고 <b>본인은 지정할 수 없습니다</b>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 0 }}>
          {formApprovers.map((id, i) => {
            const person = approverPool.find((p) => p.id === id);
            return (
              <div key={`${id}-${i}`}>
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 12,
                    background: GRAY2,
                    border: `1px solid ${token.colorBorderSecondary}`,
                    borderRadius: 8,
                    padding: "12px 14px",
                  }}
                >
                  <span
                    style={{
                      width: 24,
                      height: 24,
                      flex: "none",
                      borderRadius: "50%",
                      background: token.colorPrimary,
                      color: "#fff",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontSize: 12,
                      fontWeight: 600,
                    }}
                  >
                    {i + 1}
                  </span>
                  <span
                    style={{
                      width: 34,
                      height: 34,
                      flex: "none",
                      borderRadius: "50%",
                      background: AVATAR_COLORS[i % AVATAR_COLORS.length],
                      color: "#fff",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontSize: 14,
                      fontWeight: 600,
                    }}
                  >
                    {(person?.displayName ?? "?").slice(0, 1)}
                  </span>
                  <span style={{ flex: 1, minWidth: 0 }}>
                    <Select
                      style={{ width: "100%", maxWidth: 320 }}
                      value={id}
                      onChange={(v) =>
                        setFormApprovers((prev) => prev.map((x, idx) => (idx === i ? v : x)))
                      }
                      options={approverPool.map((p) => ({
                        value: p.id,
                        label: `${p.displayName} · ${p.title}`,
                        disabled: formApprovers.includes(p.id) && p.id !== id,
                      }))}
                    />
                  </span>
                  <span style={{ fontSize: 11, color: token.colorTextTertiary }}>{i + 1}차 승인</span>
                  {formApprovers.length > 1 && (
                    <span
                      onClick={() =>
                        setFormApprovers((prev) => prev.filter((_, idx) => idx !== i))
                      }
                      style={{ cursor: "pointer", color: token.colorTextTertiary, display: "inline-flex" }}
                    >
                      <DeleteOutlined />
                    </span>
                  )}
                </div>
                {i < formApprovers.length - 1 && (
                  <div
                    style={{
                      display: "flex",
                      justifyContent: "center",
                      padding: "6px 0",
                      color: token.colorTextQuaternary,
                    }}
                  >
                    <DownOutlined />
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <div style={{ marginTop: 12 }}>
          <Button
            type="dashed"
            block
            icon={<PlusOutlined />}
            disabled={formApprovers.length >= Math.min(10, approverPool.length)}
            onClick={() => {
              const next = approverPool.find((p) => !formApprovers.includes(p.id));
              if (next) setFormApprovers((prev) => [...prev, next.id]);
            }}
          >
            승인자 추가
          </Button>
        </div>
      </div>

      {/* 7. actions */}
      <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
        <Button onClick={() => setTab("list")}>취소</Button>
        <Button type="primary" icon={<UploadOutlined />} loading={submitting} onClick={submitRequest}>
          승인 요청 제출
        </Button>
      </div>
    </div>
  );

  // ==========================================================================
  const changedCount = detail?.rules.filter((r) => r.changedSinceApproval).length ?? 0;

  return (
    <div style={{ maxWidth: 960 }}>
      <Tabs
        activeKey={tab}
        onChange={(k) => setTab(k as "list" | "new")}
        items={[
          { key: "list", label: "요청 목록", children: listView },
          { key: "new", label: "새 요청 작성", children: newView },
        ]}
      />

      {/* 반려 사유 */}
      <Modal
        open={!!rejectTarget}
        title={rejectTarget ? `REQ-${String(rejectTarget.id)} 반려` : ""}
        okText="반려"
        cancelText="닫기"
        okButtonProps={{ danger: true, loading: acting }}
        onCancel={() => setRejectTarget(null)}
        onOk={async () => {
          if (!rejectTarget) return;
          const ok = await runAction(
            () => rejectApproval(rejectTarget.id, rejectNote.trim() || undefined),
            "반려했습니다",
          );
          if (ok) setRejectTarget(null);
        }}
      >
        <div style={{ fontSize: 13, color: token.colorTextSecondary, marginBottom: 10 }}>
          반려 사유는 감사 이벤트 로그에 남습니다 (선택).
        </div>
        <Input.TextArea
          rows={3}
          maxLength={500}
          value={rejectNote}
          onChange={(e) => setRejectNote(e.target.value)}
          placeholder="반려 사유"
        />
      </Modal>

      {/* 요청 상세 — 규칙 스냅샷 + 이벤트 로그 */}
      <Modal
        open={detailLoading || !!detail}
        width={720}
        onCancel={() => setDetail(null)}
        footer={[
          <Button key="close" onClick={() => setDetail(null)}>
            닫기
          </Button>,
        ]}
        title={
          detail ? (
            <span style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span style={{ fontFamily: MONO_FONT, fontSize: 13, color: token.colorTextTertiary }}>
                REQ-{String(detail.summary.id)}
              </span>
              <span style={{ fontSize: 15, fontWeight: 600 }}>{detail.summary.purposeTitle}</span>
              <Tag color={STATUS_COLOR[statusKey(detail.summary.status)] ?? "default"}>
                {STATUS_LABEL[statusKey(detail.summary.status)] ?? detail.summary.status}
              </Tag>
            </span>
          ) : (
            "요청 상세"
          )
        }
      >
        <Spin spinning={detailLoading}>
          {detail && (
            <div style={{ display: "flex", flexDirection: "column", gap: 20, paddingTop: 8 }}>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "14px 24px" }}>
                <div>
                  <div style={label12}>목적 코드</div>
                  <Tag color="blue" style={{ marginTop: 4 }}>
                    {detail.summary.purposeCode}
                  </Tag>
                </div>
                <div>
                  <div style={label12}>요청자</div>
                  <div style={{ fontSize: 13, marginTop: 4 }}>
                    {userLabel(users, detail.summary.requester)}
                  </div>
                </div>
                <div>
                  <div style={label12}>조회 테이블</div>
                  <div style={{ fontFamily: MONO_FONT, fontSize: 12, marginTop: 4 }}>
                    {detail.summary.tables.join(", ") || "—"}
                  </div>
                </div>
                <div>
                  <div style={label12}>비즈니스 요건</div>
                  <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 4 }}>
                    {detail.summary.businessReqs.map((r) => (
                      <Tag key={r} color="geekblue" style={{ margin: 0 }}>
                        {reqLabel[r] ?? r}
                      </Tag>
                    ))}
                    {detail.summary.businessReqs.length === 0 && <span style={label12}>—</span>}
                  </div>
                </div>
              </div>

              {/* 규칙 스냅샷 */}
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8 }}>
                  <span style={{ fontSize: 14, fontWeight: 600 }}>규칙 스냅샷 (승인 당시)</span>
                  {changedCount > 0 && (
                    <Tag color="orange" icon={<WarningOutlined />} style={{ margin: 0 }}>
                      승인 당시와 규칙이 달라졌습니다 ({changedCount}건)
                    </Tag>
                  )}
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                  {detail.rules.map((r) => (
                    <div
                      key={String(r.ruleId)}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 8,
                        padding: "8px 12px",
                        border: `1px solid ${token.colorBorderSecondary}`,
                        borderRadius: 6,
                        background: GRAY2,
                      }}
                    >
                      <span style={{ flex: 1, minWidth: 0, fontSize: 13 }}>{r.ruleName}</span>
                      {r.forced && <Tag color="red">미선택 · 강제 적용</Tag>}
                      <Tag color={r.severitySummary === "ACTIVE" ? "green" : "default"}>
                        {r.severitySummary}
                      </Tag>
                      {r.changedSinceApproval && <Tag color="orange">변경됨</Tag>}
                    </div>
                  ))}
                  {detail.rules.length === 0 && <span style={label12}>규칙 스냅샷 없음</span>}
                </div>
              </div>

              {/* 이벤트 로그 */}
              <div>
                <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>
                  감사 이벤트 로그 (append-only)
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                  {detail.events.map((e, i) => (
                    <div
                      key={i}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 10,
                        fontSize: 12,
                        padding: "6px 10px",
                        borderLeft: `2px solid ${token.colorSplit}`,
                      }}
                    >
                      <span style={{ fontFamily: MONO_FONT, color: token.colorTextTertiary, width: 120 }}>
                        {fmtDateTime(e.at)}
                      </span>
                      <Tag style={{ margin: 0 }}>{ACTION_LABEL[e.action] ?? e.action}</Tag>
                      <span>{e.step != null ? `${e.step}단계 · ` : ""}</span>
                      <span>{userLabel(users, e.actor)}</span>
                      {e.note && (
                        <span style={{ color: token.colorTextSecondary }}>— {e.note}</span>
                      )}
                    </div>
                  ))}
                  {detail.events.length === 0 && <span style={label12}>이벤트 없음</span>}
                </div>
              </div>
            </div>
          )}
        </Spin>
      </Modal>
    </div>
  );
}
