package com.twshop.product.infrastructure

import com.twshop.product.domain.InventoryStatus
import com.twshop.product.domain.InventoryUnit
import org.springframework.data.jpa.repository.JpaRepository

/** [InventoryUnit]에 대한 기본 CRUD 및 상품/상태별 조회를 제공한다. */
interface InventoryUnitRepository : JpaRepository<InventoryUnit, Long> {
    fun findByProductId(productId: Long): List<InventoryUnit>
    fun countByProductIdAndStatus(productId: Long, status: InventoryStatus): Long
}
