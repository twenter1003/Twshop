package com.twshop.waiting.application

import com.twshop.product.application.ProductService
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 대기열 진입(enter)/순번 조회(getRank) 유스케이스를 실 Redis 위에서 검증하는 통합 테스트.
 *
 * 단일 스레드로 정상/예외 흐름을 확인하는 수준이라 `integration` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고). 동시
 * 진입 시 시퀀스 유일성은 [WaitingServiceConcurrencyTest]에서 별도로 검증한다.
 *
 * 각 테스트는 [ProductService.createProduct]로 새 상품(= 새 productId)을 만들어 쓰므로,
 * Redis 키가 테스트마다 자연스럽게 분리된다 — MySQL과 달리 `@Transactional` 롤백으로
 * Redis 상태까지 정리되지는 않지만, productId가 겹치지 않아 별도 정리(cleanup)가 필요 없다.
 */
@Tag("integration")
@SpringBootTest
class WaitingServiceTest @Autowired constructor(
    private val waitingService: WaitingService,
    private val productService: ProductService,
    private val redisTemplate: StringRedisTemplate,
) {

    private fun newProductId(): Long =
        requireNotNull(productService.createProduct("대기열 테스트 상품", null, BigDecimal.TEN, 1).id)

    @Test
    fun `대기열에 처음 진입하면 1번 순번과 함께 시퀀스가 발급된다`() {
        val productId = newProductId()

        val result = waitingService.enter(productId, "buyer-1")

        assertEquals(1L, result.rank.toDisplayRank())
        assertTrue(result.issuedSequence >= 1L)
    }

    @Test
    fun `먼저 진입한 사람이 더 앞 순번을 가진다`() {
        val productId = newProductId()

        waitingService.enter(productId, "buyer-1")
        waitingService.enter(productId, "buyer-2")

        assertEquals(1L, waitingService.getRank(productId, "buyer-1").toDisplayRank())
        assertEquals(2L, waitingService.getRank(productId, "buyer-2").toDisplayRank())
    }

    @Test
    fun `대기열에 없는 buyerId를 조회하면 예외가 발생한다`() {
        val productId = newProductId()
        waitingService.enter(productId, "buyer-1")

        assertFailsWith<NoSuchElementException> {
            waitingService.getRank(productId, "buyer-없음")
        }
    }

    @Test
    fun `존재하지 않는 상품에 진입하거나 순번을 조회하면 예외가 발생한다`() {
        assertFailsWith<NoSuchElementException> {
            waitingService.enter(-1L, "buyer-1")
        }
        assertFailsWith<NoSuchElementException> {
            waitingService.getRank(-1L, "buyer-1")
        }
    }

    /**
     * ADR의 핵심 정책 검증: 재진입 시 항상 새 순번이 발급되고, 이전 순번은 남지 않는다.
     *
     * buyer-1이 먼저 들어오고(1번) buyer-2가 뒤이어 들어온 뒤(2번), buyer-1이 다시 들어오면
     * ZADD가 같은 member의 score를 덮어쓰므로 buyer-1은 맨 뒤로 밀려나고(2번), buyer-2가
     * 1번으로 당겨진다. 대기열 크기(ZCARD)도 여전히 2여야 한다 — 만약 재진입이 새 member를
     * 추가하는 방식이었다면 3이 됐을 것이다.
     */
    @Test
    fun `재진입하면 새 순번이 발급되고 이전 순번은 사라진다`() {
        val productId = newProductId()

        waitingService.enter(productId, "buyer-1")
        waitingService.enter(productId, "buyer-2")
        val reEntryResult = waitingService.enter(productId, "buyer-1")

        assertEquals(1L, waitingService.getRank(productId, "buyer-2").toDisplayRank())
        assertEquals(2L, waitingService.getRank(productId, "buyer-1").toDisplayRank())
        assertEquals(2L, reEntryResult.rank.toDisplayRank())

        val queueSize = redisTemplate.opsForZSet().size("waiting:$productId:queue")
        assertEquals(2L, queueSize)
    }
}
