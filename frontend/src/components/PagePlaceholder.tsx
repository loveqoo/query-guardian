import { Empty, Typography } from "antd";

/** Stub page shown until each screen is implemented in a later step. */
export default function PagePlaceholder({ title }: { title: string }) {
  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {title}
      </Typography.Title>
      <div
        style={{
          background: "#fff",
          border: "1px solid #f0f0f0",
          borderRadius: 8,
          padding: 48,
        }}
      >
        <Empty description="이 화면은 다음 단계에서 구현됩니다" />
      </div>
    </div>
  );
}
