package com.twshop.waiting.api

import jakarta.validation.constraints.NotBlank

/** 대기열 진입 요청. */
data class EnterWaitingRequest(
    @field:NotBlank
    val buyerId: String,
)

/** 대기열 진입 결과 응답. */
data class WaitingEntryResponse(
    val productId: Long,
    val buyerId: String,
    val rank: Long,
    val issuedSequence: Long,
)

/** 대기열 내 현재 순번 조회 응답. */
data class WaitingRankResponse(
    val productId: Long,
    val buyerId: String,
    val rank: Long,
)
