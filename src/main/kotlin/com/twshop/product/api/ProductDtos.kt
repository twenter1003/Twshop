package com.twshop.product.api

import com.twshop.product.application.InventorySummary
import com.twshop.product.domain.Product
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.Instant

/** 상품 생성 요청. */
data class CreateProductRequest(
    @field:NotBlank
    val name: String,
    val description: String?,
    @field:DecimalMin(value = "0", inclusive = false)
    val price: BigDecimal,
    @field:Min(1)
    val initialQuantity: Int,
)

/** 상품 응답. */
data class ProductResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val createdAt: Instant,
) {
    companion object {
        fun from(product: Product): ProductResponse = ProductResponse(
            id = requireNotNull(product.id),
            name = product.name,
            description = product.description,
            price = product.price,
            createdAt = product.createdAt,
        )
    }
}

/** 상품 하나의 재고 상태별 개수 응답. */
data class InventorySummaryResponse(
    val available: Long,
    val reserved: Long,
    val confirmed: Long,
    val cancelled: Long,
) {
    companion object {
        fun from(summary: InventorySummary): InventorySummaryResponse = InventorySummaryResponse(
            available = summary.available,
            reserved = summary.reserved,
            confirmed = summary.confirmed,
            cancelled = summary.cancelled,
        )
    }
}
