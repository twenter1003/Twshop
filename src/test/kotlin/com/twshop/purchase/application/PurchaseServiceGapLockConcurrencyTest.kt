package com.twshop.purchase.application

import com.twshop.product.application.ProductService
import com.twshop.product.domain.InventoryUnit
import com.twshop.product.infrastructure.InventoryUnitRepository
import com.twshop.purchase.domain.PurchaseAttemptStatus
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
 * `InventoryUnitRepository.findAvailableForUpdate()`의 `FOR UPDATE` 락이 "무관해 보이는"
 * 재입고 INSERT까지 막는지(InnoDB gap lock/next-key lock), 그리고 그 블로킹이 실제로 gap
 * 계열 락 때문인지를 `performance_schema.data_locks`/`information_schema.innodb_trx` 스냅샷으로
 * 함께 증명한다.
 *
 * (2026-09-15 ADR "MySQL 고유 락(gap lock) 겨냥 신규 동시성 테스트 — 시나리오/방법론"의
 * A4(재입고 INSERT 블로킹) + B3(래치로 블로킹 확인 → 그 순간 락 스냅샷 조회) 결정을 구현한다.
 * `docs/decisions.md` 참고.)
 *
 * [PurchaseServiceConcurrencyTest]가 스스로 남긴 한계 — "락 대기시간·데드락 같은 InnoDB 갭 락
 * 고유 동작을 겨냥한 테스트는 아니다" — 를 메우는 것이 이 클래스의 목적이라 별도 클래스로 뒀다.
 *
 * ## 왜 AVAILABLE 재고가 0인 상태에서 검증하는가
 * 처음에는 AVAILABLE 유닛이 1개 남아 있는 상태로 시도했으나, 로컬 MySQL 두 세션으로 직접
 * 재현해 보니 예상과 달랐다: `ORDER BY id ASC LIMIT 1`은 조건을 만족하는 첫 행을 찾으면 그
 * 즉시 스캔을 멈추므로, next-key lock은 "찾은 행 + 그 *이전* 갭"만 묶고 그 *이후* 구간은
 * 잠그지 않는다. 그 상태에서 새 유닛을 더 큰 id로 INSERT해 봤더니 15ms 만에 끝나 블로킹되지
 * 않았다.
 * 반대로 AVAILABLE 유닛이 하나도 없으면, "조건을 만족하는 행이 없다"를 확정하기 위해 InnoDB가
 * 해당 product_id 구간을 끝(다음 product_id의 첫 행 또는 supremum)까지 스캔해야 하고, 그
 * 결과 supremum까지 이어지는 gap lock이 걸린다 — 이 상태에서만 재입고 INSERT가 실제로
 * 블로킹됨을 확인했다(직접 측정: 4초 대기, `LOCK_MODE = X,INSERT_INTENTION`/`WAITING`이
 * `LOCK_DATA = supremum pseudo-record`에 걸리는 것도 확인). 이것이 ADR이 언급한 "재고 없음
 * 판정과 재입고가 겹치는 실제 운영 시나리오"이기도 하다. 그래서 이 테스트는 재고를 1개 만든
 * 뒤 곧바로 선점해 소진시키는 방식으로 이 전제 상태를 재현한다.
 *
 * ## 왜 PurchaseService.reserve()를 블로킹 시나리오에서 직접 호출하지 않는가
 * `reserve()`는 `@Transactional` 메서드 자체가 트랜잭션 경계라 메서드가 반환되는 순간
 * 커밋돼 버린다. 락을 잡은 채로 트랜잭션을 열어 둬야(그래야 다른 스레드의 INSERT가
 * 블로킹되는지 관찰할 수 있다) 하므로, `PlatformTransactionManager`로 트랜잭션을 수동으로 열고
 * `reserve()`가 내부에서 쓰는 것과 동일한 `findAvailableForUpdate()`를 직접 호출한 뒤 커밋을
 * 보류한다. 프로덕션 코드는 건드리지 않고, 프로덕션이 실제로 쓰는 락 쿼리를 그대로 재사용한다.
 *
 * ## 로컬/CI에 필요한 추가 MySQL 권한
 * `docs/decisions.md`의 다른 마이그레이션과 달리, 이 테스트가 필요로 하는 권한은 스키마가
 * 아니라 계정 권한이라 Flyway 마이그레이션 대상이 아니다. `twshop` 계정은 기본적으로
 * `performance_schema.data_locks`/`information_schema.innodb_trx` 조회 권한이 없어(이번
 * 세션에서 직접 겪음 — `Access denied` 에러), 새 MySQL 인스턴스에서는 계정에 다음 권한을
 * 먼저 부여해야 이 테스트가 통과한다:
 * ```sql
 * GRANT PROCESS ON *.* TO 'twshop'@'%';
 * GRANT SELECT ON performance_schema.* TO 'twshop'@'%';
 * ```
 * (로컬호스트 접속만 쓴다면 `'twshop'@'localhost'`에도 동일하게 부여해야 한다.)
 */
@SpringBootTest
class PurchaseServiceGapLockConcurrencyTest @Autowired constructor(
    private val purchaseService: PurchaseService,
    private val productService: ProductService,
    private val inventoryUnitRepository: InventoryUnitRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
) {

    @Test
    fun `AVAILABLE 유닛이 없어 FOR UPDATE 스캔이 구간 끝까지 진행되면, 같은 product_id로의 재입고 INSERT가 gap lock에 막힌다`() {
        // 재고 1개짜리 상품을 만들고 즉시 선점해서 AVAILABLE 유닛을 0개로 만든다 — 클래스 KDoc에
        // 적은 이유로, "조건을 만족하는 행 없음"을 확정하려고 구간 끝까지 스캔하는 상태를
        // 재현하기 위한 전제 조건이다.
        val product = productService.createProduct("gap lock 테스트 상품", null, BigDecimal.TEN, 1)
        val consumeAttempt = purchaseService.reserve(product.id!!, "consumer")
        check(consumeAttempt.status == PurchaseAttemptStatus.RESERVED) {
            "테스트 전제 조건(재고 소진)이 깨졌다: ${consumeAttempt.status}"
        }

        val executor = Executors.newFixedThreadPool(2)
        val scannedLatch = CountDownLatch(1) // 스레드1: FOR UPDATE 스캔 완료(0건 확인), 커밋은 아직 보류
        val insertStartedLatch = CountDownLatch(1) // 스레드2: INSERT(save) 호출 직전
        val insertDoneLatch = CountDownLatch(1) // 스레드2: INSERT(save) 호출이 리턴함(블로킹이 풀림)
        val okToCommitLatch = CountDownLatch(1) // 메인 스레드: 락 스냅샷 확인 후 스레드1에게 커밋 허가
        val errors = ConcurrentLinkedQueue<Throwable>()

        // 스레드1이 커밋을 "시도한" 시각과, 스레드2의 INSERT가 "완료된" 시각을 비교해 인과관계를
        // 증명한다. commit()이 리턴하는 시각이 아니라 호출 *직전* 시각을 기준으로 삼은 이유는,
        // commit 완료와 INSERT 언블록이 거의 동시에 일어나 스케줄링 지터로 순서가 뒤집힐 수 있는
        // 두 시각(commit 완료 vs INSERT 완료)을 비교하는 것보다, INSERT가 "적어도 커밋을
        // 시작하기 전에는 끝날 수 없다"는 더 약하지만 항상 성립하는 부등식이 flaky하지 않기
        // 때문이다.
        val commitInitiatedAtNanos = AtomicLong(-1)
        val insertCompletedAtNanos = AtomicLong(-1)

        executor.submit {
            try {
                val status = transactionManager.getTransaction(DefaultTransactionDefinition())
                val found = inventoryUnitRepository.findAvailableForUpdate(product.id!!, PageRequest.of(0, 1))
                check(found.isEmpty()) { "재고가 남아있으면 시나리오 전제가 깨진다: $found" }
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
                // 같은 트랜잭션 안에서 Product를 다시 조회해 관리 상태(managed) 엔티티로 만든다.
                // 이전 트랜잭션에서 얻은 detached 엔티티를 그대로 쓰지 않는 이유는, FK 매핑
                // 자체는 문제없이 동작하더라도 영속성 컨텍스트 경계를 명확히 지키는 쪽이
                // 테스트 코드에서도 불필요한 의심을 없애기 때문이다.
                val managedProduct = productService.getProduct(product.id!!)
                val restocked = InventoryUnit(product = managedProduct, unitCode = "restock-${System.nanoTime()}")
                insertStartedLatch.countDown()
                inventoryUnitRepository.save(restocked) // IDENTITY 전략이라 save() 호출 시점에 즉시 INSERT가 나간다
                insertCompletedAtNanos.set(System.nanoTime())
                insertDoneLatch.countDown()
                transactionManager.commit(status)
            } catch (ex: Throwable) {
                errors.add(ex)
                insertDoneLatch.countDown()
            }
        }

        insertStartedLatch.await(10, TimeUnit.SECONDS)
        Thread.sleep(300) // 스레드2가 실제로 블로킹 상태에 들어갈 시간을 준다 (로컬 MySQL RTT 대비 여유)

        // 1) 타이밍/래치로 "진짜 블로킹됐다"를 먼저 확인한다 (B3 전반부).
        assertEquals(1L, insertDoneLatch.count, "INSERT가 블로킹되지 않고 즉시 끝났다 — 시나리오가 재현되지 않음")

        // 2) 블로킹 중인 그 순간, 별도 raw JDBC 커넥션으로 실제 락 상태를 스냅샷 조회한다 (B3 후반부).
        //    ADR에 명시된 대로 완벽한 성공 보장은 없다 — 스냅샷 타이밍이 늦으면 락이 이미
        //    풀렸을 수 있어 최소한의 재시도만 둔다(최대 5회, 100ms 간격 — 과도한 폴링 금지).
        val snapshot = pollForLockSnapshot(
            isMatchingLock = { lockType, lockMode, lockStatus, _ ->
                lockType == "RECORD" && lockStatus == "WAITING" && lockMode.contains("INSERT_INTENTION")
            },
            isMatchingTrxQuery = { it.contains("insert into inventory_unit", ignoreCase = true) },
        )

        okToCommitLatch.countDown()
        val completed = insertDoneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "스레드에서 예상치 못한 예외 발생: $errors")
        assertTrue(completed, "커밋 허가 이후에도 INSERT가 끝나지 않음")

        // 3) 인과관계 검증: INSERT 완료 시각이 커밋을 시도한 시각보다 앞설 수 없다.
        assertTrue(
            insertCompletedAtNanos.get() >= commitInitiatedAtNanos.get(),
            "INSERT가 스레드1이 커밋을 시도하기 전에 완료됨 — 블로킹되지 않았다는 뜻",
        )

        // 4) gap lock의 증거 자체를 assertion으로 남긴다 — "블로킹됐다"만 확인하고 끝내면 이번
        //    결정(B3)의 목적을 채우지 못한다. INSERT_INTENTION 락은 정의상 gap이 이미 다른
        //    트랜잭션에 의해 잠겨 있을 때만 발생하므로, 이 락이 WAITING 상태로 관측됐다는 것
        //    자체가 "이 INSERT가 gap lock에 막혔다"는 직접적인 증거다.
        assertTrue(
            snapshot.hasMatchingWaitingLock,
            "블로킹된 INSERT가 INSERT_INTENTION(gap lock 대기) 락을 걸고 있다는 증거를 찾지 못함: $snapshot",
        )
        assertTrue(
            snapshot.hasMatchingLockWaitTrx,
            "innodb_trx에서 해당 INSERT가 LOCK WAIT 상태라는 증거를 찾지 못함: $snapshot",
        )
    }

    @Test
    fun `status에 인덱스가 없어, product_id 스캔 중 만난 non-AVAILABLE row도 잠긴다`() {
        // id 오름차순으로 RESERVED, RESERVED, AVAILABLE이 되도록 만든다. findAvailableForUpdate는
        // product_id 인덱스로 스캔하며 status를 추가 조건으로 거르는데(status 미인덱스, 2026-09-15
        // ADR 참고), InnoDB는 "이 인덱스 범위에서 조건을 만족하는 조건을 찾을 때까지 examine한
        // 모든 row"를 잠근다 — 최종적으로 매칭되지 않은 앞의 두 RESERVED row까지 포함해서.
        val product = productService.createProduct("gap lock A2 테스트 상품", null, BigDecimal.TEN, 3)
        val firstReserved = purchaseService.reserve(product.id!!, "buyer-1")
        purchaseService.reserve(product.id!!, "buyer-2")
        check(firstReserved.status == PurchaseAttemptStatus.RESERVED) {
            "테스트 전제 조건(앞 두 유닛 선점)이 깨졌다: ${firstReserved.status}"
        }
        val nonMatchingUnitId = firstReserved.inventoryUnit!!.id!!

        val executor = Executors.newFixedThreadPool(2)
        val scannedLatch = CountDownLatch(1) // 스레드1: FOR UPDATE 스캔 완료(AVAILABLE 1건 확인), 커밋은 아직 보류
        val updateStartedLatch = CountDownLatch(1) // 스레드2: non-AVAILABLE row UPDATE 호출 직전
        val updateDoneLatch = CountDownLatch(1) // 스레드2: UPDATE가 리턴함(블로킹이 풀림)
        val okToCommitLatch = CountDownLatch(1) // 메인 스레드: 락 스냅샷 확인 후 스레드1에게 커밋 허가
        val errors = ConcurrentLinkedQueue<Throwable>()

        val commitInitiatedAtNanos = AtomicLong(-1)
        val updateCompletedAtNanos = AtomicLong(-1)

        executor.submit {
            try {
                val status = transactionManager.getTransaction(DefaultTransactionDefinition())
                val found = inventoryUnitRepository.findAvailableForUpdate(product.id!!, PageRequest.of(0, 1))
                check(found.size == 1) { "AVAILABLE 유닛이 정확히 1개여야 시나리오 전제가 맞다: $found" }
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

        // 스레드2는 프로덕션 코드 경로(JPA)가 아니라 raw JDBC로 non-AVAILABLE row를 직접
        // UPDATE한다 — 목적이 "이 특정 row가 잠겨 있는가"를 순수하게 probing하는 것이라,
        // JPA dirty-checking 여부에 기대지 않고 명시적으로 그 row를 건드린다.
        executor.submit {
            dataSource.connection.use { conn ->
                try {
                    conn.autoCommit = false
                    updateStartedLatch.countDown()
                    conn.prepareStatement("UPDATE inventory_unit SET unit_code = ? WHERE id = ?").use { stmt ->
                        stmt.setString(1, "probed-${System.nanoTime()}")
                        stmt.setLong(2, nonMatchingUnitId)
                        stmt.executeUpdate()
                    }
                    updateCompletedAtNanos.set(System.nanoTime())
                    updateDoneLatch.countDown()
                    conn.commit()
                } catch (ex: Throwable) {
                    errors.add(ex)
                    updateDoneLatch.countDown()
                }
            }
        }

        updateStartedLatch.await(10, TimeUnit.SECONDS)
        Thread.sleep(300) // 스레드2가 실제로 블로킹 상태에 들어갈 시간을 준다

        // 1) 타이밍/래치로 "진짜 블로킹됐다"를 먼저 확인한다 (B3 전반부).
        assertEquals(1L, updateDoneLatch.count, "UPDATE가 블로킹되지 않고 즉시 끝났다 — non-AVAILABLE row가 잠기지 않았다는 뜻")

        // 2) 블로킹 중인 그 순간, 별도 raw JDBC 커넥션으로 해당 row의 락 상태를 스냅샷 조회한다 (B3 후반부).
        val snapshot = pollForLockSnapshot(
            isMatchingLock = { lockType, _, lockStatus, lockData ->
                lockType == "RECORD" && lockStatus == "WAITING" && lockData == nonMatchingUnitId.toString()
            },
            isMatchingTrxQuery = { it.contains("update inventory_unit", ignoreCase = true) },
        )

        okToCommitLatch.countDown()
        val completed = updateDoneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "스레드에서 예상치 못한 예외 발생: $errors")
        assertTrue(completed, "커밋 허가 이후에도 UPDATE가 끝나지 않음")

        // 3) 인과관계 검증: UPDATE 완료 시각이 커밋을 시도한 시각보다 앞설 수 없다.
        assertTrue(
            updateCompletedAtNanos.get() >= commitInitiatedAtNanos.get(),
            "UPDATE가 스레드1이 커밋을 시도하기 전에 완료됨 — 블로킹되지 않았다는 뜻",
        )

        // 4) 잠긴 대상이 실제로 이 non-AVAILABLE row(PK=nonMatchingUnitId)라는 증거를 남긴다.
        assertTrue(
            snapshot.hasMatchingWaitingLock,
            "블로킹된 UPDATE가 id=$nonMatchingUnitId row에 대한 WAITING record lock 증거를 찾지 못함: $snapshot",
        )
        assertTrue(
            snapshot.hasMatchingLockWaitTrx,
            "innodb_trx에서 해당 UPDATE가 LOCK WAIT 상태라는 증거를 찾지 못함: $snapshot",
        )
    }

    /**
     * [pollForLockSnapshot]/[queryLockSnapshot]의 조회 결과. 실패 시 assertion 메시지에 그대로
     * 찍혀 디버깅에 쓰인다.
     *
     * A4(재입고 INSERT gap lock)와 A2(non-AVAILABLE row 잠금) 두 시나리오가 "어떤 락/트랜잭션을
     * 매칭 조건으로 볼지"만 다르고 조회 대상 테이블·컬럼은 동일해서, 그 판정 결과만 담는
     * 공통 타입으로 합쳤다. 필드명을 `hasMatchingWaitingLock`/`hasMatchingLockWaitTrx`로 일반화한
     * 이유도 같다 — "INSERT_INTENTION"이나 "특정 PK" 같은 시나리오별 의미는 호출부의 람다
     * 이름/주석에 남기고, 여기서는 그 결과만 표현한다.
     */
    private data class LockSnapshot(
        val dataLocksRows: List<String>,
        val hasMatchingWaitingLock: Boolean,
        val hasMatchingLockWaitTrx: Boolean,
    )

    /**
     * 원하는 락 증거가 잡힐 때까지 [maxAttempts]회까지 [delayMillis] 간격으로 재시도한다.
     *
     * 스냅샷 조회 시점이 늦으면 관측하려는 락이 이미 풀렸을 수 있다는 게 B3 방법론의 알려진
     * 한계다(2026-09-15 ADR). 완전히 안정적으로 만들 수는 없으므로, 테스트를 과도하게 느리게
     * 만들지 않는 선에서(최대 5회 × 100ms) 최소한의 재시도만 둔다.
     *
     * @param isMatchingLock `performance_schema.data_locks`의 한 행(LOCK_TYPE, LOCK_MODE,
     *   LOCK_STATUS, LOCK_DATA)이 이 시나리오가 찾는 WAITING 락인지 판정한다.
     * @param isMatchingTrxQuery `information_schema.innodb_trx`의 `trx_query`가 이 시나리오가
     *   찾는 LOCK WAIT 트랜잭션(예: INSERT/UPDATE 문)인지 판정한다.
     */
    private fun pollForLockSnapshot(
        maxAttempts: Int = 5,
        delayMillis: Long = 100,
        isMatchingLock: (lockType: String, lockMode: String, lockStatus: String, lockData: String) -> Boolean,
        isMatchingTrxQuery: (String) -> Boolean,
    ): LockSnapshot {
        repeat(maxAttempts) { attempt ->
            val snapshot = queryLockSnapshot(isMatchingLock, isMatchingTrxQuery)
            if (snapshot.hasMatchingWaitingLock && snapshot.hasMatchingLockWaitTrx) return snapshot
            if (attempt < maxAttempts - 1) Thread.sleep(delayMillis)
        }
        return queryLockSnapshot(isMatchingLock, isMatchingTrxQuery)
    }

    /**
     * `performance_schema.data_locks`와 `information_schema.innodb_trx`를 raw JDBC로 1회
     * 조회해 [LockSnapshot]을 만든다. 판정 로직은 [isMatchingLock]/[isMatchingTrxQuery] 람다로
     * 호출부(A4/A2 테스트)에서 주입받는다.
     *
     * Spring이 관리하는 [DataSource]에서 커넥션을 직접 빌려 쓴다 — 이 커넥션은 테스트 스레드들의
     * 트랜잭션과 무관한 별도 세션이어야, 관찰하려는 락 상태에 스스로 관여하지 않는다(자동 커밋
     * 커넥션이라 조회 자체가 즉시 끝나고 락을 남기지 않는다).
     */
    private fun queryLockSnapshot(
        isMatchingLock: (lockType: String, lockMode: String, lockStatus: String, lockData: String) -> Boolean,
        isMatchingTrxQuery: (String) -> Boolean,
    ): LockSnapshot {
        dataSource.connection.use { conn ->
            val rows = mutableListOf<String>()
            var hasMatchingWaitingLock = false
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
                        // data_locks에는 TABLE 레벨 락 행도 섞여 있고 그런 행은 LOCK_DATA가 NULL이다.
                        // isMatchingLock 파라미터가 non-null String이라 null을 그대로 넘기면 Kotlin이
                        // 호출부에서 인자 null 체크를 하다 NPE를 던지므로, 비교 목적상 의미가 같은
                        // 빈 문자열로 방어한다(빈 문자열은 "RECORD"/PK 값과 결코 매칭되지 않는다).
                        if (isMatchingLock(lockType ?: "", lockMode ?: "", lockStatus ?: "", lockData ?: "")) {
                            hasMatchingWaitingLock = true
                        }
                    }
                }
            }

            var hasMatchingLockWaitTrx = false
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
                        if (isMatchingTrxQuery(query)) {
                            hasMatchingLockWaitTrx = true
                        }
                    }
                }
            }

            return LockSnapshot(rows, hasMatchingWaitingLock, hasMatchingLockWaitTrx)
        }
    }
}
