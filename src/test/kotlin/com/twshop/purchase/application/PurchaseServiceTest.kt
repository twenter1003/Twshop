package com.twshop.purchase.application

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryStatus
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.purchase.domain.PurchaseAttemptStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

@SpringBootTest
@Transactional
class PurchaseServiceTest @Autowired constructor(
    private val purchaseService: PurchaseService,
    private val productService: ProductService,
    private val inventoryUnitRepository: InventoryUnitRepository,
) {

    @Test
    fun `가용 재고가 있으면 유닛 하나를 선점하고 RESERVED로 기록한다`() {
        val product = productService.createProduct("선점 테스트", null, BigDecimal.TEN, 1)

        val attempt = purchaseService.reserve(product.id!!, "buyer-1")

        assertEquals(PurchaseAttemptStatus.RESERVED, attempt.status)
        assertNotNull(attempt.inventoryUnit)
        val unit = inventoryUnitRepository.findById(attempt.inventoryUnit!!.id!!).get()
        assertEquals(InventoryStatus.RESERVED, unit.status)
    }

    @Test
    fun `가용 재고가 없으면 FAILED로 기록하고 유닛은 건드리지 않는다`() {
        val product = productService.createProduct("품절 테스트", null, BigDecimal.TEN, 1)
        purchaseService.reserve(product.id!!, "buyer-1")

        val secondAttempt = purchaseService.reserve(product.id!!, "buyer-2")

        assertEquals(PurchaseAttemptStatus.FAILED, secondAttempt.status)
        assertNull(secondAttempt.inventoryUnit)
    }

    @Test
    fun `존재하지 않는 상품을 선점하려 하면 예외가 발생한다`() {
        assertThrows<NoSuchElementException> { purchaseService.reserve(-1L, "buyer-1") }
    }

    @Test
    fun `RESERVED 상태의 시도를 확정하면 유닛과 시도 모두 CONFIRMED가 된다`() {
        val product = productService.createProduct("확정 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")

        val confirmed = purchaseService.confirm(attempt.id!!)

        assertEquals(PurchaseAttemptStatus.CONFIRMED, confirmed.status)
        val unit = inventoryUnitRepository.findById(confirmed.inventoryUnit!!.id!!).get()
        assertEquals(InventoryStatus.CONFIRMED, unit.status)
    }

    @Test
    fun `RESERVED 상태의 시도를 취소하면 시도는 CANCELLED, 유닛은 AVAILABLE로 복구된다`() {
        val product = productService.createProduct("취소 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")

        val cancelled = purchaseService.cancel(attempt.id!!)

        assertEquals(PurchaseAttemptStatus.CANCELLED, cancelled.status)
        val unit = inventoryUnitRepository.findById(cancelled.inventoryUnit!!.id!!).get()
        assertEquals(InventoryStatus.AVAILABLE, unit.status)
    }

    @Test
    fun `RESERVED가 아닌 시도를 확정하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("품절 확정 테스트", null, BigDecimal.TEN, 1)
        purchaseService.reserve(product.id!!, "buyer-1")
        val failedAttempt = purchaseService.reserve(product.id!!, "buyer-2")

        assertFailsWith<IllegalStateException> { purchaseService.confirm(failedAttempt.id!!) }
    }

    @Test
    fun `RESERVED가 아닌 시도를 취소하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("품절 취소 테스트", null, BigDecimal.TEN, 1)
        purchaseService.reserve(product.id!!, "buyer-1")
        val failedAttempt = purchaseService.reserve(product.id!!, "buyer-2")

        assertFailsWith<IllegalStateException> { purchaseService.cancel(failedAttempt.id!!) }
    }

    @Test
    fun `존재하지 않는 시도를 확정하려 하면 예외가 발생한다`() {
        assertThrows<NoSuchElementException> { purchaseService.confirm(-1L) }
    }

    @Test
    fun `존재하지 않는 시도를 취소하려 하면 예외가 발생한다`() {
        assertThrows<NoSuchElementException> { purchaseService.cancel(-1L) }
    }

    // 아래 네 테스트는 위 "RESERVED가 아닌 시도를 확정/취소하려 하면 예외가 발생한다" 테스트와
    // 달리 FAILED가 아니라 이미 CONFIRMED/CANCELLED로 전이된 attempt를 대상으로 한다.
    // FAILED 시도는 inventoryUnit이 null이라 confirm()/cancel()의 `attempt.inventoryUnit!!`
    // 역참조까지 가지 않고 상태 체크에서 바로 막히는 반면, 이미 CONFIRMED/CANCELLED인 시도는
    // inventoryUnit이 non-null이므로 같은 상태 체크가 재확정/재취소(중복 처리)도 실제로
    // 막는지를 별도로 검증할 필요가 있다.

    @Test
    fun `이미 CONFIRMED된 시도를 다시 확정하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("중복 확정 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.confirm(attempt.id!!)

        assertFailsWith<IllegalStateException> { purchaseService.confirm(attempt.id) }
    }

    @Test
    fun `이미 CANCELLED된 시도를 확정하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("취소 후 확정 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.cancel(attempt.id!!)

        assertFailsWith<IllegalStateException> { purchaseService.confirm(attempt.id) }
    }

    @Test
    fun `이미 CONFIRMED된 시도를 취소하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("확정 후 취소 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.confirm(attempt.id!!)

        assertFailsWith<IllegalStateException> { purchaseService.cancel(attempt.id) }
    }

    @Test
    fun `이미 CANCELLED된 시도를 다시 취소하려 하면 예외가 발생한다`() {
        val product = productService.createProduct("중복 취소 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.cancel(attempt.id!!)

        assertFailsWith<IllegalStateException> { purchaseService.cancel(attempt.id) }
    }

    @Test
    fun `취소로 복구된 유닛은 같은 상품의 다음 선점 요청에서 다시 판매된다`() {
        // 재고 손실 방지가 실제로 동작하는지 end-to-end로 확인한다: 재고 1개짜리 상품에서
        // buyer-1이 선점 후 취소하면, buyer-2가 "같은 유닛"을 다시 선점할 수 있어야 한다.
        val product = productService.createProduct("재판매 테스트", null, BigDecimal.TEN, 1)
        val firstAttempt = purchaseService.reserve(product.id!!, "buyer-1")
        val releasedUnitId = firstAttempt.inventoryUnit!!.id!!
        purchaseService.cancel(firstAttempt.id!!)

        val secondAttempt = purchaseService.reserve(product.id, "buyer-2")

        assertEquals(PurchaseAttemptStatus.RESERVED, secondAttempt.status)
        assertEquals(releasedUnitId, secondAttempt.inventoryUnit!!.id)
        val unit = inventoryUnitRepository.findById(releasedUnitId).get()
        assertEquals(InventoryStatus.RESERVED, unit.status)
    }
}
