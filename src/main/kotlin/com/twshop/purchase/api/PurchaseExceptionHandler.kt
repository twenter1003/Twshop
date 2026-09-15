package com.twshop.purchase.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Purchase API에서 발생하는 도메인 예외를 HTTP 응답으로 변환한다. */
@RestControllerAdvice(basePackages = ["com.twshop.purchase"])
class PurchaseExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("message" to ex.message))
}
