import { Result, Tag } from "antd";
import { LockOutlined } from "@ant-design/icons";
import { ROLE_LABEL } from "../auth/demoUsers";
import { useAuth } from "../auth/AuthContext";

/**
 * 역할 부족 화면 (spec 007 §5·§8). 카탈로그·규칙 조회는 STEWARD/ADMIN 전용이라 ANALYST에게는
 * 목록 API가 403을 준다 — 에러 토스트 대신 이 안내를 보여준다.
 */
export default function StewardOnly({ what = "이 화면" }: { what?: string }) {
  const { role } = useAuth();
  return (
    <Result
      icon={<LockOutlined style={{ color: "rgba(0,0,0,.25)" }} />}
      title={`${what}은 STEWARD 이상만 조회할 수 있습니다`}
      subTitle={
        <span>
          현재 역할{" "}
          <Tag color="blue" style={{ margin: 0 }}>
            {role ? ROLE_LABEL[role] : "—"}
          </Tag>{" "}
          — 카탈로그·규칙의 조회와 변경은 데이터 거버넌스 담당(STEWARD) 또는 관리자(ADMIN) 권한이
          필요합니다.
        </span>
      }
    />
  );
}
