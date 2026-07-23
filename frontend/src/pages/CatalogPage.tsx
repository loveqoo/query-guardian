import { useCallback, useEffect, useState } from "react";
import { Controller, useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  App,
  Button,
  Descriptions,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import * as api from "../api/client";
import type {
  CatalogColumn,
  CatalogConstraint,
  CatalogTable,
  Purpose,
} from "../api/client";

// ---------- 테이블 추가/편집 모달 ----------

const tableFormSchema = z.object({
  name: z.string().min(1, "테이블 이름을 입력하세요").max(100, "100자 이하"),
  description: z.string(),
  columns: z
    .array(
      z.object({
        name: z.string().min(1, "컬럼 이름 필수"),
        type: z.string().min(1, "타입 필수"),
      }),
    )
    .min(1, "컬럼을 1개 이상 등록하세요"),
});

type TableFormValues = z.infer<typeof tableFormSchema>;

interface TableModalProps {
  open: boolean;
  editing: CatalogTable | null;
  onClose: () => void;
  onSaved: () => void;
}

function TableModal({ open, editing, onClose, onSaved }: TableModalProps) {
  const { message } = App.useApp();
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<TableFormValues>({
    resolver: zodResolver(tableFormSchema),
    defaultValues: { name: "", description: "", columns: [{ name: "", type: "" }] },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "columns" });

  useEffect(() => {
    if (!open) return;
    if (editing) {
      reset({
        name: editing.name,
        description: editing.description ?? "",
        columns: editing.columns.map((c) => ({ name: c.name, type: c.type })),
      });
    } else {
      reset({ name: "", description: "", columns: [{ name: "", type: "" }] });
    }
  }, [open, editing, reset]);

  const onSubmit = handleSubmit(async (values) => {
    try {
      if (editing) {
        await api.updateTable(editing.id, values);
      } else {
        await api.createTable(values);
      }
      message.success(editing ? "테이블이 수정되었습니다" : "테이블이 추가되었습니다");
      onSaved();
      onClose();
    } catch {
      message.error("테이블 저장에 실패했습니다");
    }
  });

  return (
    <Modal
      open={open}
      title={editing ? "테이블 편집" : "테이블 추가"}
      okText="저장"
      cancelText="취소"
      onCancel={onClose}
      onOk={onSubmit}
      confirmLoading={isSubmitting}
      destroyOnHidden
    >
      <Space direction="vertical" style={{ width: "100%" }} size="middle">
        <div>
          <Typography.Text>테이블 이름</Typography.Text>
          <Controller
            name="name"
            control={control}
            render={({ field }) => (
              <Input {...field} placeholder="예: user_events" status={errors.name ? "error" : undefined} />
            )}
          />
          {errors.name && (
            <Typography.Text type="danger">{errors.name.message}</Typography.Text>
          )}
        </div>
        <div>
          <Typography.Text>설명</Typography.Text>
          <Controller
            name="description"
            control={control}
            render={({ field }) => <Input {...field} placeholder="테이블 설명" />}
          />
        </div>
        <div>
          <Typography.Text>컬럼</Typography.Text>
          <Space direction="vertical" style={{ width: "100%" }} size="small">
            {fields.map((item, index) => (
              <Space key={item.id} align="baseline" style={{ display: "flex" }}>
                <Controller
                  name={`columns.${index}.name`}
                  control={control}
                  render={({ field }) => (
                    <Input
                      {...field}
                      placeholder="컬럼 이름"
                      style={{ width: 180 }}
                      status={errors.columns?.[index]?.name ? "error" : undefined}
                    />
                  )}
                />
                <Controller
                  name={`columns.${index}.type`}
                  control={control}
                  render={({ field }) => (
                    <Input
                      {...field}
                      placeholder="타입 (예: VARCHAR(64))"
                      style={{ width: 180 }}
                      status={errors.columns?.[index]?.type ? "error" : undefined}
                    />
                  )}
                />
                <Button
                  size="small"
                  danger
                  disabled={fields.length <= 1}
                  onClick={() => remove(index)}
                >
                  삭제
                </Button>
              </Space>
            ))}
            <Button size="small" onClick={() => append({ name: "", type: "" })}>
              + 컬럼 추가
            </Button>
            {errors.columns?.root && (
              <Typography.Text type="danger">{errors.columns.root.message}</Typography.Text>
            )}
          </Space>
        </div>
      </Space>
    </Modal>
  );
}

// ---------- 제약 추가 모달 ----------

const constraintFormSchema = z
  .object({
    kind: z.enum(["PARTITION_KEY", "REQUIRED_PREDICATE"]),
    columnName: z.string().optional(),
    predicateSql: z.string().optional(),
    purposeCode: z.string().optional(),
  })
  .superRefine((values, ctx) => {
    if (values.kind === "PARTITION_KEY" && !values.columnName) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["columnName"],
        message: "파티션 키 컬럼을 선택하세요",
      });
    }
    if (values.kind === "REQUIRED_PREDICATE" && !values.predicateSql?.trim()) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["predicateSql"],
        message: "필수 술어 SQL을 입력하세요",
      });
    }
  });

type ConstraintFormValues = z.infer<typeof constraintFormSchema>;

interface ConstraintModalProps {
  table: CatalogTable | null;
  purposes: Purpose[];
  onClose: () => void;
  onSaved: () => void;
}

function ConstraintModal({ table, purposes, onClose, onSaved }: ConstraintModalProps) {
  const { message } = App.useApp();
  const {
    control,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ConstraintFormValues>({
    resolver: zodResolver(constraintFormSchema),
    defaultValues: { kind: "PARTITION_KEY", columnName: undefined, predicateSql: "", purposeCode: undefined },
  });
  const kind = watch("kind");

  useEffect(() => {
    if (table) {
      reset({
        kind: "PARTITION_KEY",
        columnName: undefined,
        predicateSql: "",
        purposeCode: undefined,
      });
    }
  }, [table, reset]);

  const onSubmit = handleSubmit(async (values) => {
    if (!table) return;
    const input: api.ConstraintInput =
      values.kind === "PARTITION_KEY"
        ? { kind: "PARTITION_KEY", columnName: values.columnName }
        : {
            kind: "REQUIRED_PREDICATE",
            predicateSql: values.predicateSql?.trim(),
            purposeCode: values.purposeCode || undefined,
          };
    try {
      await api.createConstraint(table.id, input);
      message.success("제약이 추가되었습니다");
      onSaved();
      onClose();
    } catch {
      message.error("제약 추가에 실패했습니다");
    }
  });

  return (
    <Modal
      open={table !== null}
      title={table ? `제약 추가 — ${table.name}` : "제약 추가"}
      okText="추가"
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
                options={[
                  { value: "PARTITION_KEY", label: "파티션 키 (PARTITION_KEY)" },
                  { value: "REQUIRED_PREDICATE", label: "필수 술어 (REQUIRED_PREDICATE)" },
                ]}
              />
            )}
          />
        </div>
        {kind === "PARTITION_KEY" && (
          <div>
            <Typography.Text>컬럼</Typography.Text>
            <Controller
              name="columnName"
              control={control}
              render={({ field }) => (
                <Select
                  value={field.value ?? undefined}
                  onChange={field.onChange}
                  placeholder="파티션 키 컬럼 선택"
                  style={{ width: "100%" }}
                  status={errors.columnName ? "error" : undefined}
                  options={(table?.columns ?? []).map((c) => ({
                    value: c.name,
                    label: `${c.name} (${c.type})`,
                  }))}
                />
              )}
            />
            {errors.columnName && (
              <Typography.Text type="danger">{errors.columnName.message}</Typography.Text>
            )}
          </div>
        )}
        {kind === "REQUIRED_PREDICATE" && (
          <>
            <div>
              <Typography.Text>필수 술어 SQL</Typography.Text>
              <Controller
                name="predicateSql"
                control={control}
                render={({ field }) => (
                  <Input
                    {...field}
                    placeholder="예: consent_yn = 'Y'"
                    status={errors.predicateSql ? "error" : undefined}
                  />
                )}
              />
              {errors.predicateSql && (
                <Typography.Text type="danger">{errors.predicateSql.message}</Typography.Text>
              )}
            </div>
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
          </>
        )}
      </Space>
    </Modal>
  );
}

// ---------- 테이블 탭 ----------

interface TablesTabProps {
  purposes: Purpose[];
}

function TablesTab({ purposes }: TablesTabProps) {
  const { message } = App.useApp();
  const [tables, setTables] = useState<CatalogTable[]>([]);
  const [loading, setLoading] = useState(false);
  const [tableModalOpen, setTableModalOpen] = useState(false);
  const [editingTable, setEditingTable] = useState<CatalogTable | null>(null);
  const [constraintTarget, setConstraintTarget] = useState<CatalogTable | null>(null);

  const reload = useCallback(() => {
    setLoading(true);
    api
      .listTables()
      .then(setTables)
      .catch(() => message.error("테이블 목록을 불러오지 못했습니다"))
      .finally(() => setLoading(false));
  }, [message]);

  useEffect(() => {
    reload();
  }, [reload]);

  const handleDeleteTable = async (id: api.Id) => {
    try {
      await api.deleteTable(id);
      message.success("테이블이 삭제되었습니다");
      reload();
    } catch {
      message.error("테이블 삭제에 실패했습니다");
    }
  };

  const handleDeleteConstraint = async (id: api.Id) => {
    try {
      await api.deleteConstraint(id);
      message.success("제약이 삭제되었습니다");
      reload();
    } catch {
      message.error("제약 삭제에 실패했습니다");
    }
  };

  const columns: ColumnsType<CatalogTable> = [
    { title: "테이블", dataIndex: "name", key: "name" },
    {
      title: "설명",
      dataIndex: "description",
      key: "description",
      render: (value: string | null | undefined) => value || "-",
    },
    {
      title: "컬럼 수",
      key: "columnCount",
      render: (_, record) => record.columns.length,
    },
    {
      title: "제약 수",
      key: "constraintCount",
      render: (_, record) => record.constraints.length,
    },
    {
      title: "동작",
      key: "actions",
      render: (_, record) => (
        <Space>
          <Button
            size="small"
            onClick={() => {
              setEditingTable(record);
              setTableModalOpen(true);
            }}
          >
            편집
          </Button>
          <Button size="small" onClick={() => setConstraintTarget(record)}>
            제약 추가
          </Button>
          <Popconfirm
            title="테이블 삭제"
            description={`"${record.name}" 테이블을 카탈로그에서 삭제할까요?`}
            okText="삭제"
            cancelText="취소"
            onConfirm={() => handleDeleteTable(record.id)}
          >
            <Button size="small" danger>
              삭제
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const constraintColumns: ColumnsType<CatalogConstraint> = [
    {
      title: "종류",
      dataIndex: "kind",
      key: "kind",
      render: (kind: CatalogConstraint["kind"]) => (
        <Tag color={kind === "PARTITION_KEY" ? "blue" : "purple"}>{kind}</Tag>
      ),
    },
    {
      title: "컬럼",
      dataIndex: "columnName",
      key: "columnName",
      render: (value: string | null | undefined) => value ?? "-",
    },
    {
      title: "필수 술어",
      dataIndex: "predicateSql",
      key: "predicateSql",
      render: (value: string | null | undefined) =>
        value ? <Typography.Text code>{value}</Typography.Text> : "-",
    },
    {
      title: "Purpose",
      dataIndex: "purposeCode",
      key: "purposeCode",
      render: (value: string | null | undefined) => value ?? "항상",
    },
    {
      title: "",
      key: "actions",
      render: (_, record) => (
        <Popconfirm
          title="제약 삭제"
          description="이 제약을 삭제할까요?"
          okText="삭제"
          cancelText="취소"
          onConfirm={() => handleDeleteConstraint(record.id)}
        >
          <Button size="small" danger>
            삭제
          </Button>
        </Popconfirm>
      ),
    },
  ];

  const columnColumns: ColumnsType<CatalogColumn> = [
    { title: "컬럼", dataIndex: "name", key: "name" },
    { title: "타입", dataIndex: "type", key: "type" },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          onClick={() => {
            setEditingTable(null);
            setTableModalOpen(true);
          }}
        >
          테이블 추가
        </Button>
      </Space>
      <Table
        rowKey={(record) => String(record.id)}
        columns={columns}
        dataSource={tables}
        loading={loading}
        pagination={false}
        expandable={{
          expandedRowRender: (record) => (
            <Space direction="vertical" style={{ width: "100%" }} size="middle">
              <Descriptions size="small" column={1} title="컬럼" />
              <Table
                rowKey={(c) => String(c.id)}
                columns={columnColumns}
                dataSource={record.columns}
                size="small"
                pagination={false}
              />
              <Descriptions size="small" column={1} title="제약" />
              <Table
                rowKey={(c) => String(c.id)}
                columns={constraintColumns}
                dataSource={record.constraints}
                size="small"
                pagination={false}
                locale={{ emptyText: "등록된 제약이 없습니다" }}
              />
            </Space>
          ),
        }}
      />
      <TableModal
        open={tableModalOpen}
        editing={editingTable}
        onClose={() => setTableModalOpen(false)}
        onSaved={reload}
      />
      <ConstraintModal
        table={constraintTarget}
        purposes={purposes}
        onClose={() => setConstraintTarget(null)}
        onSaved={reload}
      />
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
    } catch {
      message.error("Purpose 삭제에 실패했습니다");
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
        카탈로그 관리
      </Typography.Title>
      <Tabs
        defaultActiveKey="tables"
        items={[
          {
            key: "tables",
            label: "테이블",
            children: <TablesTab purposes={purposes} />,
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
