package com.twshop.waiting.domain

/**
 * 대기열에서의 순위를 표현하는 값 객체.
 *
 * Redis의 `ZRANK`는 0-based 순위를 반환하지만, 사용자에게 노출하는 "내 순번"은 1-based가
 * 자연스럽다("0번째로 대기 중"보다 "1번째로 대기 중"이 직관적이다). 이 변환 책임을 domain
 * 계층에 둬서, infrastructure(Redis 응답 형식)와 api(사용자 표시 형식) 사이의 경계를
 * 명확히 한다 — Redis 커맨드가 바뀌어도 이 값 객체만 보면 된다.
 */
@JvmInline
value class WaitingRank(private val zeroBasedRank: Long) {
    init {
        require(zeroBasedRank >= 0) { "순위는 0 이상이어야 합니다: $zeroBasedRank" }
    }

    /** 사용자에게 보여줄 1-based 순번으로 변환한다. */
    fun toDisplayRank(): Long = zeroBasedRank + 1
}
