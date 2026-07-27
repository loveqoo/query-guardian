#!/usr/bin/env python3
"""서버 DTO ↔ 프론트 zod 스키마 **필드·상수 대조** (spec 013 C1).

왜 필요한가
-----------
타입 체커가 둘을 이어 주지 않는다. Kotlin은 필드를 알고 TypeScript는 스키마를 아는데,
**둘이 같은지는 아무도 안 본다.** 실제로 이 스크립트를 쓰기 전에 `ExecutionOutcome`을
`FAILED`로 적었다 — 서버는 `ERROR`다. zod는 파싱 시점에야 터지고, 그때는 감사 화면이
"알 수 없는 값"으로 비어 있을 뿐 원인이 안 보인다.

한계 (부풀리지 않는다)
---------------------
- **정규식 대조다.** 필드 *이름과 순서*만 본다 — 타입(String? vs string)은 안 본다.
- 여기 적힌 짝만 본다. 새 DTO를 만들고 이 목록에 안 넣으면 조용히 지나간다.
  그 누락은 이 스크립트가 아니라 사람이 막아야 한다.
- CI가 없으므로 **자동으로 돌지 않는다.** 와이어를 건드릴 때 손으로 돌린다.

    python3 .dev/tools/wire-contract-check.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DTO = (ROOT / "backend/src/main/kotlin/com/loveqoo/queryguardian/api/Dtos.kt").read_text()
VOCAB = (ROOT / "backend/src/main/kotlin/com/loveqoo/queryguardian/audit/AuditVocabulary.kt").read_text()
TS = (ROOT / "frontend/src/api/client.ts").read_text()

# (Kotlin data class, zod 스키마) — 화면이 실제로 파싱하는 것만
FIELD_PAIRS = [
    ("FixDto", "fixSchema"),
    ("ViolationDto", "violationSchema"),
    ("ExecutionResultDto", "executionResultSchema"),
    ("ExecutionColumnDto", "executionColumnSchema"),
    ("AppliedRewriteDto", "appliedRewriteSchema"),
    ("ExecutionEventDto", "executionEventSchema"),
    ("QuerySummaryDto", "queryListItemSchema"),
]

# (Kotlin enum, zod enum) — 상수 **이름**이 곧 와이어 값이다(Jackson 기본)
ENUM_PAIRS = [
    (VOCAB, "ExecutionOutcome", "executionOutcomeSchema"),
]


def balanced(src: str, start: int, opens: str, closes: str) -> str:
    depth = 0
    for i in range(start, len(src)):
        if src[i] == opens:
            depth += 1
        elif src[i] == closes:
            depth -= 1
            if depth == 0:
                return src[start:i]
    raise ValueError(f"짝이 안 맞는 괄호: {src[start:start + 60]!r}")


def strip_comments(body: str) -> str:
    return re.sub(r"//.*", "", re.sub(r"/\*.*?\*/", "", body, flags=re.S))


def kotlin_fields(name: str):
    m = re.search(r"data class %s\s*\(" % name, DTO)
    if not m:
        return None
    return re.findall(r"val\s+(\w+)\s*:", strip_comments(balanced(DTO, m.end() - 1, "(", ")")))


def zod_fields(name: str):
    m = re.search(r"export const %s = z\.object\(" % name, TS)
    if not m:
        return None
    body = strip_comments(balanced(TS, m.end() - 1, "(", ")")).replace("z.object({", "", 1)
    return re.findall(r"(\w+)\s*:", body)


def kotlin_enum(src: str, name: str):
    m = re.search(r"enum class %s\s*\{([^}]*)\}" % name, src)
    return None if not m else [c.strip() for c in m.group(1).split(",") if c.strip()]


def zod_enum(name: str):
    m = re.search(r"export const %s = z\.enum\(\[([^\]]*)\]\)" % name, TS)
    return None if not m else re.findall(r'"([^"]+)"', m.group(1))


def main() -> int:
    problems = 0
    for kotlin, zod in FIELD_PAIRS:
        a, b = kotlin_fields(kotlin), zod_fields(zod)
        if a is None or b is None:
            print(f"?? {kotlin}/{zod}: 선언을 못 찾았다 (이름이 바뀌었나)")
            problems += 1
        elif a != b:
            print(f"!! {kotlin} ↔ {zod}\n   서버: {a}\n   화면: {b}")
            problems += 1
        else:
            print(f"ok {kotlin} ↔ {zod} ({len(a)} 필드)")

    for src, kotlin, zod in ENUM_PAIRS:
        a, b = kotlin_enum(src, kotlin), zod_enum(zod)
        if a is None or b is None:
            print(f"?? {kotlin}/{zod}: 선언을 못 찾았다")
            problems += 1
        elif set(a) != set(b):
            print(f"!! {kotlin} ↔ {zod}\n   서버: {a}\n   화면: {b}")
            problems += 1
        else:
            print(f"ok {kotlin} ↔ {zod} ({len(a)} 상수)")

    print("=== 어긋남 %d건 ===" % problems if problems else "=== 전부 일치 ===")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
