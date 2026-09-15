package com.twshop.purchase.application

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryStatus
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.purchase.domain.PurchaseAttemptStatus
import com.twshop.purchase.infrastructure.PurchaseAttemptRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `reserve()`의 비관적 락 경합과, `confirm()`/`cancel()`의 낙관적 락 경합을 각각 검증한다.
 *
 * 클래스 단위 `@Transactional`을 쓰지 않는다 — 각 스레드가 자기 트랜잭션 안에서 락을 걸어야
 * 경합 상황이 재현되는데, 테스트 전체를 하나의 트랜잭션으로 감싸면 모든 스레드가 같은
 * 커넥션/트랜잭션을 공유하게 되어 검증하려는 상황 자체가 사라진다.
 *
 * `FOR UPDATE` 락이 스레드를 블로킹했다가 커밋 후 조건을 재평가하는 것까지는 실제 MySQL
 * 위에서 확인했지만(2026-09-15 CI를 MySQL 기준으로 전환, docs/decisions.md 참고), 락
 * 대기시간·데드락 같은 InnoDB 갭 락 고유 동작을 겨냥한 테스트는 아니다. 이 테스트는
 * "중복 선점이 없다"는 애플리케이션 레벨 불변식만 검증한다.
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

    /**
     * 같은 [com.twshop.purchase.domain.PurchaseAttempt]에 대해 confirm/cancel이 거의 동시에
     * 들어오면, 둘 다 성공하는 게 아니라 하나만 성공해야 한다.
     *
     * 나머지 하나가 실패하는 이유는 스레드 스케줄링 타이밍에 따라 둘 중 하나다 — 두 트랜잭션이
     * 정말 겹쳐서 둘 다 같은 버전의 row를 읽은 뒤 나중에 커밋하는 쪽이 [InventoryUnit]의
     * `@Version` 낙관적 락 충돌([ObjectOptimisticLockingFailureException])로 실패하거나,
     * 뒤늦게 시작한 트랜잭션이 이미 커밋된 최신 상태(RESERVED가 아님)를 그대로 읽어
     * `PurchaseService`의 상태 체크에서 [IllegalStateException]으로 실패한다. 둘 다 "이미
     * 처리된 시도를 다시 처리하지 않는다"는 같은 불변식을 다른 계층(DB 버전 체크 vs
     * 애플리케이션 상태 체크)에서 지킨 것이라 실패 원인이 아니라 "하나만 성공한다"는
     * 결과만 검증한다 — 예외 타입 하나로 단정하면 스케줄링 타이밍에 따라 테스트가 flaky해진다
     * (실측: 5회 반복 중 1회, 예외 타입을 ObjectOptimisticLockingFailureException으로만
     * 단정했을 때 IllegalStateException이 나와 실패하는 걸 확인).
     */
    @Test
    fun `같은 시도에 대해 confirm과 cancel이 동시에 들어오면 하나만 성공하고 나머지는 낙관적 락 충돌로 실패한다`() {
        val product = productService.createProduct("낙관적 락 테스트 상품", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        val attemptId = attempt.id!!

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        val successes = ConcurrentLinkedQueue<PurchaseAttemptStatus>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        executor.submit {
            startLatch.await()
            try {
                successes.add(purchaseService.confirm(attemptId).status)
            } catch (ex: Throwable) {
                failures.add(ex)
            } finally {
                doneLatch.countDown()
            }
        }
        executor.submit {
            startLatch.await()
            try {
                successes.add(purchaseService.cancel(attemptId).status)
            } catch (ex: Throwable) {
                failures.add(ex)
            } finally {
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(1, successes.size)
        assertEquals(1, failures.size)
        val failure = failures.single()
        assertTrue(
            failure is ObjectOptimisticLockingFailureException || failure is IllegalStateException,
            "예상치 못한 예외 타입: $failure",
        )
    }
}
