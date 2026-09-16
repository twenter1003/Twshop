package com.twshop.waiting.infrastructure

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

/**
 * productId별 대기열(Redis Sorted Set)과 순번 시퀀스(Redis 카운터)를 다루는 저장소.
 *
 * 대기열은 영속이 필요 없는 휘발성 상태이고 Redis 자체가 source of truth이므로
 * (2026-09-16 "Waiting 모듈 대기열 설계" ADR 참고), JPA 엔티티 없이 [StringRedisTemplate]을
 * 직접 래핑한다.
 *
 * 키 구조:
 * - `waiting:{productId}:queue` — Sorted Set, member=buyerId, score=발급된 시퀀스 값
 * - `waiting:{productId}:seq` — 시퀀스 카운터
 */
@Repository
class WaitingQueueRepository(
    private val redisTemplate: StringRedisTemplate,
) {

    /**
     * [productId]의 대기열에 [buyerId]를 진입시키고, 새로 발급된 시퀀스 값을 반환한다.
     *
     * `INCR`로 시퀀스를 하나 증가시킨 뒤 그 값을 score로 `ZADD`한다. 이미 대기열에 있는지
     * 확인하는 분기가 없다 — `ZADD`는 같은 member(buyerId)의 score를 덮어쓰므로, 재진입 시
     * "항상 새 순번을 발급한다"는 정책(2026-09-16 ADR)이 별도 로직 없이 자동으로 성립한다.
     *
     * `INCR`과 `ZADD`는 하나의 원자적 커맨드로 묶여 있지 않다 — 그 사이에 프로세스가 죽으면
     * 시퀀스 값만 소진되고 `ZADD`가 되지 않을 수 있다. 이는 순번에 "결번"을 남길 뿐 중복이나
     * 순서 역전을 일으키지 않으므로, 이번 스코프(진입/순위 조회)에서는 감수하기로 한
     * 트레이드오프다(ADR "감수한 단점" 참고). 결번 자체를 없애려면 Lua 스크립트로 두 커맨드를
     * 원자적으로 묶어야 하는데, 지금은 그 비용을 들일 필요가 없다고 판단했다.
     */
    fun enter(productId: Long, buyerId: String): Long {
        val issuedSequence = requireNotNull(redisTemplate.opsForValue().increment(sequenceKey(productId))) {
            "시퀀스 발급에 실패했습니다 (productId=$productId)"
        }
        redisTemplate.opsForZSet().add(queueKey(productId), buyerId, issuedSequence.toDouble())
        return issuedSequence
    }

    /** [productId] 대기열에서 [buyerId]의 0-based 순위를 조회한다. 대기열에 없으면 null. */
    fun findRank(productId: Long, buyerId: String): Long? =
        redisTemplate.opsForZSet().rank(queueKey(productId), buyerId)

    private fun queueKey(productId: Long) = "waiting:$productId:queue"

    private fun sequenceKey(productId: Long) = "waiting:$productId:seq"
}
