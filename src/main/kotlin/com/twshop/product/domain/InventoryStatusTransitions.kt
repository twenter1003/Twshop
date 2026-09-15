package com.twshop.product.domain

/**
 * [InventoryStatus] 간 허용된 전이 규칙을 한 곳에 모아 선언한다.
 *
 * 상태별로 전이 로직을 나눠 담는 대신 전이 테이블 하나로 표현한 이유: 상태 다이어그램과
 * 코드가 1:1로 대응되어 새 상태/전이가 추가될 때 변경 지점이 이 객체 하나로 좁혀진다
 * (2026-09-14 결정, docs/decisions.md 참고).
 */
object InventoryStatusTransitions {

    private val allowedTransitions: Map<InventoryStatus, Set<InventoryStatus>> = mapOf(
        InventoryStatus.AVAILABLE to setOf(InventoryStatus.RESERVED),
        InventoryStatus.RESERVED to setOf(InventoryStatus.CONFIRMED, InventoryStatus.CANCELLED),
        InventoryStatus.CONFIRMED to setOf(InventoryStatus.CANCELLED),
        // 취소된 유닛은 재고로 복구되어 다시 판매 가능해진다 (재고 손실 방지가 비즈니스 요구사항)
        InventoryStatus.CANCELLED to setOf(InventoryStatus.AVAILABLE),
    )

    /** [from]에서 [to]로의 전이가 허용되는지 여부를 반환한다. */
    fun isAllowed(from: InventoryStatus, to: InventoryStatus): Boolean =
        allowedTransitions[from]?.contains(to) ?: false
}
