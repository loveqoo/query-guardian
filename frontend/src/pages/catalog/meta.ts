import { z } from "zod";
import { columnClassSchema, defKindSchema } from "../../api/client";
import type { ColumnClass, DefKind } from "../../api/client";
import { KIND_COLOR, KIND_LABEL } from "../../theme";

/**
 * Catalog-screen metadata + validation helpers (spec 002 / spec 003 §8-2).
 * Self-contained so the page stays close to the design while driving real API data.
 */

/** Column classes in the design's section order. */
export const CLASS_ORDER: ColumnClass[] = [
  "PII",
  "BOOLEAN",
  "DATETIME",
  "NUMERIC",
  "KEY",
  "STRING",
];

/** Section header labels (design wording). */
export const CLASS_LABEL: Record<ColumnClass, string> = {
  PII: "개인정보(PII)",
  BOOLEAN: "BOOLEAN",
  DATETIME: "날짜·시간(DATETIME)",
  NUMERIC: "숫자(NUMERIC)",
  KEY: "키·조인(KEY)",
  STRING: "문자열(STRING)",
};

/** Representative sample column per class — used to render the modal preview. */
export const SAMPLE_COL: Record<ColumnClass, string> = {
  PII: "email",
  BOOLEAN: "is_agreed",
  DATETIME: "created_at",
  NUMERIC: "amount",
  KEY: "user_id",
  STRING: "status",
};

export interface KindMetaEntry {
  /** "엔진 동작: …" copy (spec 002 §3.2 target behavior). */
  action: string;
  /** IR → SQL skeleton with {expr}/{col} placeholders. */
  skeleton: string;
  /** Whether this kind carries an expression (BLOCK/PARTITION do not). */
  hasExpr: boolean;
}

/**
 * kind metadata for all 6 kinds. The 5 design kinds keep the mock/design.ts
 * action/skeleton wording; PARTITION is the spec 003 §8-2 addition (orange, no expr).
 */
export const KIND_META: Record<DefKind, KindMetaEntry> = {
  MASK: {
    action: "SELECT 절의 대상 컬럼을 변형식으로 치환",
    skeleton: "SELECT …, {expr} AS {col} FROM …",
    hasExpr: true,
  },
  FILTER: {
    action: "WHERE 절에 술어를 추가해 미충족 행을 제외",
    skeleton: "SELECT … FROM … WHERE {expr}",
    hasExpr: true,
  },
  BLOCK: {
    action: "쿼리가 이 컬럼을 참조하면 실행 자체를 거부",
    skeleton: "-- REJECT: 컬럼 {col} 참조 감지 → 쿼리 차단",
    hasExpr: false,
  },
  JOIN: {
    action: "필수 조인이 존재하는지 검사, 없으면 거부",
    skeleton: "SELECT … FROM … JOIN … ON {expr}",
    hasExpr: true,
  },
  INTEGRITY: {
    action: "WHERE 절에 무결성 술어를 추가",
    skeleton: "SELECT … FROM … WHERE {expr}",
    hasExpr: true,
  },
  PARTITION: {
    action: "파티션 컬럼 조건이 없으면 거부",
    skeleton: "-- REQUIRE: 파티션 컬럼 {col} 조건(=/IN/BETWEEN) 필수",
    hasExpr: false,
  },
};

/** Every kind, in the def-modal Select order. */
export const KIND_ORDER: DefKind[] = [
  "MASK",
  "FILTER",
  "BLOCK",
  "JOIN",
  "INTEGRITY",
  "PARTITION",
];

/** kind → antd Tag color (theme keys are lowercase). */
export function kindColor(kind: DefKind): string {
  return KIND_COLOR[kind.toLowerCase()] ?? "default";
}

/** kind → Korean label (theme keys are lowercase). */
export function kindLabel(kind: DefKind): string {
  return KIND_LABEL[kind.toLowerCase()] ?? kind;
}

/**
 * Render the IR → SQL preview by substituting {expr}/{col} into the skeleton.
 * Mirrors mock/design.ts enforcePreview but covers all 6 kinds.
 */
export function buildPreview(kind: DefKind, expression: string, col: string): string {
  const meta = KIND_META[kind];
  const expr = (expression || col).split("{col}").join(col);
  return meta.skeleton.split("{expr}").join(expr).split("{col}").join(col);
}

/** kinds whose expression is required (spec 002 §3.2). */
const EXPR_REQUIRED: DefKind[] = ["MASK", "FILTER", "JOIN", "INTEGRITY"];
/** kinds whose expression must contain {col} (spec 002 §3.3 M1). */
const COL_REQUIRED: DefKind[] = ["MASK", "FILTER", "INTEGRITY"];

export interface DefFormValues {
  cls: ColumnClass;
  kind: DefKind;
  name: string;
  expression: string;
  description: string;
}

/** zod schema for the def modal (spec 002 §3.3 client-side validation). */
export const defFormSchema = z
  .object({
    cls: columnClassSchema,
    kind: defKindSchema,
    name: z.string(),
    expression: z.string(),
    description: z.string(),
  })
  .superRefine((v, ctx) => {
    if (!v.name.trim()) {
      ctx.addIssue({
        path: ["name"],
        code: z.ZodIssueCode.custom,
        message: "제약 이름을 입력하세요.",
      });
    }
    const exprRequired = EXPR_REQUIRED.includes(v.kind);
    if (exprRequired && !v.expression.trim()) {
      ctx.addIssue({
        path: ["expression"],
        code: z.ZodIssueCode.custom,
        message: "이 kind는 강제식이 필요합니다.",
      });
    }
    if (
      COL_REQUIRED.includes(v.kind) &&
      v.expression.trim() &&
      !v.expression.includes("{col}")
    ) {
      ctx.addIssue({
        path: ["expression"],
        code: z.ZodIssueCode.custom,
        message: "강제식에 {col} 이 최소 1회 포함되어야 합니다.",
      });
    }
  });

export type DefFieldErrors = Partial<Record<keyof DefFormValues, string>>;

/** Validate form values, returning field → message errors ({} when valid). */
export function validateDefForm(values: DefFormValues): DefFieldErrors {
  const result = defFormSchema.safeParse(values);
  if (result.success) return {};
  const errors: DefFieldErrors = {};
  for (const issue of result.error.issues) {
    const key = issue.path[0] as keyof DefFormValues;
    if (key && !errors[key]) errors[key] = issue.message;
  }
  return errors;
}
