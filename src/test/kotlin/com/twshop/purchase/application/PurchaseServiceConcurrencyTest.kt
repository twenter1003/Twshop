package com.twshop.purchase.application

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryStatus
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.purchase.domain.PurchaseAttemptStatus
import com.twshop.purchase.infrastructure.PurchaseAttemptRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * `reserve()`에 재고보다 많은 요청이 동시에 들어와도 중복 선점(같은 유닛이 두 번 RESERVED되는 것)이
 * 없음을 검증한다.
 *
 * 클래스 단위 `@Transactional`을 쓰지 않는다 — 각 스레드가 자기 트랜잭션 안에서 비관적 락을
 * 걸어야 경합 상황이 재현되는데, 테스트 전체를 하나의 트랜잭션으로 감싸면 모든 스레드가 같은
 * 커넥션/트랜잭션을 공유하게 되어 검증하려는 상황 자체가 사라진다.
 *
 * H2(MODE=MySQL)로 `FOR UPDATE` 락이 스레드를 블로킹했다가 커밋 후 조건을 재평가하는 것까지는
 * 확인했지만, 락 대기시간·데드락 같은 InnoDB 고유 동작까지 MySQL과 동일하다고 보장하지는
 * 않는다. 이 테스트는 "중복 선점이 없다"는 애플리케이션 레벨 불변식만 검증한다.
 */
@SpringBootTest
class PurchaseServiceConcurrencyTest @Autowired constructor(
    private val purchaseService: PurchaseService,
    private val productService: ProductService,
    private val inventoryUnitRepository: InventoryUnitRepository,
    private val purchaseAttemptRepository: PurchaseAttemptRepository,
) {

    @Test
    fun `재고보다 많은 동시 요청이 들어와도 재고 수만큼만 성공하고 유닛은 중복 선점되지 않는다`() {
        val quantity = 5
        val concurrentRequests = 30
        val product = productService.createProduct("동시성 테스트 상품", null, BigDecimal.TEN, quantity)

        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(concurrentRequests)

        repeat(concurrentRequests) { i ->
            executor.submit {
                startLatch.await()
                try {
                    purchaseService.reserve(product.id!!, "buyer-$i")
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        val attempts = purchaseAttemptRepository.findAll().filter { it.product.id == product.id }
        assertEquals(quantity, attempts.count { it.status == PurchaseAttemptStatus.RESERVED })
        assertEquals(concurrentRequests - quantity, attempts.count { it.status == PurchaseAttemptStatus.FAILED })

        val units = inventoryUnitRepository.findByProductId(product.id!!)
        assertEquals(quantity, units.count { it.status == InventoryStatus.RESERVED })

        // 서로 다른 유닛이 선점됐는지 확인 — 중복 id가 있으면 같은 유닛이 두 번 선점된 것
        val reservedUnitIds = attempts.mapNotNull { it.inventoryUnit?.id }
        assertEquals(reservedUnitIds.size, reservedUnitIds.toSet().size)
    }
}
