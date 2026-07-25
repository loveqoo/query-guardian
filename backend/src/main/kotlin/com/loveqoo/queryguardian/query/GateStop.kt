package com.loveqoo.queryguardian.query

import com.loveqoo.queryguardian.api.BlockedException
import com.loveqoo.queryguardian.api.ForbiddenException
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.approval.ApprovalBlockedException
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.auth.AccessBlockedException
import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.ir.RewriteOutcome

/**
 * 감사 기록의 등급 (spec 010 I4·I5).
 *
 * P1-C1은 **현행 등급을 그대로 옮긴다** — 차단은 필수 기록, 실행 오류는 best-effort.
 * 등급의 재배치(반출이 있는 종결은 기록이 선행 조건)는 C2의 몫이다.
 */
enum class AuditGrade {
    /** 기록에 실패하면 그 실패가 응답을 대신한다. */
    REQUIRED,

    /** 기록에 실패해도 **원래 사유가 이긴다** — 감사 예외로 바꿔치면 무엇이 실패했는지 잃는다. */
    BEST_EFFORT,
}

/**
 * 게이트가 멈춘 이유 — **한 종류의 값** (spec 010 I3·I7).
 *
 * 예전에는 같은 개념이 한 함수 안에서 네 문법으로 쓰였다(try/catch·지역 `blocked()`·5인자
 * `blockedByReport()`·`runCatching`). "이 지점에 감사가 붙었는가"를 확인하려면 매 단계마다 **다른
 * 모양을 인식**해야 했고, 실제로 그중 하나를 빠뜨린 적이 있다(403인데 감사 0건 — `previewRewrite`의
 * 승인 검사). **문법의 다양성이 누락을 숨겼다.**
 *
 * 값이므로 게이트 본문은 예외를 만들지도 잡지도 않는다. 예외로의 번역은 [raise] 한 곳에서만 일어나고,
 * 그것도 `when`이 아니라 **다형성**으로 갈린다 — 변종을 추가해도 호출부는 그대로다.
 *
 * 예외 기반 비지역 반환을 쓰지 않는 이유는 스타일이 아니다: 예외는 **호출자의 트랜잭션을 롤백시키는
 * side-effect**를 갖는데, 게이트에는 되돌릴 쓰기가 없으므로(spec 010 I6) 그 효과는 우리가 통제하지
 * 않는 경계에 남기는 레버가 된다. 그리고 차단은 예외 상황이 아니라 **이 제품의 정상 결과**다.
 */
sealed interface GateStop {
    val code: AuditCode
    val detail: String?
    val outcome: ExecutionOutcome get() = ExecutionOutcome.BLOCKED
    val grade: AuditGrade get() = AuditGrade.REQUIRED

    /** 실행 오류처럼 **재작성까지는 끝난** 종결만 값을 갖는다 — 감사에 재작성문·적용 목록을 남긴다. */
    val rewritten: RewriteOutcome.Rewritten? get() = null

    /** 경계에서 예외로 번역한다. 게이트 본문에서는 호출하지 않는다. */
    fun raise(): Nothing

    /** 게이트 자체의 거부 — 권한·승인·매핑·재작성 실패. */
    data class Refused(override val code: AuditCode, val message: String) : GateStop {
        override val detail: String get() = message
        override fun raise(): Nothing = throw ForbiddenException(message)
    }

    /** 접수·룰 판정 위반 — 사용자에게 위반 목록을 그대로 돌려준다. */
    data class Violated(override val code: AuditCode, val report: LintReportDto) : GateStop {
        override val detail: String get() =
            report.violations.filter { it.severity.name == "BLOCK" }.joinToString("; ") { it.message }
        override fun raise(): Nothing = throw BlockedException(report)
    }

    /** 데이터 권한 차단 — 코드와 거부 테이블을 담은 별도 계약(spec 007 §6.5). */
    data class AccessDenied(val failure: AccessBlockedException) : GateStop {
        override val code: AuditCode get() = failure.detail.code
        override val detail: String get() = failure.detail.message
        override fun raise(): Nothing = throw failure
    }

    /** 승인 게이트 차단 — 룰 차단과 구분되는 별도 계약(spec 005 §7). */
    data class ApprovalDenied(val failure: ApprovalBlockedException) : GateStop {
        override val code: AuditCode get() = failure.detail.code
        override val detail: String get() = failure.detail.message
        override fun raise(): Nothing = throw failure
    }

    /**
     * 실행 인프라 실패도 **종결이다**(spec 010 §4.5). "인프라 예외는 잡지 않는다"를 무조건 규율로 두면
     * 이 경로의 감사가 조용히 사라진다.
     */
    data class Failed(
        val failure: ExecutionFailure,
        override val rewritten: RewriteOutcome.Rewritten,
    ) : GateStop {
        override val code: AuditCode get() = failure.kind.auditCode
        override val detail: String get() = failure.detail
        override val outcome: ExecutionOutcome get() = ExecutionOutcome.ERROR
        override val grade: AuditGrade get() = AuditGrade.BEST_EFFORT
        override fun raise(): Nothing = throw failure
    }
}
