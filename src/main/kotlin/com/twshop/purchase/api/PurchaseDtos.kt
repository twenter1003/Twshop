package com.twshop.purchase.api

import com.twshop.purchase.domain.PurchaseAttempt
import com.twshop.purchase.domain.PurchaseAttemptStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

/** 재고 선점 요청. */
data class ReservePurchaseRequest(
    @field:NotNull
    val productId: Long,
    @field:NotBlank
    val buyerId: String,
)

/** 선점 시도 결과 응답. [status]로 성공/실패를 구분한다. */
data class PurchaseAttemptResponse(
    val id: Long,
    val productId: Long,
    val buyerId: String,
    val inventoryUnitId: Long?,
    val status: PurchaseAttemptStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(attempt: PurchaseAttempt): PurchaseAttemptResponse = PurchaseAttemptResponse(
            id = requireNotNull(attempt.id),
            productId = requireNotNull(attempt.product.id),
            buyerId = attempt.buyerId,
            inventoryUnitId = attempt.inventoryUnit?.id,
            status = attempt.status,
            createdAt = attempt.createdAt,
        )
    }
}
