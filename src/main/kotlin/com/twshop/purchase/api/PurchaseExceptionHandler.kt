package com.twshop.purchase.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Purchase API에서 발생하는 도메인 예외를 HTTP 응답으로 변환한다. */
@RestControllerAdvice(basePackages = ["com.twshop.purchase"])
class PurchaseExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("message" to ex.message))

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("message" to ex.message))

    // confirm/cancel이 낙관적 락(@Version) 충돌로 실패했을 때 발생한다. 같은 attempt를
    // 동시에 confirm/cancel하려 한 경우 등 "상태가 바뀐 채로 경합했다"는 걸 클라이언트가
    // 일반적인 400(잘못된 요청)과 구분할 수 있도록 409 Conflict로 응답한다.
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLockConflict(
        ex: ObjectOptimisticLockingFailureException,
    ): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to "동시에 상태가 변경되어 요청을 처리하지 못했습니다"))
}
