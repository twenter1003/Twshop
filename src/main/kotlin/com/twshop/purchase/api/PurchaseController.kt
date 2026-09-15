package com.twshop.purchase.api

import com.twshop.purchase.application.PurchaseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 재고 선점(reserve)/확정(confirm)/취소(cancel) API.
 *
 * confirm/cancel 모두 요청 바디 없이 path의 `attemptId`만으로 대상을 식별한다
 * (2026-09-15 결정, docs/decisions.md 참고).
 */
@RestController
@RequestMapping("/api/purchase-attempts")
class PurchaseController(
    private val purchaseService: PurchaseService,
) {

    /** 재고 선점을 시도한다. 성공/실패 모두 201로 응답하고, 결과는 body의 status로 구분한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun reserve(@Valid @RequestBody request: ReservePurchaseRequest): PurchaseAttemptResponse =
        PurchaseAttemptResponse.from(purchaseService.reserve(request.productId, request.buyerId))

    /** RESERVED 상태인 선점 시도를 구매 확정으로 전이한다. */
    @PostMapping("/{attemptId}/confirm")
    fun confirm(@PathVariable attemptId: Long): PurchaseAttemptResponse =
        PurchaseAttemptResponse.from(purchaseService.confirm(attemptId))

    /** RESERVED 상태인 선점 시도를 취소하고, 재고 유닛을 다시 판매 가능 상태로 되돌린다. */
    @PostMapping("/{attemptId}/cancel")
    fun cancel(@PathVariable attemptId: Long): PurchaseAttemptResponse =
        PurchaseAttemptResponse.from(purchaseService.cancel(attemptId))
}
