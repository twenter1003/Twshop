package com.twshop.waiting.application

import com.twshop.waiting.domain.WaitingRank

/** [WaitingService.enter]의 결과 — 새로 발급된 시퀀스 값과, 그 시점 기준 순번을 함께 담는다. */
data class WaitingEntryResult(
    val issuedSequence: Long,
    val rank: WaitingRank,
)
