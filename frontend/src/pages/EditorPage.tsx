import { useEffect, useMemo, useRef, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Typography,
} from "antd";
import CodeMirror from "@uiw/react-codemirror";
import { MySQL, sql } from "@codemirror/lang-sql";
import { useNavigate, useSearchParams } from "react-router-dom";
import * as api from "../api/client";
import type { LintReport, Purpose, SchemaDict } from "../api/client";

const formSchema = z.object({
  name: z
    .string()
    .min(1, "쿼리 이름을 입력하세요")
    .max(100, "쿼리 이름은 100자 이하여야 합니다"),
  sql: z.string().min(1, "SQL을 입력하세요"),
  purposeCode: z.string().optional(),
});

type FormValues = z.infer<typeof formSchema>;

export default function EditorPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const editingId = searchParams.get("id");

  const {
    control,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { name: "", sql: "", purposeCode: undefined },
  });

  const [purposes, setPurposes] = useState<Purpose[]>([]);
  const [schemaDict, setSchemaDict] = useState<SchemaDict>({});
  const [loadingQuery, setLoadingQuery] = useState(false);
  const [lintReport, setLintReport] = useState<LintReport | null>(null);
  const [linting, setLinting] = useState(false);
  const lintSeq = useRef(0);

  // purpose 목록 · 자동완성 스키마 사전 로드
  useEffect(() => {
    api
      .listPurposes()
      .then(setPurposes)
      .catch(() => message.error("Purpose 목록을 불러오지 못했습니다"));
    api
      .getSchemaDict()
      .then(setSchemaDict)
      .catch(() => message.error("자동완성 스키마를 불러오지 못했습니다"));
  }, [message]);

  // 수정 모드: 기존 쿼리 로드
  useEffect(() => {
    if (!editingId) {
      reset({ name: "", sql: "", purposeCode: undefined });
      setLintReport(null);
      return;
    }
    setLoadingQuery(true);
    api
      .getQuery(editingId)
      .then((query) => {
        reset({
          name: query.name,
          sql: query.sql,
          purposeCode: query.purposeCode ?? undefined,
        });
        setLintReport(query.lintReport ?? null);
      })
      .catch(() => message.error("쿼리를 불러오지 못했습니다"))
      .finally(() => setLoadingQuery(false));
  }, [editingId, reset, message]);

  const sqlValue = watch("sql");
  const purposeValue = watch("purposeCode");

  // 타이핑 멈춤 500ms 후 lint
  useEffect(() => {
    if (!sqlValue || !sqlValue.trim()) {
      setLintReport(null);
      setLinting(false);
      return;
    }
    const seq = ++lintSeq.current;
    const timer = setTimeout(() => {
      setLinting(true);
      api
        .lint({ dialect: "MYSQL", sql: sqlValue, purposeCode: purposeValue || undefined })
        .then((report) => {
          if (seq === lintSeq.current) setLintReport(report);
        })
        .catch(() => {
          // lint 실패(백엔드 미기동 등)는 조용히 무시 — 저장 게이트가 최종 방어선
        })
        .finally(() => {
          if (seq === lintSeq.current) setLinting(false);
        });
    }, 500);
    return () => clearTimeout(timer);
  }, [sqlValue, purposeValue]);

  const extensions = useMemo(
    () => [sql({ dialect: MySQL, schema: schemaDict, upperCaseKeywords: true })],
    [schemaDict],
  );

  const onSubmit = handleSubmit(async (values) => {
    const input = {
      name: values.name,
      dialect: "MYSQL" as const,
      sql: values.sql,
      purposeCode: values.purposeCode || undefined,
    };
    try {
      const result = editingId
        ? await api.updateQuery(editingId, input)
        : await api.createQuery(input);
      if (!result.ok) {
        setLintReport(result.report);
        message.error("차단됨 — BLOCK 위반으로 저장이 거부되었습니다");
        return;
      }
      const warnCount =
        result.query.lintReport?.violations.filter((v) => v.severity === "WARN").length ?? 0;
      if (warnCount > 0) {
        message.warning(`저장되었습니다 (경고 ${warnCount}건)`);
      } else {
        message.success("저장되었습니다");
      }
      navigate("/queries");
    } catch {
      message.error("저장 요청에 실패했습니다");
    }
  });

  const violations = lintReport?.violations ?? [];

  return (
    <Spin spinning={loadingQuery}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {editingId ? "쿼리 수정" : "쿼리 작성"}
      </Typography.Title>
      <Form layout="vertical" onSubmitCapture={onSubmit}>
        <Space wrap align="start" size="middle">
          <Form.Item label="방언">
            <Select
              value="MYSQL"
              disabled
              style={{ width: 130 }}
              options={[{ value: "MYSQL", label: "MySQL" }]}
            />
          </Form.Item>
          <Form.Item label="Purpose">
            <Controller
              name="purposeCode"
              control={control}
              render={({ field }) => (
                <Select
                  allowClear
                  placeholder="선택 안 함"
                  style={{ width: 220 }}
                  value={field.value ?? undefined}
                  onChange={(value) => field.onChange(value)}
                  options={purposes.map((p) => ({
                    value: p.code,
                    label: p.description ? `${p.code} — ${p.description}` : p.code,
                  }))}
                />
              )}
            />
          </Form.Item>
          <Form.Item
            label="쿼리 이름"
            validateStatus={errors.name ? "error" : undefined}
            help={errors.name?.message}
          >
            <Controller
              name="name"
              control={control}
              render={({ field }) => (
                <Input {...field} style={{ width: 280 }} placeholder="쿼리 이름" />
              )}
            />
          </Form.Item>
        </Space>
        <Form.Item
          label="SQL"
          validateStatus={errors.sql ? "error" : undefined}
          help={errors.sql?.message}
        >
          <Controller
            name="sql"
            control={control}
            render={({ field }) => (
              <CodeMirror
                value={field.value}
                height="360px"
                extensions={extensions}
                onChange={field.onChange}
                placeholder="SELECT ..."
              />
            )}
          />
        </Form.Item>

        <div style={{ marginBottom: 16 }}>
          {linting && (
            <Typography.Text type="secondary">
              <Spin size="small" style={{ marginRight: 8 }} />룰 검사 중...
            </Typography.Text>
          )}
          {!linting && lintReport && violations.length === 0 && (
            <Typography.Text type="success">✓ 룰 검사 통과</Typography.Text>
          )}
          {violations.length > 0 && (
            <Space direction="vertical" style={{ width: "100%" }} size="small">
              {violations.map((v, index) => (
                <Alert
                  key={`${v.ruleId}-${index}`}
                  type={v.severity === "BLOCK" ? "error" : "warning"}
                  showIcon
                  message={`[${v.ruleId}] ${v.message}`}
                />
              ))}
            </Space>
          )}
        </div>

        <Button type="primary" htmlType="submit" loading={isSubmitting}>
          저장
        </Button>
      </Form>
    </Spin>
  );
}
