import type { Role } from "../api/client";

/**
 * 로그인 화면의 사용자 목록 (spec 007 §4 L4).
 *
 * **공개 API를 추가하지 않는다** — 공개 경로는 `POST /api/auth/login` 단 하나이므로 미인증 상태에서
 * 사용자 목록을 받을 수 없다. 그래서 데모 시드(spec 007 §3.2 = `UserBootstrap`)와 1:1인 **프론트 상수**로 둔다.
 * 시드가 바뀌면 이 표도 함께 갱신해야 한다(로그인은 서버가 검증하므로 표가 틀려도 인증이 뚫리지는 않는다).
 */
export interface DemoUser {
  id: string;
  name: string;
  title: string;
  role: Role;
}

export const DEMO_USERS: DemoUser[] = [
  { id: "u1", name: "김도현", title: "데이터 분석가", role: "ANALYST" },
  { id: "u2", name: "이서연", title: "데이터 분석가", role: "ANALYST" },
  { id: "u3", name: "박민준", title: "데이터 엔지니어", role: "ANALYST" },
  { id: "u4", name: "정하윤", title: "데이터 거버넌스", role: "STEWARD" },
  { id: "ap1", name: "최지훈", title: "마케팅본부장", role: "STEWARD" },
  { id: "ap2", name: "한도윤", title: "데이터플랫폼장", role: "STEWARD" },
  { id: "ap3", name: "서준호", title: "정보보호책임자(CISO)", role: "STEWARD" },
  { id: "ap4", name: "김영은", title: "최고데이터책임자(CDO)", role: "STEWARD" },
  { id: "adm1", name: "시스템 관리자", title: "플랫폼 관리자", role: "ADMIN" },
];

/** 데모 공통 비밀번호 — **운영 반입 금지** (spec 007 H9). */
export const DEMO_PASSWORD = "qg-demo";

export const ROLE_LABEL: Record<Role, string> = {
  ANALYST: "분석가",
  STEWARD: "데이터 스튜어드",
  ADMIN: "관리자",
};

export const ROLE_COLOR: Record<Role, string> = {
  ANALYST: "blue",
  STEWARD: "geekblue",
  ADMIN: "purple",
};
