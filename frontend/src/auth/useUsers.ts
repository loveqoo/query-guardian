import { useEffect, useMemo, useState } from "react";
import { listUsers, type AppUser, type Role } from "../api/client";
import { useAuth } from "./AuthContext";

/** 승인자 자격 = STEWARD 이상 (spec 007 §5 — ANALYST 지정 시 400). */
export function isStewardRole(role: Role | string): boolean {
  return role === "STEWARD" || role === "ADMIN";
}

/**
 * `GET /api/users` — 전 인증 사용자. `/api/directory/*`(폐기)의 대체이며 승인자 피커·표시 이름 해석에 쓴다.
 * 세션 사용자가 바뀌면 다시 로드한다(캐시 키 = user.id).
 */
export function useUsers(): { users: AppUser[]; approvers: AppUser[]; loading: boolean } {
  const { user } = useAuth();
  const sessionKey = user?.id ?? "";
  const [users, setUsers] = useState<AppUser[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!sessionKey) {
      setUsers([]);
      return;
    }
    let alive = true;
    setLoading(true);
    listUsers()
      .then((list) => {
        if (alive) setUsers(list);
      })
      .catch(() => {
        if (alive) setUsers([]);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [sessionKey]);

  const approvers = useMemo(
    () => users.filter((u) => u.enabled && isStewardRole(u.role)),
    [users],
  );
  return { users, approvers, loading };
}

/** 사용자 id → 표시 이름. 미등록 id는 그대로 노출한다. */
export function userLabel(users: AppUser[], id: string | null | undefined): string {
  if (!id) return "—";
  return users.find((u) => u.id === id)?.displayName ?? id;
}

/** 사용자 id → "이름 · 직책". */
export function userLongLabel(users: AppUser[], id: string | null | undefined): string {
  if (!id) return "—";
  const found = users.find((u) => u.id === id);
  return found ? `${found.displayName} · ${found.title}` : id;
}
