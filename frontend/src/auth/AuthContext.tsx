import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  login as apiLogin,
  logout as apiLogout,
  me as apiMe,
  subscribeUnauthorized,
  type Me,
  type Role,
} from "../api/client";

/**
 * 세션 인증 컨텍스트 (spec 007 §4·§8).
 *
 * - 마운트 시 `GET /api/auth/me` 1회. **401은 미로그인이라는 정상 신호**이므로 토스트를 띄우지 않는다.
 * - `subscribeUnauthorized`로 전역 401 훅을 받아 세션을 비운다 → 라우팅이 `/login`으로 보낸다.
 * - 캐시 무효화: `user.id`가 바뀌면 화면들이 다시 로드한다. 각 화면은 사용자별 데이터
 *   (`/catalog/schema` 자동완성 사전, `/approvals/usable`, 목록·권한)를 `user.id`를 키로 fetch한다.
 */
export interface AuthValue {
  user: Me | null;
  role: Role | null;
  /** STEWARD 또는 ADMIN — 승인·검토·카탈로그/규칙 쓰기 권한 (spec 007 §5). */
  isSteward: boolean;
  isAdmin: boolean;
  /** 부트스트랩(me()) 완료 여부. false면 아직 로그인 여부를 모른다. */
  ready: boolean;
  login: (userId: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [ready, setReady] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setUser(await apiMe());
    } catch {
      // 401 = 미로그인(정상 흐름, 토스트 금지). 그 외 오류도 화면을 막지 않고 미인증으로 떨어뜨린다.
      setUser(null);
    } finally {
      setReady(true);
    }
  }, []);

  // 부트스트랩
  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 전역 401 훅 — 세션 만료·서버 재기동 시 즉시 미인증으로 전환한다.
  useEffect(
    () =>
      subscribeUnauthorized(() => {
        setUser(null);
        setReady(true);
      }),
    [],
  );

  const login = useCallback(async (userId: string, password: string) => {
    const next = await apiLogin(userId, password);
    // 사용자 전환 = 캐시 키(user.id) 변경 → 자동완성 사전·usable 목록 등이 새로 로드된다.
    setUser(next);
    setReady(true);
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiLogout();
    } catch {
      /* 이미 만료된 세션(401) — 결과는 같다 */
    } finally {
      setUser(null);
      setReady(true);
    }
  }, []);

  const value = useMemo<AuthValue>(
    () => ({
      user,
      role: user?.role ?? null,
      isSteward: user?.role === "STEWARD" || user?.role === "ADMIN",
      isAdmin: user?.role === "ADMIN",
      ready,
      login,
      logout,
      refresh,
    }),
    [user, ready, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}

/** 로그인 사용자 id — 사용자별 캐시(useEffect deps)의 키로 쓴다. 미로그인은 빈 문자열. */
export function useSessionKey(): string {
  return useAuth().user?.id ?? "";
}
