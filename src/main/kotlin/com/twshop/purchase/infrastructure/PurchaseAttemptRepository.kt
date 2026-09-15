package com.twshop.purchase.infrastructure

import com.twshop.purchase.domain.PurchaseAttempt
import org.springframework.data.jpa.repository.JpaRepository

/** [PurchaseAttempt]에 대한 기본 CRUD 접근을 제공한다. */
interface PurchaseAttemptRepository : JpaRepository<PurchaseAttempt, Long>
