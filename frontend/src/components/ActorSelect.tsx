import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { Select, Tag, Tooltip } from "antd";
import { UserSwitchOutlined } from "@ant-design/icons";
import {
  getActor,
  listDirectoryApprovers,
  listDirectoryUsers,
  setActor,
  subscribeActor,
  type DirectoryPerson,
} from "../api/client";

/**
 * 현재 actor 구독 훅. 모든 actor-bearing 호출은 `X-QG-Actor` 헤더로 이 값을 싣는다.
 *
 * **주의(spec 005 §5)**: actor는 인증되지 않은 클라이언트 제공 문자열이다 — 접근 통제가 아니다.
 */
export function useActor(): [string, (id: string) => void] {
  const actor = useSyncExternalStore(subscribeActor, getActor, getActor);
  return [actor, setActor];
}

/** 디렉터리(사용자+승인자) 전원 — 캐시 없이 컴포넌트마다 1회 로드. */
export function useDirectory(): { users: DirectoryPerson[]; approvers: DirectoryPerson[] } {
  const [users, setUsers] = useState<DirectoryPerson[]>([]);
  const [approvers, setApprovers] = useState<DirectoryPerson[]>([]);
  useEffect(() => {
    listDirectoryUsers().then(setUsers).catch(() => void 0);
    listDirectoryApprovers().then(setApprovers).catch(() => void 0);
  }, []);
  return { users, approvers };
}

/** actor id → 표시 이름. 미등록 id는 그대로 노출한다. */
export function personLabel(people: DirectoryPerson[], id: string | null | undefined): string {
  if (!id) return "—";
  return people.find((p) => p.id === id)?.name ?? id;
}

export const ACTOR_CAPTION = "데모 — 신원 위조 가능, 인증 후속";

interface Props {
  /** 라벨("현재 사용자") 노출 여부 */
  showLabel?: boolean;
  width?: number;
}

/**
 * 전역 "현재 사용자" 선택기 (spec 005 §5·§8). 세 화면(승인 요청·에디터·저장된 쿼리) 툴바에서 공유한다.
 * 선택값은 localStorage에 남아 새로고침 후에도 유지된다.
 */
export default function ActorSelect({ showLabel = true, width = 210 }: Props) {
  const [actor, select] = useActor();
  const { users, approvers } = useDirectory();

  const options = useMemo(
    () => [
      {
        label: "요청자 (사용자)",
        options: users.map((u) => ({ value: u.id, label: `${u.name} · ${u.role}` })),
      },
      {
        label: "승인자",
        options: approvers.map((a) => ({ value: a.id, label: `${a.name} · ${a.role}` })),
      },
    ],
    [users, approvers],
  );

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      {showLabel && (
        <span style={{ fontSize: 12, color: "rgba(0,0,0,.45)", flex: "none" }}>현재 사용자</span>
      )}
      <Select
        size="small"
        style={{ width }}
        value={actor}
        onChange={select}
        options={options}
        suffixIcon={<UserSwitchOutlined />}
        placeholder="행위자 선택"
      />
      <Tooltip title="본 화면의 승인·검토 액션은 인증되지 않은 스텁 identity로 수행됩니다 (spec 005 §5).">
        <Tag color="orange" style={{ margin: 0, cursor: "help" }}>
          {ACTOR_CAPTION}
        </Tag>
      </Tooltip>
    </span>
  );
}
