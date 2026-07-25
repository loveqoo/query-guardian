package com.loveqoo.queryguardian.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 게이트 차단: 저장 요청이 BLOCK 위반에 걸렸다. 422 + 위반 목록으로 응답한다 (spec §8). */
class BlockedException(val report: LintReportDto) : RuntimeException("query blocked")

class NotFoundException(message: String) : RuntimeException(message)

/** 무결성 충돌(중복 매핑, 참조 중 삭제 등) → 409 (spec 002 H5). */
class ConflictException(message: String) : RuntimeException(message)

data class ErrorResponse(val message: String)

@RestControllerAdvice
class ApiExceptionHandler {

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

    /** 승인 차단 — 룰 차단(422)과 구분되는 403 (spec 005 §7). */
    @ExceptionHandler(com.loveqoo.queryguardian.approval.ApprovalBlockedException::class)
    fun approvalBlocked(e: com.loveqoo.queryguardian.approval.ApprovalBlockedException): ResponseEntity<ApprovalBlockedDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.detail)
}
