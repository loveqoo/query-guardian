import type { ReactNode } from "react";
import {
  AppstoreOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  CodeOutlined,
  FileOutlined,
  KeyOutlined,
  ThunderboltOutlined,
  AuditOutlined,
} from "@ant-design/icons";

/** A single navigable screen. */
export interface ScreenDef {
  key: string;
  path: string;
  group: string;
  title: string;
  icon: ReactNode;
}

/** Navigation groups mirroring dc.html navItems (lines 933–938). */
export interface NavGroup {
  group: string;
  items: ScreenDef[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    group: "탐색",
    items: [
      { key: "databases", path: "/databases", group: "탐색", title: "데이터베이스", icon: <AppstoreOutlined /> },
    ],
  },
  {
    group: "쿼리",
    items: [
      { key: "editor", path: "/editor", group: "쿼리", title: "쿼리 에디터", icon: <CodeOutlined /> },
      { key: "queries", path: "/queries", group: "쿼리", title: "저장된 쿼리", icon: <FileOutlined /> },
    ],
  },
  {
    group: "거버넌스",
    items: [
      { key: "approvals", path: "/approvals", group: "거버넌스", title: "승인 요청", icon: <CheckCircleOutlined /> },
      { key: "rules", path: "/rules", group: "거버넌스", title: "규칙 관리", icon: <ThunderboltOutlined /> },
      { key: "catalog", path: "/catalog", group: "거버넌스", title: "제약 카탈로그", icon: <BulbOutlined /> },
      { key: "audit", path: "/audit", group: "거버넌스", title: "실행 감사", icon: <AuditOutlined /> },
    ],
  },
  {
    group: "관리",
    items: [
      { key: "admin", path: "/admin", group: "관리", title: "접근 권한 관리", icon: <KeyOutlined /> },
    ],
  },
];

/** Flat list of all screens. */
export const SCREENS: ScreenDef[] = NAV_GROUPS.flatMap((g) => g.items);

/** Look up a screen by its route path. */
export function screenByPath(pathname: string): ScreenDef | undefined {
  return SCREENS.find((s) => pathname === s.path || pathname.startsWith(s.path + "/"));
}
