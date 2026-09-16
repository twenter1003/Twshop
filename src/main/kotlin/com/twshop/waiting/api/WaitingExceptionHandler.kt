package com.twshop.waiting.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Waiting API에서 발생하는 도메인 예외를 HTTP 응답으로 변환한다.
 *
 * `PurchaseExceptionHandler`(2026-09-15 결정)와 동일한 패턴을 따른다 — productId/buyerId를
 * 찾지 못하면 404, 그 외 도메인 규칙 위반은 400으로 매핑한다.
 */
@RestControllerAdvice(basePackages = ["com.twshop.waiting"])
class WaitingExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("message" to ex.message))

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("message" to ex.message))
}
