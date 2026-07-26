package com.loveqoo.queryguardian.query.gate

import com.loveqoo.queryguardian.api.BlockedDetail
import com.loveqoo.queryguardian.api.LintReportDto
import com.loveqoo.queryguardian.audit.AuditCode
import com.loveqoo.queryguardian.audit.ExecutionOutcome
import com.loveqoo.queryguardian.exec.ExecutionFailure
import com.loveqoo.queryguardian.ir.RewriteOutcome
import com.loveqoo.queryguardian.rules.Severity
import org.springframework.http.HttpStatus

/** 게이트 차단의 기본 응답 바디 — **분류 코드가 반드시 실린다**. */
data class GateErrorDto(val code: AuditCode, val message: String)

/**
 * 게이트가 멈췄음을 경계로 나르는 유일한 예외.
 *
 * 게이트 본문은 이것을 만들지 않는다 — [GateStop.raise]만이 만들고, 그것도 경계(`orRaise`)에서만
 * 호출된다. `@ExceptionHandler`가 [GateStop.status]와 [GateStop.body]로 응답을 만든다.
 */
class GateStopException(val stop: GateStop) : RuntimeException(stop.detail)

/**
 * 게이트가 멈춘 이유 — **한 종류의 값** (spec 010 I3·I7).
 *
 * 예전에는 같은 개념이 한 함수 안에서 네 문법으로 쓰였다(try/catch·지역 `blocked()`·5인자
 * `blockedByReport()`·`runCatching`). "이 지점에 감사가 붙었는가"를 확인하려면 매 단계마다 **다른
 * 모양을 인식**해야 했고, 실제로 그중 하나를 빠뜨린 적이 있다(403인데 감사 0건 — `previewRewrite`의
 * 승인 검사). **문법의 다양성이 누락을 숨겼다.**
 *
 * **[code]가 감사와 응답 양쪽의 유일한 출처다**(spec 010 A2). 예전에는 감사에는 남는 분류가 응답에는
 * 실리지 않는 경로가 있었다 — 사용자는 "권한이 없습니다"만 받고 무엇이 막았는지 알 수 없었다.
 * 스타일 문제가 아니라 계약 결함이었다(리뷰 R3).
 *
 * **모든 `GateStop`은 "반출이 없는 종결"이다**(spec 010 I5) — 정의상 데이터도 강제식도 나가지 않는다.
 * 그래서 감사 기록은 전부 best-effort이고 **원래 사유가 기록 실패를 이긴다**. 등급을 필드로 들 필요가
 * 없다: 반출이 있는 종결(SUCCESS·PREVIEW)은 애초에 이 타입이 아니라 진입점이 다룬다.
 *
 * 예외 기반 비지역 반환을 쓰지 않는 이유는 스타일이 아니다: 예외는 **호출자의 트랜잭션을 롤백시키는
 * side-effect**를 갖는데, 게이트에는 되돌릴 쓰기가 없으므로(spec 010 I6) 그 효과는 우리가 통제하지
 * 않는 경계에 남기는 레버가 된다. 그리고 차단은 예외 상황이 아니라 **이 제품의 정상 결과**다.
 *
 * ## 상태 코드의 기준
 *
 * **403은 "이 사람이 할 수 없다", 422는 "이 요청을 처리할 수 없다".** 예전에는 재작성 실패와 매핑
 * 부재까지 403이었는데, 그것은 권한 문제가 아니다 — 같은 사람이 다른 SQL을 쓰면 통과한다.
 */
sealed interface GateStop {
    val code: AuditCode

    /** 감사의 `error_detail` — 원문이므로 STEWARD/ADMIN에게만 보인다. */
    val detail: String?

    val status: HttpStatus

    /** 응답 바디. 어떤 모양이든 [code]를 싣는다. */
    val body: Any

    val outcome: ExecutionOutcome get() = ExecutionOutcome.BLOCKED

    /** 실행 오류처럼 **재작성까지는 끝난** 종결만 값을 갖는다 — 감사에 재작성문·적용 목록을 남긴다. */
    val rewritten: RewriteOutcome.Rewritten? get() = null

    /**
     * 판정까지 갔던 종결만 값을 갖는다. 저장 게이트가 **차단된 쿼리의 룰 hit도** 통계에 넣을 때 쓴다 —
     * "무엇이 자주 걸리는가"가 통계의 목적이므로 걸린 것을 빼면 목적이 뒤집힌다.
     */
    val report: LintReportDto? get() = null

    /** 경계에서만 호출한다. 게이트 본문은 예외를 만들지 않는다. */
    fun raise(): Nothing = throw GateStopException(this)

    /** **이 사람이 할 수 없다** — 열람 권한·소유권·검토 상태. */
    data class Denied(override val code: AuditCode, val message: String) : GateStop {
        override val detail: String get() = message
        override val status: HttpStatus get() = HttpStatus.FORBIDDEN
        override val body: Any get() = GateErrorDto(code, message)
    }

    /**
     * **이 요청을 처리할 수 없다** — 실행 대상 매핑 부재, 재작성 불가.
     * 권한 문제가 아니다: 같은 사람이 다른 SQL을 쓰면 통과한다.
     */
    data class Unprocessable(override val code: AuditCode, val message: String) : GateStop {
        override val detail: String get() = message
        override val status: HttpStatus get() = HttpStatus.UNPROCESSABLE_ENTITY
        override val body: Any get() = GateErrorDto(code, message)
    }

    /** 접수·룰 판정 위반 — 사용자에게 위반 목록을 그대로 돌려주되 분류 코드를 함께 싣는다. */
    data class Violated(override val code: AuditCode, override val report: LintReportDto) : GateStop {
        override val detail: String get() =
            report.violations.filter { it.severity == Severity.BLOCK }.joinToString("; ") { it.message }
        override val status: HttpStatus get() = HttpStatus.UNPROCESSABLE_ENTITY
        override val body: Any get() = report.copy(code = code)
    }

    /**
     * **자기 계약을 가진 차단** — 데이터 권한(spec 007 §6.5)과 승인 게이트(spec 005 §7).
     *
     * [Denied]와 갈라져 있는 이유는 상태 코드가 아니라 **바디**다: 이쪽은 거부된 테이블 목록·요청 상태
     * 같은 자기 필드를 실어 프론트가 좁게 분기한다. 그래서 [body]는 원래 DTO를 **그대로** 내보낸다.
     *
     * 예전에는 이것이 `AccessDenied`·`ApprovalDenied` 두 변종이었고 **네 멤버 구현이 문자 그대로
     * 같았다**. 같은 개념에 두 모양을 주면 나중에 한쪽만 고쳐진다 — 공통을 [BlockedDetail]로 올려 하나로 둔다.
     */
    data class Blocked(val denial: BlockedDetail) : GateStop {
        override val code: AuditCode get() = denial.code
        override val detail: String get() = denial.message
        override val status: HttpStatus get() = HttpStatus.FORBIDDEN
        override val body: Any get() = denial
    }

    /**
     * 실행 인프라 실패도 **종결이다**(spec 010 §4.5). "인프라 예외는 잡지 않는다"를 무조건 규율로 두면
     * 이 경로의 감사가 조용히 사라진다.
     *
     * 사용자에게는 **분류 코드와 안내문만** 준다 — MySQL 오류 메시지는 데이터 값을 에코한다
     * (`Truncated incorrect ... value: '...'`). 원문은 [detail]로 감사에만 남는다.
     */
    data class Failed(
        val failure: ExecutionFailure,
        override val rewritten: RewriteOutcome.Rewritten,
    ) : GateStop {
        override val code: AuditCode get() = failure.kind.auditCode
        override val detail: String get() = failure.detail
        override val status: HttpStatus get() = HttpStatus.UNPROCESSABLE_ENTITY
        override val outcome: ExecutionOutcome get() = ExecutionOutcome.ERROR
        override val body: Any get() = GateErrorDto(code, failure.kind.userMessage)
    }
}
