import type { Fix } from "./client";

/**
 * **제안 조각을 SQL에 적용한다** (spec 013 §3-1 · F4).
 *
 * 서버는 고쳐진 SQL을 만들어 주지 않는다(spec 012 §9 — 조각만 준다). 그러므로 **"적용"의 의미를
 * 정하는 것은 이 함수다.** UI에서 떼어 둔 이유가 그것이다: 화면 렌더링과 무관한 계약이고,
 * 계약은 재어져야 한다.
 *
 * ## 케이스 표는 여기 없다
 *
 * `tests/apply-fix-cases.json`(저장소 루트)을 백엔드 `FixRoundTripTest`와 **함께** 읽는다.
 * 구현은 두 벌일 수밖에 없지만(브라우저에서 Kotlin을 못 돌린다) 케이스까지 두 벌이면 갈라져도
 * 아무도 모른다 — 실제로 갈라져 있었다(`" WHERE "` 리터럴 공백 vs `\s`).
 *
 * ## 확신할 수 없으면 고치지 않는다
 *
 * 이것은 파서가 아니라 텍스트 조작이고, 적대 검토가 그 대가를 반례로 보여 줬다: 첫 `WHERE`를 그냥
 * 고치면 **CTE 안에 들어가** 바깥 별칭을 참조하는 깨진 SQL이 되고 원래 위반은 그대로 남아 아무리
 * 눌러도 안 풀린다. UNION이면 어느 팔의 위반인지 조각이 말해 주지 않는다.
 *
 * 그래서 **괄호 깊이와 문자열 리터럴을 보고**, 판단할 수 없으면 원본을 그대로 돌려준다.
 * 호출부는 `next === prev`로 그 사실을 알고 사용자에게 직접 고치라고 말한다 —
 * 깨진 SQL을 만들어 주는 것보다 "여기는 직접 고치세요"가 정직하다.
 */
export function applyFix(sql: string, fix: Fix): string {
  const shape = scan(sql);

  if (fix.kind === "REPLACE_PROJECTION") {
    if (!fix.from) return sql;
    // 단어 경계 셋이 모두 필요하다(실측):
    //  - 앞이 식별자/점이면 안 된다 → `email_verified`, `u.email`을 건드리지 않는다
    //  - 앞이 **여는 괄호여도 안 된다** → 이미 `mask_email(email)`인 것을 또 감싸는 것을 막는다.
    //    대가로 `SELECT (email)` 같은 괄호 친 참조는 못 고친다 — 자리를 못 찾은 것으로 처리된다.
    //  - 뒤가 식별자/여는 괄호면 안 된다 → 함수명 `email(...)`을 건드리지 않는다
    const token = fix.from.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const hit = matchAll(sql, new RegExp(`(?<![\\w.(])${token}(?![\\w(])`, "g"))
      // **문자열 리터럴 안은 건드리지 않는다.** `WHERE tag = 'email'`의 `email`을 바꾸면
      // 리터럴의 뜻이 달라지는데 투영은 그대로라 위반도 안 사라진다(적대 검토 반례).
      .find((m) => !shape.inLiteral[m.index]);
    return hit ? sql.slice(0, hit.index) + fix.to + sql.slice(hit.index + hit[0].length) : sql;
  }

  // 최상위 WHERE에 AND로 잇는다. 기존 조건 **앞**에 넣는다 — 뒤에 붙이면 원본이 OR일 때
  // `a OR b AND new`가 되어 우선순위가 무너진다(`AND`가 `OR`보다 강하다).
  const wheres = topLevel(sql, /\sWHERE\s/gi, shape);
  // 최상위 WHERE가 둘 이상이면 **UNION 등 여러 갈래**다. 어느 갈래의 위반인지 조각이 말해 주지
  // 않으므로 찍어 넣지 않는다 — 엉뚱한 갈래에 넣으면 그쪽이 깨지고 원래 갈래는 그대로 막힌다.
  if (wheres.length > 1) return sql;
  if (wheres.length === 1) {
    const w = wheres[0];
    // 매치한 공백을 **그대로 되돌려 놓는다**. 공백 하나로 바꾸면 사용자가 넣은 줄바꿈·들여쓰기가
    // 사라진다 — 우리가 고치라고 해서 눌렀는데 서식이 무너지면 그것도 남의 쿼리를 고친 것이다.
    return sql.slice(0, w.index) + w[0] + `${fix.to} AND ` + sql.slice(w.index + w[0].length);
  }

  // WHERE가 없으면 **최상위** 다음 절 앞에 새로 만든다(CTE 안의 LIMIT을 보고 끼어들지 않는다).
  const tails = topLevel(sql, /\s(LIMIT|GROUP\s+BY|ORDER\s+BY|HAVING)\s/gi, shape);
  if (tails.length === 0) return `${sql} WHERE ${fix.to}`;
  return sql.slice(0, tails[0].index) + ` WHERE ${fix.to}` + sql.slice(tails[0].index);
}

/**
 * 인덱스별 괄호 깊이와 "문자열 리터럴 안인가".
 *
 * 파서가 아니다 — **이 함수가 물러날 자리를 알기 위한 최소 정보**다. 주석·달러 인용 같은 것은
 * 모른다. 모르는 것을 만나면 깊이가 어긋날 수 있고, 그때는 조각이 안 들어가거나(안전) 엉뚱한 데
 * 들어간다(안 안전). 그 한계 때문에 케이스 표가 있다.
 */
interface Shape {
  depth: number[];
  inLiteral: boolean[];
}

function scan(sql: string): Shape {
  const depth = new Array<number>(sql.length).fill(0);
  const inLiteral = new Array<boolean>(sql.length).fill(false);
  let level = 0;
  let quote: string | null = null;
  for (let i = 0; i < sql.length; i++) {
    const ch = sql[i];
    if (quote) {
      inLiteral[i] = true;
      depth[i] = level;
      if (ch === quote) {
        if (sql[i + 1] === quote) {
          // `''` — 리터럴 안의 이스케이프된 인용부호다. 닫는 것이 아니다.
          inLiteral[i + 1] = true;
          depth[i + 1] = level;
          i++;
        } else {
          quote = null;
        }
      }
      continue;
    }
    if (ch === "'" || ch === '"' || ch === "`") {
      quote = ch;
      inLiteral[i] = true;
      depth[i] = level;
      continue;
    }
    if (ch === "(") level++;
    depth[i] = level;
    if (ch === ")") level = Math.max(0, level - 1);
  }
  return { depth, inLiteral };
}

function matchAll(sql: string, re: RegExp): RegExpExecArray[] {
  const out: RegExpExecArray[] = [];
  re.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    out.push(m);
    if (m[0].length === 0) re.lastIndex++;
  }
  return out;
}

/** 괄호 밖(깊이 0)이고 리터럴이 아닌 매치만. **키워드 위치**로 판단한다 — 앞 공백은 깊이가 다를 수 있다. */
function topLevel(sql: string, re: RegExp, shape: Shape): RegExpExecArray[] {
  return matchAll(sql, re).filter((m) => {
    const keywordAt = m.index + (m[0].length - m[0].replace(/^\s+/, "").length);
    return shape.depth[keywordAt] === 0 && !shape.inLiteral[keywordAt];
  });
}
