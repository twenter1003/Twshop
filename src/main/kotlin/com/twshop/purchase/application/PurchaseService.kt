package com.twshop.purchase.application

import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.product.infrastructure.ProductRepository
import com.twshop.purchase.domain.PurchaseAttempt
import com.twshop.purchase.domain.PurchaseAttemptStatus
import com.twshop.purchase.infrastructure.PurchaseAttemptRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 재고 선점(reserve) 유스케이스.
 *
 * 여러 요청이 동시에 같은 상품의 재고를 선점하려 할 때, 비관적 락(`SELECT ... FOR UPDATE`)으로
 * [InventoryUnit] row 하나를 잠근 뒤 상태를 전이한다. 낙관적 락 대신 비관적 락을 선택한 이유는
 * "마지막 재고를 여러 명이 동시에 노리는" hot-row 경합에서 낙관적 락은 재시도가 폭증하는 반면,
 * 비관적 락은 DB가 순서를 보장해 애플리케이션이 재시도 로직을 직접 구현할 필요가 없기 때문이다
 * (2026-09-15 결정, docs/decisions.md 참고).
 */
@Service
class PurchaseService(
    private val productRepository: ProductRepository,
    private val inventoryUnitRepository: InventoryUnitRepository,
    private val purchaseAttemptRepository: PurchaseAttemptRepository,
) {

    /**
     * [buyerId]가 [productId]의 재고 유닛 하나를 선점을 시도한다.
     *
     * 가용 유닛이 있으면 하나를 잠가서 RESERVED로 전이하고, 없으면 실패로 기록한다.
     * 두 경우 모두 [PurchaseAttempt]가 남으므로 예외 대신 반환값의 [PurchaseAttempt.status]로
     * 성공/실패를 구분한다.
     *
     * @throws NoSuchElementException [productId]에 해당하는 상품이 없을 때
     */
    @Transactional
    fun reserve(productId: Long, buyerId: String): PurchaseAttempt {
        val product = productRepository.findById(productId)
            .orElseThrow { NoSuchElementException("상품을 찾을 수 없습니다: $productId") }

        val unit = inventoryUnitRepository.findAvailableForUpdate(productId, PageRequest.of(0, 1))
            .firstOrNull()
            ?: return purchaseAttemptRepository.save(
                PurchaseAttempt(product = product, buyerId = buyerId, status = PurchaseAttemptStatus.FAILED),
            )

        unit.reserve()
        return purchaseAttemptRepository.save(
            PurchaseAttempt(
                product = product,
                buyerId = buyerId,
                inventoryUnit = unit,
                status = PurchaseAttemptStatus.RESERVED,
            ),
        )
    }
}
