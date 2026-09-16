package com.twshop.product.domain

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `InventoryUnit`의 상태 전이 규칙을 검증하는 순수 단위 테스트.
 *
 * Spring 컨텍스트나 DB 없이 도메인 객체만으로 검증 가능해 `unit` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("unit")
class InventoryUnitTest {

    private fun newUnit(): InventoryUnit {
        val product = Product(name = "테스트 상품", price = BigDecimal.TEN)
        return InventoryUnit(product = product, unitCode = "unit-1")
    }

    @Test
    fun `초기 상태는 AVAILABLE이다`() {
        val unit = newUnit()
        assertEquals(InventoryStatus.AVAILABLE, unit.status)
    }

    @Test
    fun `가용에서 선점, 확정까지 정상 전이된다`() {
        val unit = newUnit()

        unit.reserve()
        assertEquals(InventoryStatus.RESERVED, unit.status)

        unit.confirm()
        assertEquals(InventoryStatus.CONFIRMED, unit.status)
    }

    @Test
    fun `취소된 유닛은 다시 가용 상태로 복구할 수 있다`() {
        val unit = newUnit()

        unit.reserve()
        unit.cancel()
        assertEquals(InventoryStatus.CANCELLED, unit.status)

        unit.release()
        assertEquals(InventoryStatus.AVAILABLE, unit.status)
    }

    @Test
    fun `확정된 유닛도 취소할 수 있다`() {
        val unit = newUnit()

        unit.reserve()
        unit.confirm()
        unit.cancel()

        assertEquals(InventoryStatus.CANCELLED, unit.status)
    }

    @Test
    fun `가용 상태에서 확정으로 바로 전이할 수 없다`() {
        val unit = newUnit()
        assertThrows<IllegalStateException> { unit.confirm() }
    }

    @Test
    fun `확정된 유닛을 다시 선점할 수 없다`() {
        val unit = newUnit()
        unit.reserve()
        unit.confirm()

        assertThrows<IllegalStateException> { unit.reserve() }
    }

    @Test
    fun `가용 상태에서 바로 취소할 수 없다`() {
        val unit = newUnit()
        assertThrows<IllegalStateException> { unit.cancel() }
    }
}
