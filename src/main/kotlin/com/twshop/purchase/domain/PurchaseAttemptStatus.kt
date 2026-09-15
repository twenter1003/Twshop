package com.twshop.purchase.domain

/**
 * [PurchaseAttempt]가 가질 수 있는 결과 상태.
 *
 * 확정(confirm)/취소(cancel)는 이번 세션 범위 밖이므로, 재고 선점 성공 여부만 구분한다.
 */
enum class PurchaseAttemptStatus {
    /** 재고 유닛 하나를 선점(RESERVED)하는 데 성공했다. */
    RESERVED,

    /** 선점 가능한 재고 유닛이 없어 실패했다. */
    FAILED,
}
