package com.twshop.product.api

import com.fasterxml.jackson.databind.ObjectMapper
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
 * 상품 API의 HTTP 계약(요청/응답 스키마, 상태 코드)을 실 MySQL 위에서 검증하는 통합 테스트.
 *
 * 단일 스레드로 API 계약을 확인하는 수준이라 `integration` 계층으로 분류한다
 * (2026-09-16 "테스트 피라미드 공식화 범위" ADR, `docs/testing-strategy.md` 참고).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {

    @Test
    fun `상품을 생성하고 조회, 재고 요약까지 확인할 수 있다`() {
        val request = CreateProductRequest(
            name = "한정판 후드티",
            description = "겨울 한정 수량",
            price = BigDecimal("89000"),
            initialQuantity = 3,
        )

        val response = mockMvc.perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("한정판 후드티"))
            .andReturn()

        val created = objectMapper.readValue(response.response.contentAsString, ProductResponse::class.java)

        mockMvc.perform(get("/api/products/${created.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("한정판 후드티"))

        mockMvc.perform(get("/api/products/${created.id}/inventory-summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.available").value(3))
    }

    @Test
    fun `존재하지 않는 상품을 조회하면 404를 반환한다`() {
        mockMvc.perform(get("/api/products/999999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `초기 수량이 0이면 400을 반환한다`() {
        val request = CreateProductRequest(
            name = "잘못된 요청",
            description = null,
            price = BigDecimal("1000"),
            initialQuantity = 0,
        )

        mockMvc.perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isBadRequest)
    }
}
