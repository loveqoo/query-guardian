import { useEffect, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import {
  Alert,
  App,
  Button,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Switch,
  Tabs,
  Tag,
  Tooltip,
} from "antd";
import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import {
  ApiError,
  apiErrorMessage,
  createDef,
  createMapping,
  createPurpose,
  deleteDef,
  deleteMapping,
  deletePurpose,
  listDefs,
  listMappings,
  listPurposes,
  listTables,
  updateDef,
  updateTable,
} from "../api/client";
import type {
  CatalogColumn,
  CatalogTable,
  ColumnClass,
  ConstraintDef,
  ConstraintMapping,
  DefInput,
  DefKind,
  Id,
  MappingInput,
  Purpose,
  TableInput,
} from "../api/client";
import { MONO_FONT } from "../theme";
import { useAuth } from "../auth/AuthContext";
import StewardOnly from "../components/StewardOnly";
import {
  buildPreview,
  CLASS_LABEL,
  CLASS_ORDER,
  KIND_META,
  KIND_ORDER,
  SAMPLE_COL,
  kindColor,
  kindLabel,
  validateDefForm,
} from "./catalog/meta";
import type { DefFieldErrors, DefFormValues } from "./catalog/meta";

// ---------- shared styles ----------

const BORDER = "1px solid #f0f0f0";
const PRIMARY = "#1677ff";
const TEXT_TERTIARY = "rgba(0,0,0,.45)";
const TEXT_QUATERNARY = "rgba(0,0,0,.25)";

const CARD: CSSProperties = {
  background: "#fff",
  border: BORDER,
  borderRadius: 8,
  overflow: "hidden",
};

/** default kind offered when adding a new def in a given class. */
const DEFAULT_KIND: Record<ColumnClass, DefKind> = {
  PII: "MASK",
  BOOLEAN: "FILTER",
  DATETIME: "FILTER",
  NUMERIC: "FILTER",
  KEY: "JOIN",
  STRING: "FILTER",
};

// ============================================================================
// Page
// ============================================================================

export default function CatalogPage() {
  const { message } = App.useApp();
  /**
   * 카탈로그 조회·변경은 STEWARD/ADMIN 전용 (spec 007 §5·§6.2 — ANALYST는 목록 API가 403).
   * 403 토스트 대신 안내 화면을 띄우고 **요청 자체를 보내지 않는다**.
   */
  const { isSteward, user } = useAuth();
  const sessionKey = isSteward ? (user?.id ?? "") : "";

  const [tab, setTab] = useState<"defs" | "mappings" | "purposes">("defs");
  const [defs, setDefs] = useState<ConstraintDef[]>([]);
  const [tables, setTables] = useState<CatalogTable[]>([]);
  const [purposes, setPurposes] = useState<Purpose[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // mapping tab
  const [tableId, setTableId] = useState<Id | null>(null);
  const [mappings, setMappings] = useState<ConstraintMapping[]>([]);
  const [mapLoading, setMapLoading] = useState(false);

  // modals
  const [defModal, setDefModal] = useState<{
    editing: ConstraintDef | null;
    initial: DefFormValues;
  } | null>(null);
  const [mapColumn, setMapColumn] = useState<CatalogColumn | null>(null);
  const [attrColumn, setAttrColumn] = useState<CatalogColumn | null>(null);

  const selectedTable = tables.find((t) => t.id === tableId) ?? null;

  // ---- loads ----
  useEffect(() => {
    if (!sessionKey) {
      setLoading(false);
      return;
    }
    let live = true;
    (async () => {
      setLoading(true);
      setLoadError(null);
      try {
        const [d, t, p] = await Promise.all([listDefs(), listTables(), listPurposes()]);
        if (!live) return;
        setDefs(d);
        setTables(t);
        setPurposes(p);
        setTableId((prev) => prev ?? t[0]?.id ?? null);
      } catch (e) {
        if (live) setLoadError(apiErrorMessage(e) ?? "카탈로그를 불러오지 못했습니다.");
      } finally {
        if (live) setLoading(false);
      }
    })();
    return () => {
      live = false;
    };
  }, [sessionKey]);

  useEffect(() => {
    if (tableId == null) {
      setMappings([]);
      return;
    }
    let live = true;
    setMapLoading(true);
    (async () => {
      try {
        const m = await listMappings({ tableId });
        if (live) setMappings(m);
      } catch (e) {
        if (live) message.error(apiErrorMessage(e) ?? "매핑을 불러오지 못했습니다.");
      } finally {
        if (live) setMapLoading(false);
      }
    })();
    return () => {
      live = false;
    };
  }, [tableId, message]);

  async function reloadDefs() {
    try {
      setDefs(await listDefs());
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "정의를 다시 불러오지 못했습니다.");
    }
  }

  async function reloadMappings() {
    if (tableId == null) return;
    try {
      setMappings(await listMappings({ tableId }));
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "매핑을 다시 불러오지 못했습니다.");
    }
  }

  async function reloadTables() {
    try {
      setTables(await listTables());
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "테이블을 다시 불러오지 못했습니다.");
    }
  }

  // ---- def actions ----
  function openDefModal(cls: ColumnClass, editing?: ConstraintDef) {
    setDefModal({
      editing: editing ?? null,
      initial: editing
        ? {
            cls: editing.cls,
            kind: editing.kind,
            name: editing.name,
            expression: editing.expression ?? "",
            description: editing.description ?? "",
          }
        : {
            cls,
            kind: DEFAULT_KIND[cls],
            name: "",
            expression: "",
            description: "",
          },
    });
  }

  async function handleDeleteDef(def: ConstraintDef) {
    try {
      await deleteDef(def.id);
      message.success("정의를 삭제했습니다.");
      await reloadDefs();
    } catch (e) {
      const server = apiErrorMessage(e);
      if (e instanceof ApiError && e.status === 409) {
        message.error(server ?? "매핑이 남아 있어 삭제할 수 없습니다. 먼저 매핑을 해제하세요.");
      } else {
        message.error(server ?? "삭제에 실패했습니다.");
      }
    }
  }

  // ---- mapping actions ----
  async function handleDeleteMapping(m: ConstraintMapping) {
    try {
      await deleteMapping(m.id);
      message.success("매핑을 해제했습니다.");
      await Promise.all([reloadMappings(), reloadDefs()]);
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "매핑 해제에 실패했습니다.");
    }
  }

  // ---- render ----
  if (!isSteward) return <StewardOnly what="제약 카탈로그" />;
  if (loading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }
  if (loadError) {
    return <Alert type="error" showIcon message={loadError} style={{ maxWidth: 1040 }} />;
  }

  return (
    <div style={{ maxWidth: 1040 }}>
      <Tabs
        activeKey={tab}
        onChange={(k) => setTab(k as typeof tab)}
        items={[
          { key: "defs", label: "제약 정의" },
          { key: "mappings", label: "컬럼 매핑" },
          { key: "purposes", label: "목적(Purpose)" },
        ]}
      />

      {tab === "defs" && (
        <DefsTab defs={defs} onAdd={openDefModal} onEdit={openDefModal} onDelete={handleDeleteDef} />
      )}

      {tab === "mappings" && (
        <MappingsTab
          tables={tables}
          tableId={tableId}
          onTableChange={setTableId}
          table={selectedTable}
          mappings={mappings}
          loading={mapLoading}
          onRemoveMapping={handleDeleteMapping}
          onOpenMap={setMapColumn}
          onOpenAttr={setAttrColumn}
        />
      )}

      {tab === "purposes" && (
        <PurposesTab
          purposes={purposes}
          onChanged={async () => setPurposes(await listPurposes())}
        />
      )}

      {defModal && (
        <DefModal
          editing={defModal.editing}
          initial={defModal.initial}
          onClose={() => setDefModal(null)}
          onSaved={async () => {
            setDefModal(null);
            await reloadDefs();
          }}
        />
      )}

      {mapColumn && (
        <MapModal
          column={mapColumn}
          defs={defs}
          purposes={purposes}
          onClose={() => setMapColumn(null)}
          onSaved={async () => {
            setMapColumn(null);
            await Promise.all([reloadMappings(), reloadDefs()]);
          }}
        />
      )}

      {attrColumn && selectedTable && (
        <AttrModal
          table={selectedTable}
          column={attrColumn}
          onClose={() => setAttrColumn(null)}
          onSaved={async () => {
            setAttrColumn(null);
            await Promise.all([reloadTables(), reloadMappings()]);
          }}
        />
      )}
    </div>
  );
}

// ============================================================================
// 제약 정의 탭
// ============================================================================

function DefsTab({
  defs,
  onAdd,
  onEdit,
  onDelete,
}: {
  defs: ConstraintDef[];
  onAdd: (cls: ColumnClass) => void;
  onEdit: (cls: ColumnClass, def: ConstraintDef) => void;
  onDelete: (def: ConstraintDef) => void;
}) {
  return (
    <>
      <div style={{ fontSize: 13, color: "rgba(0,0,0,.65)", marginBottom: 16 }}>
        컬럼 타입(class)별로 제약 사항을 정의합니다. 하나의 타입에 여러 제약을 등록할 수 있고, 각
        테이블 컬럼에 매핑됩니다.
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {CLASS_ORDER.map((cls) => {
          const list = defs.filter((d) => d.cls === cls);
          return (
            <div key={cls} style={CARD}>
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  padding: "12px 16px",
                  borderBottom: BORDER,
                  background: "#fafafa",
                }}
              >
                <span style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 600, fontSize: 14 }}>
                  {CLASS_LABEL[cls]}
                  <span style={{ fontSize: 12, color: TEXT_TERTIARY, fontWeight: 400 }}>타입</span>
                </span>
                <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => onAdd(cls)}>
                  정의 추가
                </Button>
              </div>
              <div>
                {list.length === 0 ? (
                  <div style={{ padding: "16px", fontSize: 12, color: TEXT_QUATERNARY }}>
                    등록된 제약이 없습니다.
                  </div>
                ) : (
                  list.map((d) => (
                    <div
                      key={String(d.id)}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 12,
                        padding: "12px 16px",
                        borderBottom: "1px solid #f5f5f5",
                      }}
                    >
                      <span style={{ width: 56, flex: "none" }}>
                        <Tag color={kindColor(d.kind)} style={{ marginInlineEnd: 0 }}>
                          {kindLabel(d.kind)}
                        </Tag>
                      </span>
                      <span style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ fontSize: 14, fontWeight: 500 }}>{d.name}</div>
                        {d.description && (
                          <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginTop: 2 }}>
                            {d.description}
                          </div>
                        )}
                        <div
                          style={{
                            fontFamily: MONO_FONT,
                            fontSize: 11,
                            color: PRIMARY,
                            marginTop: 4,
                            whiteSpace: "nowrap",
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                          }}
                        >
                          {buildPreview(d.kind, d.expression ?? "", "{col}")}
                        </div>
                      </span>
                      <span style={{ fontSize: 11, color: TEXT_TERTIARY, flex: "none" }}>
                        {d.mappingCount}개 컬럼 매핑
                      </span>
                      <Button
                        type="text"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => onEdit(d.cls, d)}
                      />
                      <Popconfirm
                        title="이 정의를 삭제할까요?"
                        okText="삭제"
                        cancelText="취소"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => onDelete(d)}
                      >
                        <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    </div>
                  ))
                )}
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}

// ============================================================================
// 정의 모달
// ============================================================================

function DefModal({
  editing,
  initial,
  onClose,
  onSaved,
}: {
  editing: ConstraintDef | null;
  initial: DefFormValues;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [values, setValues] = useState<DefFormValues>(initial);
  const [errors, setErrors] = useState<DefFieldErrors>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const meta = KIND_META[values.kind];

  function set<K extends keyof DefFormValues>(key: K, val: DefFormValues[K]) {
    setValues((v) => ({ ...v, [key]: val }));
  }

  async function save() {
    const found = validateDefForm(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: DefInput = {
      cls: values.cls,
      kind: values.kind,
      name: values.name.trim(),
      description: values.description.trim(),
      expression: meta.hasExpr ? values.expression.trim() || undefined : undefined,
    };
    setSaving(true);
    setServerError(null);
    try {
      if (editing) await updateDef(editing.id, input);
      else await createDef(input);
      message.success(editing ? "정의를 수정했습니다." : "정의를 추가했습니다.");
      onSaved();
    } catch (e) {
      const server = apiErrorMessage(e);
      if (e instanceof ApiError && e.status === 400) {
        setServerError(server ?? "입력값을 확인하세요.");
      } else {
        message.error(server ?? "저장에 실패했습니다.");
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      open
      title={
        <span style={{ display: "flex", alignItems: "center", gap: 10 }}>
          제약 정의
          <Tag color="geekblue" style={{ marginInlineEnd: 0 }}>
            {CLASS_LABEL[values.cls]}
          </Tag>
        </span>
      }
      width={460}
      onCancel={onClose}
      okText="저장"
      cancelText="취소"
      confirmLoading={saving}
      okButtonProps={{ icon: <CheckOutlined /> }}
      onOk={save}
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16, paddingTop: 8 }}>
        <Field label="강제 방식 (kind)">
          <Select
            style={{ width: "100%" }}
            value={values.kind}
            onChange={(k) => set("kind", k)}
            options={KIND_ORDER.map((k) => ({ value: k, label: kindLabel(k) }))}
          />
          <Hint>엔진 동작: {meta.action}</Hint>
        </Field>

        <Field label="제약 이름" required error={errors.name}>
          <Input
            value={values.name}
            onChange={(e) => set("name", e.target.value)}
            placeholder="예: 전화번호 마스킹 (010-****-1234)"
            status={errors.name ? "error" : undefined}
          />
        </Field>

        {meta.hasExpr && (
          <Field label="강제식 (expression)" error={errors.expression}>
            <Input
              value={values.expression}
              onChange={(e) => set("expression", e.target.value)}
              placeholder="{col} >= NOW() - INTERVAL 90 DAY"
              status={errors.expression ? "error" : undefined}
            />
            <Hint>
              대상 컬럼은 <code style={{ fontFamily: MONO_FONT }}>{"{col}"}</code>, 입력값은{" "}
              <code style={{ fontFamily: MONO_FONT }}>:param</code> 으로 표기
            </Hint>
          </Field>
        )}

        <Field label="실행 미리보기 (IR → SQL)">
          <div
            style={{
              background: "#0b1220",
              borderRadius: 6,
              padding: "12px 14px",
              fontFamily: MONO_FONT,
              fontSize: 12,
              lineHeight: 1.6,
              color: "#e6edf3",
              whiteSpace: "pre-wrap",
              overflowWrap: "break-word",
            }}
          >
            {buildPreview(values.kind, values.expression, SAMPLE_COL[values.cls])}
          </div>
        </Field>

        <Field label="설명">
          <Input
            value={values.description}
            onChange={(e) => set("description", e.target.value)}
            placeholder="제약의 동작을 간단히 설명"
          />
        </Field>

        {serverError && <Alert type="error" showIcon message={serverError} />}
      </div>
    </Modal>
  );
}

// ============================================================================
// 컬럼 매핑 탭
// ============================================================================

function MappingsTab({
  tables,
  tableId,
  onTableChange,
  table,
  mappings,
  loading,
  onRemoveMapping,
  onOpenMap,
  onOpenAttr,
}: {
  tables: CatalogTable[];
  tableId: Id | null;
  onTableChange: (id: Id) => void;
  table: CatalogTable | null;
  mappings: ConstraintMapping[];
  loading: boolean;
  onRemoveMapping: (m: ConstraintMapping) => void;
  onOpenMap: (col: CatalogColumn) => void;
  onOpenAttr: (col: CatalogColumn) => void;
}) {
  if (tables.length === 0) {
    return (
      <div style={{ ...CARD, padding: 48 }}>
        <Empty description="등록된 테이블이 없습니다." />
      </div>
    );
  }

  const GRID = "1.1fr 0.9fr 2fr 150px";

  return (
    <>
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 16 }}>
        <div style={{ width: 240 }}>
          <Select
            style={{ width: "100%" }}
            value={tableId ?? undefined}
            onChange={onTableChange}
            placeholder="테이블 선택"
            options={tables.map((t) => ({ value: t.id, label: t.name }))}
          />
        </div>
      </div>

      <div style={CARD}>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: GRID,
            padding: "12px 20px",
            borderBottom: BORDER,
            fontSize: 12,
            color: TEXT_TERTIARY,
            fontWeight: 500,
          }}
        >
          <span>컬럼</span>
          <span>타입</span>
          <span>매핑된 제약</span>
          <span />
        </div>

        {loading ? (
          <div style={{ padding: 40, textAlign: "center" }}>
            <Spin />
          </div>
        ) : !table ? (
          <div style={{ padding: 40 }}>
            <Empty description="테이블을 선택하세요." />
          </div>
        ) : table.columns.length === 0 ? (
          <div style={{ padding: 40 }}>
            <Empty description="컬럼이 없습니다." />
          </div>
        ) : (
          table.columns.map((col) => {
            const colMappings = mappings.filter((m) => m.columnId === col.id);
            return (
              <div
                key={String(col.id)}
                style={{
                  display: "grid",
                  gridTemplateColumns: GRID,
                  padding: "12px 20px",
                  borderBottom: "1px solid #f5f5f5",
                  alignItems: "center",
                }}
              >
                <span
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 6,
                    fontFamily: MONO_FONT,
                    fontSize: 13,
                  }}
                >
                  {col.name}
                  {col.isPii && (
                    <Tag color="red" style={{ marginInlineEnd: 0 }}>
                      PII
                    </Tag>
                  )}
                </span>
                <span style={{ fontSize: 12, color: "rgba(0,0,0,.65)" }}>
                  <div>{CLASS_LABEL[col.cls]}</div>
                  <div style={{ fontFamily: MONO_FONT, fontSize: 11, color: TEXT_TERTIARY }}>
                    {col.type}
                  </div>
                </span>
                <span style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                  {colMappings.length === 0 ? (
                    <span style={{ fontSize: 12, color: TEXT_QUATERNARY }}>매핑 없음</span>
                  ) : (
                    colMappings.map((m) => (
                      <MappingChip key={String(m.id)} m={m} onRemove={() => onRemoveMapping(m)} />
                    ))
                  )}
                </span>
                <span style={{ textAlign: "right", display: "flex", gap: 6, justifyContent: "flex-end" }}>
                  <Button size="small" onClick={() => onOpenAttr(col)}>
                    속성
                  </Button>
                  <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={() => onOpenMap(col)}>
                    매핑
                  </Button>
                </span>
              </div>
            );
          })
        )}
      </div>
    </>
  );
}

function MappingChip({ m, onRemove }: { m: ConstraintMapping; onRemove: () => void }) {
  const mismatch = m.clsMismatch;
  const chip = (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        background: mismatch ? "#fffbe6" : "#e6f4ff",
        border: `1px solid ${mismatch ? "#ffe58f" : "#91caff"}`,
        borderRadius: 100,
        padding: "2px 8px 2px 10px",
        fontSize: 12,
        color: mismatch ? "#d48806" : PRIMARY,
      }}
    >
      <span>{m.defName}</span>
      <span style={{ fontSize: 10, opacity: 0.7 }}>{kindLabel(m.defKind)}</span>
      <CloseOutlined
        onClick={onRemove}
        style={{ cursor: "pointer", fontSize: 10, color: mismatch ? "#d48806" : PRIMARY }}
      />
    </span>
  );
  return mismatch ? (
    <Tooltip title="클래스 불일치 — 판정은 계속 적용됩니다">{chip}</Tooltip>
  ) : (
    chip
  );
}

// ============================================================================
// 매핑 모달
// ============================================================================

function MapModal({
  column,
  defs,
  purposes,
  onClose,
  onSaved,
}: {
  column: CatalogColumn;
  defs: ConstraintDef[];
  purposes: Purpose[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { message } = App.useApp();
  const candidates = defs.filter((d) => d.cls === column.cls);
  const [defId, setDefId] = useState<Id | null>(null);
  const [purposeCode, setPurposeCode] = useState<string | undefined>(undefined);
  const [paramsText, setParamsText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const selectedDef = candidates.find((d) => d.id === defId) ?? null;
  const isFilter = selectedDef?.kind === "FILTER";
  const hasParams = Boolean(selectedDef?.expression?.includes(":"));

  async function save() {
    if (defId == null) {
      setError("매핑할 제약을 선택하세요.");
      return;
    }
    let paramsJson: string | undefined;
    if (isFilter && hasParams && paramsText.trim()) {
      try {
        JSON.parse(paramsText);
        paramsJson = paramsText.trim();
      } catch {
        setError("params JSON 형식이 올바르지 않습니다.");
        return;
      }
    }
    const input: MappingInput = {
      columnId: column.id,
      defId,
      purposeCode: isFilter ? purposeCode || undefined : undefined,
      paramsJson,
    };
    setSaving(true);
    setError(null);
    try {
      await createMapping(input);
      message.success("제약을 매핑했습니다.");
      onSaved();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError("이미 매핑되어 있습니다.");
      } else {
        setError(apiErrorMessage(e) ?? "매핑에 실패했습니다.");
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      open
      width={480}
      onCancel={onClose}
      okText="매핑 저장"
      cancelText="취소"
      confirmLoading={saving}
      okButtonProps={{ icon: <CheckOutlined />, disabled: candidates.length === 0 }}
      onOk={save}
      title={
        <div>
          <div style={{ fontSize: 15, fontWeight: 600, display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontFamily: MONO_FONT }}>{column.name}</span> 제약 매핑
          </div>
          <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginTop: 4, fontWeight: 400 }}>
            같은 클래스({CLASS_LABEL[column.cls]})에 등록된 제약 중에서 선택하세요
          </div>
        </div>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16, paddingTop: 8 }}>
        {candidates.length === 0 ? (
          <Alert
            type="info"
            showIcon
            message={`${CLASS_LABEL[column.cls]} 클래스에 등록된 제약 정의가 없습니다.`}
            description="제약 정의 탭에서 먼저 정의를 추가하세요."
          />
        ) : (
          <>
            <Field label="제약 정의">
              <Select
                style={{ width: "100%" }}
                value={defId ?? undefined}
                onChange={(v) => {
                  setDefId(v);
                  setError(null);
                }}
                placeholder="매핑할 제약 선택"
                options={candidates.map((d) => ({
                  value: d.id,
                  label: `${kindLabel(d.kind)} · ${d.name}`,
                }))}
              />
              {selectedDef?.expression && (
                <div
                  style={{
                    fontFamily: MONO_FONT,
                    fontSize: 11,
                    color: PRIMARY,
                    marginTop: 6,
                  }}
                >
                  {selectedDef.expression}
                </div>
              )}
            </Field>

            {isFilter && (
              <Field label="목적 (purpose) — 선택">
                <Select
                  style={{ width: "100%" }}
                  allowClear
                  value={purposeCode}
                  onChange={(v) => setPurposeCode(v)}
                  placeholder="조건부 적용 목적 (선택)"
                  options={purposes.map((p) => ({ value: p.code, label: p.code }))}
                />
              </Field>
            )}

            {isFilter && hasParams && (
              <Field label="파라미터 (params JSON) — 선택">
                <Input.TextArea
                  rows={3}
                  value={paramsText}
                  onChange={(e) => setParamsText(e.target.value)}
                  placeholder={'{ "start": "2026-01-01", "end": "2026-12-31" }'}
                  style={{ fontFamily: MONO_FONT, fontSize: 12 }}
                />
              </Field>
            )}
          </>
        )}

        {error && <Alert type="error" showIcon message={error} />}
      </div>
    </Modal>
  );
}

// ============================================================================
// 컬럼 속성 모달 (is_pii / cls override)
// ============================================================================

function AttrModal({
  table,
  column,
  onClose,
  onSaved,
}: {
  table: CatalogTable;
  column: CatalogColumn;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [isPii, setIsPii] = useState(column.isPii);
  const [cls, setCls] = useState<ColumnClass>(column.cls);
  const [saving, setSaving] = useState(false);

  async function save() {
    const input: TableInput = {
      name: table.name,
      description: table.description ?? "",
      columns: table.columns.map((c) =>
        c.id === column.id
          ? { name: c.name, type: c.type, isPii, cls }
          : { name: c.name, type: c.type, isPii: c.isPii, cls: c.cls },
      ),
    };
    setSaving(true);
    try {
      await updateTable(table.id, input);
      message.success("컬럼 속성을 수정했습니다.");
      onSaved();
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "속성 수정에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      open
      width={400}
      onCancel={onClose}
      okText="저장"
      cancelText="취소"
      confirmLoading={saving}
      onOk={save}
      title={
        <span>
          <span style={{ fontFamily: MONO_FONT }}>{column.name}</span> 속성
        </span>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16, paddingTop: 8 }}>
        <Field label="개인정보 (is_pii)">
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <Switch checked={isPii} onChange={setIsPii} />
            <span style={{ fontSize: 13, color: "rgba(0,0,0,.65)" }}>
              PII로 표시하면 클래스가 개인정보(PII)로 판별됩니다.
            </span>
          </div>
        </Field>
        <Field label="컬럼 클래스 (cls override)">
          <Select
            style={{ width: "100%" }}
            value={cls}
            onChange={setCls}
            options={CLASS_ORDER.map((c) => ({ value: c, label: CLASS_LABEL[c] }))}
          />
          <Hint>클래스·PII를 바꿔도 기존 매핑은 유지되며, 불일치 매핑은 경고로 표시됩니다.</Hint>
        </Field>
      </div>
    </Modal>
  );
}

// ============================================================================
// 목적(Purpose) 탭
// ============================================================================

function PurposesTab({
  purposes,
  onChanged,
}: {
  purposes: Purpose[];
  onChanged: () => Promise<void>;
}) {
  const { message } = App.useApp();
  const [code, setCode] = useState("");
  const [desc, setDesc] = useState("");
  const [adding, setAdding] = useState(false);

  async function add() {
    if (!code.trim()) {
      message.warning("목적 코드를 입력하세요.");
      return;
    }
    setAdding(true);
    try {
      await createPurpose({ code: code.trim(), description: desc.trim() });
      message.success("목적을 추가했습니다.");
      setCode("");
      setDesc("");
      await onChanged();
    } catch (e) {
      message.error(apiErrorMessage(e) ?? "추가에 실패했습니다.");
    } finally {
      setAdding(false);
    }
  }

  async function remove(p: Purpose) {
    try {
      await deletePurpose(p.id);
      message.success("목적을 삭제했습니다.");
      await onChanged();
    } catch (e) {
      const server = apiErrorMessage(e);
      if (e instanceof ApiError && e.status === 409) {
        message.error(server ?? "참조하는 매핑이 있어 삭제할 수 없습니다.");
      } else {
        message.error(server ?? "삭제에 실패했습니다.");
      }
    }
  }

  return (
    <>
      <div style={{ fontSize: 13, color: "rgba(0,0,0,.65)", marginBottom: 16 }}>
        FILTER 제약을 조건부로 적용할 목적(purpose)을 관리합니다. 목적은 쿼리 실행 시 지정됩니다.
      </div>
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 16 }}>
        <div style={{ width: 200 }}>
          <Input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="목적 코드 (예: marketing)"
            style={{ fontFamily: MONO_FONT }}
          />
        </div>
        <div className="qg-shrink-mobile" style={{ flex: 1, minWidth: 200 }}>
          <Input value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="설명" />
        </div>
        <Button type="primary" icon={<PlusOutlined />} loading={adding} onClick={add}>
          목적 추가
        </Button>
      </div>
      <div style={CARD}>
        {purposes.length === 0 ? (
          <div style={{ padding: 40 }}>
            <Empty description="등록된 목적이 없습니다." />
          </div>
        ) : (
          purposes.map((p) => (
            <div
              key={String(p.id)}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 12,
                padding: "12px 20px",
                borderBottom: "1px solid #f5f5f5",
              }}
            >
              <span style={{ fontFamily: MONO_FONT, fontSize: 13, fontWeight: 500, width: 200, flex: "none" }}>
                {p.code}
              </span>
              <span style={{ flex: 1, minWidth: 0, fontSize: 13, color: "rgba(0,0,0,.65)" }}>
                {p.description || "—"}
              </span>
              <Popconfirm
                title="이 목적을 삭제할까요?"
                okText="삭제"
                cancelText="취소"
                okButtonProps={{ danger: true }}
                onConfirm={() => remove(p)}
              >
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            </div>
          ))
        )}
      </div>
    </>
  );
}

// ============================================================================
// small building blocks
// ============================================================================

function Field({
  label,
  required,
  error,
  children,
}: {
  label: string;
  required?: boolean;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div>
      <label style={{ display: "block", fontSize: 13, fontWeight: 500, marginBottom: 8 }}>
        {label}
        {required && <span style={{ color: "#ff4d4f" }}> *</span>}
      </label>
      {children}
      {error && <div style={{ fontSize: 12, color: "#ff4d4f", marginTop: 6 }}>{error}</div>}
    </div>
  );
}

function Hint({ children }: { children: ReactNode }) {
  return <div style={{ fontSize: 12, color: TEXT_TERTIARY, marginTop: 6 }}>{children}</div>;
}
