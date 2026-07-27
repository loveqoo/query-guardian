import { useCallback, useEffect, useState } from "react";
import { App, Button, Select, Space, Table, Tag, Tooltip } from "antd";
import type { ColumnsType } from "antd/es/table";
import { ReloadOutlined } from "@ant-design/icons";
import { MONO_FONT } from "../theme";
import { useAuth } from "../auth/AuthContext";
import { userLabel, useUsers } from "../auth/useUsers";
import StewardOnly from "../components/StewardOnly";
import { limitStatus } from "../api/execution";
import { listExecutionAudit, type ExecutionEvent, type ExecutionOutcome } from "../api/client";

/**
 * **실행 감사** (spec 013 §3-4) — 누가 언제 무엇을 실행했고 무엇이 막혔는가.
 *
 * ## 왜 저장 쿼리와 분리된 화면인가
 *
 * 쿼리별 이력(`/api/queries/{id}/executions`)만 쓰면 두 가지가 안 보인다: 쿼리를 지우면 그 실행
 * 기록에 도달할 수 없고(행위자가 감사를 은닉할 수 있다), 미리보기 기록은 `query_id`가 null이라
 * 어떤 쿼리에도 매달려 있지 않다. **감사는 대상 행의 생사와 무관해야 한다.**
 *
 * ## 디자인에 없는 화면이다
 *
 * 그래서 새 관용구를 발명하지 않고 저장 쿼리 화면의 표·태그·여백을 그대로 답습한다.
 * 발명하면 이 화면에서만 디자인 충실도가 깨진다(spec 013 §8-2).
 *
 * ## 화면이 다시 계산하는 값이 없다
 *
 * 결말·행수·소요·상한·코드 전부 서버가 준 것을 그대로 보인다. 특히 상한은 세 값을 뭉치지 않는다
 * (F2) — 여기서 요약하면 "왜 잘렸는지"를 감사에서 잃는다.
 */

const OUTCOME_COLOR: Record<ExecutionOutcome, string> = {
  SUCCESS: "green",
  BLOCKED: "gold",
  ERROR: "red",
  PREVIEW: "default",
};

/** 서버 어휘를 그대로 쓰되 뜻을 한 번 풀어 준다 — **라벨을 바꾸지는 않는다**(F3). */
const OUTCOME_HINT: Record<ExecutionOutcome, string> = {
  SUCCESS: "실행되어 결과가 반환됨",
  BLOCKED: "게이트가 막음 (데이터 반출 없음)",
  ERROR: "실행 중 실패 (타임아웃·SQL 오류 등)",
  PREVIEW: "실행 없이 미리보기만 — 강제식이 노출되므로 기록한다",
};

const TEXT_TERTIARY = "#8c8c8c";

function fmtAt(s: string): string {
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? s : d.toLocaleString("ko-KR", { hour12: false });
}

export default function AuditPage() {
  const { message } = App.useApp();
  const { isSteward } = useAuth();
  const { users } = useUsers();

  const [events, setEvents] = useState<ExecutionEvent[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [outcome, setOutcome] = useState<ExecutionOutcome | undefined>(undefined);
  const [actor, setActor] = useState<string | undefined>(undefined);
  /** 더 받을 것이 있는가 — 마지막 응답이 비지 않았다면 이어서 받아 볼 수 있다. */
  const [exhausted, setExhausted] = useState(false);

  /**
   * 필터가 바뀌면 **커서를 버리고 처음부터** 받는다. 커서를 유지하면 새 필터의 결과가
   * 옛 필터의 위치에서 시작해 앞부분이 통째로 빠진다.
   */
  const load = useCallback(
    async (before?: ExecutionEvent["id"]) => {
      setLoading(true);
      try {
        const page = await listExecutionAudit({ actor, outcome, before });
        setTotal(page.total);
        setExhausted(page.events.length === 0);
        setEvents((prev) => (before == null ? page.events : [...prev, ...page.events]));
      } catch {
        message.error("실행 감사를 불러오지 못했습니다");
      } finally {
        setLoading(false);
      }
    },
    [actor, outcome, message],
  );

  useEffect(() => {
    if (!isSteward) return;
    void load();
  }, [isSteward, load]);

  if (!isSteward) return <StewardOnly what="실행 감사" />;

  const columns: ColumnsType<ExecutionEvent> = [
    {
      title: "시각",
      dataIndex: "at",
      width: 170,
      render: (at: string) => <span style={{ fontSize: 13 }}>{fmtAt(at)}</span>,
    },
    {
      title: "행위자",
      dataIndex: "actor",
      width: 130,
      render: (a: string) => <span style={{ fontSize: 13 }}>{userLabel(users, a)}</span>,
    },
    {
      title: "결말",
      dataIndex: "outcome",
      width: 110,
      render: (o: ExecutionOutcome) => (
        <Tooltip title={OUTCOME_HINT[o]}>
          <Tag color={OUTCOME_COLOR[o]}>{o}</Tag>
        </Tooltip>
      ),
    },
    {
      title: "행수",
      dataIndex: "rowCount",
      width: 80,
      align: "right",
      render: (n: number | null | undefined) => <span style={{ fontSize: 13 }}>{n ?? "—"}</span>,
    },
    {
      title: "소요",
      dataIndex: "elapsedMs",
      width: 90,
      align: "right",
      render: (ms: number | null | undefined) => (
        <span style={{ fontSize: 13 }}>{ms == null ? "—" : `${(ms / 1000).toFixed(2)}s`}</span>
      ),
    },
    {
      title: "상한",
      key: "limit",
      width: 190,
      render: (_: unknown, e) => {
        const s = limitStatus(e);
        if (s.appliedLimit == null && s.configuredCap == null) return <span style={{ fontSize: 13 }}>—</span>;
        return (
          <span style={{ fontSize: 12 }}>
            {/* 적용 상한과 설정 상한을 **둘 다** 보인다 — 하나만 보이면 사용자가 좁힌 것과 거버넌스가
                자른 것이 구별되지 않는다(D5). */}
            적용 {s.appliedLimit ?? "—"} / 설정 {s.configuredCap ?? "—"}
            {s.truncatedByGovernance && <Tag color="gold" style={{ marginLeft: 6 }}>잘림</Tag>}
            <div style={{ color: TEXT_TERTIARY }}>더 있는지: {s.moreRows}</div>
          </span>
        );
      },
    },
    {
      title: "코드",
      dataIndex: "errorCode",
      render: (code: string | null | undefined, e) => (
        <span style={{ fontSize: 12, fontFamily: MONO_FONT }}>
          {code ?? "—"}
          {/* 원문은 서버가 STEWARD/ADMIN에게만 채운다 — 화면은 받은 대로 보인다. */}
          {e.errorDetail && (
            <div style={{ color: TEXT_TERTIARY, whiteSpace: "pre-wrap" }}>{e.errorDetail}</div>
          )}
        </span>
      ),
    },
    {
      title: "실행된 SQL",
      dataIndex: "rewrittenSql",
      width: 260,
      render: (sql: string | null | undefined) =>
        sql ? (
          <Tooltip title={<span style={{ whiteSpace: "pre-wrap" }}>{sql}</span>} styles={{ root: { maxWidth: 520 } }}>
            <span
              style={{
                fontFamily: MONO_FONT,
                fontSize: 12,
                display: "inline-block",
                maxWidth: 240,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                verticalAlign: "bottom",
              }}
            >
              {sql}
            </span>
          </Tooltip>
        ) : (
          <span style={{ fontSize: 13, color: TEXT_TERTIARY }}>—</span>
        ),
    },
  ];

  const oldest = events.length > 0 ? events[events.length - 1].id : undefined;

  return (
    <div style={{ animation: "qgFade .2s" }}>
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
        <Space size={10} wrap>
          <Select
            allowClear
            placeholder="결말"
            style={{ width: 150 }}
            value={outcome}
            onChange={(v) => setOutcome(v)}
            options={(Object.keys(OUTCOME_COLOR) as ExecutionOutcome[]).map((o) => ({ value: o, label: o }))}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="행위자"
            style={{ width: 180 }}
            value={actor}
            onChange={(v) => setActor(v)}
            options={users.map((u) => ({ value: u.id, label: userLabel(users, u.id) }))}
          />
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            새로 고침
          </Button>
        </Space>
        <div style={{ fontSize: 13, color: TEXT_TERTIARY }}>
          {/* 전체 건수는 **서버 헤더**에서 온다 — 받은 목록 길이로 세면 "200건이 전부"로 읽힌다. */}
          {events.length}건 표시{total != null && ` · 전체 ${total}건`}
        </div>
      </div>

      <Table<ExecutionEvent>
        rowKey={(e) => String(e.id)}
        size="middle"
        loading={loading && events.length === 0}
        dataSource={events}
        columns={columns}
        pagination={false}
        scroll={{ x: "max-content" }}
        locale={{ emptyText: "기록이 없습니다" }}
      />

      {events.length > 0 && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: 16 }}>
          <Button loading={loading} disabled={exhausted} onClick={() => void load(oldest)}>
            {exhausted ? "더 이상 없습니다" : "더 보기"}
          </Button>
        </div>
      )}
    </div>
  );
}
