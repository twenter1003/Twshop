package com.twshop.product.infrastructure

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryUnit
import com.twshop.purchase.application.PurchaseService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `InventoryUnitRepository.findAvailableForUpdate()`가 `status`에 인덱스가 없어
 * `product_id` 보조 인덱스로 스캔한 뒤 애플리케이션/스토리지 엔진 레벨에서 status를
 * 필터링한다는 걸 이용해, 조건에 맞지 않는(non-AVAILABLE) row까지 실제로 레코드 락을
 * 거는지 `performance_schema.data_locks`/`information_schema.innodb_trx` 스냅샷으로 증명한다.
 *
 * (2026-09-15 ADR "MySQL 고유 락(gap lock) 겨냥 신규 동시성 테스트 — 시나리오/방법론"의
 * A2(status 섞여 있을 때 non-AVAILABLE row까지 잠기는가) + B3(래치로 블로킹 확인 → 그 순간
 * 락 스냅샷 조회) 결정을 구현한다. 방법론(B3)은 [PurchaseServiceGapLockConcurrencyTest]에서
 * A4를 구현할 때 이미 검증된 것을 그대로 재사용한다 — 새 개념이 아니라 같은 방법론을 다른
 * 시나리오에 적용하는 것이라 별도 `/decide`는 다시 거치지 않았다. `docs/decisions.md` 참고.)
 *
 * ## 왜 이 시나리오가 A4보다 더 흔하게 발생하는 문제인가
 * A4(재고 완전 소진)는 "조건을 만족하는 행이 없다"를 확정해야 하는 특수한 경우에만
 * 발생했다. 이 테스트가 다루는 상황은 그보다 훨씬 흔하다 — **상품 하나에 판매 이력이 조금만
 * 쌓여도**(일부는 팔리고 일부는 아직 남아있는 정상적인 상태) 발생한다. `findAvailableForUpdate`는
 * `ORDER BY id ASC LIMIT 1`이라 가장 낮은 id의 AVAILABLE 유닛을 찾는데, 그보다 낮은 id를 가진
 * 유닛들이 이미 RESERVED/CONFIRMED 상태라면, 그 유닛들을 반드시 먼저 스캔해서 "이 row는
 * 조건에 안 맞는다"는 걸 확인해야 한다. 로컬 MySQL 두 세션으로 직접 재현해 실측한 결과,
 * InnoDB는 이 "확인 과정"에서 스캔한 row 자체에 `X,REC_NOT_GAP`(일반 레코드 락, gap이
 * 아니라 그 행 자체에 대한 락)을 걸었다 — 조건에 맞지 않아 최종 결과에는 포함되지 않는
 * row인데도 잠긴 것이다. 이 락은 next-key/gap 계열이 아니라 순수 레코드 락이라, A4보다
 * 메커니즘이 단순하고 재현 조건도 훨씬 덜 까다롭다(재고가 하나라도 팔린 적 있는 거의 모든
 * 상품에서 발생).
 *
 * ## 실측 근거 (이 테스트를 작성하기 전 로컬 MySQL 두 세션으로 직접 확인)
 * 재고 3개짜리 상품을 만들고 낮은 id 2개를 먼저 선점(RESERVED)시킨 뒤, 세 번째(AVAILABLE)를
 * 찾는 `FOR UPDATE` 스캔을 트랜잭션 안에서 커밋하지 않고 열어둔 채, 다른 세션에서 첫 번째
 * RESERVED 유닛(스캔이 지나쳤을 row)을 `SELECT ... FOR UPDATE`로 잠가봤다:
 * ```
 * LOCK_TYPE  LOCK_MODE           LOCK_STATUS  LOCK_DATA
 * RECORD     X                   GRANTED      <product_id>, <reserved_unit_id>   (보조 인덱스)
 * RECORD     X,REC_NOT_GAP       GRANTED      <reserved_unit_id>                 (클러스터드 인덱스, 스캔한 세션이 보유)
 * RECORD     X,REC_NOT_GAP       WAITING      <reserved_unit_id>                 (다른 세션이 대기)
 * ```
 * 4초 뒤 `Lock wait timeout exceeded`로 확인 — 실제로 블로킹됐다.
 *
 * ## 왜 `PurchaseService.reserve()`를 직접 쓰지 않고 트랜잭션을 수동으로 여는가
 * [PurchaseServiceGapLockConcurrencyTest]와 동일한 이유다 — `@Transactional` 메서드는
 * 반환과 동시에 커밋되어 락을 열어둔 채 관찰할 수 없다.
 *
 * ## 왜 두 번째 스레드는 리포지토리 메서드가 아니라 `EntityManager.find()`를 직접 쓰는가
 * "조건에 안 맞는 row에 락을 거는가"를 검증하려는 것이지 특정 API 표면을 검증하려는 게
 * 아니다. 이 테스트만을 위해 프로덕션 리포지토리에 새 메서드를 추가하는 건 스코프
 * 과다(CLAUDE.md 원칙 2)라, `EntityManager.find(id, PESSIMISTIC_WRITE)`로 "이 row를
 * `SELECT ... FOR UPDATE`로 잠근다"는 동일한 의도를 프로덕션 코드 변경 없이 재현한다.
 *
 * ## A3: 서로 다른 상품끼리는 블로킹되지 않는가 (negative case)
 * `findAvailableForUpdate`의 WHERE 절은 `product_id`(인덱스 있음) 등호 조건 + `status`(인덱스
 * 없음) 필터다. A2에서 확인한 "조건에 안 맞는 row도 잠근다"는 동작이 같은 `product_id` *범위
 * 안에서* 일어나는 것이지, 다른 `product_id`까지 침범하는 건 아닐 것이라는 게 기대였다. 로컬
 * MySQL 두 세션으로 인접한 id를 가진 서로 다른 상품 A/B를 만들어 확인한 결과, 기대대로
 * A의 스캔이 열려 있는 동안 B의 스캔은 전혀 블로킹되지 않았다 — 두 트랜잭션 모두
 * `RUNNING`(대기 없음) 상태로 각자의 row에 `GRANTED` 레코드 락만 들고 있었다. A2와 달리
 * "막힌다"가 아니라 "안 막힌다"를 증명하는 시나리오라, 스냅샷도 "두 락이 동시에 GRANTED
 * 상태로 공존하고 WAITING이 하나도 없다"는 것 자체가 증거다.
 */
@SpringBootTest
class InventoryUnitRepositoryLockScopeTest @Autowired constructor(
    private val productService: ProductService,
    private val purchaseService: PurchaseService,
    private val inventoryUnitRepository: InventoryUnitRepository,
    private val transactionManager: PlatformTransactionManager,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
) {

    @Test
    fun `findAvailableForUpdate 스캔은 status가 안 맞아 지나친 RESERVED row에도 레코드 락을 건다`() {
        // 재고 3개 중 낮은 id 2개를 먼저 선점시켜, 세 번째(AVAILABLE)를 ORDER BY id ASC로
        // 찾으려면 반드시 그 두 RESERVED row를 먼저 스캔해서 지나쳐야 하는 상태를 만든다.
        val product = productService.createProduct("lock 범위 테스트 상품", null, BigDecimal.TEN, 3)
        val firstReserved = purchaseService.reserve(product.id!!, "buyer-1").inventoryUnit!!.id!!
        purchaseService.reserve(product.id!!, "buyer-2")

        val executor = Executors.newFixedThreadPool(2)
        val scannedLatch = CountDownLatch(1) // 스레드1: FOR UPDATE 스캔 완료, 커밋은 아직 보류
        val lockAttemptStartedLatch = CountDownLatch(1) // 스레드2: RESERVED row 락 시도 직전
        val lockAttemptDoneLatch = CountDownLatch(1) // 스레드2: 락 시도가 리턴함(블로킹이 풀림)
        val okToCommitLatch = CountDownLatch(1) // 메인 스레드: 락 스냅샷 확인 후 스레드1에게 커밋 허가
        val errors = ConcurrentLinkedQueue<Throwable>()

        // A4 테스트와 동일한 이유로, commit()이 리턴하는 시각이 아니라 호출 *직전* 시각을
        // 기준으로 인과관계를 증명한다 — commit 완료와 락 해제가 거의 동시에 일어나는 두 시각을
        // 비교하는 것보다 더 약하지만 항상 성립하는 부등식이 flaky하지 않다.
        val commitInitiatedAtNanos = AtomicLong(-1)
        val lockAcquiredAtNanos = AtomicLong(-1)

        executor.submit {
            try {
                val status = transactionManager.getTransaction(DefaultTransactionDefinition())
                val found = inventoryUnitRepository.findAvailableForUpdate(product.id!!, PageRequest.of(0, 1))
                check(found.size == 1) { "AVAILABLE 유닛이 정확히 1개 남아있어야 시나리오 전제가 성립한다: $found" }
                scannedLatch.countDown()
                okToCommitLatch.await(10, TimeUnit.SECONDS)
                commitInitiatedAtNanos.set(System.nanoTime())
                transactionManager.commit(status)
            } catch (ex: Throwable) {
                errors.add(ex)
                scannedLatch.countDown()
            }
        }
        scannedLatch.await(10, TimeUnit.SECONDS)

        executor.submit {
            try {
                val status = transactionManager.getTransaction(DefaultTransactionDefinition())
                lockAttemptStartedLatch.countDown()
                entityManager.find(InventoryUnit::class.java, firstReserved, LockModeType.PESSIMISTIC_WRITE)
                lockAcquiredAtNanos.set(System.nanoTime())
                lockAttemptDoneLatch.countDown()
                transactionManager.commit(status)
            } catch (ex: Throwable) {
                errors.add(ex)
                lockAttemptDoneLatch.countDown()
            }
        }

        lockAttemptStartedLatch.await(10, TimeUnit.SECONDS)
        Thread.sleep(300) // 스레드2가 실제로 블로킹 상태에 들어갈 시간을 준다

        // 1) 타이밍/래치로 "진짜 블로킹됐다"를 먼저 확인한다 (B3 전반부).
        assertEquals(1L, lockAttemptDoneLatch.count, "RESERVED row 락 시도가 블로킹되지 않고 즉시 끝났다 — 시나리오가 재현되지 않음")

        // 2) 블로킹 중인 그 순간, 별도 raw JDBC 커넥션으로 실제 락 상태를 스냅샷 조회한다 (B3 후반부).
        val snapshot = pollForRecordLockSnapshot(firstReserved)

        okToCommitLatch.countDown()
        val completed = lockAttemptDoneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "스레드에서 예상치 못한 예외 발생: $errors")
        assertTrue(completed, "커밋 허가 이후에도 락 시도가 끝나지 않음")

        // 3) 인과관계 검증: 락 획득 시각이 커밋을 시도한 시각보다 앞설 수 없다.
        assertTrue(
            lockAcquiredAtNanos.get() >= commitInitiatedAtNanos.get(),
            "RESERVED row 락 획득이 스레드1이 커밋을 시도하기 전에 완료됨 — 블로킹되지 않았다는 뜻",
        )

        // 4) 증거 자체를 assertion으로 남긴다 — status가 AVAILABLE이 아닌 row(firstReserved)에
        //    대한 대기 중인 X 레코드 락이 관측됐다는 것 자체가 "스캔이 이 row를 잠갔다"는 증거다.
        assertTrue(
            snapshot.hasWaitingRecordLock,
            "블로킹된 락 시도가 RESERVED row에 대한 WAITING 레코드 락을 걸고 있다는 증거를 찾지 못함: $snapshot",
        )
        assertTrue(
            snapshot.hasLockWaitTrxForSelect,
            "innodb_trx에서 해당 SELECT ... FOR UPDATE가 LOCK WAIT 상태라는 증거를 찾지 못함: $snapshot",
        )
    }

    @Test
    fun `서로 다른 상품의 findAvailableForUpdate 스캔은 서로 블로킹되지 않는다`() {
        val productA = productService.createProduct("lock 범위 테스트 상품 A", null, BigDecimal.TEN, 1)
        val productB = productService.createProduct("lock 범위 테스트 상품 B", null, BigDecimal.TEN, 1)

        val executor = Executors.newFixedThreadPool(2)
        val scannedALatch = CountDownLatch(1)
        val scannedBLatch = CountDownLatch(1)
        val okToCommitLatch = CountDownLatch(1) // 메인 스레드: 두 락 동시 보유 확인 후 둘 다에게 커밋 허가
        val errors = ConcurrentLinkedQueue<Throwable>()
        val unitAId = AtomicLong(-1)
        val unitBId = AtomicLong(-1)

        fun holdScan(productId: Long, scannedLatch: CountDownLatch, unitIdHolder: AtomicLong) {
            try {
                val status = transactionManager.getTransaction(DefaultTransactionDefinition())
                val found = inventoryUnitRepository.findAvailableForUpdate(productId, PageRequest.of(0, 1))
                check(found.size == 1) { "AVAILABLE 유닛이 정확히 1개 있어야 시나리오 전제가 성립한다: $found" }
                unitIdHolder.set(found.first().id!!)
                scannedLatch.countDown()
                okToCommitLatch.await(10, TimeUnit.SECONDS)
                transactionManager.commit(status)
            } catch (ex: Throwable) {
                errors.add(ex)
                scannedLatch.countDown()
            }
        }

        executor.submit { holdScan(productA.id!!, scannedALatch, unitAId) }
        // 상품A의 스캔이 열려 있는 동안 곧바로 상품B의 스캔을 시도한다 — 블로킹된다면 이
        // await 자체가 시간 안에 끝나지 않는다.
        scannedALatch.await(10, TimeUnit.SECONDS)
        val productBScanStartedAtNanos = System.nanoTime()
        executor.submit { holdScan(productB.id!!, scannedBLatch, unitBId) }
        val productBScanned = scannedBLatch.await(2, TimeUnit.SECONDS)
        val productBScanElapsedMillis = (System.nanoTime() - productBScanStartedAtNanos) / 1_000_000

        // 1) negative case의 핵심 assertion — 상품A 스캔이 열려 있는데도 상품B 스캔이
        //    블로킹 없이(2초 이내) 끝났다.
        assertTrue(productBScanned, "상품A의 스캔이 열려 있는 동안 상품B의 스캔이 블로킹됐다 — 서로 다른 상품끼리도 잠긴다는 뜻")

        // 2) 두 락이 동시에 GRANTED 상태로 공존하는 그 순간을 스냅샷으로 남긴다 — "안 막힌다"는
        //    걸 타이밍만으로 주장하지 않고, 실제로 서로 다른 트랜잭션이 각자 락을 쥔 채 대기
        //    없이 공존한다는 걸 직접 확인한다.
        val snapshot = queryIndependentGrantSnapshot(unitAId.get(), unitBId.get())

        okToCommitLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        assertTrue(errors.isEmpty(), "스레드에서 예상치 못한 예외 발생: $errors")
        assertTrue(
            productBScanElapsedMillis < 2000,
            "상품B 스캔이 예상보다 오래 걸렸다(${productBScanElapsedMillis}ms) — 블로킹 의심",
        )
        assertTrue(
            snapshot.bothGrantedIndependently,
            "두 상품의 락이 동시에 GRANTED 상태로 공존한다는 증거를 찾지 못함: $snapshot",
        )
        assertTrue(
            snapshot.noWaitingLocks,
            "다른 상품 스캔인데도 WAITING 락이 관측됨 — 서로 블로킹됐다는 뜻: $snapshot",
        )
    }

    /** [queryIndependentGrantSnapshot]의 조회 결과. */
    private data class IndependentGrantSnapshot(
        val dataLocksRows: List<String>,
        val bothGrantedIndependently: Boolean,
        val noWaitingLocks: Boolean,
    )

    /**
     * `unitAId`, `unitBId` 각각에 대한 클러스터드 인덱스 레코드 락이 서로 다른 트랜잭션에서
     * `GRANTED` 상태로 동시에 존재하고, `inventory_unit`에 대한 `WAITING` 락은 하나도 없다는
     * 걸 raw JDBC로 1회 확인한다. A2 테스트의 [querySnapshot]과 달리 여기선 대기를 기다릴
     * 필요가 없다 — 두 스레드 모두 커밋을 보류한 채 호출자가 명시적으로 동시 보유를 확인한
     * 뒤에야 커밋을 허가하므로, 재시도 없이 1회 조회로 충분하다.
     */
    private fun queryIndependentGrantSnapshot(unitAId: Long, unitBId: Long): IndependentGrantSnapshot {
        dataSource.connection.use { conn ->
            val rows = mutableListOf<String>()
            var grantedTrxForA: Long? = null
            var grantedTrxForB: Long? = null
            var hasWaiting = false
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT ENGINE_TRANSACTION_ID, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
                    FROM performance_schema.data_locks
                    WHERE OBJECT_NAME = 'inventory_unit'
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val trxId = rs.getLong("ENGINE_TRANSACTION_ID")
                        val lockType = rs.getString("LOCK_TYPE")
                        val lockMode = rs.getString("LOCK_MODE")
                        val lockStatus = rs.getString("LOCK_STATUS")
                        val lockData = rs.getString("LOCK_DATA")
                        rows.add("trx=$trxId $lockType/$lockMode/$lockStatus/$lockData")
                        if (lockStatus == "WAITING") hasWaiting = true
                        if (lockType == "RECORD" && lockMode.contains("REC_NOT_GAP") && lockStatus == "GRANTED") {
                            when (lockData) {
                                unitAId.toString() -> grantedTrxForA = trxId
                                unitBId.toString() -> grantedTrxForB = trxId
                            }
                        }
                    }
                }
            }

            val bothGrantedIndependently = grantedTrxForA != null && grantedTrxForB != null && grantedTrxForA != grantedTrxForB
            return IndependentGrantSnapshot(rows, bothGrantedIndependently, !hasWaiting)
        }
    }

    /** [querySnapshot]의 조회 결과. 실패 시 assertion 메시지에 그대로 찍혀 디버깅에 쓰인다. */
    private data class RecordLockSnapshot(
        val dataLocksRows: List<String>,
        val hasWaitingRecordLock: Boolean,
        val hasLockWaitTrxForSelect: Boolean,
    )

    /**
     * 락 증거가 잡힐 때까지 [maxAttempts]회까지 [delayMillis] 간격으로 재시도한다.
     *
     * [PurchaseServiceGapLockConcurrencyTest]와 동일한 B3 방법론의 알려진 한계 — 스냅샷
     * 조회 시점이 늦으면 관측하려는 락이 이미 풀렸을 수 있다 — 를 최소한의 재시도로 완화한다.
     */
    private fun pollForRecordLockSnapshot(reservedUnitId: Long, maxAttempts: Int = 5, delayMillis: Long = 100): RecordLockSnapshot {
        repeat(maxAttempts) { attempt ->
            val snapshot = querySnapshot(reservedUnitId)
            if (snapshot.hasWaitingRecordLock && snapshot.hasLockWaitTrxForSelect) return snapshot
            if (attempt < maxAttempts - 1) Thread.sleep(delayMillis)
        }
        return querySnapshot(reservedUnitId)
    }

    /**
     * `performance_schema.data_locks`와 `information_schema.innodb_trx`를 raw JDBC로 1회
     * 조회해 [RecordLockSnapshot]을 만든다. 자동 커밋 별도 세션을 쓰는 이유는
     * [PurchaseServiceGapLockConcurrencyTest.querySnapshot]과 동일하다.
     */
    private fun querySnapshot(reservedUnitId: Long): RecordLockSnapshot {
        dataSource.connection.use { conn ->
            val rows = mutableListOf<String>()
            var hasWaitingRecordLock = false
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
                    FROM performance_schema.data_locks
                    WHERE OBJECT_NAME = 'inventory_unit'
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val lockType = rs.getString("LOCK_TYPE")
                        val lockMode = rs.getString("LOCK_MODE")
                        val lockStatus = rs.getString("LOCK_STATUS")
                        val lockData = rs.getString("LOCK_DATA")
                        rows.add("$lockType/$lockMode/$lockStatus/$lockData")
                        if (lockType == "RECORD" && lockStatus == "WAITING" && lockData == reservedUnitId.toString()) {
                            hasWaitingRecordLock = true
                        }
                    }
                }
            }

            var hasLockWaitTrxForSelect = false
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT trx_state, trx_query
                    FROM information_schema.innodb_trx
                    WHERE trx_state = 'LOCK WAIT'
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) {
                        val query = rs.getString("trx_query") ?: ""
                        if (query.contains("inventory_unit", ignoreCase = true) && query.contains("select", ignoreCase = true)) {
                            hasLockWaitTrxForSelect = true
                        }
                    }
                }
            }

            return RecordLockSnapshot(rows, hasWaitingRecordLock, hasLockWaitTrxForSelect)
        }
    }
}
