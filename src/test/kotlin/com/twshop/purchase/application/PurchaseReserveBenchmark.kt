package com.twshop.purchase.application

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryStatus
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.purchase.domain.PurchaseAttemptStatus
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `reserve()` 락 전략(2026-09-15 결정, docs/decisions.md)의 before/after를 실측한다.
 *
 * - before: 락을 걸지 않은 순수 read-then-write 구현 ([reserveWithoutLock]).
 * - after: 실제로 채택한 비관적 락 구현 ([PurchaseService.reserve]).
 *
 * 같은 조건(재고 수, 동시 요청 수)에서 세 시나리오를 비교한다:
 * 1. 락 없음, 지연 없음 — 비관적 락과 처리량을 공정하게 비교하기 위한 baseline.
 * 2. 락 없음, 인위적 지연 — read-write 사이 경합 창을 넓혀 오버셀을 안정적으로 재현하는 용도.
 *    지연 자체가 처리량을 깎으므로 이 행의 TPS는 다른 행과 비교하지 않는다.
 * 3. 비관적 락 — 실제 채택안.
 *
 * 한 번만 측정하면 JVM 워밍업/실행 순서 효과로 결론이 왜곡된다(첫 실행이 항상 느리게 나옴).
 * 그래서 워밍업 1라운드를 버리고 [rounds]회를 측정해 중앙값으로 비교한다.
 *
 * 결과는 표준 출력과 `build/benchmark-result.txt`에 남긴다. 실측값은 사람이 확인 후
 * `docs/benchmarks/`에 기록한다.
 *
 * `@Tag("benchmark")`로 기본 `./gradlew test`에서 제외된다(build.gradle.kts 참고) — 타이밍에
 * 의존하는 시연이라 CI에서 반복 안정성을 보장하지 않기 때문이다.
 * 수동 실행: `./gradlew benchmarkTest`
 *
 * HTTP 스택 전체 처리량은 `load-test/`의 autocannon 벤치마크(`docs/benchmarks/2026-09-16-autocannon-reserve-http.md`) 참고.
 */
@SpringBootTest
@Tag("benchmark")
class PurchaseReserveBenchmark @Autowired constructor(
    private val purchaseService: PurchaseService,
    private val productService: ProductService,
    private val inventoryUnitRepository: InventoryUnitRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    private val quantity = 3
    private val concurrentRequests = 50
    private val rounds = 5

    @Test
    fun `락 없음 vs 비관적 락 처리량-정합성 비교`() {
        val scenarios = listOf(
            "락 없음, 지연 없음" to { productId: Long, buyerId: String -> reserveWithoutLock(productId, buyerId, delayMs = 0) },
            "락 없음, 지연 5ms (레이스 재현용)" to { productId: Long, buyerId: String -> reserveWithoutLock(productId, buyerId, delayMs = 5) },
            "비관적 락 (FOR UPDATE)" to { productId: Long, buyerId: String ->
                purchaseService.reserve(productId, buyerId).status == PurchaseAttemptStatus.RESERVED
            },
        )

        // 워밍업: 결과는 버리고 클래스 로딩/커넥션 풀/JIT을 데운다.
        scenarios.forEach { (label, reserve) -> runOneRound(label, reserve) }

        val results = scenarios.map { (label, reserve) ->
            val roundResults = (1..rounds).map { runOneRound(label, reserve) }
            AggregatedResult(
                label = label,
                medianElapsedMs = median(roundResults.map { it.elapsedMs }),
                medianTps = median(roundResults.map { it.tps }),
                totalSuccess = roundResults.sumOf { it.successCount },
                totalOversell = roundResults.sumOf { it.oversellCount },
                totalCapacity = rounds * quantity,
            )
        }

        val lines = mutableListOf(
            "=== reserve() 락 전략 벤치마크 (재고 $quantity, 동시 요청 $concurrentRequests, $rounds 라운드 중앙값 + 워밍업 1라운드) ===",
            String.format("%-30s | elapsed(ms) | TPS(req/s) | 성공/용량 | 오버셀", "시나리오"),
        )
        results.forEach { r ->
            lines += String.format(
                "%-30s | %11.1f | %10.1f | %5d/%-4d | %4d건",
                r.label, r.medianElapsedMs, r.medianTps, r.totalSuccess, r.totalCapacity, r.totalOversell,
            )
        }
        lines += ""
        lines += "처리량 비교(공정): '락 없음, 지연 없음' vs '비관적 락' — 둘 다 인위적 지연 없음."
        lines += "정합성 비교: '락 없음, 지연 5ms'에서만 오버셀 재현 시도 (지연이 있어 처리량은 참고하지 말 것)."
        lines.forEach(::println)
        // gradle이 기본적으로 stdout을 감추므로, 사람이 확인할 실측값을 파일로도 남긴다.
        File("build/benchmark-result.txt").writeText(lines.joinToString("\n"))
    }

    private data class RoundResult(val elapsedMs: Double, val tps: Double, val successCount: Int, val oversellCount: Int)
    private data class AggregatedResult(
        val label: String,
        val medianElapsedMs: Double,
        val medianTps: Double,
        val totalSuccess: Int,
        val totalOversell: Int,
        val totalCapacity: Int,
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /** [reserve]가 true(성공)/false(실패)를 반환하도록 통일해, 예외 유무가 아니라 반환값으로 성공을 판정한다. */
    private fun runOneRound(label: String, reserve: (Long, String) -> Boolean): RoundResult {
        val product = productService.createProduct(label, null, BigDecimal.TEN, quantity)
        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(concurrentRequests)
        val successCount = AtomicInteger(0)

        val start = System.nanoTime()
        repeat(concurrentRequests) { i ->
            executor.submit {
                startLatch.await()
                try {
                    if (reserve(product.id!!, "buyer-$i")) successCount.incrementAndGet()
                } catch (e: Exception) {
                    // 재고 소진/락 획득 실패 등은 실패로 취급하고 카운트하지 않는다.
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        doneLatch.await(60, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        executor.shutdown()

        val tps = concurrentRequests / (elapsedMs / 1000.0)
        // 오버셀: 물리적 재고 수보다 "선점 성공"으로 판정된 시도가 많으면, 같은 유닛을 둘 이상이
        // 동시에 가져갔다는 뜻이다.
        val oversell = (successCount.get() - quantity).coerceAtLeast(0)
        return RoundResult(elapsedMs, tps, successCount.get(), oversell)
    }

    /**
     * 락 없이 가용 유닛을 읽고(read) 상태를 바꿔 저장(write)하는 순진한 구현.
     *
     * [delayMs]는 read와 write 사이에 넣는 인위적 지연이다. 지연을 넣어야 경합 창이 넓어져
     * 레이스가 테스트에서 안정적으로 재현되지만, 그만큼 처리량 수치가 나빠지므로 지연을 준
     * 시나리오의 TPS는 다른 시나리오와 직접 비교하지 않는다.
     */
    private fun reserveWithoutLock(productId: Long, buyerId: String, delayMs: Long): Boolean =
        transactionTemplate.execute {
            val unit = inventoryUnitRepository.findByProductId(productId)
                .firstOrNull { it.status == InventoryStatus.AVAILABLE }
                ?: return@execute false
            if (delayMs > 0) Thread.sleep(delayMs)
            unit.reserve()
            inventoryUnitRepository.save(unit)
            true
        } ?: false
}
