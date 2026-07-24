/**
 * Mock data module — faithful transcription of the design's x-dc state + helpers.
 * Source: docs/design/query-guardian-design/Query Guardian.dc.html (lines 836–1201).
 * Names and values are kept EXACTLY as in the source; later screens render this stub data.
 */

// ============================================================================
// Types
// ============================================================================

export type VendorColor = "blue" | "geekblue" | "purple";
export type ConstraintKind = "mask" | "filter" | "block" | "join" | "integrity";
export type ConstraintClass =
  | "pii"
  | "boolean"
  | "datetime"
  | "numeric"
  | "key"
  | "string";
export type RuleOp =
  | "requires"
  | "joins"
  | "must_be_within"
  | "must_be_masked"
  | "blocks";
export type ApprovalStatus = "pending" | "approved" | "rejected" | "cancelled";
export type Severity = "error" | "warning";

export interface Database {
  key: string;
  name: string;
  vendor: string;
  vendorColor: VendorColor;
  host: string;
  tables: number;
}

export interface TableRef {
  name: string;
  rows: string;
}

export interface Column {
  name: string;
  type: string;
  keys: string[];
  isPii: boolean;
  nullable?: boolean;
  def?: string | null;
  comment: string;
}

export interface IndexDef {
  type: string;
  name: string;
  columns: string[];
  note: string;
}

export type TableMeta = Record<string, string>;

export interface ConstraintDef {
  id: string;
  kind: ConstraintKind;
  cls: ConstraintClass;
  name: string;
  desc: string;
  expr: string;
}

export interface KindMetaEntry {
  label: string;
  color: string;
  action: string;
  skeleton: string;
}

export interface OpMetaEntry {
  label: string;
  color: string;
}

export interface RuleCondNode {
  type: "cond";
  id: string;
  op: RuleOp;
  db?: string;
  table?: string;
  column?: string;
  subject?: string;
  value: string;
}

export interface RuleTree {
  id: string;
  combinator: "all" | "any";
  children: RuleCondNode[];
}

export interface RuleMeta {
  key: string;
  name: string;
  scope: "single" | "multi" | "global";
  server: string | null;
  severity: Severity;
  hits: number;
}

export interface Approval {
  id: string;
  purpose: string;
  requester: string;
  approver: string;
  status: ApprovalStatus;
  reqs: string[];
  date: string;
  tables: string[];
}

export interface BusinessReq {
  key: string;
  label: string;
  desc: string;
}

export interface ReqTableOption {
  id: string;
  vendor: string;
  vendorColor: VendorColor;
  db: string;
  host: string;
  table: string;
}

export interface Approver {
  name: string;
  role: string;
  initial: string;
  color: string;
}

export interface User {
  id: string;
  name: string;
  role: string;
  initial: string;
  color: string;
}

export interface Server {
  key: string;
  vendor: string;
  vendorColor: VendorColor;
  host: string;
  cluster: string;
  nodes: string;
  databases: Record<string, string[]>;
}

/** Per-user permission state produced by buildDefaultPerms(). */
export interface UserPerms {
  dbs: Record<string, boolean>;
  tables: Record<string, boolean>;
}
export type PermsMap = Record<string, UserPerms>;

// ============================================================================
// Data (x-dc state + class fields)
// ============================================================================

export const databases: Database[] = [
  { key: "mysql-prod", name: "prod-main", vendor: "MySQL", vendorColor: "blue", host: "10.0.2.11:3306", tables: 42 },
  { key: "pg-analytics", name: "analytics-dw", vendor: "PostgreSQL", vendorColor: "geekblue", host: "10.0.3.4:5432", tables: 88 },
  { key: "trino-lake", name: "data-lake", vendor: "Trino", vendorColor: "purple", host: "trino.internal:8080", tables: 213 },
];

export const tablesByDb: Record<string, TableRef[]> = {
  "mysql-prod": [
    { name: "users", rows: "2.1M" }, { name: "orders", rows: "8.7M" }, { name: "marketing_consents", rows: "1.4M" },
    { name: "payments", rows: "8.3M" }, { name: "products", rows: "12K" }, { name: "sessions", rows: "44M" },
  ],
  "pg-analytics": [
    { name: "dim_user", rows: "2.1M" }, { name: "fact_orders", rows: "8.7M" }, { name: "fact_events", rows: "312M" },
    { name: "dim_product", rows: "12K" }, { name: "agg_daily_active", rows: "540" },
  ],
  "trino-lake": [
    { name: "events_raw", rows: "4.2B" }, { name: "clickstream", rows: "1.1B" }, { name: "ad_impressions", rows: "820M" },
    { name: "user_profiles", rows: "2.1M" }, { name: "revenue_daily", rows: "900" },
  ],
};

export const columnsByTable: Record<string, Column[]> = {
  users: [
    { name: "id", type: "BIGINT", keys: ["PK"], isPii: false, nullable: false, def: "AUTO_INCREMENT", comment: "사용자 고유 ID" },
    { name: "email", type: "VARCHAR(255)", keys: ["UK"], isPii: true, nullable: false, def: null, comment: "로그인 이메일 · 유니크" },
    { name: "name", type: "VARCHAR(100)", keys: [], isPii: true, nullable: false, def: null, comment: "실명" },
    { name: "phone", type: "VARCHAR(20)", keys: ["UK"], isPii: true, nullable: true, def: null, comment: "휴대폰 번호 · 유니크" },
    { name: "ssn", type: "VARCHAR(13)", keys: [], isPii: true, nullable: true, def: null, comment: "주민등록번호" },
    { name: "status", type: "VARCHAR(20)", keys: ["IDX"], isPii: false, nullable: false, def: "'active'", comment: "active / dormant" },
    { name: "created_at", type: "DATETIME", keys: ["IDX"], isPii: false, nullable: false, def: "CURRENT_TIMESTAMP", comment: "가입 일시" },
  ],
  marketing_consents: [
    { name: "id", type: "BIGINT", keys: ["PK"], isPii: false, nullable: false, def: "AUTO_INCREMENT", comment: "" },
    { name: "user_id", type: "BIGINT", keys: ["FK", "UK"], isPii: false, nullable: false, def: null, comment: "users.id 참조 · 유니크 제약" },
    { name: "is_agreed", type: "BOOLEAN", keys: [], isPii: false, nullable: false, def: "FALSE", comment: "수신 동의 여부" },
    { name: "channel", type: "VARCHAR(20)", keys: ["IDX"], isPii: false, nullable: false, def: "'email'", comment: "email / sms / push" },
    { name: "consent_at", type: "DATETIME", keys: [], isPii: false, nullable: true, def: null, comment: "동의 일시" },
  ],
  orders: [
    { name: "id", type: "BIGINT", keys: ["PK"], isPii: false, nullable: false, def: "AUTO_INCREMENT", comment: "" },
    { name: "user_id", type: "BIGINT", keys: ["FK"], isPii: false, nullable: false, def: null, comment: "users.id 참조" },
    { name: "amount", type: "DECIMAL(12,2)", keys: ["CHECK"], isPii: false, nullable: false, def: "0.00", comment: "CHECK (amount >= 0)" },
    { name: "status", type: "VARCHAR(20)", keys: ["IDX"], isPii: false, nullable: false, def: "'paid'", comment: "paid / refunded" },
    { name: "ordered_at", type: "DATETIME", keys: ["PARTITION"], isPii: false, nullable: false, def: "CURRENT_TIMESTAMP", comment: "RANGE 파티션 키 (월별)" },
  ],
  events_raw: [
    { name: "event_id", type: "VARCHAR", keys: ["PK"], isPii: false, nullable: false, def: null, comment: "UUID" },
    { name: "user_id", type: "BIGINT", keys: ["IDX"], isPii: false, nullable: true, def: null, comment: "" },
    { name: "event_type", type: "VARCHAR", keys: [], isPii: false, nullable: false, def: null, comment: "click / view / purchase" },
    { name: "payload", type: "JSON", keys: [], isPii: false, nullable: true, def: null, comment: "이벤트 속성" },
    { name: "dt", type: "DATE", keys: ["PARTITION"], isPii: false, nullable: false, def: null, comment: "Hive 파티션 키 (일자)" },
    { name: "hour", type: "INTEGER", keys: ["PARTITION"], isPii: false, nullable: false, def: "0", comment: "Hive 파티션 키 (시간)" },
  ],
};

export const genericCols: Column[] = [
  { name: "id", type: "BIGINT", keys: ["PK"], isPii: false, comment: "" },
  { name: "name", type: "VARCHAR(255)", keys: [], isPii: false, comment: "" },
  { name: "value", type: "DOUBLE", keys: [], isPii: false, comment: "" },
  { name: "dt", type: "DATE", keys: ["PARTITION"], isPii: false, comment: "파티션 키" },
  { name: "updated_at", type: "TIMESTAMP", keys: ["IDX"], isPii: false, comment: "" },
];

export const tableComments: Record<string, string> = {
  users: "서비스 가입 사용자 마스터",
  marketing_consents: "마케팅 수신 동의 이력",
  orders: "주문 내역",
  events_raw: "원본 이벤트 로그 (파티셔닝)",
};

export const tableMetaByName: Record<string, TableMeta> = {
  users: { "엔진": "InnoDB", "인코딩": "utf8mb4", "정렬": "utf8mb4_0900_ai_ci", "로우": "2.1M" },
  marketing_consents: { "엔진": "InnoDB", "인코딩": "utf8mb4", "정렬": "utf8mb4_0900_ai_ci", "로우": "1.4M" },
  orders: { "엔진": "InnoDB", "인코딩": "utf8mb4", "정렬": "utf8mb4_0900_ai_ci", "로우": "8.7M" },
  events_raw: { "포맷": "Parquet", "압축": "ZSTD", "파티션": "dt / hour", "로우": "4.2B" },
};

export const genericTableMeta: TableMeta = { "엔진": "InnoDB", "인코딩": "utf8mb4", "로우": "—" };

export const kindMeta: Record<ConstraintKind, KindMetaEntry> = {
  mask: { label: "마스킹", color: "purple", action: "SELECT 절의 대상 컬럼을 변형식으로 치환", skeleton: "SELECT …, {expr} AS {col} FROM …" },
  filter: { label: "필터", color: "blue", action: "WHERE 절에 술어를 추가해 미충족 행을 제외", skeleton: "SELECT … FROM … WHERE {expr}" },
  block: { label: "차단", color: "red", action: "쿼리가 이 컬럼을 참조하면 실행 자체를 거부", skeleton: "-- REJECT: 컬럼 {col} 참조 감지 → 쿼리 차단" },
  join: { label: "조인", color: "cyan", action: "필수 조인이 존재하는지 검사, 없으면 거부", skeleton: "SELECT … FROM … JOIN … ON {expr}" },
  integrity: { label: "무결성", color: "geekblue", action: "WHERE 절에 무결성 술어를 추가", skeleton: "SELECT … FROM … WHERE {expr}" },
};

export const classLabels: Record<ConstraintClass, string> = {
  pii: "개인정보",
  boolean: "BOOLEAN",
  datetime: "날짜/시간",
  numeric: "숫자",
  key: "키/조인",
  string: "문자열",
};

export const constraintDefs: ConstraintDef[] = [
  { id: "d_pii_domain", kind: "mask", cls: "pii", name: "도메인만 노출 (a***@x.com)", desc: "이메일 로컬파트 마스킹, 도메인만 노출", expr: "mask_domain({col})" },
  { id: "d_pii_phone", kind: "mask", cls: "pii", name: "전화번호 마스킹 (010-****-1234)", desc: "가운데 4자리 마스킹", expr: "mask_middle({col}, 4)" },
  { id: "d_pii_full", kind: "mask", cls: "pii", name: "전체 마스킹 (****)", desc: "값 전체를 마스킹 처리", expr: "'****'" },
  { id: "d_pii_block", kind: "block", cls: "pii", name: "조회 전면 차단", desc: "해당 컬럼 조회 자체를 차단", expr: "{col}" },
  { id: "d_bool_true", kind: "filter", cls: "boolean", name: "반드시 TRUE", desc: "값이 TRUE인 행만 허용", expr: "{col} = TRUE" },
  { id: "d_bool_false", kind: "filter", cls: "boolean", name: "반드시 FALSE", desc: "값이 FALSE인 행만 허용", expr: "{col} = FALSE" },
  { id: "d_dt_30", kind: "filter", cls: "datetime", name: "최근 30일 이내", desc: "조회 시점 기준 30일 이내", expr: "{col} >= NOW() - INTERVAL 30 DAY" },
  { id: "d_dt_90", kind: "filter", cls: "datetime", name: "최근 90일 이내", desc: "조회 시점 기준 90일 이내", expr: "{col} >= NOW() - INTERVAL 90 DAY" },
  { id: "d_dt_period", kind: "filter", cls: "datetime", name: "특정 기간 내", desc: "지정한 시작~종료 기간 내", expr: "{col} BETWEEN :start AND :end" },
  { id: "d_dt_notnull", kind: "integrity", cls: "datetime", name: "NOT NULL 필수", desc: "NULL 값 금지", expr: "{col} IS NOT NULL" },
  { id: "d_num_gte0", kind: "filter", cls: "numeric", name: "0 이상", desc: "음수 금지", expr: "{col} >= 0" },
  { id: "d_num_range", kind: "filter", cls: "numeric", name: "범위 지정 (min ~ max)", desc: "지정 범위 내 값만 허용", expr: "{col} BETWEEN :min AND :max" },
  { id: "d_num_max", kind: "filter", cls: "numeric", name: "상한 제한", desc: "지정 상한 이하", expr: "{col} <= :max" },
  { id: "d_num_notnull", kind: "integrity", cls: "numeric", name: "NOT NULL 필수", desc: "NULL 값 금지", expr: "{col} IS NOT NULL" },
  { id: "d_key_join", kind: "join", cls: "key", name: "참조 테이블 키로 조인", desc: "외래키로 참조 테이블과 조인", expr: "{col} = :ref_table.id" },
  { id: "d_key_notnull", kind: "integrity", cls: "key", name: "NOT NULL 필수", desc: "NULL 값 금지", expr: "{col} IS NOT NULL" },
  { id: "d_key_single", kind: "filter", cls: "key", name: "단일 값 제한", desc: "단일 값만 허용", expr: "{col} = :value" },
  { id: "d_str_eq", kind: "filter", cls: "string", name: "지정값과 일치", desc: "지정한 값과 정확히 일치", expr: "{col} = :value" },
  { id: "d_str_like", kind: "filter", cls: "string", name: "LIKE 패턴 일치", desc: "지정 패턴에 매칭", expr: "{col} LIKE :pattern" },
  { id: "d_str_in", kind: "filter", cls: "string", name: "IN 목록 포함", desc: "허용 목록에 포함", expr: "{col} IN (:list)" },
  { id: "d_str_notnull", kind: "integrity", cls: "string", name: "NOT NULL 필수", desc: "NULL 값 금지", expr: "{col} IS NOT NULL" },
];

export const colConstraints: Record<string, string[]> = {
  "prod-main/users/phone": ["d_pii_phone", "d_pii_full", "d_pii_block"],
  "prod-main/users/email": ["d_pii_domain", "d_pii_full", "d_pii_block"],
  "prod-main/users/name": ["d_pii_full", "d_pii_block"],
  "prod-main/users/ssn": ["d_pii_block", "d_pii_full"],
  "prod-main/marketing_consents/is_agreed": ["d_bool_true", "d_bool_false"],
  "prod-main/marketing_consents/user_id": ["d_key_join", "d_key_notnull"],
  "prod-main/marketing_consents/consent_at": ["d_dt_30", "d_dt_90", "d_dt_period", "d_dt_notnull"],
};

export const ruleTrees: Record<string, RuleTree> = {
  r1: { id: "g0", combinator: "all", children: [
    { type: "cond", id: "c1", op: "must_be_masked", db: "prod-main", table: "users", column: "phone", value: "d_pii_phone" },
    { type: "cond", id: "c2", op: "must_be_masked", db: "prod-main", table: "users", column: "email", value: "d_pii_domain" },
    { type: "cond", id: "c3", op: "blocks", db: "prod-main", table: "users", column: "ssn", value: "d_pii_block" },
  ] },
  r2: { id: "g0", combinator: "all", children: [
    { type: "cond", id: "c1", op: "requires", db: "prod-main", table: "marketing_consents", column: "is_agreed", value: "d_bool_true" },
    { type: "cond", id: "c2", op: "joins", db: "prod-main", table: "marketing_consents", column: "user_id", value: "d_key_join" },
    { type: "cond", id: "c3", op: "must_be_within", db: "prod-main", table: "marketing_consents", column: "consent_at", value: "d_dt_90" },
  ] },
  r3: { id: "g0", combinator: "all", children: [
    { type: "cond", id: "c1", op: "requires", subject: "SELECT statement", value: "LIMIT ≤ 1000 필수" },
    { type: "cond", id: "c2", op: "blocks", subject: "SELECT *", value: "전체 컬럼 조회 금지" },
  ] },
};

export const indexesByTable: Record<string, IndexDef[]> = {
  users: [
    { type: "PRIMARY", name: "pk_users", columns: ["id"], note: "" },
    { type: "UNIQUE", name: "uk_users_email", columns: ["email"], note: "" },
    { type: "UNIQUE", name: "uk_users_phone", columns: ["phone"], note: "" },
    { type: "INDEX", name: "idx_users_status_created", columns: ["status", "created_at"], note: "복합 인덱스" },
  ],
  marketing_consents: [
    { type: "PRIMARY", name: "pk_mc", columns: ["id"], note: "" },
    { type: "UNIQUE", name: "uk_mc_user_channel", columns: ["user_id", "channel"], note: "복합 유니크 · 사용자당 채널 1건" },
    { type: "FOREIGN", name: "fk_mc_user", columns: ["user_id"], note: "→ users.id" },
    { type: "INDEX", name: "idx_mc_channel", columns: ["channel"], note: "" },
  ],
  orders: [
    { type: "PRIMARY", name: "pk_orders", columns: ["id"], note: "" },
    { type: "FOREIGN", name: "fk_orders_user", columns: ["user_id"], note: "→ users.id" },
    { type: "UNIQUE", name: "uk_orders_user_ordered", columns: ["user_id", "ordered_at"], note: "복합 유니크" },
    { type: "INDEX", name: "idx_orders_status", columns: ["status"], note: "" },
    { type: "PARTITION", name: "p_range_ordered", columns: ["ordered_at"], note: "RANGE · 월별" },
  ],
  events_raw: [
    { type: "PARTITION", name: "hive_part", columns: ["dt", "hour"], note: "Hive 복합 파티션" },
    { type: "INDEX", name: "bloom_user_id", columns: ["user_id"], note: "Bloom filter (Trino)" },
    { type: "INDEX", name: "bloom_event_type", columns: ["event_type"], note: "Bloom filter (Trino)" },
  ],
};

export const genericIndexes: IndexDef[] = [
  { type: "PRIMARY", name: "pk", columns: ["id"], note: "" },
  { type: "PARTITION", name: "p_dt", columns: ["dt"], note: "일자 파티션" },
];

export const baseApprovals: Approval[] = [
  { id: "REQ-1043", purpose: "Q3 마케팅 캠페인 대상자 추출", requester: "김도현", approver: "최지훈 (마케팅본부장)", status: "pending", reqs: ["마케팅 동의", "개인정보 포함"], date: "2026-07-23", tables: ["users", "marketing_consents"] },
  { id: "REQ-1041", purpose: "VIP 고객 리텐션 분석", requester: "이서연", approver: "한도윤 (데이터플랫폼장)", status: "approved", reqs: ["개인정보 포함", "민감정보 마스킹"], date: "2026-07-21", tables: ["users", "orders", "payments"] },
  { id: "REQ-1038", purpose: "환불 이상거래 감사", requester: "정하윤", approver: "한도윤 (데이터플랫폼장)", status: "rejected", reqs: ["민감정보 마스킹"], date: "2026-07-18", tables: ["payments", "orders"] },
];

export const businessReqs: BusinessReq[] = [
  { key: "marketing", label: "마케팅 수신 동의자 한정", desc: "is_agreed = TRUE 인 사용자만 조회" },
  { key: "pii", label: "개인정보(PII) 포함", desc: "이메일·전화번호 등 식별정보 조회 필요" },
  { key: "mask", label: "민감정보 마스킹 적용", desc: "주민번호·카드번호는 마스킹 처리" },
  { key: "retention", label: "보관기간 준수", desc: "수집 후 최대 90일 데이터만 사용" },
  { key: "external", label: "외부 반출 목적", desc: "제3자 제공/반출 검토 필요" },
];

export const tableOptions: string[] = ["users", "marketing_consents", "orders", "payments", "products", "sessions"];

export const reqTableOptions: ReqTableOption[] = [
  { id: "t1", vendor: "MySQL", vendorColor: "blue", db: "prod-main", host: "10.0.2.11:3306", table: "users" },
  { id: "t2", vendor: "MySQL", vendorColor: "blue", db: "prod-main", host: "10.0.2.11:3306", table: "marketing_consents" },
  { id: "t3", vendor: "MySQL", vendorColor: "blue", db: "prod-main", host: "10.0.2.11:3306", table: "orders" },
  { id: "t4", vendor: "PostgreSQL", vendorColor: "geekblue", db: "analytics-dw", host: "10.0.3.4:5432", table: "fact_events" },
  { id: "t5", vendor: "Trino", vendorColor: "purple", db: "data-lake", host: "trino.internal:8080", table: "events_raw" },
];

export const approverPool: Approver[] = [
  { name: "최지훈", role: "마케팅본부장", initial: "최", color: "#722ed1" },
  { name: "한도윤", role: "데이터플랫폼장", initial: "한", color: "#1677ff" },
  { name: "서준호", role: "정보보호책임자(CISO)", initial: "서", color: "#08979c" },
  { name: "김영은", role: "최고데이터책임자(CDO)", initial: "김", color: "#d46b08" },
];

export const users: User[] = [
  { id: "u1", name: "김도현", role: "데이터 분석가", initial: "김", color: "#1677ff" },
  { id: "u2", name: "이서연", role: "데이터 분석가", initial: "이", color: "#722ed1" },
  { id: "u3", name: "박민준", role: "데이터 엔지니어", initial: "박", color: "#08979c" },
  { id: "u4", name: "정하윤", role: "데이터 거버넌스", initial: "정", color: "#d46b08" },
];

export const rulesMeta: RuleMeta[] = [
  { key: "r1", name: "PII 컬럼 마스킹 필수", scope: "single", server: "mysql-prod", severity: "error", hits: 12 },
  { key: "r2", name: "마케팅 동의 사용자 한정", scope: "multi", server: "mysql-prod", severity: "error", hits: 5 },
  { key: "r3", name: "대량 조회 LIMIT 강제", scope: "global", server: null, severity: "warning", hits: 31 },
];

export const servers: Server[] = [
  { key: "mysql-prod", vendor: "MySQL", vendorColor: "blue", host: "proxysql.prod:6033", cluster: "Master/Slave 프록시", nodes: "1 master · 2 replica", databases: { "prod-main": ["users", "orders", "marketing_consents", "payments", "products", "sessions"], "prod-archive": ["users_archive", "orders_archive"] } },
  { key: "pg-analytics", vendor: "PostgreSQL", vendorColor: "geekblue", host: "10.0.3.4:5432", cluster: "단일 인스턴스", nodes: "1 primary", databases: { "analytics-dw": ["dim_user", "fact_orders", "fact_events", "dim_product", "agg_daily_active"] } },
  { key: "trino-lake", vendor: "Trino", vendorColor: "purple", host: "trino.internal:8080", cluster: "Coordinator/Worker", nodes: "1 coordinator · 12 worker", databases: { "data-lake": ["events_raw", "clickstream", "ad_impressions", "user_profiles", "revenue_daily"] } },
];

export const opMeta: Record<RuleOp, OpMetaEntry> = {
  requires: { label: "요건 필요", color: "blue" },
  joins: { label: "조인 강제", color: "cyan" },
  must_be_within: { label: "기간 이내", color: "gold" },
  must_be_masked: { label: "마스킹 필수", color: "purple" },
  blocks: { label: "차단", color: "red" },
};

// ============================================================================
// Helper functions (ported from x-dc)
// ============================================================================

/** columnsByTable[table] with generic fallback. */
export function columnsFor(table: string): Column[] {
  return columnsByTable[table] || genericCols;
}

/** Classify a column into a ConstraintClass (dc.html lines 1031–1042). */
export function colClass(col?: Column | null): ConstraintClass {
  if (!col) return "string";
  if (col.isPii) return "pii";
  const t = (col.type || "").toUpperCase();
  if (t.includes("BOOL")) return "boolean";
  if (/DATE|TIME|TIMESTAMP/.test(t)) return "datetime";
  if (/INT|DECIMAL|DOUBLE|NUMERIC|FLOAT/.test(t)) {
    if ((col.keys || []).some((k) => k === "PK" || k === "FK") || /(^id$|_id$)/.test(col.name)) return "key";
    return "numeric";
  }
  return "string";
}

/** Look up a constraint definition by id. */
export function defById(id: string): ConstraintDef | undefined {
  return constraintDefs.find((d) => d.id === id);
}

/** All constraint definitions in a class. */
export function defsByClass(cls: ConstraintClass): ConstraintDef[] {
  return constraintDefs.filter((d) => d.cls === cls);
}

/** Render an enforcement preview string for a kind/expr/col (dc.html lines 1025–1029). */
export function enforcePreview(kind: string, expr?: string, col?: string): string {
  const m = kindMeta[kind as ConstraintKind];
  if (!m) return "";
  const c = col || "{col}";
  return (m.skeleton || "{expr}")
    .split("{expr}")
    .join((expr || c).split("{col}").join(c))
    .split("{col}")
    .join(c);
}

/** AI-generated SQL stub (dc.html line 1199). */
export function mockSql(text: string): string {
  return (
    "-- AI 생성: " + text.slice(0, 40) +
    "\nSELECT u.id, u.email, u.name, m.consent_at" +
    "\nFROM users AS u" +
    "\nJOIN marketing_consents AS m ON m.user_id = u.id" +
    "\nWHERE m.is_agreed = TRUE" +
    "\n  AND m.consent_at >= NOW() - INTERVAL 30 DAY" +
    "\nORDER BY m.consent_at DESC" +
    "\nLIMIT 100;"
  );
}

/** Label for a rule-condition node's value (dc.html lines 1054–1057). */
export function condValueLabel(node: RuleCondNode): string {
  if (node.db || node.table) {
    const d = defById(node.value);
    return d ? d.name : (node.value || "");
  }
  return node.value || "";
}

/** Build the default per-user permission map (dc.html lines 1125–1140). */
export function buildDefaultPerms(): PermsMap {
  const p: PermsMap = {};
  users.forEach((u) => {
    p[u.id] = { dbs: {}, tables: {} };
    databases.forEach((d) => {
      p[u.id].dbs[d.key] = true;
      (tablesByDb[d.key] || []).forEach((t) => {
        p[u.id].tables[d.key + "/" + t.name] = true;
      });
    });
  });
  p.u1.dbs["trino-lake"] = false;
  p.u1.tables["mysql-prod/payments"] = false;
  p.u1.tables["mysql-prod/sessions"] = false;
  p.u2.dbs["mysql-prod"] = false;
  p.u3.dbs["pg-analytics"] = false;
  return p;
}

export interface ConstraintOptions {
  options: { value: string; label: string }[];
  cls: ConstraintClass;
  type: string | null;
  mapped: number;
}

/** Constraint definitions mapped to a db/table/column → rule-builder options (dc.html lines 1046–1053). */
export function constraintOptionsFor(db: string, table: string, columnName: string): ConstraintOptions {
  const col = columnsFor(table).find((c) => c.name === columnName);
  const cls = colClass(col);
  const key = db + "/" + table + "/" + columnName;
  const ids = colConstraints[key] || [];
  const options = ids
    .map((id) => defById(id))
    .filter((d): d is ConstraintDef => Boolean(d))
    .map((d) => ({ value: d.id, label: d.name }));
  return { options, cls, type: col ? col.type : null, mapped: ids.length };
}
