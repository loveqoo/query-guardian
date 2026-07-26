import { z } from "zod";

// ---------- 공통 스키마 ----------

/** 백엔드 id 타입(숫자/문자열)에 모두 대응 */
export const idSchema = z.union([z.number(), z.string()]);
export type Id = z.infer<typeof idSchema>;

export const violationSchema = z.object({
  ruleId: z.string(),
  severity: z.enum(["BLOCK", "WARN"]),
  message: z.string(),
});
export type Violation = z.infer<typeof violationSchema>;

export const lintReportSchema = z.object({
  violations: z.array(violationSchema),
  blocked: z.boolean(),
});
export type LintReport = z.infer<typeof lintReportSchema>;

/** 쿼리 검토 상태 (spec 005 §3.2). 이번 스펙에서는 표시·감사용. */
export const reviewStatusSchema = z.enum(["PENDING_REVIEW", "APPROVED", "REJECTED"]);
export type ReviewStatus = z.infer<typeof reviewStatusSchema>;

export const queryListItemSchema = z.object({
  id: idSchema,
  name: z.string(),
  dialect: z.string(),
  purposeCode: z.string().nullish(),
  /** 근거 승인 요청 id (spec 005 §3.2 — NOT NULL). */
  requestId: idSchema,
  reviewStatus: reviewStatusSchema,
  reviewer: z.string().nullish(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type QueryListItem = z.infer<typeof queryListItemSchema>;

export const savedQuerySchema = queryListItemSchema.extend({
  sql: z.string(),
  reviewedAt: z.string().nullish(),
  reviewNote: z.string().nullish(),
  lintReport: lintReportSchema.nullish(),
});
export type SavedQuery = z.infer<typeof savedQuerySchema>;

// ---------- 승인 요청 (spec 005 §3.1 · §7) ----------

export const approvalStatusSchema = z.enum([
  "PENDING",
  "APPROVED",
  "REJECTED",
  "CANCELLED",
]);
export type ApprovalStatus = z.infer<typeof approvalStatusSchema>;

export const approverDecisionSchema = z.enum(["PENDING", "APPROVED", "REJECTED"]);
export type ApproverDecision = z.infer<typeof approverDecisionSchema>;

export const approverSchema = z.object({
  step: z.number(),
  approverId: z.string(),
  name: z.string(),
  role: z.string(),
  decision: approverDecisionSchema,
  decidedAt: z.string().nullish(),
});
export type ApprovalApprover = z.infer<typeof approverSchema>;

export const approvalSummarySchema = z.object({
  id: idSchema,
  purposeTitle: z.string(),
  purposeCode: z.string(),
  /** 요청자 = `app_user.id` (세션 principal, spec 007 §4). */
  requester: z.string(),
  status: approvalStatusSchema,
  currentStep: z.number(),
  tables: z.array(z.string()),
  businessReqs: z.array(z.string()),
  approvers: z.array(approverSchema),
  submittedAt: z.string(),
  decidedAt: z.string().nullish(),
});
export type ApprovalSummary = z.infer<typeof approvalSummarySchema>;

/** 승인 당시 동결된 규칙 스냅샷 (H2). forced = 요청자가 고르지 않았지만 항상 적용되는 규칙. */
export const ruleSnapshotSchema = z.object({
  ruleId: idSchema,
  ruleName: z.string(),
  severitySummary: z.string(),
  forced: z.boolean(),
  changedSinceApproval: z.boolean(),
});
export type RuleSnapshot = z.infer<typeof ruleSnapshotSchema>;

/** append-only 감사 이벤트 (H6). */
export const approvalEventSchema = z.object({
  step: z.number().nullish(),
  actor: z.string(),
  action: z.string(),
  note: z.string().nullish(),
  at: z.string(),
});
export type ApprovalEvent = z.infer<typeof approvalEventSchema>;

export const approvalDetailSchema = z.object({
  summary: approvalSummarySchema,
  rules: z.array(ruleSnapshotSchema),
  events: z.array(approvalEventSchema),
});
export type ApprovalDetail = z.infer<typeof approvalDetailSchema>;

/** 403 승인 차단 응답 — 422 룰 차단과 구분되는 별도 계약 (§7 H5). */
export const approvalBlockedSchema = z.object({
  code: z.enum([
    "NO_REQUEST",
    "NOT_APPROVED",
    "REQUESTER_MISMATCH",
    "TABLES_NOT_COVERED",
  ]),
  message: z.string(),
  requestId: idSchema.nullish(),
  requestStatus: z.string().nullish(),
  uncoveredTables: z.array(z.string()).default([]),
});
export type ApprovalBlocked = z.infer<typeof approvalBlockedSchema>;

/** 403 데이터 권한 차단 — 역할 부족(ErrorResponse{message})과 구분되는 코드 포함 403 (spec 007 §6.5). */
export const accessBlockedSchema = z.object({
  code: z.enum(["TABLES_NOT_PERMITTED", "TABLES_UNKNOWN", "REQUESTER_MISMATCH"]),
  message: z.string(),
  deniedTables: z.array(z.string()).default([]),
});
export type AccessBlocked = z.infer<typeof accessBlockedSchema>;

// ---------- 인증·사용자·권한 (spec 007 §4·§5) ----------

export const roleSchema = z.enum(["ANALYST", "STEWARD", "ADMIN"]);
export type Role = z.infer<typeof roleSchema>;

/** `GET /api/auth/me` · 로그인 응답. password_hash는 어떤 응답에도 없다 (spec 007 H9). */
export const meSchema = z.object({
  id: z.string(),
  displayName: z.string(),
  /** 직책("마케팅본부장") — `role`(열거형)과 다른 값 (spec 007 H4-a). */
  title: z.string(),
  role: roleSchema,
});
export type Me = z.infer<typeof meSchema>;

/** `GET /api/users` — 전 인증 사용자 열람 가능(승인 라인 편성, H3 카브아웃). */
export const appUserSchema = meSchema.extend({ enabled: z.boolean() });
export type AppUser = z.infer<typeof appUserSchema>;

export const tablePermSchema = z.object({ tableName: z.string(), allowed: z.boolean() });
export type TablePerm = z.infer<typeof tablePermSchema>;

/** 행 부재 = 허용(default-allow, spec 007 §3.1) — 백엔드가 전 테이블을 채워 내려준다. */
export const permissionsSchema = z.object({
  userId: z.string(),
  serverAllowed: z.boolean(),
  tables: z.array(tablePermSchema),
});
export type Permissions = z.infer<typeof permissionsSchema>;

export interface PermissionsInput {
  serverAllowed: boolean;
  tables: TablePerm[];
}

/** append-only 감사 이벤트 (spec 007 M2). */
export const permissionEventSchema = z.object({
  targetUserId: z.string(),
  actor: z.string(),
  scope: z.string(),
  target: z.string(),
  beforeAllowed: z.boolean().nullish(),
  afterAllowed: z.boolean(),
  at: z.string(),
});
export type PermissionEvent = z.infer<typeof permissionEventSchema>;

export const businessReqSchema = z.object({
  code: z.string(),
  label: z.string(),
  description: z.string(),
});
export type BusinessReq = z.infer<typeof businessReqSchema>;

export const columnClassSchema = z.enum([
  "PII",
  "BOOLEAN",
  "DATETIME",
  "NUMERIC",
  "KEY",
  "STRING",
]);
export type ColumnClass = z.infer<typeof columnClassSchema>;

export const defKindSchema = z.enum([
  "MASK",
  "FILTER",
  "BLOCK",
  "JOIN",
  "INTEGRITY",
  "PARTITION",
]);
export type DefKind = z.infer<typeof defKindSchema>;

export const catalogColumnSchema = z.object({
  id: idSchema,
  name: z.string(),
  type: z.string(),
  isPii: z.boolean(),
  cls: columnClassSchema,
});
export type CatalogColumn = z.infer<typeof catalogColumnSchema>;

export const catalogTableSchema = z.object({
  id: idSchema,
  name: z.string(),
  description: z.string().nullish(),
  columns: z.array(catalogColumnSchema),
});
export type CatalogTable = z.infer<typeof catalogTableSchema>;

/**
 * `GET /api/my/tables` — 탐색기·요청 피커용 (spec 007 §6.3).
 * 전 테이블 + `accessible`, 비허용 테이블은 **컬럼이 생략**된다(이름은 잠금 UI를 위해 노출).
 */
export const myTableSchema = z.object({
  id: idSchema.nullish(),
  name: z.string(),
  description: z.string().nullish(),
  accessible: z.boolean(),
  columns: z.array(catalogColumnSchema),
});
export type MyTable = z.infer<typeof myTableSchema>;

/** 제약 정의 (spec 002 §5.3 DefDto) */
export const constraintDefSchema = z.object({
  id: idSchema,
  cls: columnClassSchema,
  kind: defKindSchema,
  name: z.string(),
  description: z.string().nullish(),
  expression: z.string().nullish(),
  mappingCount: z.number(),
});
export type ConstraintDef = z.infer<typeof constraintDefSchema>;

/** 컬럼 매핑 (spec 002 §5.3 MappingDto) */
export const constraintMappingSchema = z.object({
  id: idSchema,
  tableId: idSchema,
  tableName: z.string(),
  columnId: idSchema,
  columnName: z.string(),
  defId: idSchema,
  defName: z.string(),
  defKind: defKindSchema,
  purposeCode: z.string().nullish(),
  paramsJson: z.string().nullish(),
  clsMismatch: z.boolean(),
});
export type ConstraintMapping = z.infer<typeof constraintMappingSchema>;

export const purposeSchema = z.object({
  id: idSchema,
  code: z.string(),
  description: z.string().nullish(),
});
export type Purpose = z.infer<typeof purposeSchema>;

export const schemaDictSchema = z.record(z.string(), z.array(z.string()));
export type SchemaDict = z.infer<typeof schemaDictSchema>;

// ---------- 규칙 (spec 004 §3·§7) ----------

export const ruleScopeSchema = z.enum(["SINGLE", "MULTI", "GLOBAL"]);
export type RuleScope = z.infer<typeof ruleScopeSchema>;

/** 조건 단위 severity (§3.1). 목록 요약은 "NONE"도 가능. */
export const ruleSeveritySchema = z.enum(["BLOCK", "WARN"]);
export type RuleSeverity = z.infer<typeof ruleSeveritySchema>;

export const ruleOpSchema = z.enum([
  "requires",
  "blocks",
  "joins",
  "must_be_within",
  "must_be_masked",
]);
export type RuleOp = z.infer<typeof ruleOpSchema>;

/**
 * 조건 leaf (§3.1). `node:"cond"`으로 판별.
 *
 * `judged`는 **더 이상 백엔드가 보내지 않는다**(파생 값을 `tree_json`에 저장하면 원본과 어긋날 수 있고,
 * 엄격 파서에서 규칙 전체가 손상으로 떨어진다 — 손상 규칙은 평가에서 제외되므로 fail-open이다).
 * 화면은 이미 `isJudgedOp(op)`로 스스로 계산하므로 그대로 동작한다. 옛 응답 호환을 위해 optional로 남긴다.
 */
export const ruleConditionSchema = z.object({
  node: z.literal("cond"),
  op: ruleOpSchema,
  severity: ruleSeveritySchema,
  table: z.string().nullish(),
  column: z.string().nullish(),
  defId: idSchema.nullish(),
  mappingId: idSchema.nullish(),
  refTable: z.string().nullish(),
  refColumn: z.string().nullish(),
  subject: z.string().nullish(),
  value: z.string().nullish(),
  judged: z.boolean().optional(),
});
export type RuleCondition = z.infer<typeof ruleConditionSchema>;

/** 그룹 노드 — 재귀(그룹은 그룹/조건을 자식으로). z.lazy로 자기참조 (§3.1). */
export interface RuleGroup {
  node: "group";
  combinator: "all" | "any";
  children: RuleTreeNode[];
}
export type RuleTreeNode = RuleGroup | RuleCondition;

export const ruleGroupSchema: z.ZodType<RuleGroup> = z.lazy(() =>
  z.object({
    node: z.literal("group"),
    combinator: z.enum(["all", "any"]),
    children: z.array(ruleNodeSchema),
  }),
);
export const ruleNodeSchema: z.ZodType<RuleTreeNode> = z.lazy(() =>
  z.union([ruleGroupSchema, ruleConditionSchema]),
);

/** 목록 항목 (§7). enforced=판정 조건 보유 여부, corrupt=tree_json 파싱 실패. */
export const ruleDtoSchema = z.object({
  id: idSchema,
  name: z.string(),
  scope: ruleScopeSchema,
  server: z.string().nullish(),
  severity: z.enum(["BLOCK", "WARN", "NONE"]),
  hits: z.number(),
  enabled: z.boolean(),
  enforced: z.boolean(),
  corrupt: z.boolean(),
});
export type RuleDto = z.infer<typeof ruleDtoSchema>;

/** 상세 (§7). 파싱 실패 시 tree=null + corrupt=true. */
export const ruleDetailSchema = z.object({
  id: idSchema,
  name: z.string(),
  scope: ruleScopeSchema,
  server: z.string().nullish(),
  enabled: z.boolean(),
  tree: ruleGroupSchema.nullable(),
  corrupt: z.boolean(),
});
export type RuleDetail = z.infer<typeof ruleDetailSchema>;

export const ruleTestResultSchema = z.object({ message: z.string() });

export interface RuleInput {
  name: string;
  scope: RuleScope;
  server?: string | null;
  enabled?: boolean;
  tree: RuleGroup;
}

// ---------- 요청 타입 ----------

/** purposeCode는 보내지 않는다 — 서버가 승인 요청에서 주입한다 (spec 005 C1). */
export interface LintInput {
  dialect: "MYSQL";
  sql: string;
  requestId?: Id;
}

/** purposeCode 없음 · requestId 필수 (spec 005 C1·§4). */
export interface QueryInput {
  name: string;
  dialect: "MYSQL";
  sql: string;
  requestId: Id;
}

export interface ApprovalTableInput {
  /** 표시·감사 전용 (파서가 스키마를 버려 검증 불가, §2). */
  db?: string;
  tableName: string;
}

export interface ApprovalApproverInput {
  /** 1부터 연속 */
  step: number;
  approverId: string;
}

export interface ApprovalInput {
  purposeTitle: string;
  purposeCode: string;
  tables: ApprovalTableInput[];
  ruleIds: number[];
  businessReqs: string[];
  approvers: ApprovalApproverInput[];
}

export interface ApprovalFilter {
  status?: ApprovalStatus;
  requester?: string;
}

export interface ReviewInput {
  decision: "APPROVED" | "REJECTED";
  note?: string;
}

export interface TableColumnInput {
  name: string;
  type: string;
  isPii: boolean;
  /** 생략 시 백엔드가 자동 판별, 지정 시 override */
  cls?: ColumnClass;
}

export interface TableInput {
  name: string;
  description: string;
  columns: TableColumnInput[];
}

export interface DefInput {
  cls: ColumnClass;
  kind: DefKind;
  name: string;
  description: string;
  expression?: string;
}

export interface MappingInput {
  columnId: Id;
  defId: Id;
  purposeCode?: string;
  paramsJson?: string;
}

export interface MappingFilter {
  tableId?: Id;
  columnId?: Id;
  defId?: Id;
}

export interface PurposeInput {
  code: string;
  description: string;
}

// ---------- fetch 래퍼 ----------

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
  ) {
    super(`API 오류 (HTTP ${status})`);
    this.name = "ApiError";
  }
}

/** 400/409 응답 바디 {message}에서 서버 메시지를 추출한다 (없으면 null) */
export function apiErrorMessage(err: unknown): string | null {
  if (!(err instanceof ApiError)) return null;
  const body = err.body;
  if (typeof body === "string" && body) return body;
  if (
    body != null &&
    typeof body === "object" &&
    "message" in body &&
    typeof (body as { message: unknown }).message === "string"
  ) {
    return (body as { message: string }).message;
  }
  return null;
}

const BASE = "/api";

// ---------- 세션 · 전역 401 훅 (spec 007 §8 M5) ----------

/**
 * 세션은 **쿠키(HttpOnly)** 다. vite 프록시로 same-origin이므로 `credentials`를 지정하지 않는다
 * (기본값 `same-origin`이 쿠키를 실어 보낸다). 구 actor 헤더는 백엔드가 400으로 거부하므로 어디서도 보내지 않는다.
 */
const unauthorizedListeners = new Set<() => void>();

/** 전역 401 훅. 어떤 요청이든 401이면 호출된다 → 앱은 로그인 화면으로 보낸다. 반환값은 해지 함수. */
export function subscribeUnauthorized(fn: () => void): () => void {
  unauthorizedListeners.add(fn);
  return () => {
    unauthorizedListeners.delete(fn);
  };
}

/**
 * 401을 만나면 훅에 알리고 **영원히 settle되지 않는** 프라미스를 돌려준다.
 * 화면마다 "API 오류" 토스트를 띄우는 대신 진행 중 흐름을 그대로 버리는 방식(진행 중 요청 취소, §8).
 * 앱은 이미 로그인 화면으로 리다이렉트되어 해당 컴포넌트가 언마운트된다.
 */
function handleUnauthorized<T>(): Promise<T> {
  unauthorizedListeners.forEach((fn) => fn());
  return new Promise<T>(() => {
    /* 의도적으로 settle하지 않는다 */
  });
}

interface RequestOptions {
  /** true면 401을 훅으로 넘기지 않고 `ApiError`로 던진다 (로그인 실패·부트스트랩 me()). */
  throw401?: boolean;
}

async function rawRequest(path: string, init?: RequestInit): Promise<Response> {
  const headers: Record<string, string> = {};
  if (init?.body != null) headers["Content-Type"] = "application/json";
  return fetch(`${BASE}${path}`, { ...init, headers });
}

async function readBody(res: Response): Promise<unknown> {
  if (res.status === 204) return undefined;
  const text = await res.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

async function request<T>(
  schema: z.ZodType<T>,
  path: string,
  init?: RequestInit,
  opts?: RequestOptions,
): Promise<T> {
  const res = await rawRequest(path, init);
  const body = await readBody(res);
  if (res.status === 401 && !opts?.throw401) return handleUnauthorized<T>();
  if (!res.ok) throw new ApiError(res.status, body);
  return schema.parse(body);
}

async function requestVoid(path: string, init?: RequestInit, opts?: RequestOptions): Promise<void> {
  const res = await rawRequest(path, init);
  const body = await readBody(res);
  if (res.status === 401 && !opts?.throw401) return handleUnauthorized<void>();
  if (!res.ok) throw new ApiError(res.status, body);
}

// ---------- 인증 (spec 007 §4) ----------

/** 200 Me | 401(사유 구분 없는 동일 메시지). 401은 화면이 직접 처리하므로 던진다. */
export function login(userId: string, password: string): Promise<Me> {
  return request(
    meSchema,
    "/auth/login",
    { method: "POST", body: JSON.stringify({ userId, password }) },
    { throw401: true },
  );
}

/** 이미 만료된 세션에서도 호출될 수 있으므로 401을 훅으로 넘기지 않고 던진다(호출자가 무시). */
export function logout(): Promise<void> {
  return requestVoid("/auth/logout", { method: "POST" }, { throw401: true });
}

/** **401 = 미로그인(정상 부트스트랩 흐름)** — 에러 토스트 금지. 그래서 훅을 타지 않고 던진다. */
export function me(): Promise<Me> {
  return request(meSchema, "/auth/me", undefined, { throw401: true });
}

// ---------- 사용자 · 권한 (spec 007 §4 H3) ----------

/** 전 인증 사용자 (승인자 피커·표시 이름 해석). `/api/directory/*`를 대체한다. */
export function listUsers(): Promise<AppUser[]> {
  return request(z.array(appUserSchema), "/users");
}

/** 본인 또는 ADMIN (그 외 403). */
export function getPermissions(userId: string): Promise<Permissions> {
  return request(permissionsSchema, `/users/${userId}/permissions`);
}

/** ADMIN 전용. 자기 자신의 권한 편집은 **403 CANNOT_EDIT_OWN_PERMISSION**. */
export function savePermissions(userId: string, input: PermissionsInput): Promise<Permissions> {
  return request(permissionsSchema, `/users/${userId}/permissions`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** ADMIN 전용 — append-only 감사 이력. */
export function listPermissionHistory(userId: string): Promise<PermissionEvent[]> {
  return request(z.array(permissionEventSchema), `/users/${userId}/permissions/history`);
}

/** 탐색기·요청 피커 — 전 테이블 + accessible (비허용은 컬럼 생략). */
export function myTables(): Promise<MyTable[]> {
  return request(z.array(myTableSchema), "/my/tables");
}

// ---------- lint / 쿼리 ----------

/**
 * lint 결과 (spec 007 §6.0 — 인증 → **데이터 권한(403)** → 룰):
 * - 성공: 200 LintReportDto
 * - `ACCESS`: 403 AccessBlockedDto — 권한 없는/미등록 테이블, 남의 requestId
 */
export type LintResult =
  | { ok: true; report: LintReport }
  | { ok: false; error: AccessBlocked };

/** requestId를 보내면 서버가 그 요청의 purposeCode로 판정한다 (C1 — 저장 게이트와 동일 조건). */
export async function lint(input: LintInput): Promise<LintResult> {
  const res = await rawRequest("/lint", { method: "POST", body: JSON.stringify(input) });
  const body = await readBody(res);
  if (res.status === 401) return handleUnauthorized<LintResult>();
  if (res.status === 403) {
    const parsed = accessBlockedSchema.safeParse(body);
    if (parsed.success) return { ok: false, error: parsed.data };
    throw new ApiError(res.status, body); // 역할 권한 부족(ErrorResponse) 등
  }
  if (!res.ok) throw new ApiError(res.status, body);
  return { ok: true, report: lintReportSchema.parse(body) };
}

/**
 * 저장 결과 (spec 005 §7 H5 + spec 007 §6.5 — 차단 계약 3종):
 * - 성공(201/200)
 * - `RULES`: 422 LintReportDto — 룰 게이트 차단
 * - `ACCESS`: 403 AccessBlockedDto — 데이터 권한 차단(권한 밖·미등록 테이블)
 * - `APPROVAL`: 403 ApprovalBlockedDto — 승인 게이트 차단
 *
 * ACCESS와 APPROVAL은 **둘 다 403**이라 `code`로 구분한다. `REQUESTER_MISMATCH`는 양쪽에 다 있으므로
 * AccessBlockedDto에만 있는 `deniedTables` 필드 유무로 최종 판별한다.
 */
export type SaveQueryResult =
  | { ok: true; query: SavedQuery }
  | { ok: false; kind: "RULES"; report: LintReport }
  | { ok: false; kind: "ACCESS"; error: AccessBlocked }
  | { ok: false; kind: "APPROVAL"; error: ApprovalBlocked };

function isAccessBlockedBody(body: unknown): boolean {
  if (body == null || typeof body !== "object") return false;
  const code = (body as { code?: unknown }).code;
  if (code === "TABLES_NOT_PERMITTED" || code === "TABLES_UNKNOWN") return true;
  // REQUESTER_MISMATCH는 두 계약에 모두 존재 → 필드 형태로 구분
  return code === "REQUESTER_MISMATCH" && "deniedTables" in (body as object);
}

async function saveQuery(path: string, method: "POST" | "PUT", input: QueryInput): Promise<SaveQueryResult> {
  const res = await rawRequest(path, { method, body: JSON.stringify(input) });
  const body = await readBody(res);
  if (res.status === 401) return handleUnauthorized<SaveQueryResult>();
  if (res.status === 422) return { ok: false, kind: "RULES", report: lintReportSchema.parse(body) };
  if (res.status === 403) {
    if (isAccessBlockedBody(body)) {
      return { ok: false, kind: "ACCESS", error: accessBlockedSchema.parse(body) };
    }
    const approval = approvalBlockedSchema.safeParse(body);
    if (approval.success) return { ok: false, kind: "APPROVAL", error: approval.data };
    throw new ApiError(res.status, body); // 역할 권한 부족(ErrorResponse{message})
  }
  if (!res.ok) throw new ApiError(res.status, body);
  return { ok: true, query: savedQuerySchema.parse(body) };
}

export function createQuery(input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery("/queries", "POST", input);
}

export function updateQuery(id: Id, input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery(`/queries/${id}`, "PUT", input);
}

/** 검토 결정 — STEWARD/ADMIN 전용(403). 200 | 409 {message}(자가 검토·현재 BLOCK). */
export function reviewQuery(id: Id, input: ReviewInput): Promise<SavedQuery> {
  return request(savedQuerySchema, `/queries/${id}/review`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listQueries(): Promise<QueryListItem[]> {
  return request(z.array(queryListItemSchema), "/queries");
}

export function getQuery(id: Id): Promise<SavedQuery> {
  return request(savedQuerySchema, `/queries/${id}`);
}

export function deleteQuery(id: Id): Promise<void> {
  return requestVoid(`/queries/${id}`, { method: "DELETE" });
}

// ---------- 승인 요청 (spec 005 §7) ----------

export function listApprovals(filter: ApprovalFilter = {}): Promise<ApprovalSummary[]> {
  const params = new URLSearchParams();
  if (filter.status) params.set("status", filter.status);
  if (filter.requester) params.set("requester", filter.requester);
  const qs = params.toString();
  return request(z.array(approvalSummarySchema), `/approvals${qs ? `?${qs}` : ""}`);
}

export function getApproval(id: Id): Promise<ApprovalDetail> {
  return request(approvalDetailSchema, `/approvals/${id}`);
}

/**
 * 201 | 400 {message}(승인자 0·비연속 step·중복 인물·미등록 purpose/요건·카탈로그 밖 테이블 ·
 * ANALYST를 승인자로 지정 · **본인을 승인자로 지정 `REQUESTER_IS_APPROVER`**) — 요청자는 세션 principal.
 */
export function createApproval(input: ApprovalInput): Promise<ApprovalDetail> {
  return request(approvalDetailSchema, "/approvals", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

function decide(id: Id, action: "approve" | "reject" | "cancel", note?: string): Promise<ApprovalDetail> {
  return request(approvalDetailSchema, `/approvals/${id}/${action}`, {
    method: "POST",
    body: JSON.stringify({ note: note ?? null }),
  });
}

/** STEWARD/ADMIN 전용(403). 200 | 409 {message}(순서 아닌 승인자·재결정·이미 결정된 요청·동시성) */
export function approveApproval(id: Id, note?: string): Promise<ApprovalDetail> {
  return decide(id, "approve", note);
}

export function rejectApproval(id: Id, note?: string): Promise<ApprovalDetail> {
  return decide(id, "reject", note);
}

/** 요청자 본인·PENDING만 (409 otherwise) */
export function cancelApproval(id: Id): Promise<ApprovalDetail> {
  return decide(id, "cancel");
}

/** 에디터 요청 선택용 — **세션 사용자**가 요청자인 APPROVED 요청. */
export function listUsableApprovals(): Promise<ApprovalSummary[]> {
  return request(z.array(approvalSummarySchema), "/approvals/usable");
}

// ---------- 비즈니스 요건 (spec 007 H4-b) ----------

/**
 * 요건 5종은 승인자 풀과 무관한 **상수 유지**이므로 `/api/directory/business-reqs`는 계속 쓴다.
 * 반면 `/api/directory/users|approvers`는 폐기 → `listUsers()`로 대체하고, 이름→id 복원표도 제거했다 (H4-c).
 */
export function listBusinessReqs(): Promise<BusinessReq[]> {
  return request(z.array(businessReqSchema), "/directory/business-reqs");
}

// ---------- 카탈로그 ----------

export function listTables(): Promise<CatalogTable[]> {
  return request(z.array(catalogTableSchema), "/catalog/tables");
}

export function createTable(input: TableInput): Promise<CatalogTable> {
  return request(catalogTableSchema, "/catalog/tables", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateTable(id: Id, input: TableInput): Promise<CatalogTable> {
  return request(catalogTableSchema, `/catalog/tables/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function deleteTable(id: Id): Promise<void> {
  return requestVoid(`/catalog/tables/${id}`, { method: "DELETE" });
}

// ---------- 제약 정의 (spec 002 §5.3) ----------

export function listDefs(): Promise<ConstraintDef[]> {
  return request(z.array(constraintDefSchema), "/catalog/defs");
}

export function createDef(input: DefInput): Promise<ConstraintDef> {
  return request(constraintDefSchema, "/catalog/defs", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateDef(id: Id, input: DefInput): Promise<ConstraintDef> {
  return request(constraintDefSchema, `/catalog/defs/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 매핑이 남아 있으면 409 {message} */
export function deleteDef(id: Id): Promise<void> {
  return requestVoid(`/catalog/defs/${id}`, { method: "DELETE" });
}

// ---------- 컬럼 매핑 (spec 002 §5.3) ----------

export function listMappings(filter: MappingFilter = {}): Promise<ConstraintMapping[]> {
  const params = new URLSearchParams();
  if (filter.tableId != null) params.set("tableId", String(filter.tableId));
  if (filter.columnId != null) params.set("columnId", String(filter.columnId));
  if (filter.defId != null) params.set("defId", String(filter.defId));
  const qs = params.toString();
  return request(z.array(constraintMappingSchema), `/catalog/mappings${qs ? `?${qs}` : ""}`);
}

/** 400(클래스 불일치·판정 미지원 FILTER·params 검증) | 409(중복) */
export function createMapping(input: MappingInput): Promise<ConstraintMapping> {
  return request(constraintMappingSchema, "/catalog/mappings", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function deleteMapping(id: Id): Promise<void> {
  return requestVoid(`/catalog/mappings/${id}`, { method: "DELETE" });
}

export function listPurposes(): Promise<Purpose[]> {
  return request(z.array(purposeSchema), "/catalog/purposes");
}

export function createPurpose(input: PurposeInput): Promise<Purpose> {
  return request(purposeSchema, "/catalog/purposes", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function deletePurpose(id: Id): Promise<void> {
  return requestVoid(`/catalog/purposes/${id}`, { method: "DELETE" });
}

export function getSchemaDict(): Promise<SchemaDict> {
  return request(schemaDictSchema, "/catalog/schema");
}

// ---------- 규칙 (spec 004 §7) ----------

export function listRules(): Promise<RuleDto[]> {
  return request(z.array(ruleDtoSchema), "/rules");
}

export function getRule(id: Id): Promise<RuleDetail> {
  return request(ruleDetailSchema, `/rules/${id}`);
}

/** 201 RuleDetail | 400 {message}(매핑 안 된 defId·빈 그룹·requires 판정불가·op/severity 오류) */
export function createRule(input: RuleInput): Promise<RuleDetail> {
  return request(ruleDetailSchema, "/rules", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateRule(id: Id, input: RuleInput): Promise<RuleDetail> {
  return request(ruleDetailSchema, `/rules/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function deleteRule(id: Id): Promise<void> {
  return requestVoid(`/rules/${id}`, { method: "DELETE" });
}

/** 테스트 실행 — 이번 스펙에서는 스텁 메시지만 반환 (§2). */
export function testRule(id: Id): Promise<{ message: string }> {
  return request(ruleTestResultSchema, `/rules/${id}/test`, { method: "POST" });
}
