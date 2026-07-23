import { useCallback, useEffect, useState } from "react";
import { App, Button, Popconfirm, Space, Table, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useNavigate } from "react-router-dom";
import * as api from "../api/client";
import type { QueryListItem } from "../api/client";

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("ko-KR");
}

export default function QueriesPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [queries, setQueries] = useState<QueryListItem[]>([]);
  const [loading, setLoading] = useState(false);

  const reload = useCallback(() => {
    setLoading(true);
    api
      .listQueries()
      .then(setQueries)
      .catch(() => message.error("쿼리 목록을 불러오지 못했습니다"))
      .finally(() => setLoading(false));
  }, [message]);

  useEffect(() => {
    reload();
  }, [reload]);

  const handleDelete = async (id: api.Id) => {
    try {
      await api.deleteQuery(id);
      message.success("삭제되었습니다");
      reload();
    } catch {
      message.error("삭제에 실패했습니다");
    }
  };

  const columns: ColumnsType<QueryListItem> = [
    { title: "이름", dataIndex: "name", key: "name" },
    {
      title: "Purpose",
      dataIndex: "purposeCode",
      key: "purposeCode",
      render: (value: string | null | undefined) => value ?? "-",
    },
    {
      title: "생성일",
      dataIndex: "createdAt",
      key: "createdAt",
      render: formatDate,
    },
    {
      title: "수정일",
      dataIndex: "updatedAt",
      key: "updatedAt",
      render: formatDate,
    },
    {
      title: "동작",
      key: "actions",
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => navigate(`/editor?id=${record.id}`)}>
            열기
          </Button>
          <Popconfirm
            title="쿼리 삭제"
            description={`"${record.name}" 쿼리를 삭제할까요?`}
            okText="삭제"
            cancelText="취소"
            onConfirm={() => handleDelete(record.id)}
          >
            <Button size="small" danger>
              삭제
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        저장된 쿼리
      </Typography.Title>
      <Table
        rowKey={(record) => String(record.id)}
        columns={columns}
        dataSource={queries}
        loading={loading}
        pagination={{ pageSize: 20, hideOnSinglePage: true }}
      />
    </div>
  );
}
