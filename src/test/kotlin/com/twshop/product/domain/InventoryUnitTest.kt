package com.twshop.product.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals

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
