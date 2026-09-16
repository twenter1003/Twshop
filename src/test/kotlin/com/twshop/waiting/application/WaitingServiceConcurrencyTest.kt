package com.twshop.waiting.application

import com.twshop.product.application.ProductService
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * 여러 스레드가 동시에 같은 productId 대기열에 진입해도 발급되는 시퀀스 값이 겹치지 않음을
 * 검증한다.
 *
 * Redis의 `INCR`은 단일 스레드 커맨드 실행 모델 덕에 그 자체로 원자적이라는 것이
 * 이 유일성 보장의 근거다(2026-09-16 "Waiting 모듈 대기열 설계" ADR 참고) — 애플리케이션
 * 코드가 별도 락을 걸지 않아도 Redis가 커맨드 단위로 순서를 보장한다.
 *
 * 멀티스레드로 경합을 재현하므로 `concurrency` 계층으로 분류한다(2026-09-16 "테스트
 * 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("concurrency")
@SpringBootTest
class WaitingServiceConcurrencyTest @Autowired constructor(
    private val waitingService: WaitingService,
    private val productService: ProductService,
) {

    @Test
    fun `동시에 여러 buyer가 진입해도 발급되는 시퀀스는 서로 겹치지 않는다`() {
        val concurrentRequests = 50
        val product = productService.createProduct("대기열 동시성 테스트 상품", null, BigDecimal.TEN, 1)
        val productId = requireNotNull(product.id)

        val executor = Executors.newFixedThreadPool(concurrentRequests)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(concurrentRequests)
        val issuedSequences = ConcurrentLinkedQueue<Long>()

        repeat(concurrentRequests) { i ->
            executor.submit {
                startLatch.await()
                try {
                    issuedSequences.add(waitingService.enter(productId, "buyer-$i").issuedSequence)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(concurrentRequests, issuedSequences.size)
        // 유일성 검증: Set으로 바꿔도 크기가 줄지 않으면 중복 발급이 없었다는 뜻이다.
        assertEquals(concurrentRequests, issuedSequences.toSet().size)
        // 이번 시나리오는 모두 서로 다른 buyerId의 첫 진입이라 결번 없이 1..N이 정확히
        // 한 번씩 발급돼야 한다 (재진입이 섞이는 시나리오였다면 결번이 생길 수 있다).
        assertEquals((1L..concurrentRequests.toLong()).toSet(), issuedSequences.toSet())
    }
}
