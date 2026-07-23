package com.loveqoo.queryguardian.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 게이트 차단: 저장 요청이 BLOCK 위반에 걸렸다. 422 + 위반 목록으로 응답한다 (spec §8). */
class BlockedException(val report: LintReportDto) : RuntimeException("query blocked")

class NotFoundException(message: String) : RuntimeException(message)

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
}
