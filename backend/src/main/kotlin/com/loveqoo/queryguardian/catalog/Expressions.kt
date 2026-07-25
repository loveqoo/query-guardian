package com.loveqoo.queryguardian.catalog

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 강제식(`{col}`/`:param`) 치환 유틸 (spec 002 §3.3).
 * 치환은 텍스트 기반이지만 안전 전제가 있다: (1) 등록 시 파싱·단일 술어·서브쿼리 금지 검증을 통과한
 * 표현식만 저장되고, (2) `{col}`에 들어가는 값은 카탈로그가 관리하는 컬럼명뿐이며,
 * (3) `:param` 값은 스칼라 리터럴로 검증된다. 임의 사용자 문자열은 이 경로로 들어오지 않는다.
 */
object Expressions {
    const val COL = "{col}"
    private val PARAM = Regex(":([A-Za-z_][A-Za-z0-9_]*)")
    private val NUMERIC = Regex("^-?\\d+(\\.\\d+)?$")

    fun paramNames(expression: String): Set<String> =
        PARAM.findAll(expression).map { it.groupValues[1] }.toSet()

    /** `{col}` → 컬럼명, `:name` → params 값(숫자는 그대로, 그 외 따옴표 감싸기). 누락 파라미터는 null 반환. */
    fun substitute(expression: String, columnName: String, params: Map<String, String>): String? {
        var missing = false
        val result = expression.replace(COL, columnName).let { withCol ->
            PARAM.replace(withCol) { m ->
                val value = params[m.groupValues[1]]
                if (value == null) { missing = true; m.value }
                else if (NUMERIC.matches(value)) value
                else "'" + value.replace("'", "''") + "'"
            }
        }
        return if (missing) null else result
    }

    /**
     * `:param`만 치환하고 `{col}`은 **그대로 남긴다** (spec 008 §3.5 M1-2).
     * 재작성기가 그 자리에 원본 컬럼 표현식(`u.email`)을 넣어야 한정자가 보존되기 때문이다 —
     * 여기서 컬럼명으로 미리 바꿔버리면 조인 쿼리에서 어느 테이블의 컬럼인지 잃는다.
     */
    fun substituteParams(expression: String, params: Map<String, String>): String? {
        var missing = false
        val result = PARAM.replace(expression) { m ->
            val value = params[m.groupValues[1]]
            if (value == null) { missing = true; m.value }
            else if (NUMERIC.matches(value)) value
            else "'" + value.replace("'", "''") + "'"
        }
        return if (missing) null else result
    }

    /** params_json 파싱: JSON 객체 + 스칼라 값만 허용 (§3.3). 위반 시 null. */
    fun parseParams(objectMapper: ObjectMapper, paramsJson: String?): Map<String, String>? {
        if (paramsJson.isNullOrBlank()) return emptyMap()
        return try {
            val node = objectMapper.readTree(paramsJson)
            if (!node.isObject) return null
            val map = mutableMapOf<String, String>()
            node.fields().forEach { (k, v) ->
                if (!v.isValueNode) return null
                map[k] = v.asText()
            }
            map
        } catch (e: Exception) {
            null
        }
    }
}
