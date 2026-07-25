package com.loveqoo.queryguardian.ir

/** MASK 매핑 컬럼이 한 스코프에서 어떻게 쓰였는가 — 마스킹 재작성 가능성의 유일한 판단 기준 (spec 008 §3.0.1). */
enum class MaskUsage {
    /** 이 스코프에서 참조되지 않았다. */
    ABSENT,

    /** 최상위 bare 투영으로만 쓰였다 — 강제식 치환으로 표현할 수 있다. */
    PROJECTION_ONLY,

    /** 함수 인자·CASE·WHERE·GROUP BY·ORDER BY·`*` 투영 등 — 치환으로 표현할 수 없다. */
    NOT_EXPRESSIBLE,
}

/**
 * 판정(`rules`의 `must_be_masked`)과 재작성(`exec`의 계획 수립)이 **이 함수 하나**를 공유한다.
 *
 * 두 곳이 갈라지면 두 방향의 실패가 생긴다: 저장 때 통과했는데 실행에서 거부(사용자 혼란), 또는
 * 저장 때 막지 않았는데 실행이 **마스킹 없이** 통과(평문 유출). 후자를 원리적으로 없애려면 기준이 하나여야 한다.
 * 그래서 이 로직은 어느 계층도 아닌 공용 어휘(`ir`)에 둔다.
 *
 * 판정 방법: **참조 횟수 > 최상위 bare 투영 횟수**면 투영 아닌 위치에도 쓰였다는 뜻이다.
 * `columnRefs`는 위치를 기록하지 않지만 bare 투영은 정확히 참조 1건을 기여하므로, 초과분은 반드시 다른 위치다
 * (`CONCAT(email,'')`·`WHERE email = …`·`ORDER BY email` 등 — 적대 검토가 실측한 한 겹 우회가 여기서 걸린다).
 */
/** 한 스코프에서 발견된 마스킹 대상 사용 — 순회 축을 판정·재작성이 공유하기 위한 결과 타입. */
data class MaskFinding(
    val instanceKey: String,
    val logicalTable: String,
    val column: String,
    val usage: MaskUsage,
)

/**
 * 이 스코프에서 **마스킹 대상 컬럼이 쓰인 모든 경우**를 찾는다. 판정(`must-be-masked`)과 재작성 계획이
 * 이 함수를 순회 축으로 공유한다.
 *
 * 순회 축이 `scope.tables`이면 두 가지가 새는 것이 실측됐다(적대 검토 CRITICAL 2·3):
 * 1. `SELECT email FROM users, user_events` — 인스턴스가 둘 이상이면 IR이 귀속을 포기(table=null)하는데,
 *    인스턴스 키로만 세면 **투영 0·참조 0 = ABSENT**가 되어 평문이 나간다. `NoBlockedColumnRule`은 같은
 *    상황에 fail-closed 폴백을 갖고 있었으나 마스킹 축에는 없었다 — 같은 귀속 실패인데 한쪽만 안전했다.
 * 2. `SELECT u.id, (SELECT u.email) AS leak FROM users u` — 자식 스코프의 참조는 **부모 인스턴스**를 가리키므로
 *    그 스코프의 `tables`에는 없다. 아무 스코프도 그 (인스턴스, 컬럼) 짝을 보지 않았다.
 *
 * [maskedColumnsOf]는 논리 테이블명 → MASK 매핑 컬럼(소문자) 집합.
 */
fun maskFindings(scope: SelectScope, maskedColumnsOf: (String) -> Set<String>): List<MaskFinding> {
    val findings = mutableListOf<MaskFinding>()

    // 이 스코프가 **실제로 참조하는** 물리 인스턴스 — 부모 체인에서 해석된 것(상관 참조)까지 포함한다.
    val instances = (scope.tables + scope.columnRefs.mapNotNull { it.table })
        .filter { it.physical }
        .distinctBy { it.instanceKey }

    for (instance in instances) {
        for (column in maskedColumnsOf(instance.name)) {
            val usage = maskUsageOf(scope, instance.instanceKey, column)
            if (usage != MaskUsage.ABSENT) {
                findings += MaskFinding(instance.instanceKey, instance.name, column, usage)
            }
        }
    }

    // 귀속 불가 참조(table=null): 어느 인스턴스의 컬럼인지 모르므로 **안전하게 치환할 수 없다** →
    // 표현 불가로 확정한다(fail-closed). 사용자는 컬럼을 한정(`u.email`)하면 정상 마스킹된다.
    val unattributed = scope.columnRefs.filter { it.table == null }.map { it.column.lowercase() }.toSet()
    for (column in unattributed) {
        val owner = instances.firstOrNull { column in maskedColumnsOf(it.name) } ?: continue
        if (findings.none { it.column.equals(column, ignoreCase = true) && it.instanceKey == owner.instanceKey }) {
            findings += MaskFinding(owner.instanceKey, owner.name, column, MaskUsage.NOT_EXPRESSIBLE)
        }
    }
    return findings
}

fun maskUsageOf(scope: SelectScope, instanceKey: String, column: String): MaskUsage {
    // `*`는 무엇이 나갈지 IR이 알 수 없다. 단, `o.*`처럼 한정된 star는 그 인스턴스만 덮는다.
    val starCovers = scope.selectItems.any { item ->
        item is SelectItem.Star && (item.qualifier == null || item.qualifier.equals(instanceKey, ignoreCase = true))
    }
    if (starCovers) return MaskUsage.NOT_EXPRESSIBLE

    val projected = scope.selectItems.withIndex().filter { (_, item) ->
        item is SelectItem.Column &&
            item.column.table == instanceKey &&
            item.column.column.equals(column, ignoreCase = true)
    }
    val references = scope.columnRefs.count { ref ->
        ref.table?.instanceKey == instanceKey && ref.column.equals(column, ignoreCase = true)
    }
    if (references > projected.size) return MaskUsage.NOT_EXPRESSIBLE
    if (projected.isEmpty()) return MaskUsage.ABSENT

    // 마스킹은 many-to-one이다(실측: 서로 다른 두 이메일이 같은 `j***@naver.com`이 된다).
    // 따라서 치환 후 중복 제거하면 원본보다 행이 줄어든다 — 조용한 결과 변경보다 거부가 안전하다.
    if (scope.distinct) return MaskUsage.NOT_EXPRESSIBLE

    // GROUP BY/ORDER BY/HAVING이 이 투영을 **이름이나 서수로** 가리키면, 치환 후 그룹·정렬 기준이
    // 마스킹된 값으로 바뀐다(`SELECT email AS e … GROUP BY e`, `GROUP BY 1`). 역시 결과 의미가 달라진다.
    val referencedByOutputName = projected.any { (index, item) ->
        val outputName = ((item as SelectItem.Column).alias ?: item.column.column).lowercase()
        outputName in scope.outputRefs || (index + 1).toString() in scope.outputRefs
    }
    if (referencedByOutputName) return MaskUsage.NOT_EXPRESSIBLE

    return MaskUsage.PROJECTION_ONLY
}
