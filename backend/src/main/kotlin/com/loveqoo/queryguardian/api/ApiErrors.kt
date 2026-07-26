package com.loveqoo.queryguardian.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 게이트 차단: 저장 요청이 BLOCK 위반에 걸렸다. 422 + 위반 목록으로 응답한다 (spec §8). */
class BlockedException(val report: LintReportDto) : RuntimeException("query blocked")

class NotFoundException(message: String) : RuntimeException(message)

/** 미인증 → 401 (spec 007 §4). 프론트 부트스트랩의 정상 흐름이기도 하다. */
class UnauthenticatedException(message: String) : RuntimeException(message)

/** 인증됐으나 역할 권한 부족 → 403 (spec 007 §5). 데이터 권한 부족은 AccessBlockedException. */
class ForbiddenException(message: String) : RuntimeException(message)

/** 무결성 충돌(중복 매핑, 참조 중 삭제 등) → 409 (spec 002 H5). */
class ConflictException(message: String) : RuntimeException(message)

data class ErrorResponse(val message: String)

@RestControllerAdvice
class ApiExceptionHandler {

    /**
     * 실행 실패 (spec 008 §6): 사용자에게는 **분류 코드와 안내문만** 준다.
     * MySQL 오류 메시지는 데이터 값을 에코하므로(`Truncated incorrect ... value: '...'`) 원문은 감사에만 남는다.
     */
    @ExceptionHandler(com.loveqoo.queryguardian.exec.ExecutionFailure::class)
    fun onExecutionFailure(e: com.loveqoo.queryguardian.exec.ExecutionFailure) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(mapOf("code" to e.kind.auditCode.name, "message" to e.kind.userMessage))

    /**
     * **게이트 차단 — 상태와 바디를 `GateStop`이 정한다** (spec 010 A2).
     *
     * 여기에 `when`이 없는 것이 핵심이다. 새 차단 사유를 추가해도 이 핸들러는 그대로이고,
     * 응답 코드와 감사 코드는 같은 필드(`GateStop.code`)에서 나오므로 갈라질 자리가 없다.
     */
    @ExceptionHandler(com.loveqoo.queryguardian.query.GateStopException::class)
    fun gateStopped(e: com.loveqoo.queryguardian.query.GateStopException): ResponseEntity<Any> =
        ResponseEntity.status(e.stop.status).body(e.stop.body)

    @ExceptionHandler(BlockedException::class)
    fun blocked(e: BlockedException): ResponseEntity<LintReportDto> =
        ResponseEntity.unprocessableEntity().body(e.report)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "잘못된 요청"))

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "찾을 수 없음"))

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "충돌"))

    @ExceptionHandler(UnauthenticatedException::class)
    fun unauthenticated(e: UnauthenticatedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(e.message ?: "로그인이 필요합니다"))

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(e: ForbiddenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(e.message ?: "권한이 없습니다"))

    /** 데이터 권한 차단 — 역할 부족(ErrorResponse)과 구분되는 코드 포함 403 (spec 007 §6.5). */
    @ExceptionHandler(com.loveqoo.queryguardian.auth.AccessBlockedException::class)
    fun accessBlocked(e: com.loveqoo.queryguardian.auth.AccessBlockedException): ResponseEntity<AccessBlockedDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.detail)

    /** 승인 차단 — 룰 차단(422)과 구분되는 403 (spec 005 §7). */
    @ExceptionHandler(com.loveqoo.queryguardian.approval.ApprovalBlockedException::class)
    fun approvalBlocked(e: com.loveqoo.queryguardian.approval.ApprovalBlockedException): ResponseEntity<ApprovalBlockedDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.detail)
}
