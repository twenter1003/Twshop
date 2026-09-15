package com.twshop.product.application

import com.twshop.product.domain.InventoryStatus
import com.twshop.product.domain.InventoryUnit
import com.twshop.product.domain.Product
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.product.infrastructure.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * Product/Inventory에 대한 기본 CRUD 유스케이스를 담당한다.
 *
 * 상품 생성 시 초기 수량만큼 [InventoryUnit]을 함께 만든다. 재고는 카운터가 아니라
 * row 자체이므로(2026-09-14 결정 참고), "상품 생성"이 곧 "재고 확보"가 된다.
 */
@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val inventoryUnitRepository: InventoryUnitRepository,
) {

    @Transactional
    fun createProduct(name: String, description: String?, price: BigDecimal, initialQuantity: Int): Product {
        require(initialQuantity > 0) { "초기 수량은 1개 이상이어야 합니다" }

        val product = productRepository.save(Product(name = name, description = description, price = price))
        val units = (1..initialQuantity).map {
            InventoryUnit(product = product, unitCode = "${product.id}-${UUID.randomUUID()}")
        }
        inventoryUnitRepository.saveAll(units)
        return product
    }

    fun getProduct(productId: Long): Product =
        productRepository.findById(productId)
            .orElseThrow { NoSuchElementException("상품을 찾을 수 없습니다: $productId") }

    fun listProducts(): List<Product> = productRepository.findAll()

    @Transactional
    fun deleteProduct(productId: Long) {
        val product = getProduct(productId)
        inventoryUnitRepository.deleteAll(inventoryUnitRepository.findByProductId(product.id!!))
        productRepository.delete(product)
    }

    fun getInventorySummary(productId: Long): InventorySummary {
        getProduct(productId) // 존재하지 않는 상품이면 여기서 예외 발생

        return InventorySummary(
            available = inventoryUnitRepository.countByProductIdAndStatus(productId, InventoryStatus.AVAILABLE),
            reserved = inventoryUnitRepository.countByProductIdAndStatus(productId, InventoryStatus.RESERVED),
            confirmed = inventoryUnitRepository.countByProductIdAndStatus(productId, InventoryStatus.CONFIRMED),
            cancelled = inventoryUnitRepository.countByProductIdAndStatus(productId, InventoryStatus.CANCELLED),
        )
    }
}
