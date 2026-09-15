package com.twshop.purchase.api

import com.twshop.purchase.application.PurchaseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 재고 선점(reserve) API.
 *
 * 선점 확정(confirm)/취소(cancel) API는 이번 세션 범위 밖이라 아직 없다.
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
}
