package com.twshop.product.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 판매 대상 상품.
 *
 * 재고 수량은 이 엔티티에 카운터 필드로 두지 않는다. [InventoryUnit] row 개수 자체가
 * 재고 수량을 의미하므로, 카운터를 별도로 두면 두 값이 어긋날 위험(갱신 누락으로 인한
 * 정합성 깨짐)이 생긴다 (2026-09-14 재고 모델링 결정, docs/decisions.md 참고).
 */
@Entity
@Table(name = "product")
class Product(
    @Column(nullable = false)
    var name: String,

    @Column
    var description: String? = null,

    @Column(nullable = false, precision = 12, scale = 2)
    var price: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
