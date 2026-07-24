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

export const queryListItemSchema = z.object({
  id: idSchema,
  name: z.string(),
  dialect: z.string(),
  purposeCode: z.string().nullish(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type QueryListItem = z.infer<typeof queryListItemSchema>;

export const savedQuerySchema = queryListItemSchema.extend({
  sql: z.string(),
  lintReport: lintReportSchema.nullish(),
});
export type SavedQuery = z.infer<typeof savedQuerySchema>;

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

// ---------- 요청 타입 ----------

export interface LintInput {
  dialect: "MYSQL";
  sql: string;
  purposeCode?: string;
}

export interface QueryInput {
  name: string;
  dialect: "MYSQL";
  sql: string;
  purposeCode?: string;
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

async function request<T>(schema: z.ZodType<T>, path: string, init?: RequestInit): Promise<T> {
  const res = await rawRequest(path, init);
  const body = await readBody(res);
  if (!res.ok) throw new ApiError(res.status, body);
  return schema.parse(body);
}

async function requestVoid(path: string, init?: RequestInit): Promise<void> {
  const res = await rawRequest(path, init);
  if (!res.ok) throw new ApiError(res.status, await readBody(res));
}

// ---------- lint / 쿼리 ----------

export function lint(input: LintInput): Promise<LintReport> {
  return request(lintReportSchema, "/lint", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 저장 결과: 성공(201/200) 또는 422 차단(위반 리포트) */
export type SaveQueryResult =
  | { ok: true; query: SavedQuery }
  | { ok: false; report: LintReport };

async function saveQuery(path: string, method: "POST" | "PUT", input: QueryInput): Promise<SaveQueryResult> {
  const res = await rawRequest(path, { method, body: JSON.stringify(input) });
  const body = await readBody(res);
  if (res.status === 422) return { ok: false, report: lintReportSchema.parse(body) };
  if (!res.ok) throw new ApiError(res.status, body);
  return { ok: true, query: savedQuerySchema.parse(body) };
}

export function createQuery(input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery("/queries", "POST", input);
}

export function updateQuery(id: Id, input: QueryInput): Promise<SaveQueryResult> {
  return saveQuery(`/queries/${id}`, "PUT", input);
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
