package com.twshop.purchase.domain

/**
 * [PurchaseAttempt]가 가질 수 있는 결과 상태.
 */
enum class PurchaseAttemptStatus {
    /** 재고 유닛 하나를 선점(RESERVED)하는 데 성공했다. */
    RESERVED,

    /** 선점 가능한 재고 유닛이 없어 실패했다. */
    FAILED,

    /** 선점된 유닛의 구매가 확정됐다. */
    CONFIRMED,

    /** 선점이 취소됐다. */
    CANCELLED,
}
