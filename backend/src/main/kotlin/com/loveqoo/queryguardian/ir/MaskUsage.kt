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
fun maskUsageOf(scope: SelectScope, instanceKey: String, column: String): MaskUsage {
    // `*`는 무엇이 나갈지 IR이 알 수 없다. 단, `o.*`처럼 한정된 star는 그 인스턴스만 덮는다.
    val starCovers = scope.selectItems.any { item ->
        item is SelectItem.Star && (item.qualifier == null || item.qualifier.equals(instanceKey, ignoreCase = true))
    }
    if (starCovers) return MaskUsage.NOT_EXPRESSIBLE

    val projections = scope.selectItems.count { item ->
        item is SelectItem.Column &&
            item.column.table == instanceKey &&
            item.column.column.equals(column, ignoreCase = true)
    }
    val references = scope.columnRefs.count { ref ->
        ref.table?.instanceKey == instanceKey && ref.column.equals(column, ignoreCase = true)
    }
    return when {
        references > projections -> MaskUsage.NOT_EXPRESSIBLE
        projections > 0 -> MaskUsage.PROJECTION_ONLY
        else -> MaskUsage.ABSENT
    }
}
