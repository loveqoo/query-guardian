import type { ExecutionResult } from "./client";

/**
 * **행 상한을 어떻게 읽을 것인가** (spec 013 F2 · retrospect 012가 M3에 넘긴 제약).
 *
 * 서버가 세 값을 따로 주는 데는 이유가 있고, 화면이 그것을 뭉치면 이유가 사라진다.
 * 여기 떼어 둔 것은 렌더링과 무관한 **판단**이기 때문이다 — 판단은 재어져야 한다
 * (`tests/limit-status.spec.ts`).
 *
 * 규칙 둘:
 * 1. **거버넌스가 잘랐다 = 적용 상한이 설정 상한과 같다.** 사용자가 더 작게 걸었으면
 *    (`LIMIT 5` vs 설정 1000) 자른 것은 사용자다 — 경고할 일이 아니다.
 * 2. **"더 있는지"는 세 상태다.** `null`은 "확인하지 않음"이고 "없음"과 다른 사실이다
 *    (상한이 0이면 초과 탐지용 1행조차 조회하지 않으므로 확인 자체를 안 한 것이다).
 *    불리언으로 좁히면 확인하지 않은 것이 "없음"으로 둔갑한다.
 *
 * 어느 값도 화면에서 다시 계산하지 않는다 — 서버·감사와 갈리는 자리를 만들지 않는다.
 */
export type MoreRows = "있음" | "없음" | "확인하지 않음";

export interface LimitStatus {
  /** 적용된 상한. null이면 서버가 상한을 말하지 않았다. */
  appliedLimit: number | null;
  /** 설정 상한. */
  configuredCap: number | null;
  /** 거버넌스 상한 때문에 잘렸는가 — 사용자가 스스로 좁힌 경우는 false다. */
  truncatedByGovernance: boolean;
  moreRows: MoreRows;
}

export function limitStatus(
  result: Pick<ExecutionResult, "effectiveLimit" | "configuredCap" | "moreRowsExist">,
): LimitStatus {
  const appliedLimit = result.effectiveLimit ?? null;
  const configuredCap = result.configuredCap ?? null;
  return {
    appliedLimit,
    configuredCap,
    truncatedByGovernance: appliedLimit != null && configuredCap != null && appliedLimit === configuredCap,
    moreRows:
      result.moreRowsExist == null ? "확인하지 않음" : result.moreRowsExist ? "있음" : "없음",
  };
}
