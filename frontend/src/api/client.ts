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
  /** 요청자 = 디렉터리 actor id (스텁 identity, §5). */
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

// ---------- 디렉터리 (spec 005 §7 — 관리형 목록) ----------

/**
 * `id`가 optional인 이유: 백엔드 `DirectoryPersonDto`는 현재 `{name, role}`만 직렬화한다
 * (`Directory.Person`은 id를 갖지만 DTO 매핑에서 탈락). actor 헤더·approverId는 **id**를 요구하므로
 * id가 없으면 이름 → id 매핑, 그래도 없으면 목록 순서(u1.., ap1..)로 복원한다.
 */
export const directoryPersonSchema = z.object({
  id: z.string().optional(),
  name: z.string(),
  role: z.string(),
});

export interface DirectoryPerson {
  id: string;
  name: string;
  role: string;
}

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
 * 조건 leaf (§3.1). `node:"cond"`으로 판별. `judged`는 백엔드가 계산해 함께 직렬화하는
 * 읽기 전용 플래그(requires/blocks/joins=true) — 저장 시엔 보내지 않는다.
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

// ---------- 행위자(actor) 스텁 — 접근 통제가 아님 (spec 005 §5) ----------

/**
 * actor는 **인증되지 않은 클라이언트 제공 문자열**이다. 순차 승인·자가 검토 금지·요청자 일치 검사는
 * 워크플로·감사 장치이며 접근 통제가 아니다(위조 가능). 인증 도입 시 이 모듈이 유일한 교체 지점.
 */
export const ACTOR_HEADER = "X-QG-Actor";
const ACTOR_STORAGE_KEY = "qg.actor";
const DEFAULT_ACTOR = "u1";

function readStoredActor(): string {
  try {
    return window.localStorage.getItem(ACTOR_STORAGE_KEY) || DEFAULT_ACTOR;
  } catch {
    return DEFAULT_ACTOR;
  }
}

let currentActor = readStoredActor();
const actorListeners = new Set<() => void>();

export function getActor(): string {
  return currentActor;
}

export function setActor(actor: string): void {
  if (!actor || actor === currentActor) return;
  currentActor = actor;
  try {
    window.localStorage.setItem(ACTOR_STORAGE_KEY, actor);
  } catch {
    /* storage 비활성 환경은 메모리 값만 사용 */
  }
  actorListeners.forEach((fn) => fn());
}

/** useSyncExternalStore 용 구독자. 반환값은 해지 함수. */
export function subscribeActor(fn: () => void): () => void {
  actorListeners.add(fn);
  return () => {
    actorListeners.delete(fn);
  };
}

interface RequestOptions {
  /** true면 `X-QG-Actor` 헤더를 현재 actor로 실어 보낸다. */
  actor?: boolean;
}

async function rawRequest(
  path: string,
  init?: RequestInit,
  opts?: RequestOptions,
): Promise<Response> {
  const headers: Record<string, string> = {};
  if (init?.body != null) headers["Content-Type"] = "application/json";
  if (opts?.actor) headers[ACTOR_HEADER] = getActor();
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
  const res = await rawRequest(path, init, opts);
  const body = await readBody(res);
  if (!res.ok) throw new ApiError(res.status, body);
  return schema.parse(body);
}

async function requestVoid(path: string, init?: RequestInit, opts?: RequestOptions): Promise<void> {
  const res = await rawRequest(path, init, opts);
  if (!res.ok) throw new ApiError(res.status, await readBody(res));
}

// ---------- lint / 쿼리 ----------

/** requestId를 보내면 서버가 그 요청의 purposeCode로 판정한다 (C1 — 저장 게이트와 동일 조건). */
export function lint(input: LintInput): Promise<LintReport> {
  return request(lintReportSchema, "/lint", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/**
 * 저장 결과 (spec 005 §7 H5 — 차단 계약 2종):
 * - 성공(201/200)
 * - `RULES`: 422 LintReportDto — 룰 게이트 차단
 * - `APPROVAL`: 403 ApprovalBlockedDto — 승인 게이트 차단
 */
export type SaveQueryResult =
  | { ok: true; query: SavedQuery }
  | { ok: false; kind: "RULES"; report: LintReport }
  | { ok: false; kind: "APPROVAL"; error: ApprovalBlocked };

async function saveQuery(path: string, method: "POST" | "PUT", input: QueryInput): Promise<SaveQueryResult> {
  const res = await rawRequest(path, { method, body: JSON.stringify(input) }, { actor: true });
  const body = await readBody(res);
  if (res.status === 422) return { ok: false, kind: "RULES", report: lintReportSchema.parse(body) };
  if (res.status === 403) return { ok: false, kind: "APPROVAL", error: approvalBlockedSchema.parse(body) };
  if (!res.ok) throw new ApiError(res.status, body);
  return { ok: true, query: savedQuerySchema.parse(body) };
}

export function createQuery(input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery("/queries", "POST", input);
}

export function updateQuery(id: Id, input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery(`/queries/${id}`, "PUT", input);
}

/** 검토 결정 — 200 | 409 {message}(자가 검토·현재 BLOCK). */
export function reviewQuery(id: Id, input: ReviewInput): Promise<SavedQuery> {
  return request(
    savedQuerySchema,
    `/queries/${id}/review`,
    { method: "POST", body: JSON.stringify(input) },
    { actor: true },
  );
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

/** 201 | 400 {message}(승인자 0·비연속 step·중복 인물·미등록 purpose/요건·카탈로그 밖 테이블) */
export function createApproval(input: ApprovalInput): Promise<ApprovalDetail> {
  return request(
    approvalDetailSchema,
    "/approvals",
    { method: "POST", body: JSON.stringify(input) },
    { actor: true },
  );
}

function decide(id: Id, action: "approve" | "reject" | "cancel", note?: string): Promise<ApprovalDetail> {
  return request(
    approvalDetailSchema,
    `/approvals/${id}/${action}`,
    { method: "POST", body: JSON.stringify({ note: note ?? null }) },
    { actor: true },
  );
}

/** 200 | 409 {message}(순서 아닌 actor·재결정·이미 결정된 요청·동시성) */
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

/** 에디터 요청 선택용 — 현재 actor가 요청자인 APPROVED 요청. */
export function listUsableApprovals(): Promise<ApprovalSummary[]> {
  return request(z.array(approvalSummarySchema), "/approvals/usable", undefined, { actor: true });
}

// ---------- 디렉터리 (spec 005 §7) ----------

/**
 * 백엔드 DTO가 id를 빠뜨린 경우의 복원 표 (`Directory` 상수와 1:1).
 * id가 응답에 들어오면 그 값이 우선한다.
 */
const USER_ID_BY_NAME: Record<string, string> = {
  김도현: "u1",
  이서연: "u2",
  박민준: "u3",
  정하윤: "u4",
};
const APPROVER_ID_BY_NAME: Record<string, string> = {
  최지훈: "ap1",
  한도윤: "ap2",
  서준호: "ap3",
  김영은: "ap4",
};

async function fetchDirectory(
  path: string,
  byName: Record<string, string>,
  prefix: string,
): Promise<DirectoryPerson[]> {
  const people = await request(z.array(directoryPersonSchema), path);
  return people.map((p, i) => ({
    id: p.id ?? byName[p.name] ?? `${prefix}${i + 1}`,
    name: p.name,
    role: p.role,
  }));
}

export function listDirectoryUsers(): Promise<DirectoryPerson[]> {
  return fetchDirectory("/directory/users", USER_ID_BY_NAME, "u");
}

export function listDirectoryApprovers(): Promise<DirectoryPerson[]> {
  return fetchDirectory("/directory/approvers", APPROVER_ID_BY_NAME, "ap");
}

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
