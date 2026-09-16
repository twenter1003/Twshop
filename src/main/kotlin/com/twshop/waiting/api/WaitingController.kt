package com.twshop.waiting.api

import com.twshop.waiting.application.WaitingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 대기열 진입/순번 조회 API.
 *
 * 순번 도달 시 reserve 연계, 이탈/포기 처리, 대기열 크기 제한은 다음 작업 단위로 미뤄뒀다
 * (2026-09-16 "Waiting 모듈 대기열 설계" ADR 재검토 조건 참고). 이 컨트롤러는 진입과
 * 순번 조회까지만 다룬다.
 */
@RestController
@RequestMapping("/api/waiting")
class WaitingController(
    private val waitingService: WaitingService,
) {

    /** [productId] 대기열에 진입한다. 이미 대기 중이어도 거부하지 않고 새 순번을 발급한다. */
    @PostMapping("/{productId}/enter")
    @ResponseStatus(HttpStatus.CREATED)
    fun enter(
        @PathVariable productId: Long,
        @Valid @RequestBody request: EnterWaitingRequest,
    ): WaitingEntryResponse {
        val result = waitingService.enter(productId, request.buyerId)
        return WaitingEntryResponse(
            productId = productId,
            buyerId = request.buyerId,
            rank = result.rank.toDisplayRank(),
            issuedSequence = result.issuedSequence,
        )
    }

    /** [productId] 대기열에서 [buyerId]의 현재 순번(1-based)을 조회한다. */
    @GetMapping("/{productId}/rank")
    fun getRank(@PathVariable productId: Long, @RequestParam buyerId: String): WaitingRankResponse {
        val rank = waitingService.getRank(productId, buyerId)
        return WaitingRankResponse(productId = productId, buyerId = buyerId, rank = rank.toDisplayRank())
    }
}
