package com.loveqoo.queryguardian.api

import com.loveqoo.queryguardian.rules.RuleGroup

/** 규칙 목록 항목 (spec 004 §7). enforced=판정 조건이 하나라도 있는가(미강제 표시용). */
data class RuleDto(
    val id: Long,
    val name: String,
    val scope: String,
    val server: String?,
    val severity: String,   // 파생 요약 (조건 severity 최댓값), 없으면 "NONE"
    val hits: Long,
    val enabled: Boolean,
    val enforced: Boolean,
    val corrupt: Boolean,   // tree_json 파싱 실패 (H6)
)

data class RuleDetailDto(
    val id: Long,
    val name: String,
    val scope: String,
    val server: String?,
    val enabled: Boolean,
    val tree: RuleGroup?,   // 파싱 실패 시 null + corrupt
    val corrupt: Boolean,
)

data class SaveRuleRequest(
    val name: String,
    val scope: String,
    val server: String? = null,
    val enabled: Boolean = true,
    val tree: RuleGroup,
)
