package com.twshop.purchase.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.twshop.product.application.ProductService
import com.twshop.purchase.application.PurchaseService
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 구매 시도(reserve) API의 HTTP 계약을 실 MySQL 위에서 검증하는 통합 테스트.
 *
 * 단일 스레드로 API 계약을 확인하는 수준이라 `integration` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PurchaseControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val productService: ProductService,
    private val purchaseService: PurchaseService,
) {

    @Test
    fun `재고가 있으면 선점 요청은 201과 함께 RESERVED 상태를 반환한다`() {
        val product = productService.createProduct("API 선점 테스트", null, BigDecimal.TEN, 1)
        val request = ReservePurchaseRequest(productId = product.id!!, buyerId = "buyer-1")

        mockMvc.perform(
            post("/api/purchase-attempts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andExpect(jsonPath("$.inventoryUnitId", notNullValue()))
    }

    @Test
    fun `재고가 없는 상품을 요청하면 404를 반환한다`() {
        val request = ReservePurchaseRequest(productId = -1L, buyerId = "buyer-1")

        mockMvc.perform(
            post("/api/purchase-attempts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `RESERVED 상태의 시도를 확정하면 200과 함께 CONFIRMED 상태를 반환한다`() {
        val product = productService.createProduct("API 확정 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")

        mockMvc.perform(post("/api/purchase-attempts/${attempt.id}/confirm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `RESERVED 상태의 시도를 취소하면 200과 함께 CANCELLED 상태를 반환한다`() {
        val product = productService.createProduct("API 취소 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")

        mockMvc.perform(post("/api/purchase-attempts/${attempt.id}/cancel"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `존재하지 않는 시도를 확정하려 하면 404를 반환한다`() {
        mockMvc.perform(post("/api/purchase-attempts/-1/confirm"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `존재하지 않는 시도를 취소하려 하면 404를 반환한다`() {
        mockMvc.perform(post("/api/purchase-attempts/-1/cancel"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `RESERVED가 아닌 시도를 확정하려 하면 400을 반환한다`() {
        val product = productService.createProduct("API 중복 확정 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.confirm(attempt.id!!)

        mockMvc.perform(post("/api/purchase-attempts/${attempt.id}/confirm"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `RESERVED가 아닌 시도를 취소하려 하면 400을 반환한다`() {
        val product = productService.createProduct("API 중복 취소 테스트", null, BigDecimal.TEN, 1)
        val attempt = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.cancel(attempt.id!!)

        mockMvc.perform(post("/api/purchase-attempts/${attempt.id}/cancel"))
            .andExpect(status().isBadRequest)
    }
}
