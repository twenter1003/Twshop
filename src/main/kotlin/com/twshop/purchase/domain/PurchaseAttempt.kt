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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: PurchaseAttemptStatus,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
