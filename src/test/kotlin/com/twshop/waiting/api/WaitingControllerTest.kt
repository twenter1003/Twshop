package com.twshop.waiting.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.twshop.product.application.ProductService
import com.twshop.waiting.application.WaitingService
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 대기열 진입/순번 조회 API의 HTTP 계약을 검증하는 통합 테스트.
 *
 * 단일 스레드로 API 계약을 확인하는 수준이라 `integration` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WaitingControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val productService: ProductService,
    private val waitingService: WaitingService,
) {

    private fun newProductId(): Long =
        requireNotNull(productService.createProduct("API 대기열 테스트 상품", null, BigDecimal.TEN, 1).id)

    @Test
    fun `대기열에 진입하면 201과 함께 순번을 반환한다`() {
        val productId = newProductId()

        mockMvc.perform(
            post("/api/waiting/$productId/enter")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(EnterWaitingRequest(buyerId = "buyer-1"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.productId").value(productId))
            .andExpect(jsonPath("$.buyerId").value("buyer-1"))
            .andExpect(jsonPath("$.rank").value(1))
            .andExpect(jsonPath("$.issuedSequence").isNumber)
    }

    @Test
    fun `존재하지 않는 상품에 진입하려 하면 404를 반환한다`() {
        mockMvc.perform(
            post("/api/waiting/-1/enter")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(EnterWaitingRequest(buyerId = "buyer-1"))),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `buyerId가 공백이면 400을 반환한다`() {
        val productId = newProductId()

        mockMvc.perform(
            post("/api/waiting/$productId/enter")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(EnterWaitingRequest(buyerId = "  "))),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `진입한 buyer의 순번을 조회하면 200과 함께 순번을 반환한다`() {
        val productId = newProductId()
        waitingService.enter(productId, "buyer-1")

        mockMvc.perform(get("/api/waiting/$productId/rank").param("buyerId", "buyer-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.productId").value(productId))
            .andExpect(jsonPath("$.buyerId").value("buyer-1"))
            .andExpect(jsonPath("$.rank").value(1))
    }

    @Test
    fun `대기열에 없는 buyer의 순번을 조회하면 404를 반환한다`() {
        val productId = newProductId()

        mockMvc.perform(get("/api/waiting/$productId/rank").param("buyerId", "buyer-없음"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `존재하지 않는 상품의 순번을 조회하면 404를 반환한다`() {
        mockMvc.perform(get("/api/waiting/-1/rank").param("buyerId", "buyer-1"))
            .andExpect(status().isNotFound)
    }
}
