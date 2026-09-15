package com.twshop.product.domain

/**
 * 재고 유닛([InventoryUnit])이 가질 수 있는 상태.
 *
 * AVAILABLE(가용) → RESERVED(선점) → CONFIRMED(확정)이 기본 흐름이지만,
 * 선점/확정 단계 모두에서 CANCELLED(취소)로 빠질 수 있고, 취소된 유닛은 재고 손실을
 * 막기 위해 다시 AVAILABLE로 복구된다. 허용되는 전이는 [InventoryStatusTransitions]에
 * 선언적으로 정의한다.
 */
enum class InventoryStatus {
    /** 아직 아무에게도 선점되지 않아 판매 가능한 상태 */
    AVAILABLE,

    /** 특정 주문 시도에 의해 임시로 붙들린 상태 (결제 대기 등) */
    RESERVED,

    /** 결제/구매가 확정되어 판매가 종료된 상태 */
    CONFIRMED,

    /** 선점 또는 구매가 취소된 상태 */
    CANCELLED,
}
