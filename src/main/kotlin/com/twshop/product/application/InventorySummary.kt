package com.twshop.product.application

/** 상품 하나의 재고 상태별 유닛 개수 요약. */
data class InventorySummary(
    val available: Long,
    val reserved: Long,
    val confirmed: Long,
    val cancelled: Long,
)
