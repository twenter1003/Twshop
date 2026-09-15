package com.twshop.product.infrastructure

import com.twshop.product.domain.InventoryStatus
import com.twshop.product.domain.InventoryUnit
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** [InventoryUnit]에 대한 기본 CRUD 및 상품/상태별 조회를 제공한다. */
interface InventoryUnitRepository : JpaRepository<InventoryUnit, Long> {
    fun findByProductId(productId: Long): List<InventoryUnit>
    fun countByProductIdAndStatus(productId: Long, status: InventoryStatus): Long

    /**
     * 특정 상품의 가용(AVAILABLE) 유닛 중 [pageable]이 정한 개수만큼을 비관적 락(FOR UPDATE)으로 잠근다.
     *
     * 가용 유닛 전체가 아니라 실제로 선점할 만큼만 잠그기 위해 `pageable`로 LIMIT을 건다.
     * 조건을 만족하는 유닛 전체를 잠그면, 서로 다른 유닛을 노리는 동시 요청까지 불필요하게
     * 직렬화되어 처리량이 떨어진다 (2026-09-15 락 전략 결정, docs/decisions.md 참고).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from InventoryUnit u where u.product.id = :productId and u.status = com.twshop.product.domain.InventoryStatus.AVAILABLE order by u.id asc")
    fun findAvailableForUpdate(@Param("productId") productId: Long, pageable: Pageable): List<InventoryUnit>
}
