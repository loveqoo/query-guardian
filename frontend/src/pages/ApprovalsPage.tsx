import { useMemo, useState } from "react";
import {
  App,
  Button,
  Checkbox,
  Input,
  Select,
  Tabs,
  Tag,
  theme,
} from "antd";
import {
  CheckOutlined,
  DeleteOutlined,
  DownOutlined,
  PlusOutlined,
  SearchOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import { MONO_FONT, STATUS_COLOR, STATUS_LABEL, VENDOR_COLOR } from "../theme";
import {
  approverPool,
  baseApprovals,
  businessReqs,
  reqTableOptions,
  ruleTrees,
  rulesMeta,
} from "../mock/design";
import type { Approver } from "../mock/design";

/** Stub message for the approval workflow (spec 003 §4-2 → implemented in spec 004). */
const STUB_MSG = "승인 워크플로는 다음 단계(spec 004)에서 구현됩니다";

/** Approver row in the sequential approval line (local editable state). */
interface ReqApprover extends Approver {
  id: string;
}

/**
 * Tables referenced by a rule tree, as `db.table` keys.
 * Ports dc.html ruleTablesOf() (line 1387) — our ruleTrees are flat, so a
 * direct child scan replaces _collectLeaves recursion.
 */
function ruleTablesOf(rk: string): string[] {
  const tree = ruleTrees[rk];
  if (!tree) return [];
  return Array.from(
    new Set(
      tree.children
        .filter((n) => n.table)
        .map((n) => `${n.db}.${n.table}`),
    ),
  );
}

export default function ApprovalsPage() {
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const GRAY2 = "#fafafa";

  const [tab, setTab] = useState<"list" | "new">("list");

  // --- 요청 목록 filters ---
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [requester, setRequester] = useState("all");
  const [approver, setApprover] = useState("all");

  // --- 새 요청 작성 form state (seeded from dc.html line 854–859) ---
  const [purpose, setPurpose] = useState("");
  const [reqChecks, setReqChecks] = useState<Record<string, boolean>>({
    marketing: true,
    pii: true,
    mask: true,
    retention: false,
    external: false,
  });
  const [reqTables, setReqTables] = useState<string[]>(["t1", "t2"]);
  const [reqRules, setReqRules] = useState<string[]>(["r1", "r2"]);
  const [reqApprovers, setReqApprovers] = useState<ReqApprover[]>([
    { id: "ap1", name: "최지훈", role: "마케팅본부장", initial: "최", color: "#722ed1" },
    { id: "ap2", name: "한도윤", role: "데이터플랫폼장", initial: "한", color: "#1677ff" },
  ]);

  const requesterOptions = useMemo(
    () => [
      { label: "요청자 전체", value: "all" },
      ...Array.from(new Set(baseApprovals.map((a) => a.requester))).map((x) => ({
        label: x,
        value: x,
      })),
    ],
    [],
  );
  const approverOptions = useMemo(
    () => [
      { label: "승인자 전체", value: "all" },
      ...Array.from(new Set(baseApprovals.map((a) => a.approver))).map((x) => ({
        label: x,
        value: x,
      })),
    ],
    [],
  );

  const filtered = useMemo(
    () =>
      baseApprovals.filter(
        (a) =>
          (!search ||
            (a.id + " " + a.purpose).toLowerCase().includes(search.toLowerCase())) &&
          (status === "all" || a.status === status) &&
          (requester === "all" || a.requester === requester) &&
          (approver === "all" || a.approver === approver),
      ),
    [search, status, requester, approver],
  );

  const toggleReqRule = (key: string) =>
    setReqRules((prev) =>
      prev.includes(key) ? prev.filter((x) => x !== key) : [...prev, key],
    );
  const toggleReqTable = (id: string) =>
    setReqTables((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  const toggleCheck = (key: string) =>
    setReqChecks((prev) => ({ ...prev, [key]: !prev[key] }));
  const addApprover = () =>
    setReqApprovers((prev) => {
      const next = approverPool[prev.length % approverPool.length];
      return [...prev, { ...next, id: "ap" + Date.now() }];
    });
  const removeApprover = (id: string) =>
    setReqApprovers((prev) => (prev.length > 1 ? prev.filter((a) => a.id !== id) : prev));

  const label12 = { fontSize: 12, color: token.colorTextTertiary };

  // ========================================================================
  // 요청 목록
  // ========================================================================
  const listView = (
    <>
      <div
        style={{
          display: "flex",
          gap: 10,
          alignItems: "center",
          flexWrap: "wrap",
          marginBottom: 16,
        }}
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
          onChange={setStatus}
          options={[
            { label: "상태 전체", value: "all" },
            { label: "승인 대기", value: "pending" },
            { label: "승인됨", value: "approved" },
            { label: "반려됨", value: "rejected" },
            { label: "요청 취소됨", value: "cancelled" },
          ]}
        />
        <Select
          style={{ width: 150 }}
          value={requester}
          onChange={setRequester}
          options={requesterOptions}
        />
        <Select
          style={{ width: 220 }}
          value={approver}
          onChange={setApprover}
          options={approverOptions}
        />
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {filtered.map((a) => {
          const pending = a.status === "pending";
          return (
            <div
              key={a.id}
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
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 10,
                      marginBottom: 4,
                    }}
                  >
                    <span
                      style={{
                        fontFamily: MONO_FONT,
                        fontSize: 12,
                        color: token.colorTextTertiary,
                      }}
                    >
                      {a.id}
                    </span>
                    <Tag color={STATUS_COLOR[a.status]}>{STATUS_LABEL[a.status]}</Tag>
                  </div>
                  <div
                    style={{
                      fontSize: 15,
                      fontWeight: 600,
                      color: token.colorTextHeading,
                    }}
                  >
                    {a.purpose}
                  </div>
                </div>
                <div style={{ textAlign: "right", flex: "none" }}>
                  <div style={label12}>요청일</div>
                  <div style={{ fontSize: 13 }}>{a.date}</div>
                </div>
              </div>

              <div style={{ display: "flex", gap: 32, marginTop: 14, flexWrap: "wrap" }}>
                <div>
                  <div style={{ ...label12, marginBottom: 4 }}>요청자</div>
                  <div style={{ fontSize: 13 }}>{a.requester}</div>
                </div>
                <div>
                  <div style={{ ...label12, marginBottom: 4 }}>승인자 (상위 조직장)</div>
                  <div style={{ fontSize: 13 }}>{a.approver}</div>
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
                    {a.tables.join(", ")}
                  </div>
                </div>
              </div>

              <div style={{ display: "flex", gap: 6, marginTop: 14, flexWrap: "wrap" }}>
                {a.reqs.map((r, i) => (
                  <Tag key={i} color="geekblue">
                    {r}
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
                  <Button size="small" onClick={() => message.info(STUB_MSG)}>
                    요청 취소
                  </Button>
                  <Button size="small" danger onClick={() => message.info(STUB_MSG)}>
                    반려
                  </Button>
                  <Button
                    size="small"
                    type="primary"
                    icon={<CheckOutlined />}
                    onClick={() => message.info(STUB_MSG)}
                  >
                    승인
                  </Button>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </>
  );

  // ========================================================================
  // 새 요청 작성 — 쿼리 작성 요청서
  // ========================================================================
  const newView = (
    <div
      style={{
        background: "#fff",
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: 8,
        padding: "28px 32px",
      }}
    >
      <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 6 }}>
        쿼리 작성 요청서
      </div>
      <div
        style={{ fontSize: 13, color: token.colorTextSecondary, marginBottom: 24 }}
      >
        요건을 제출하면 지정된 상위 조직장의 승인 후 쿼리 작성이 가능합니다.
      </div>

      {/* 1. 쿼리 목적 */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          쿼리 목적 <span style={{ color: token.colorError }}>*</span>
        </label>
        <Input.TextArea
          rows={2}
          placeholder="예: Q3 마케팅 캠페인 대상자 추출"
          value={purpose}
          onChange={(e) => setPurpose(e.target.value)}
        />
      </div>

      {/* 2. 조회 대상 테이블 · 적용 규칙 */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          조회 대상 테이블 · 적용 규칙 <span style={{ color: token.colorError }}>*</span>
        </label>
        <div style={{ ...label12, marginBottom: 10 }}>
          테이블을 선택하면 연결된 규칙이 펼쳐집니다. 함께 적용할 규칙을 선택하세요.
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          {reqTableOptions.map((o) => {
            const selected = reqTables.includes(o.id);
            const qkey = `${o.db}.${o.table}`;
            const rules = rulesMeta.filter(
              (r) => r.scope === "global" || ruleTablesOf(r.key).includes(qkey),
            );
            const selCount = rules.filter((r) => reqRules.includes(r.key)).length;
            const ruleCountLabel = selected
              ? `${selCount}/${rules.length} 규칙 선택`
              : `${rules.length} 규칙 연결`;
            return (
              <div
                key={o.id}
                style={{
                  borderRadius: 8,
                  overflow: "hidden",
                  border: `1px solid ${
                    selected ? token.colorPrimary : token.colorBorderSecondary
                  }`,
                }}
              >
                <div
                  onClick={() => toggleReqTable(o.id)}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 10,
                    padding: "10px 14px",
                    cursor: "pointer",
                    background: selected ? token.colorPrimaryBg : "#fff",
                  }}
                >
                  <span style={{ width: 92, flex: "none" }}>
                    <Tag color={VENDOR_COLOR[o.vendor]}>{o.vendor}</Tag>
                  </span>
                  <span
                    style={{
                      display: "flex",
                      flexDirection: "column",
                      minWidth: 0,
                      flex: 1,
                    }}
                  >
                    <span style={{ fontFamily: MONO_FONT, fontSize: 13, color: token.colorText }}>
                      {o.db}.{o.table}
                    </span>
                    <span style={{ fontSize: 11, color: token.colorTextTertiary }}>
                      {ruleCountLabel}
                    </span>
                  </span>
                  {selected && (
                    <span style={{ display: "inline-flex", color: token.colorPrimary }}>
                      <CheckOutlined />
                    </span>
                  )}
                </div>
                {selected && (
                  <div
                    style={{
                      padding: "10px 12px 12px",
                      borderTop: `1px solid ${token.colorBorderSecondary}`,
                      background: GRAY2,
                      display: "flex",
                      flexDirection: "column",
                      gap: 8,
                    }}
                  >
                    {rules.map((r) => {
                      const checked = reqRules.includes(r.key);
                      return (
                        <div
                          key={r.key}
                          onClick={() => toggleReqRule(r.key)}
                          style={{
                            display: "flex",
                            gap: 10,
                            alignItems: "center",
                            padding: "9px 12px",
                            borderRadius: 6,
                            cursor: "pointer",
                            border: `1px solid ${
                              checked ? token.colorPrimaryBorder : token.colorBorderSecondary
                            }`,
                            background: checked ? token.colorPrimaryBg : "#fff",
                          }}
                        >
                          <Checkbox
                            checked={checked}
                            onClick={(e) => e.stopPropagation()}
                            onChange={() => toggleReqRule(r.key)}
                          />
                          <span style={{ flex: 1, minWidth: 0, fontSize: 13, fontWeight: 500 }}>
                            {r.name}
                          </span>
                          {r.scope === "global" && <Tag color="gold">전역</Tag>}
                          <Tag color={r.severity === "error" ? "red" : "gold"}>
                            {r.severity === "error" ? "오류" : "경고"}
                          </Tag>
                        </div>
                      );
                    })}
                    {rules.length === 0 && (
                      <div style={{ fontSize: 12, color: token.colorTextTertiary, padding: 2 }}>
                        이 테이블에 연결된 규칙이 없습니다.
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* 3. 비즈니스 요건 */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 12 }}>
          비즈니스 요건 (해당 항목 체크)
        </label>
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          {businessReqs.map((b) => {
            const checked = !!reqChecks[b.key];
            return (
              <div
                key={b.key}
                onClick={() => toggleCheck(b.key)}
                style={{
                  display: "flex",
                  gap: 12,
                  alignItems: "flex-start",
                  padding: "12px 14px",
                  borderRadius: 8,
                  cursor: "pointer",
                  border: `1px solid ${
                    checked ? token.colorPrimaryBorder : token.colorBorderSecondary
                  }`,
                  background: checked ? token.colorPrimaryBg : "#fff",
                }}
              >
                <Checkbox
                  checked={checked}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggleCheck(b.key)}
                />
                <span style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500 }}>{b.label}</div>
                  <div style={{ fontSize: 12, color: token.colorTextTertiary, marginTop: 2 }}>
                    {b.desc}
                  </div>
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {/* 4. 승인 라인 (순차 승인) */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 8 }}>
          승인 라인 (순차 승인)
        </label>
        <div style={{ ...label12, marginBottom: 12 }}>
          위에서부터 순서대로 승인이 진행됩니다
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 0 }}>
          {reqApprovers.map((ap, i) => (
            <div key={ap.id}>
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
                    background: ap.color,
                    color: "#fff",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 14,
                    fontWeight: 600,
                  }}
                >
                  {ap.initial}
                </span>
                <span style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 500 }}>{ap.name}</div>
                  <div style={{ fontSize: 12, color: token.colorTextTertiary }}>{ap.role}</div>
                </span>
                <span style={{ fontSize: 11, color: token.colorTextTertiary }}>
                  {i + 1}차 승인
                </span>
                {reqApprovers.length > 1 && (
                  <span
                    onClick={() => removeApprover(ap.id)}
                    style={{
                      cursor: "pointer",
                      color: token.colorTextTertiary,
                      display: "inline-flex",
                    }}
                  >
                    <DeleteOutlined />
                  </span>
                )}
              </div>
              {i < reqApprovers.length - 1 && (
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
          ))}
        </div>
        <div style={{ marginTop: 12 }}>
          <Button type="dashed" block icon={<PlusOutlined />} onClick={addApprover}>
            승인자 추가
          </Button>
        </div>
      </div>

      {/* 5. actions */}
      <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
        <Button onClick={() => setTab("list")}>취소</Button>
        <Button
          type="primary"
          icon={<UploadOutlined />}
          onClick={() => message.info(STUB_MSG)}
        >
          승인 요청 제출
        </Button>
      </div>
    </div>
  );

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
    </div>
  );
}
