package com.twshop.product.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * 한 상품 안에서 개별적으로 추적되는 판매 단위 하나.
 *
 * 좌석 예매의 "좌석 한 장"처럼, 상품의 재고를 수량이 아니라 row 단위로 관리한다.
 * 어떤 유닛이 선점/확정/취소 중 어느 상태인지를 유닛 단위로 추적할 수 있어야 하기
 * 때문이다 (2026-09-14 재고 모델링 결정, docs/decisions.md 참고).
 *
 * 상태 전이는 [InventoryStatusTransitions]에 정의된 규칙만 허용하며, 규칙을 벗어난
 * 전이를 시도하면 [IllegalStateException]을 던진다.
 *
 * 여러 요청이 동시에 같은 유닛을 선점하려 할 때의 동시성 제어(락 전략)는 이번 세션
 * 범위 밖이다 — 지금은 엔티티 수준의 상태 전이 규칙만 다루고, 실제 락 전략은
 * Purchase/Sale 모듈에서 트래픽 시나리오를 놓고 별도로 /decide한다.
 */
@Entity
@Table(name = "inventory_unit")
class InventoryUnit(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    @Column(name = "unit_code", nullable = false, unique = true)
    val unitCode: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InventoryStatus = InventoryStatus.AVAILABLE
        private set

    // confirm()/cancel() 동시 호출 경합을 감지하기 위한 낙관적 락 (2026-09-15 결정,
    // docs/decisions.md 참고). reserve()가 쓰는 비관적 락(findAvailableForUpdate)과는
    // 별개 메커니즘 — reserve는 여러 buyer가 같은 재고를 다투는 hot-row 경합이라 비관적
    // 락을 유지하고, confirm/cancel은 이미 특정 buyer가 선점한 유닛 하나를 다루는 드문
    // 충돌 감지가 목적이라 낙관적 락으로 충분하다고 판단했다.
    @Version
    val version: Long = 0

    /** 가용 → 선점. 주문 시도가 재고를 임시로 붙잡을 때 호출한다. */
    fun reserve() = transitionTo(InventoryStatus.RESERVED)

    /** 선점 → 확정. 결제가 완료되어 판매가 확정될 때 호출한다. */
    fun confirm() = transitionTo(InventoryStatus.CONFIRMED)

    /** 선점 또는 확정 → 취소. */
    fun cancel() = transitionTo(InventoryStatus.CANCELLED)

    /** 취소 → 가용. 취소된 유닛을 다시 판매 가능한 재고로 복구한다. */
    fun release() = transitionTo(InventoryStatus.AVAILABLE)

    private fun transitionTo(target: InventoryStatus) {
        check(InventoryStatusTransitions.isAllowed(status, target)) {
            "${status}에서 ${target}(으)로 전이할 수 없습니다 (unitCode=$unitCode)"
        }
        status = target
    }
}
