package com.twshop.purchase.domain

import com.twshop.product.domain.InventoryUnit
import com.twshop.product.domain.Product
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
import java.time.Instant

/**
 * 구매자 한 명이 특정 상품의 재고를 선점하려 한 시도 한 건을 기록한다.
 *
 * 성공/실패 여부와 무관하게 시도 자체를 남긴다 — 재고가 소진된 이후에도 얼마나 많은
 * 선점 시도가 실패하는지는 한정 수량 판매 도메인에서 중요한 관측 대상이기 때문이다.
 * 성공한 시도만 [inventoryUnit]을 갖는다.
 *
 * 생성 시점의 [status]는 reserve() 유스케이스가 RESERVED 또는 FAILED로 확정해 넘기고,
 * RESERVED로 생성된 시도만 이후 [markConfirmed]/[markCancelled]로 전이될 수 있다.
 */
@Entity
@Table(name = "purchase_attempt")
class PurchaseAttempt(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    @Column(name = "buyer_id", nullable = false)
    val buyerId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_unit_id")
    val inventoryUnit: InventoryUnit? = null,

    status: PurchaseAttemptStatus,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PurchaseAttemptStatus = status
        private set

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /** RESERVED → CONFIRMED. 결제/구매가 확정될 때 호출한다. */
    fun markConfirmed() = transitionTo(PurchaseAttemptStatus.CONFIRMED)

    /** RESERVED → CANCELLED. 선점을 취소할 때 호출한다. */
    fun markCancelled() = transitionTo(PurchaseAttemptStatus.CANCELLED)

    private fun transitionTo(target: PurchaseAttemptStatus) {
        // RESERVED→CONFIRMED, RESERVED→CANCELLED 두 전이뿐이라 InventoryUnit처럼 별도
        // 전이 테이블을 두지 않고 메서드 안에서 직접 체크한다 (과설계 방지, CLAUDE.md 원칙 2).
        check(status == PurchaseAttemptStatus.RESERVED) {
            "${status}에서 ${target}(으)로 전이할 수 없습니다 (attemptId=$id)"
        }
        status = target
    }
}
