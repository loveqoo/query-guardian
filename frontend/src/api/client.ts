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

export const catalogColumnSchema = z.object({
  id: idSchema,
  name: z.string(),
  type: z.string(),
});
export type CatalogColumn = z.infer<typeof catalogColumnSchema>;

export const constraintKindSchema = z.enum(["PARTITION_KEY", "REQUIRED_PREDICATE"]);
export type ConstraintKind = z.infer<typeof constraintKindSchema>;

export const catalogConstraintSchema = z.object({
  id: idSchema,
  kind: constraintKindSchema,
  columnName: z.string().nullish(),
  predicateSql: z.string().nullish(),
  purposeCode: z.string().nullish(),
});
export type CatalogConstraint = z.infer<typeof catalogConstraintSchema>;

export const catalogTableSchema = z.object({
  id: idSchema,
  name: z.string(),
  description: z.string().nullish(),
  columns: z.array(catalogColumnSchema),
  constraints: z.array(catalogConstraintSchema),
});
export type CatalogTable = z.infer<typeof catalogTableSchema>;

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

export interface TableInput {
  name: string;
  description: string;
  columns: { name: string; type: string }[];
}

export interface ConstraintInput {
  kind: ConstraintKind;
  columnName?: string;
  predicateSql?: string;
  purposeCode?: string;
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

// 백엔드는 제약이 추가된 테이블 전체를 돌려준다
export function createConstraint(tableId: Id, input: ConstraintInput): Promise<CatalogTable> {
  return request(catalogTableSchema, `/catalog/tables/${tableId}/constraints`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function deleteConstraint(id: Id): Promise<void> {
  return requestVoid(`/catalog/constraints/${id}`, { method: "DELETE" });
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
