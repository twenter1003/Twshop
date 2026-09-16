# 테스트 전략

이 문서는 이 프로젝트의 테스트를 4개 레이어로 정의하고, 각 레이어가 "무엇을
검증하는지"와 "왜 이 레이어가 존재하는지"를 설명한다.

`/decide` 절차로 기록된 [2026-09-16] "테스트 피라미드 공식화 범위" ADR(`docs/decisions.md`)의
선택(B: 문서화 + `@Tag` 분류)을 구현한 결과물이다. 이 문서 자체는 Gradle 태스크나 CI를
바꾸지 않는다 — `@Tag`는 현재 어떤 태스크에서도 레이어별로 소비되지 않는 선언적
메타데이터다(같은 ADR의 "감수한 단점" 참고).

## 왜 정삼각형 피라미드가 아닌가

일반적인 테스트 피라미드는 "빠르고 많은 단위 테스트 위에 소수의 통합/E2E 테스트를
얹는" 정삼각형 모양을 이상적으로 본다. 이 프로젝트는 그렇지 않다 — 8개 테스트
클래스 중 순수 단위 테스트는 `InventoryUnitTest` 1개뿐이고, 나머지 7개는 전부
`@SpringBootTest`로 실제 MySQL을 띄운다.

이유는 이 프로젝트의 핵심 학습 대상 자체가 DB 트랜잭션 경계 없이는 검증이 불가능하기
때문이다:

- 비관적 락(`SELECT ... FOR UPDATE`)이 다른 트랜잭션을 실제로 블로킹하는지
  ([InventoryUnit.reserve() 동시성 제어 락 전략](decisions.md) 참고)
- 낙관적 락(`@Version`)이 동시 수정 시 `ObjectOptimisticLockingFailureException`을
  실제로 던지는지
- InnoDB gap lock/next-key lock이 "무관해 보이는" 재입고 INSERT까지 막는지
  ([MySQL 고유 락(gap lock) 겨냥 신규 동시성 테스트](decisions.md) 참고)

이런 동작은 Mock이나 인메모리 DB로는 재현되지 않는다(H2는 실제로 이 프로젝트가
겪었던 문제이기도 하다 — [CI/테스트를 실제 MySQL로 검증하는 구조](decisions.md) ADR
참고, H2로는 InnoDB 고유 락 동작을 검증할 수 없어 MySQL로 전환했다). 그래서 이
프로젝트는 자연스럽게 "실 DB 위에서 도는 테스트가 대다수"인 역피라미드에 가까운
분포를 갖는다. 이것은 안티패턴이 아니라, 검증 대상의 성격이 그렇게 요구한 결과다.

## 레이어 정의

### unit
Spring 컨텍스트를 띄우지 않는 순수 도메인 로직 테스트. `InventoryUnit`의 상태 전이
같은, 프레임워크나 DB에 의존하지 않는 로직을 검증한다. 가장 빠르고 가장 안정적이다.

### integration
`@SpringBootTest`로 실 MySQL을 띄워 서비스 계층/HTTP API 계약을 검증한다. 각
테스트는 **단일 스레드** 기준으로 동작한다 — "정상 흐름에서 올바른 결과가
나오는가"(재고 생성, 선점 성공/실패, API 응답 스키마 등)를 확인하는 것이 목적이고,
동시 요청 간 경합은 다루지 않는다.

### concurrency
`@SpringBootTest` + 멀티스레드로 락 경합이나 MySQL(InnoDB) 고유 동작을 검증한다.
여러 스레드가 각자의 트랜잭션 안에서 동시에 락을 다투게 만들어, "동시 요청이
들어와도 재고가 중복 선점되지 않는가", "gap lock이 실제로 무관한 행까지 잠그는가"
같은 질문에 답한다. integration과 달리 타이밍(래치, 폴링)에 의존하는 부분이 있어
드물게 느린 환경에서 흔들릴 여지가 있다 — 각 테스트 클래스 KDoc에 그 근거와 완화
방법을 남겨뒀다.

### benchmark
이미 `@Tag("benchmark")`로 존재하는, 처리량(TPS)을 실측하는 레이어. `build.gradle.kts`의
`tasks.test`가 `excludeTags("benchmark")`로 기본 실행에서 제외하고, 별도
`benchmarkTest` 태스크로만 수동 실행한다 — 타이밍 자체가 결과인 시연이라 CI에서
반복 안정성을 보장하지 않기 때문이다. 이번 작업에서 이 레이어의 태그/설정은 건드리지
않았다.

## 파일 → 레이어 매핑

| 클래스 | 레이어 | 비고 |
|---|---|---|
| `InventoryUnitTest` | unit | Spring 컨텍스트 없음 |
| `ProductServiceTest` | integration | `@SpringBootTest` + `@Transactional`, 단일 스레드 |
| `ProductControllerTest` | integration | MockMvc로 HTTP 계약 검증, 단일 스레드 |
| `PurchaseServiceTest` | integration | `@SpringBootTest` + `@Transactional`, 단일 스레드 |
| `PurchaseControllerTest` | integration | MockMvc로 HTTP 계약 검증, 단일 스레드 |
| `PurchaseServiceConcurrencyTest` | concurrency | 비관적/낙관적 락 경합, 멀티스레드 |
| `PurchaseServiceGapLockConcurrencyTest` | concurrency | gap lock/record lock, 멀티스레드 + 락 스냅샷 조회 |
| `PurchaseReserveBenchmark` | benchmark | 처리량 실측, 기본 실행 제외 |
| `WaitingServiceTest` | integration | `@SpringBootTest`, 실 Redis 위에서 진입/순번 조회, 단일 스레드 |
| `WaitingControllerTest` | integration | MockMvc로 HTTP 계약 검증, 단일 스레드 |
| `WaitingServiceConcurrencyTest` | concurrency | Redis `INCR` 기반 시퀀스 유일성, 멀티스레드 |

## 관련 ADR

- [2026-09-15] InventoryUnit.reserve() 동시성 제어 락 전략 (비관적 락 선택 이유)
- [2026-09-15] confirm/cancel 동시성 제어 (낙관적 락 선택 이유)
- [2026-09-15] CI/테스트를 실제 MySQL로 검증하는 구조 (H2 → MySQL 전환 이유)
- [2026-09-15] MySQL 고유 락(gap lock) 겨냥 신규 동시성 테스트 — 시나리오/방법론
- [2026-09-16] PurchaseReserveBenchmark를 autocannon 기반 실부하테스트로 전환
- [2026-09-16] 테스트 피라미드 공식화 범위 (이 문서를 만든 결정 그 자체)

모두 `docs/decisions.md`에 전문이 있다.
