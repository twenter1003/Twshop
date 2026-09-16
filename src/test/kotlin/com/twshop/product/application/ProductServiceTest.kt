package com.twshop.product.application

import com.twshop.product.domain.InventoryStatus
import com.twshop.product.infrastructure.InventoryUnitRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `ProductService`의 상품 생성 유스케이스를 실 MySQL 위에서 검증하는 통합 테스트.
 *
 * 단일 스레드로 서비스/저장소 계약을 확인하는 수준이라 `integration` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ProductServiceTest @Autowired constructor(
    private val productService: ProductService,
    private val inventoryUnitRepository: InventoryUnitRepository,
) {

    @Test
    fun `상품을 생성하면 초기 수량만큼 재고 유닛이 함께 생성된다`() {
        val product = productService.createProduct(
            name = "한정판 스니커즈",
            description = null,
            price = BigDecimal("129000"),
            initialQuantity = 5,
        )

        assertNotNull(product.id)
        val units = inventoryUnitRepository.findByProductId(product.id!!)
        assertEquals(5, units.size)
        assertEquals(5, units.count { it.status == InventoryStatus.AVAILABLE })
    }

    @Test
    fun `초기 수량이 0 이하면 생성할 수 없다`() {
        assertThrows<IllegalArgumentException> {
            productService.createProduct("상품", null, BigDecimal.TEN, 0)
        }
    }

    @Test
    fun `존재하지 않는 상품을 조회하면 예외가 발생한다`() {
        assertThrows<NoSuchElementException> { productService.getProduct(-1L) }
    }

    @Test
    fun `상품을 삭제하면 재고 유닛도 함께 삭제된다`() {
        val product = productService.createProduct("삭제 테스트", null, BigDecimal.ONE, 2)

        productService.deleteProduct(product.id!!)

        assertEquals(0, inventoryUnitRepository.findByProductId(product.id!!).size)
    }

    @Test
    fun `재고 요약은 상태별 유닛 개수를 반환한다`() {
        val product = productService.createProduct("요약 테스트", null, BigDecimal.ONE, 3)

        val summary = productService.getInventorySummary(product.id!!)

        assertEquals(3, summary.available)
        assertEquals(0, summary.reserved)
        assertEquals(0, summary.confirmed)
        assertEquals(0, summary.cancelled)
    }
}
