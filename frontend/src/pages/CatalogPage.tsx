import { useCallback, useEffect, useMemo, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import * as api from "../api/client";
import { defKindSchema } from "../api/client";
import type {
  CatalogColumn,
  CatalogTable,
  ColumnClass,
  ConstraintDef,
  ConstraintMapping,
  DefKind,
  Purpose,
} from "../api/client";

// ---------- 클래스 / kind 메타 (spec 002 §3, learning 004 §1.6) ----------

const CLASS_ORDER: ColumnClass[] = ["PII", "BOOLEAN", "DATETIME", "NUMERIC", "KEY", "STRING"];

const CLASS_LABEL: Record<ColumnClass, string> = {
  PII: "개인정보(PII)",
  BOOLEAN: "BOOLEAN",
  DATETIME: "날짜·시간(DATETIME)",
  NUMERIC: "숫자(NUMERIC)",
  KEY: "키·조인(KEY)",
  STRING: "문자열(STRING)",
};

/** 프리뷰용 표본 컬럼명 (클라이언트 전용) */
const SAMPLE_COL: Record<ColumnClass, string> = {
  PII: "email",
  BOOLEAN: "consent_yn",
  DATETIME: "event_date",
  NUMERIC: "amount",
  KEY: "user_id",
  STRING: "name",
};

interface KindMeta {
  label: string;
  color: string;
  /** "엔진 동작: …" 힌트 (spec 002 §3.2 목표 동작) */
  behavior: string;
  /** BLOCK/PARTITION은 expr 없음 */
  hasExpr: boolean;
  /** expr에 {col} 최소 1회 필수 */
  requiresCol: boolean;
}

const KIND_META: Record<DefKind, KindMeta> = {
  MASK: {
    label: "마스킹",
    color: "blue",
    behavior: "SELECT 절의 대상 컬럼을 변형식으로 치환",
    hasExpr: true,
    requiresCol: true,
  },
  FILTER: {
    label: "필터",
    color: "geekblue",
    behavior: "WHERE 절에 술어를 추가해 미충족 행을 제외",
    hasExpr: true,
    requiresCol: true,
  },
  BLOCK: {
    label: "차단",
    color: "red",
    behavior: "쿼리가 이 컬럼을 참조하면 실행 자체를 거부",
    hasExpr: false,
    requiresCol: false,
  },
  JOIN: {
    label: "조인",
    color: "purple",
    behavior: "필수 조인이 존재하는지 검사, 없으면 거부",
    hasExpr: true,
    requiresCol: false,
  },
  INTEGRITY: {
    label: "무결성",
    color: "cyan",
    behavior: "WHERE 절에 무결성 술어를 추가",
    hasExpr: true,
    requiresCol: true,
  },
  PARTITION: {
    label: "파티션",
    color: "orange",
    behavior: "파티션 컬럼 조건이 없으면 거부",
    hasExpr: false,
    requiresCol: false,
  },
};

const monoBlue: React.CSSProperties = { fontFamily: "monospace", color: "#1677ff" };

// ---------- 정의 추가/편집 모달 ----------

const defFormSchema = z
  .object({
    kind: defKindSchema,
    name: z.string().min(1, "이름을 입력하세요").max(100, "100자 이하"),
    expression: z.string(),
    description: z.string(),
  })
  .superRefine((values, ctx) => {
    const meta = KIND_META[values.kind];
    const expr = values.expression.trim();
    if (meta.hasExpr && !expr) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["expression"],
        message: "강제식을 입력하세요",
      });
    }
    if (meta.requiresCol && expr && !expr.includes("{col}")) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["expression"],
        message: "강제식에 {col}이 최소 1회 포함되어야 합니다",
      });
    }
  });

type DefFormValues = z.infer<typeof defFormSchema>;

interface DefModalProps {
  /** null이면 닫힘 */
  cls: ColumnClass | null;
  editing: ConstraintDef | null;
  onClose: () => void;
  onSaved: () => void;
}

function DefModal({ cls, editing, onClose, onSaved }: DefModalProps) {
  const { message } = App.useApp();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    control,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<DefFormValues>({
    resolver: zodResolver(defFormSchema),
    defaultValues: { kind: "MASK", name: "", expression: "", description: "" },
  });
  const kind = watch("kind");
  const expression = watch("expression");
  const meta = KIND_META[kind];

  useEffect(() => {
    if (cls === null) return;
    setServerError(null);
    if (editing) {
      reset({
        kind: editing.kind,
        name: editing.name,
        expression: editing.expression ?? "",
        description: editing.description ?? "",
      });
    } else {
      reset({ kind: "MASK", name: "", expression: "", description: "" });
    }
  }, [cls, editing, reset]);

  const preview =
    cls && meta.hasExpr && expression.trim()
      ? expression.split("{col}").join(SAMPLE_COL[cls])
      : null;

  const onSubmit = handleSubmit(async (values) => {
    if (cls === null) return;
    const kindMeta = KIND_META[values.kind];
    const input: api.DefInput = {
      cls,
      kind: values.kind,
      name: values.name.trim(),
      description: values.description.trim(),
      expression: kindMeta.hasExpr ? values.expression.trim() : undefined,
    };
    setServerError(null);
    try {
      if (editing) {
        await api.updateDef(editing.id, input);
      } else {
        await api.createDef(input);
      }
      message.success(editing ? "정의가 수정되었습니다" : "정의가 추가되었습니다");
      onSaved();
      onClose();
    } catch (err) {
      setServerError(api.apiErrorMessage(err) ?? "정의 저장에 실패했습니다");
    }
  });

  return (
    <Modal
      open={cls !== null}
      title={
        cls
          ? `${editing ? "정의 편집" : "정의 추가"} — ${CLASS_LABEL[cls]}`
          : "정의 추가"
      }
      okText="저장"
      cancelText="취소"
      onCancel={onClose}
      onOk={onSubmit}
      confirmLoading={isSubmitting}
      destroyOnHidden
    >
      <Space direction="vertical" style={{ width: "100%" }} size="middle">
        <div>
          <Typography.Text>종류</Typography.Text>
          <Controller
            name="kind"
            control={control}
            render={({ field }) => (
              <Select
                value={field.value}
                onChange={field.onChange}
                style={{ width: "100%" }}
                options={(Object.keys(KIND_META) as DefKind[]).map((k) => ({
                  value: k,
                  label: `${KIND_META[k].label} (${k})`,
                }))}
              />
            )}
          />
          <Typography.Text type="secondary">엔진 동작: {meta.behavior}</Typography.Text>
        </div>
        <div>
          <Typography.Text>이름 *</Typography.Text>
          <Controller
            name="name"
            control={control}
            render={({ field }) => (
              <Input
                {...field}
                placeholder="예: 이메일 마스킹"
                status={errors.name ? "error" : undefined}
              />
            )}
          />
          {errors.name && (
            <Typography.Text type="danger">{errors.name.message}</Typography.Text>
          )}
        </div>
        {meta.hasExpr && (
          <div>
            <Typography.Text>강제식</Typography.Text>
            <Controller
              name="expression"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  placeholder="예: CONCAT(LEFT({col}, 1), '***')"
                  style={{ fontFamily: "monospace" }}
                  status={errors.expression ? "error" : undefined}
                />
              )}
            />
            <div>
              <Typography.Text type="secondary">
                대상 컬럼은 {"{col}"}, 입력값은 :param 으로 표기
              </Typography.Text>
            </div>
            {errors.expression && (
              <Typography.Text type="danger">{errors.expression.message}</Typography.Text>
            )}
          </div>
        )}
        <div>
          <Typography.Text>설명</Typography.Text>
          <Controller
            name="description"
            control={control}
            render={({ field }) => <Input {...field} placeholder="정의 설명" />}
          />
        </div>
        {preview && (
          <div>
            <Typography.Text type="secondary">미리보기 (표본 컬럼 치환)</Typography.Text>
            <pre
              style={{
                margin: "4px 0 0",
                padding: "10px 12px",
                background: "#1e1e1e",
                color: "#d4d4d4",
                borderRadius: 6,
                fontFamily: "monospace",
                fontSize: 13,
                overflowX: "auto",
              }}
            >
              {preview}
            </pre>
          </div>
        )}
        {serverError && <Alert type="error" showIcon message={serverError} />}
      </Space>
    </Modal>
  );
}

// ---------- 제약 정의 탭 ----------

interface DefsTabProps {
  active: boolean;
}

function DefsTab({ active }: DefsTabProps) {
  const { message } = App.useApp();
  const [defs, setDefs] = useState<ConstraintDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalCls, setModalCls] = useState<ColumnClass | null>(null);
  const [editingDef, setEditingDef] = useState<ConstraintDef | null>(null);

  const reload = useCallback(() => {
    setLoading(true);
    api
      .listDefs()
      .then(setDefs)
      .catch(() => message.error("제약 정의 목록을 불러오지 못했습니다"))
      .finally(() => setLoading(false));
  }, [message]);

  useEffect(() => {
    if (active) reload();
  }, [active, reload]);

  const defsByClass = useMemo(() => {
    const map = new Map<ColumnClass, ConstraintDef[]>();
    for (const cls of CLASS_ORDER) map.set(cls, []);
    for (const def of defs) map.get(def.cls)?.push(def);
    return map;
  }, [defs]);

  const handleDelete = async (def: ConstraintDef) => {
    try {
      await api.deleteDef(def.id);
      message.success("정의가 삭제되었습니다");
      reload();
    } catch (err) {
      // 매핑이 남아 있으면 409 {message} — 서버 사유 노출
      message.error(api.apiErrorMessage(err) ?? "정의 삭제에 실패했습니다");
    }
  };

  return (
    <div>
      <Space direction="vertical" style={{ width: "100%" }} size="large">
        {CLASS_ORDER.map((cls) => (
          <div key={cls}>
            <Space
              style={{ width: "100%", justifyContent: "space-between", marginBottom: 8 }}
            >
              <Typography.Title level={5} style={{ margin: 0 }}>
                {CLASS_LABEL[cls]} 타입
              </Typography.Title>
              <Button
                size="small"
                onClick={() => {
                  setEditingDef(null);
                  setModalCls(cls);
                }}
              >
                정의 추가
              </Button>
            </Space>
            <List
              size="small"
              bordered
              loading={loading}
              dataSource={defsByClass.get(cls) ?? []}
              locale={{ emptyText: "등록된 정의가 없습니다" }}
              renderItem={(def) => (
                <List.Item
                  actions={[
                    <Button
                      key="edit"
                      size="small"
                      onClick={() => {
                        setEditingDef(def);
                        setModalCls(cls);
                      }}
                    >
                      편집
                    </Button>,
                    <Popconfirm
                      key="delete"
                      title="정의 삭제"
                      description={`"${def.name}" 정의를 삭제할까요?`}
                      okText="삭제"
                      cancelText="취소"
                      onConfirm={() => handleDelete(def)}
                    >
                      <Button size="small" danger>
                        삭제
                      </Button>
                    </Popconfirm>,
                  ]}
                >
                  <Space wrap size="small">
                    <Tag color={KIND_META[def.kind].color}>{KIND_META[def.kind].label}</Tag>
                    <Typography.Text strong>{def.name}</Typography.Text>
                    {def.description && (
                      <Typography.Text type="secondary">{def.description}</Typography.Text>
                    )}
                    {def.expression && <span style={monoBlue}>{def.expression}</span>}
                    <Typography.Text type="secondary">
                      {def.mappingCount}개 컬럼 매핑
                    </Typography.Text>
                  </Space>
                </List.Item>
              )}
            />
          </div>
        ))}
      </Space>
      <DefModal
        cls={modalCls}
        editing={editingDef}
        onClose={() => setModalCls(null)}
        onSaved={reload}
      />
    </div>
  );
}

// ---------- 컬럼 속성(isPii/cls) 편집 모달 ----------

interface ColumnEditModalProps {
  table: CatalogTable;
  /** null이면 닫힘 */
  column: CatalogColumn | null;
  onClose: () => void;
  onSaved: () => void;
}

function ColumnEditModal({ table, column, onClose, onSaved }: ColumnEditModalProps) {
  const { message } = App.useApp();
  const [isPii, setIsPii] = useState(false);
  const [cls, setCls] = useState<ColumnClass>("STRING");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!column) return;
    setIsPii(column.isPii);
    setCls(column.cls);
  }, [column]);

  const handleOk = async () => {
    if (!column) return;
    setSaving(true);
    try {
      // PUT tables — 나머지 컬럼은 현재 저장값 그대로 보존해 전체 컬럼을 전송
      await api.updateTable(table.id, {
        name: table.name,
        description: table.description ?? "",
        columns: table.columns.map((c) =>
          c.id === column.id
            ? { name: c.name, type: c.type, isPii, cls }
            : { name: c.name, type: c.type, isPii: c.isPii, cls: c.cls },
        ),
      });
      message.success("컬럼 속성이 수정되었습니다");
      onSaved();
      onClose();
    } catch (err) {
      message.error(api.apiErrorMessage(err) ?? "컬럼 속성 수정에 실패했습니다");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={column !== null}
      title={column ? `컬럼 속성 — ${column.name}` : "컬럼 속성"}
      okText="저장"
      cancelText="취소"
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={saving}
      destroyOnHidden
    >
      <Space direction="vertical" style={{ width: "100%" }} size="middle">
        <Checkbox checked={isPii} onChange={(e) => setIsPii(e.target.checked)}>
          개인정보(PII) 컬럼
        </Checkbox>
        <div>
          <Typography.Text>클래스 (수동 override)</Typography.Text>
          <Select
            value={cls}
            onChange={setCls}
            style={{ width: "100%" }}
            options={CLASS_ORDER.map((c) => ({ value: c, label: CLASS_LABEL[c] }))}
          />
          <Typography.Text type="secondary">
            클래스를 바꿔도 기존 매핑은 삭제되지 않습니다 — 불일치 매핑은 경고로 표시되며 판정은
            계속 적용됩니다.
          </Typography.Text>
        </div>
      </Space>
    </Modal>
  );
}

// ---------- 매핑 모달 ----------

const mappingFormSchema = z
  .object({
    defId: z.string().min(1, "제약 정의를 선택하세요"),
    purposeCode: z.string().optional(),
    paramsJson: z.string().optional(),
  })
  .superRefine((values, ctx) => {
    const raw = values.paramsJson?.trim();
    if (raw) {
      try {
        JSON.parse(raw);
      } catch {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["paramsJson"],
          message: "유효한 JSON이 아닙니다",
        });
      }
    }
  });

type MappingFormValues = z.infer<typeof mappingFormSchema>;

interface MappingModalProps {
  /** null이면 닫힘 */
  column: CatalogColumn | null;
  purposes: Purpose[];
  onClose: () => void;
  onSaved: () => void;
}

function MappingModal({ column, purposes, onClose, onSaved }: MappingModalProps) {
  const { message } = App.useApp();
  const [defs, setDefs] = useState<ConstraintDef[]>([]);
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    control,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<MappingFormValues>({
    resolver: zodResolver(mappingFormSchema),
    defaultValues: { defId: "", purposeCode: undefined, paramsJson: "" },
  });

  useEffect(() => {
    if (!column) return;
    setServerError(null);
    reset({ defId: "", purposeCode: undefined, paramsJson: "" });
    api
      .listDefs()
      .then(setDefs)
      .catch(() => message.error("제약 정의 목록을 불러오지 못했습니다"));
  }, [column, reset, message]);

  const candidates = useMemo(
    () => (column ? defs.filter((d) => d.cls === column.cls) : []),
    [defs, column],
  );
  const defId = watch("defId");
  const selectedDef = candidates.find((d) => String(d.id) === defId);
  const isFilter = selectedDef?.kind === "FILTER";
  const hasParam = isFilter && (selectedDef?.expression ?? "").includes(":");

  const onSubmit = handleSubmit(async (values) => {
    if (!column || !selectedDef) return;
    setServerError(null);
    try {
      await api.createMapping({
        columnId: column.id,
        defId: selectedDef.id,
        purposeCode: isFilter && values.purposeCode ? values.purposeCode : undefined,
        paramsJson: hasParam && values.paramsJson?.trim() ? values.paramsJson.trim() : undefined,
      });
      message.success("매핑이 추가되었습니다");
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof api.ApiError && err.status === 409) {
        setServerError("이미 매핑되어 있습니다");
        return;
      }
      // 400: 클래스 불일치 · 판정 미지원 FILTER · params 검증 — 서버 사유 노출
      setServerError(api.apiErrorMessage(err) ?? "매핑 추가에 실패했습니다");
    }
  });

  return (
    <Modal
      open={column !== null}
      title={
        column ? (
          <span>
            <Typography.Text code>{column.name}</Typography.Text> 제약 매핑
          </span>
        ) : (
          "제약 매핑"
        )
      }
      okText="매핑"
      cancelText="취소"
      onCancel={onClose}
      onOk={onSubmit}
      confirmLoading={isSubmitting}
      destroyOnHidden
    >
      <Space direction="vertical" style={{ width: "100%" }} size="middle">
        {column && (
          <Typography.Text type="secondary">
            같은 클래스({CLASS_LABEL[column.cls]})에 등록된 제약 중에서 선택하세요
          </Typography.Text>
        )}
        <div>
          <Typography.Text>제약 정의 *</Typography.Text>
          <Controller
            name="defId"
            control={control}
            render={({ field }) => (
              <Select
                value={field.value || undefined}
                onChange={field.onChange}
                placeholder="제약 정의 선택"
                style={{ width: "100%" }}
                status={errors.defId ? "error" : undefined}
                options={candidates.map((d) => ({
                  value: String(d.id),
                  label: `${KIND_META[d.kind].label} — ${d.name}`,
                }))}
                notFoundContent="같은 클래스에 등록된 정의가 없습니다"
              />
            )}
          />
          {errors.defId && (
            <Typography.Text type="danger">{errors.defId.message}</Typography.Text>
          )}
        </div>
        {isFilter && (
          <div>
            <Typography.Text>적용 Purpose (선택)</Typography.Text>
            <Controller
              name="purposeCode"
              control={control}
              render={({ field }) => (
                <Select
                  allowClear
                  value={field.value ?? undefined}
                  onChange={field.onChange}
                  placeholder="항상 적용 (purpose 미지정)"
                  style={{ width: "100%" }}
                  options={purposes.map((p) => ({
                    value: p.code,
                    label: p.description ? `${p.code} — ${p.description}` : p.code,
                  }))}
                />
              )}
            />
          </div>
        )}
        {hasParam && (
          <div>
            <Typography.Text>파라미터 값 (JSON, 선택)</Typography.Text>
            <Controller
              name="paramsJson"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  placeholder={'예: {"param": "Y"}'}
                  style={{ fontFamily: "monospace" }}
                  status={errors.paramsJson ? "error" : undefined}
                />
              )}
            />
            {errors.paramsJson && (
              <Typography.Text type="danger">{errors.paramsJson.message}</Typography.Text>
            )}
          </div>
        )}
        {serverError && <Alert type="error" showIcon message={serverError} />}
      </Space>
    </Modal>
  );
}

// ---------- 컬럼 매핑 탭 ----------

interface MappingTabProps {
  active: boolean;
  purposes: Purpose[];
}

function MappingTab({ active, purposes }: MappingTabProps) {
  const { message } = App.useApp();
  const [tables, setTables] = useState<CatalogTable[]>([]);
  const [tablesLoading, setTablesLoading] = useState(false);
  const [selectedTableId, setSelectedTableId] = useState<string | null>(null);
  const [mappings, setMappings] = useState<ConstraintMapping[]>([]);
  const [mappingsLoading, setMappingsLoading] = useState(false);
  const [mappingTarget, setMappingTarget] = useState<CatalogColumn | null>(null);
  const [editTarget, setEditTarget] = useState<CatalogColumn | null>(null);

  const selectedTable = tables.find((t) => String(t.id) === selectedTableId) ?? null;

  const reloadTables = useCallback(() => {
    setTablesLoading(true);
    api
      .listTables()
      .then(setTables)
      .catch(() => message.error("테이블 목록을 불러오지 못했습니다"))
      .finally(() => setTablesLoading(false));
  }, [message]);

  const reloadMappings = useCallback(() => {
    if (!selectedTable) {
      setMappings([]);
      return;
    }
    setMappingsLoading(true);
    api
      .listMappings({ tableId: selectedTable.id })
      .then(setMappings)
      .catch(() => message.error("매핑 목록을 불러오지 못했습니다"))
      .finally(() => setMappingsLoading(false));
  }, [selectedTable, message]);

  useEffect(() => {
    if (active) reloadTables();
  }, [active, reloadTables]);

  useEffect(() => {
    reloadMappings();
  }, [reloadMappings]);

  const mappingsByColumn = useMemo(() => {
    const map = new Map<string, ConstraintMapping[]>();
    for (const m of mappings) {
      const key = String(m.columnId);
      const list = map.get(key);
      if (list) list.push(m);
      else map.set(key, [m]);
    }
    return map;
  }, [mappings]);

  const handleDeleteMapping = async (mapping: ConstraintMapping) => {
    try {
      await api.deleteMapping(mapping.id);
      message.success("매핑이 해제되었습니다");
      reloadMappings();
    } catch (err) {
      message.error(api.apiErrorMessage(err) ?? "매핑 해제에 실패했습니다");
    }
  };

  const columns: ColumnsType<CatalogColumn> = [
    {
      title: "컬럼",
      key: "name",
      render: (_, record) => (
        <Space size="small">
          <Typography.Text>{record.name}</Typography.Text>
          {record.isPii && <Tag color="red">PII</Tag>}
        </Space>
      ),
    },
    { title: "타입", dataIndex: "type", key: "type" },
    {
      title: "클래스",
      key: "cls",
      render: (_, record) => CLASS_LABEL[record.cls],
    },
    {
      title: "매핑된 제약",
      key: "mappings",
      render: (_, record) => {
        const list = mappingsByColumn.get(String(record.id)) ?? [];
        if (list.length === 0) return <Typography.Text type="secondary">-</Typography.Text>;
        return (
          <Space size={[4, 4]} wrap>
            {list.map((m) => {
              const chip = (
                <Tag
                  key={String(m.id)}
                  color={m.clsMismatch ? "warning" : KIND_META[m.defKind].color}
                  closable
                  onClose={(e) => {
                    e.preventDefault();
                    void handleDeleteMapping(m);
                  }}
                >
                  {m.defName} · {KIND_META[m.defKind].label}
                  {m.purposeCode ? ` · ${m.purposeCode}` : ""}
                </Tag>
              );
              return m.clsMismatch ? (
                <Tooltip
                  key={String(m.id)}
                  title="클래스 불일치 — 판정은 계속 적용됩니다"
                >
                  {chip}
                </Tooltip>
              ) : (
                chip
              );
            })}
          </Space>
        );
      },
    },
    {
      title: "동작",
      key: "actions",
      render: (_, record) => (
        <Space>
          <Button size="small" type="primary" ghost onClick={() => setMappingTarget(record)}>
            매핑
          </Button>
          <Button size="small" onClick={() => setEditTarget(record)}>
            속성
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text>테이블</Typography.Text>
        <Select
          value={selectedTableId ?? undefined}
          onChange={(v: string) => setSelectedTableId(v)}
          placeholder="테이블 선택"
          style={{ width: 320 }}
          loading={tablesLoading}
          options={tables.map((t) => ({
            value: String(t.id),
            label: t.description ? `${t.name} — ${t.description}` : t.name,
          }))}
        />
      </Space>
      {selectedTable ? (
        <Table
          rowKey={(record) => String(record.id)}
          columns={columns}
          dataSource={selectedTable.columns}
          loading={mappingsLoading}
          pagination={false}
          size="middle"
        />
      ) : (
        <Typography.Paragraph type="secondary">
          테이블을 선택하면 컬럼별 제약 매핑을 관리할 수 있습니다.
        </Typography.Paragraph>
      )}
      <MappingModal
        column={mappingTarget}
        purposes={purposes}
        onClose={() => setMappingTarget(null)}
        onSaved={reloadMappings}
      />
      {selectedTable && (
        <ColumnEditModal
          table={selectedTable}
          column={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={() => {
            reloadTables();
            reloadMappings();
          }}
        />
      )}
    </div>
  );
}

// ---------- Purpose 탭 ----------

const purposeFormSchema = z.object({
  code: z.string().min(1, "코드를 입력하세요").max(50, "50자 이하"),
  description: z.string(),
});

type PurposeFormValues = z.infer<typeof purposeFormSchema>;

interface PurposeTabProps {
  purposes: Purpose[];
  loading: boolean;
  reload: () => void;
}

function PurposeTab({ purposes, loading, reload }: PurposeTabProps) {
  const { message } = App.useApp();
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<PurposeFormValues>({
    resolver: zodResolver(purposeFormSchema),
    defaultValues: { code: "", description: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await api.createPurpose(values);
      message.success("Purpose가 추가되었습니다");
      reset({ code: "", description: "" });
      reload();
    } catch {
      message.error("Purpose 추가에 실패했습니다");
    }
  });

  const handleDelete = async (id: api.Id) => {
    try {
      await api.deletePurpose(id);
      message.success("Purpose가 삭제되었습니다");
      reload();
    } catch (err) {
      // 참조 매핑 존재 시 409 {message}
      message.error(api.apiErrorMessage(err) ?? "Purpose 삭제에 실패했습니다");
    }
  };

  const columns: ColumnsType<Purpose> = [
    { title: "코드", dataIndex: "code", key: "code" },
    {
      title: "설명",
      dataIndex: "description",
      key: "description",
      render: (value: string | null | undefined) => value || "-",
    },
    {
      title: "동작",
      key: "actions",
      render: (_, record) => (
        <Popconfirm
          title="Purpose 삭제"
          description={`"${record.code}"를 삭제할까요?`}
          okText="삭제"
          cancelText="취소"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button size="small" danger>
            삭제
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <Typography.Paragraph type="secondary">
        Purpose는 관리형 목록입니다 — 에디터에서는 여기 등록된 코드만 선택할 수 있습니다.
      </Typography.Paragraph>
      <form onSubmit={onSubmit}>
        <Space align="start" style={{ marginBottom: 16 }} wrap>
          <div>
            <Controller
              name="code"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  placeholder="코드 (예: MARKETING)"
                  style={{ width: 220 }}
                  status={errors.code ? "error" : undefined}
                />
              )}
            />
            {errors.code && (
              <div>
                <Typography.Text type="danger">{errors.code.message}</Typography.Text>
              </div>
            )}
          </div>
          <Controller
            name="description"
            control={control}
            render={({ field }) => (
              <Input {...field} placeholder="설명" style={{ width: 320 }} />
            )}
          />
          <Button type="primary" htmlType="submit" loading={isSubmitting}>
            추가
          </Button>
        </Space>
      </form>
      <Table
        rowKey={(record) => String(record.id)}
        columns={columns}
        dataSource={purposes}
        loading={loading}
        pagination={false}
      />
    </div>
  );
}

// ---------- 페이지 ----------

export default function CatalogPage() {
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState("defs");
  const [purposes, setPurposes] = useState<Purpose[]>([]);
  const [purposesLoading, setPurposesLoading] = useState(false);

  const reloadPurposes = useCallback(() => {
    setPurposesLoading(true);
    api
      .listPurposes()
      .then(setPurposes)
      .catch(() => message.error("Purpose 목록을 불러오지 못했습니다"))
      .finally(() => setPurposesLoading(false));
  }, [message]);

  useEffect(() => {
    reloadPurposes();
  }, [reloadPurposes]);

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        제약 카탈로그
      </Typography.Title>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: "defs",
            label: "제약 정의",
            children: <DefsTab active={activeTab === "defs"} />,
          },
          {
            key: "mappings",
            label: "컬럼 매핑",
            children: <MappingTab active={activeTab === "mappings"} purposes={purposes} />,
          },
          {
            key: "purposes",
            label: "Purpose",
            children: (
              <PurposeTab
                purposes={purposes}
                loading={purposesLoading}
                reload={reloadPurposes}
              />
            ),
          },
        ]}
      />
    </div>
  );
}
