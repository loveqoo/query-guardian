import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  App,
  Button,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Alert,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  FileOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import {
  MONO_FONT,
  STATUS_COLOR,
  VENDOR_COLOR,
  sqlHighlight,
} from "../theme";
import ActorSelect, { personLabel, useActor, useDirectory } from "../components/ActorSelect";
import {
  apiErrorMessage,
  deleteQuery,
  getQuery,
  listQueries,
  reviewQuery,
  type Id,
  type QueryListItem,
  type ReviewStatus,
} from "../api/client";

/* ---------- design tokens (dc.html lines 369-416, 802-835) ---------- */
const LINK_COLOR = "#1677ff"; // var(--color-link)
const RULE_PASS_COLOR = "#389e0d"; // var(--green-7)
const RULE_FAIL_COLOR = "#ff4d4f"; // var(--color-error)
const TEXT_TERTIARY = "#8c8c8c";
const TEXT_SECONDARY = "#595959";

/**
 * 검토 상태(review_status) 라벨/색 (spec 005 §3.2).
 * theme STATUS_* 팔레트를 그대로 재사용해 승인 요청 화면과 색 체계를 맞춘다.
 */
const REVIEW_LABEL: Record<ReviewStatus, string> = {
  PENDING_REVIEW: "검토 대기",
  APPROVED: "검토 승인",
  REJECTED: "반려",
};
const REVIEW_COLOR: Record<ReviewStatus, string> = {
  PENDING_REVIEW: STATUS_COLOR.pending,
  APPROVED: STATUS_COLOR.approved,
  REJECTED: STATUS_COLOR.rejected,
};
type RuleState = "passed" | "failed" | "none";

interface Row {
  key: string;
  id: Id;
  name: string;
  vendor: string;
  db: string;
  author: string;
  /** 실 review_status (spec 005 §3.2 — 이번 버전은 표시·감사용). */
  review: ReviewStatus;
  reviewer?: string | null;
  requestId?: Id;
  rule: RuleState;
  updated: string;
  /** 예시(디자인 시드) 행이면 sql 을 이미 보유. 실제 행은 상세 조회 시 로드. */
  sql?: string;
  isSample: boolean;
}

/** dialect 문자열 → 표시용 벤더명 (VENDOR_COLOR 키). */
function vendorFromDialect(dialect: string): string {
  const u = (dialect || "").toUpperCase();
  if (u.includes("MYSQL")) return "MySQL";
  if (u.includes("POSTGRE")) return "PostgreSQL";
  if (u.includes("TRINO")) return "Trino";
  return dialect || "—";
}

/** ISO 타임스탬프 → YYYY-MM-DD (디자인 표기). */
function fmtDate(s: string): string {
  return s && s.length >= 10 ? s.slice(0, 10) : s;
}

/** 실제 목록 항목 → 표 행. 작성자·데이터베이스는 실 API에 없어 스텁. */
function realRow(q: QueryListItem): Row {
  return {
    key: `real-${q.id}`,
    id: q.id,
    name: q.name,
    vendor: vendorFromDialect(q.dialect),
    db: "—", // 실 API 미보유
    author: "—", // 실 API 미보유 (작성자 필드 없음)
    review: q.reviewStatus, // 실 review_status
    reviewer: q.reviewer,
    requestId: q.requestId,
    rule: "none", // 목록 API는 lintReport 미포함 → 상세에서 판정
    updated: fmtDate(q.updatedAt),
    isSample: false,
  };
}

/** 디자인의 7행 샘플 (dc.html lines 1333-1340) — 실제 데이터가 없을 때만 예시로 노출. */
type SampleSeed = Omit<Row, "key" | "id" | "isSample">;
const SAMPLE_ROWS: Row[] = ([
  { name: "marketing_consent_users", db: "prod-main", vendor: "MySQL", author: "김도현", review: "APPROVED", rule: "passed", updated: "2026-07-22", sql: "SELECT u.id, u.email, u.name, m.consent_at\nFROM users u\nJOIN marketing_consents m ON m.user_id = u.id\nWHERE m.is_agreed = TRUE\nLIMIT 100;" },
  { name: "high_value_customers_q3", db: "analytics-dw", vendor: "PostgreSQL", author: "이서연", review: "PENDING_REVIEW", rule: "passed", updated: "2026-07-23", sql: "SELECT user_id, SUM(amount) AS total\nFROM fact_orders\nWHERE ordered_at >= '2026-07-01'\nGROUP BY user_id\nORDER BY total DESC\nLIMIT 100;" },
  { name: "churn_risk_segment", db: "data-lake", vendor: "Trino", author: "박민준", review: "PENDING_REVIEW", rule: "failed", updated: "2026-07-24", sql: "SELECT user_id, last_active_at\nFROM user_profiles\nWHERE last_active_at < NOW() - INTERVAL '30' DAY;" },
  { name: "daily_active_users", db: "analytics-dw", vendor: "PostgreSQL", author: "김도현", review: "APPROVED", rule: "passed", updated: "2026-07-20", sql: "SELECT dt, COUNT(DISTINCT user_id) AS dau\nFROM fact_events\nGROUP BY dt\nORDER BY dt DESC;" },
  { name: "refund_audit_2026", db: "prod-main", vendor: "MySQL", author: "정하윤", review: "REJECTED", rule: "failed", updated: "2026-07-19", sql: "SELECT o.id, o.amount, o.status\nFROM orders o\nWHERE o.status = 'refunded'\nLIMIT 200;" },
  { name: "signup_funnel_weekly", db: "analytics-dw", vendor: "PostgreSQL", author: "이서연", review: "APPROVED", rule: "passed", updated: "2026-07-16", sql: "SELECT week, step, COUNT(*) AS cnt\nFROM fact_events\nWHERE event_type = 'signup'\nGROUP BY week, step;" },
  { name: "ad_click_attribution", db: "data-lake", vendor: "Trino", author: "박민준", review: "PENDING_REVIEW", rule: "passed", updated: "2026-07-15", sql: "SELECT campaign_id, COUNT(*) AS clicks\nFROM ad_impressions\nWHERE dt >= DATE '2026-07-01'\nGROUP BY campaign_id;" },
] as SampleSeed[]).map((q, i) => ({ ...q, key: `sample-${i}`, id: `sample-${i}`, isSample: true }));

/** 규칙 검사 아이콘+라벨 (dc.html lines 396, 1346-1348). */
function RuleCell({ rule }: { rule: RuleState }) {
  if (rule === "none") return <span style={{ color: TEXT_TERTIARY }}>—</span>;
  const pass = rule === "passed";
  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        fontSize: 13,
        color: pass ? RULE_PASS_COLOR : RULE_FAIL_COLOR,
      }}
    >
      {pass ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
      {pass ? "통과" : "실패"}
    </span>
  );
}

interface Detail {
  row: Row;
  sql: string;
  rule: RuleState;
  review: ReviewStatus;
  reviewer?: string | null;
  reviewNote?: string | null;
  requestId?: Id;
}

export default function QueriesPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [actor] = useActor();
  const { users, approvers } = useDirectory();

  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [usingSample, setUsingSample] = useState(false);

  // filters (dc.html lines 374-378, 1354-1359)
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [vendor, setVendor] = useState("all");
  const [author, setAuthor] = useState("all");
  const [rule, setRule] = useState("all");
  const [page, setPage] = useState(1);

  // detail modal (dc.html lines 802-835)
  const [detail, setDetail] = useState<Detail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [reviewing, setReviewing] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const items = await listQueries();
      if (items.length === 0) {
        setRows(SAMPLE_ROWS);
        setUsingSample(true);
      } else {
        setRows(items.map(realRow));
        setUsingSample(false);
      }
    } catch {
      message.error("쿼리 목록을 불러오지 못했습니다");
      setRows([]);
      setUsingSample(false);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // reset to page 1 when a filter changes
  useEffect(() => {
    setPage(1);
  }, [search, status, vendor, author, rule]);

  const authorOptions = useMemo(() => {
    const uniq = Array.from(new Set(rows.map((r) => r.author)));
    return [{ label: "작성자 전체", value: "all" }, ...uniq.map((a) => ({ label: a, value: a }))];
  }, [rows]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter(
      (r) =>
        (!q || `${r.name} ${r.db} ${r.author}`.toLowerCase().includes(q)) &&
        (status === "all" || r.review === status) &&
        (vendor === "all" || r.vendor === vendor) &&
        (author === "all" || r.author === author) &&
        (rule === "all" || r.rule === rule),
    );
  }, [rows, search, status, vendor, author, rule]);

  function openDetail(row: Row) {
    if (row.isSample) {
      setDetail({ row, sql: row.sql ?? "", rule: row.rule, review: row.review });
      return;
    }
    setDetailLoading(true);
    setDetail({ row, sql: "", rule: row.rule, review: row.review, requestId: row.requestId });
    getQuery(row.id)
      .then((q) => {
        const r: RuleState = q.lintReport ? (q.lintReport.blocked ? "failed" : "passed") : "none";
        setDetail({
          row,
          sql: q.sql,
          rule: r,
          review: q.reviewStatus,
          reviewer: q.reviewer,
          reviewNote: q.reviewNote,
          requestId: q.requestId,
        });
      })
      .catch(() => {
        message.error("쿼리 상세를 불러오지 못했습니다");
        setDetail(null);
      })
      .finally(() => setDetailLoading(false));
  }

  /** 검토 결정 — 409(자가 검토·현재 BLOCK)는 서버 메시지를 그대로 보여준다 (spec 005 §3.2). */
  async function handleReview(decision: "APPROVED" | "REJECTED") {
    if (!detail || detail.row.isSample) return;
    setReviewing(true);
    try {
      const q = await reviewQuery(detail.row.id, { decision });
      message.success(decision === "APPROVED" ? "검토 승인했습니다" : "검토 반려했습니다");
      setDetail((prev) =>
        prev
          ? { ...prev, review: q.reviewStatus, reviewer: q.reviewer, reviewNote: q.reviewNote }
          : prev,
      );
      void load();
    } catch (err) {
      message.error(apiErrorMessage(err) ?? "검토 처리에 실패했습니다");
    } finally {
      setReviewing(false);
    }
  }

  function openInEditor(row: Row) {
    navigate(row.isSample ? "/editor" : `/editor?id=${row.id}`);
  }

  async function handleDelete(row: Row) {
    try {
      await deleteQuery(row.id);
      message.success("쿼리를 삭제했습니다");
      void load();
    } catch {
      message.error("쿼리 삭제에 실패했습니다");
    }
  }

  const columns: ColumnsType<Row> = [
    {
      title: "쿼리 이름",
      dataIndex: "name",
      width: "22%",
      render: (name: string, row) => (
        <span style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0 }}>
          <span style={{ color: TEXT_TERTIARY, display: "inline-flex" }}>
            <FileOutlined style={{ fontSize: 15 }} />
          </span>
          <a
            onClick={() => openDetail(row)}
            style={{
              fontFamily: MONO_FONT,
              fontSize: 13,
              fontWeight: 500,
              color: LINK_COLOR,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {name}
          </a>
          {row.isSample && (
            <Tag color="default" style={{ marginInlineStart: 0 }}>
              예시
            </Tag>
          )}
        </span>
      ),
    },
    {
      title: "벤더",
      dataIndex: "vendor",
      width: "9%",
      render: (v: string) => <Tag color={VENDOR_COLOR[v] ?? "default"}>{v}</Tag>,
    },
    {
      title: "데이터베이스",
      dataIndex: "db",
      width: "13%",
      render: (db: string) => (
        <span style={{ fontSize: 13, color: TEXT_SECONDARY, fontFamily: MONO_FONT }}>{db}</span>
      ),
    },
    {
      title: "작성자",
      dataIndex: "author",
      width: "10%",
      render: (a: string) => <span style={{ fontSize: 13, color: TEXT_SECONDARY }}>{a}</span>,
    },
    {
      title: "검토 상태",
      dataIndex: "review",
      width: "10%",
      render: (s: ReviewStatus) => <Tag color={REVIEW_COLOR[s] ?? "default"}>{REVIEW_LABEL[s] ?? s}</Tag>,
    },
    {
      title: "규칙 검사",
      dataIndex: "rule",
      width: "10%",
      render: (r: RuleState) => <RuleCell rule={r} />,
    },
    {
      title: "수정일",
      dataIndex: "updated",
      width: "11%",
      render: (u: string) => <span style={{ fontSize: 13, color: TEXT_TERTIARY }}>{u}</span>,
    },
    {
      title: "",
      key: "actions",
      width: 130,
      align: "right",
      render: (_: unknown, row) => (
        <Space size={2}>
          <Button type="text" size="small" icon={<EyeOutlined />} onClick={() => openDetail(row)} title="보기" />
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => openInEditor(row)} title="에디터에서 열기" />
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            title="복제"
            onClick={() => message.info("복제 기능은 다음 단계에서 구현됩니다")}
          />
          {!row.isSample && (
            <Popconfirm
              title="쿼리 삭제"
              description="이 쿼리를 삭제하시겠습니까?"
              okText="삭제"
              cancelText="취소"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(row)}
            >
              <Button type="text" size="small" danger icon={<DeleteOutlined />} title="삭제" />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const detailRow = detail?.row;

  return (
    <div style={{ animation: "qgFade .2s" }}>
      {/* filter bar (dc.html lines 372-381) */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 12,
          marginBottom: 16,
          flexWrap: "wrap",
        }}
      >
        <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
          <Input
            prefix={<SearchOutlined />}
            placeholder="이름·DB·작성자 검색"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            allowClear
            style={{ width: 220 }}
          />
          <Select
            value={status}
            onChange={setStatus}
            style={{ width: 140 }}
            options={[
              { label: "검토 상태 전체", value: "all" },
              { label: "검토 대기", value: "PENDING_REVIEW" },
              { label: "검토 승인", value: "APPROVED" },
              { label: "반려", value: "REJECTED" },
            ]}
          />
          <Select
            value={vendor}
            onChange={setVendor}
            style={{ width: 150 }}
            options={[
              { label: "벤더 전체", value: "all" },
              { label: "MySQL", value: "MySQL" },
              { label: "PostgreSQL", value: "PostgreSQL" },
              { label: "Trino", value: "Trino" },
            ]}
          />
          <Select value={author} onChange={setAuthor} style={{ width: 140 }} options={authorOptions} />
          <Select
            value={rule}
            onChange={setRule}
            style={{ width: 150 }}
            options={[
              { label: "규칙 검사 전체", value: "all" },
              { label: "통과", value: "passed" },
              { label: "실패", value: "failed" },
            ]}
          />
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <ActorSelect />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate("/editor")}>
            새 쿼리 작성
          </Button>
        </div>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="검토 상태는 이번 버전에서 표시·감사용입니다 — 실행·재사용을 차단하지 않습니다 (spec 005 §2)."
      />

      {usingSample && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="저장된 쿼리가 없어 예시 데이터를 표시합니다. 새 쿼리를 작성하면 실제 목록으로 대체됩니다."
        />
      )}

      <Table<Row>
        columns={columns}
        dataSource={filtered}
        loading={loading}
        size="middle"
        pagination={{
          current: page,
          pageSize: 5,
          total: filtered.length,
          onChange: setPage,
          showSizeChanger: false,
          showTotal: (total) =>
            `총 ${total}건 · ${page}/${Math.max(1, Math.ceil(total / 5))} 페이지`,
        }}
      />

      {/* detail modal (dc.html lines 802-835) */}
      <Modal
        open={!!detail}
        onCancel={() => setDetail(null)}
        width={620}
        footer={[
          <Button key="close" onClick={() => setDetail(null)}>
            닫기
          </Button>,
          <Button
            key="reject"
            danger
            loading={reviewing}
            disabled={!detailRow || detailRow.isSample}
            onClick={() => void handleReview("REJECTED")}
          >
            검토 반려
          </Button>,
          <Button
            key="approve"
            icon={<CheckCircleOutlined />}
            loading={reviewing}
            disabled={!detailRow || detailRow.isSample}
            onClick={() => void handleReview("APPROVED")}
          >
            검토 승인
          </Button>,
          <Button
            key="open"
            type="primary"
            icon={<EditOutlined />}
            disabled={!detailRow}
            onClick={() => {
              if (detailRow) openInEditor(detailRow);
              setDetail(null);
            }}
          >
            에디터에서 열기
          </Button>,
        ]}
        title={
          detailRow ? (
            <span style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0 }}>
              <span style={{ color: TEXT_TERTIARY, display: "inline-flex" }}>
                <FileOutlined style={{ fontSize: 16 }} />
              </span>
              <span
                style={{
                  fontFamily: MONO_FONT,
                  fontSize: 15,
                  fontWeight: 600,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {detailRow.name}
              </span>
              <Tag color={REVIEW_COLOR[detail?.review ?? detailRow.review] ?? "default"}>
                {REVIEW_LABEL[detail?.review ?? detailRow.review] ?? detailRow.review}
              </Tag>
            </span>
          ) : null
        }
      >
        {detailRow && (
          <>
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "1fr 1fr",
                gap: "18px 24px",
                margin: "8px 0 22px",
              }}
            >
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>데이터베이스</div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Tag color={VENDOR_COLOR[detailRow.vendor] ?? "default"}>{detailRow.vendor}</Tag>
                  <span style={{ fontSize: 13, color: TEXT_SECONDARY }}>{detailRow.db}</span>
                </div>
              </div>
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>작성자</div>
                <div style={{ fontSize: 14 }}>{detailRow.author}</div>
              </div>
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>규칙 검사</div>
                <RuleCell rule={detail.rule} />
              </div>
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>수정일</div>
                <div style={{ fontSize: 14 }}>{detailRow.updated}</div>
              </div>
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>근거 승인 요청</div>
                <div style={{ fontFamily: MONO_FONT, fontSize: 13 }}>
                  {detail.requestId != null ? `REQ-${String(detail.requestId)}` : "—"}
                </div>
              </div>
              <div>
                <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 6 }}>검토자</div>
                <div style={{ fontSize: 14 }}>
                  {detail.reviewer
                    ? personLabel([...users, ...approvers], detail.reviewer)
                    : "—"}
                  {detail.reviewNote ? (
                    <span style={{ fontSize: 12, color: TEXT_SECONDARY }}> · {detail.reviewNote}</span>
                  ) : null}
                </div>
              </div>
            </div>

            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={`검토는 현재 사용자(${personLabel([...users, ...approvers], actor)}) 명의로 기록됩니다 — 데모: 신원 위조 가능, 인증 후속. 본인이 요청한 쿼리는 자가 검토 불가(409)이며, 현재 규칙 기준 BLOCK이면 승인할 수 없습니다.`}
            />

            <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginBottom: 8 }}>쿼리 (SQL)</div>
            <div
              style={{
                background: "#fafafa",
                border: "1px solid #f0f0f0",
                borderRadius: 8,
                overflow: "auto",
              }}
            >
              <pre
                style={{
                  margin: 0,
                  padding: "14px 16px",
                  fontFamily: MONO_FONT,
                  fontSize: 13,
                  lineHeight: 1.7,
                  whiteSpace: "pre-wrap",
                  overflowWrap: "break-word",
                }}
                dangerouslySetInnerHTML={{
                  __html: detailLoading ? "불러오는 중…" : sqlHighlight(detail.sql),
                }}
              />
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}
