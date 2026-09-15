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

    /**
     * [attemptId]에 해당하는 선점 시도를 구매 확정으로 전이한다.
     *
     * reserve()가 비관적 락을 쓰는 것과 달리, confirm은 [InventoryUnit]의 `@Version` 낙관적
     * 락에만 의존한다. 여기서는 이미 특정 buyer가 선점해 둔 유닛 하나만 다루므로 경합이
     * "여러 buyer가 같은 재고를 동시에 다투는" hot-row 패턴이 아니라 "같은 attempt에 대한
     * confirm/cancel이 드물게 동시에 들어오는" 패턴이다. 드문 충돌을 감지하는 목적이라
     * 낙관적 락으로 충분하다고 판단했다 (2026-09-15 결정, docs/decisions.md 참고). 버전
     * 충돌이 나면 [org.springframework.orm.ObjectOptimisticLockingFailureException]이 그대로
     * 전파되며, 이 메서드는 별도로 잡지 않는다 — 컨트롤러/예외 핸들러 레벨에서 409로 변환한다.
     *
     * @throws NoSuchElementException [attemptId]에 해당하는 시도가 없을 때
     * @throws IllegalStateException 시도가 RESERVED 상태가 아닐 때
     */
    @Transactional
    fun confirm(attemptId: Long): PurchaseAttempt {
        val attempt = purchaseAttemptRepository.findById(attemptId)
            .orElseThrow { NoSuchElementException("선점 시도를 찾을 수 없습니다: $attemptId") }

        check(attempt.status == PurchaseAttemptStatus.RESERVED) {
            "RESERVED 상태가 아닌 시도는 확정할 수 없습니다 (attemptId=$attemptId, status=${attempt.status})"
        }

        attempt.inventoryUnit!!.confirm()
        attempt.markConfirmed()
        return purchaseAttemptRepository.save(attempt)
    }

    /**
     * [attemptId]에 해당하는 선점 시도를 취소한다.
     *
     * 유닛을 CANCELLED로 전이시키는 데서 멈추지 않고 곧바로 `release()`까지 호출해 AVAILABLE로
     * 되돌린다 — CANCELLED에 머물러 있으면 그 유닛은 다시 판매되지 못하고 영구히 재고 손실로
     * 남기 때문이다(`InventoryStatus` 주석에 이미 명시된 정책). cancel()과 release()를 한
     * 트랜잭션으로 묶어, "취소했지만 재고 복구는 안 된" 중간 상태가 외부에 노출되지 않게 한다.
     *
     * 동시성 제어는 confirm()과 동일하게 낙관적 락에만 의존한다.
     *
     * @throws NoSuchElementException [attemptId]에 해당하는 시도가 없을 때
     * @throws IllegalStateException 시도가 RESERVED 상태가 아닐 때
     */
    @Transactional
    fun cancel(attemptId: Long): PurchaseAttempt {
        val attempt = purchaseAttemptRepository.findById(attemptId)
            .orElseThrow { NoSuchElementException("선점 시도를 찾을 수 없습니다: $attemptId") }

        check(attempt.status == PurchaseAttemptStatus.RESERVED) {
            "RESERVED 상태가 아닌 시도는 취소할 수 없습니다 (attemptId=$attemptId, status=${attempt.status})"
        }

        attempt.inventoryUnit!!.cancel()
        attempt.inventoryUnit.release()
        attempt.markCancelled()
        return purchaseAttemptRepository.save(attempt)
    }
}
