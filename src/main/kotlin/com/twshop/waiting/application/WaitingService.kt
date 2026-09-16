package com.twshop.waiting.application

import com.twshop.product.application.ProductService
import com.twshop.waiting.domain.WaitingRank
import com.twshop.waiting.infrastructure.WaitingQueueRepository
import org.springframework.stereotype.Service

/**
 * 대기열 진입/순번 조회 유스케이스.
 *
 * 순번 도달 시 reserve와의 연계, 이탈/포기 처리(TTL), 대기열 크기 제한은 이번 스코프에
 * 포함하지 않는다 — 진입과 순번 조회만 다룬다(2026-09-16 "Waiting 모듈 대기열 설계" ADR
 * 재검토 조건 참고).
 *
 * Product 모듈에 대한 의존은 Purchase 모듈이 이미 Product를 조회 전용으로 참조하는 것과
 * 같은 패턴이다 — productId 존재 검증만 필요하므로 [ProductService.getProduct]를 그대로
 * 재사용한다.
 */
@Service
class WaitingService(
    private val productService: ProductService,
    private val waitingQueueRepository: WaitingQueueRepository,
) {

    /**
     * [buyerId]를 [productId] 대기열에 진입시키고, 새로 발급된 순번을 반환한다.
     *
     * 이미 대기열에 있는지 확인하는 분기 없이 항상 새 시퀀스를 발급한다 — "재진입하면 항상
     * 새 순번"이라는 정책(2026-09-16 ADR)에 따른 것으로, 안정적인 신원 확인 수단이 없는
     * 상태에서(User/Auth 모듈 부재) "재진입 자체에 비용을 매겨" 무분별한 재요청을 억제하려는
     * 실무적 타협이다. 정상 사용자의 실수 재진입도 똑같이 순번 손해를 본다는 단점을 감수한다.
     *
     * @throws NoSuchElementException [productId]에 해당하는 상품이 없을 때
     */
    fun enter(productId: Long, buyerId: String): WaitingEntryResult {
        productService.getProduct(productId) // 존재하지 않는 상품이면 여기서 예외 발생

        val issuedSequence = waitingQueueRepository.enter(productId, buyerId)
        val rank = waitingQueueRepository.findRank(productId, buyerId)
            ?: error("방금 진입시킨 buyerId의 순위를 찾을 수 없습니다 (productId=$productId, buyerId=$buyerId)")

        return WaitingEntryResult(issuedSequence = issuedSequence, rank = WaitingRank(rank))
    }

    /**
     * [buyerId]의 [productId] 대기열 내 현재 순번을 조회한다.
     *
     * @throws NoSuchElementException [productId]에 해당하는 상품이 없거나, [buyerId]가
     *   대기열에 없을 때
     */
    fun getRank(productId: Long, buyerId: String): WaitingRank {
        productService.getProduct(productId)

        val rank = waitingQueueRepository.findRank(productId, buyerId)
            ?: throw NoSuchElementException("대기열에서 buyerId를 찾을 수 없습니다: $buyerId")

        return WaitingRank(rank)
    }
}
