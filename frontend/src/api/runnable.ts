/**
 * **저장 쿼리를 실행할 수 있는가, 없다면 왜인가** (spec 013 §3-3 · F3).
 *
 * 서버가 막을 것을 화면이 미리 말한다. 활성으로 보였다가 403이 나면 사용자는 자기가 뭘 잘못했는지
 * 모른다 — 특히 대행 실행 금지는 **화면에 단서가 하나도 없는** 규칙이다.
 *
 * 렌더링에서 떼어 둔 이유: 이것은 **권한 판단**이고, 틀리면 화면이 서버 정책과 어긋난다.
 * 어긋나는 방향이 둘인데 둘 다 나쁘다 — 느슨하면 거짓 기대를 주고, 빡빡하면 정당한 실행을 막는다.
 * 그래서 재는 자리를 따로 둔다(`tests/runnable.spec.ts`).
 *
 * **이유 문자열을 돌려주는 것**이지 불리언이 아니다. 불리언이면 "왜"를 화면이 다시 추론해야 하고,
 * 그 추론이 여기 판정과 갈라진다(learning 019: 합 타입에서 버린 것은 이유다).
 */
export interface Runnable {
  isSample?: boolean;
  /** 검토 상태 — 승인된 것만 실행할 수 있다(spec 008 §7). */
  review: string;
  /** 소유자 = 근거 승인 요청의 요청자. null이면 근거가 사라진 것이다. */
  owner?: string | null;
}

export function runDeniedReason(row: Runnable, viewerId: string | undefined): string | null {
  if (row.isSample) return "예시 행입니다";
  // 검토 상태는 **왜 안 되는지**를 갈라 말한다 — "승인된 것만"이라고만 하면 사용자가 다음에 무엇을
  // 해야 하는지 모른다(대기 중이면 기다리는 것이고, 반려면 고쳐서 다시 올리는 것이다).
  if (row.review === "PENDING_REVIEW") return "검토 대기 중입니다 — 승인 후 실행할 수 있습니다";
  if (row.review === "REJECTED") return "반려된 쿼리는 실행할 수 없습니다";
  if (row.review !== "APPROVED") return "검토 상태를 확인하는 중입니다";
  // 근거 요청이 사라지면 **아무도** 소유자가 아니다. 스튜어드라고 예외가 아니다.
  if (!row.owner) return "근거 승인 요청이 없어 실행할 수 없습니다";
  // 스튜어드에게도 남의 쿼리는 막힌다 — 보는 능력과 실행하는 능력은 다르다(결정 14).
  if (!viewerId || row.owner !== viewerId) return "본인이 저장한 쿼리만 실행할 수 있습니다 (대행 실행 불허)";
  return null;
}
