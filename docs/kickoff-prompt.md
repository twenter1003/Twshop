# Claude Code 첫 세션 킥오프 프롬프트

CLAUDE.md와 .claude/commands/는 이미 프로젝트 루트에 배치되어 있다.
Phase 1(뼈대 완성 단계)을 시작하는 새 세션에서 아래 프롬프트를 그대로 붙여넣으면 된다.

---

이 프로젝트는 CLAUDE.md에 정의된 원칙을 따라 진행한다.
지금부터 Phase 1: 뼈대 완성 단계를 시작한다.

1. 프로젝트 초기 세팅 (Gradle + Kotlin + Spring Boot, 모듈러 모놀리식 기준으로
   User/Auth, Product/Inventory, Sale, Purchase, Waiting 패키지 분리)
2. Product/Inventory 모듈부터 시작한다. 좌석/상품 엔티티와 상태 머신(가용 → 선점 → 확정 → 취소)을 설계한다.
   - 새로운 개념(상태 머신 설계, 동시성 경계 등)이 나오면 /learn 절차를 따라라.
3. 엔티티/상태 머신 설계에 여러 안이 있으면 /decide로 비교표를 만들고 나에게 선택을 요청해라.
4. 이번 세션 범위는 "엔티티 + 기본 CRUD API + 테스트"까지다. 그 이상 진행하지 마라.
5. 끝나면 /checkpoint로 요약해라.

지금 진행해도 좋은지 먼저 계획만 3~5줄로 보여주고, 내가 승인하면 시작해라.

---

# Phase 2 후속 세션 킥오프 프롬프트 (테스트 완결성 개선)

Phase 2(Sale/Purchase 동시성 제어: 락 전략 결정, reserve/confirm/cancel API, 동시성 테스트,
벤치마크)가 끝나 PR #2로 올라간 다음 세션에서 이어서 쓴다.

---

이 프로젝트는 CLAUDE.md에 정의된 원칙을 따라 진행한다.
Phase 2(Sale/Purchase 동시성 제어)는 완료돼서 PR #2로 올라가 있다. `docs/decisions.md`와
`docs/benchmarks/2026-09-15-reserve-lock-strategy.md`에 그동안의 결정과 실측 기록이 있으니
먼저 읽어라.

지금까지 모든 테스트는 H2(MODE=MySQL) 인메모리 DB로만 돌렸고, 동시성 벤치마크도 실제 부하테스트
도구가 아니라 서비스 메서드를 직접 호출하는 인메모리 방식이었다. 이전 세션에서 사용자가 "테스트를
생략하지 말고, 못 하는 이유가 있으면 해결해서 완벽하게 하라"고 지시했고, 조사 결과:
- 이 프로젝트가 돌아가는 샌드박스는 Docker 데몬을 못 띄운다(`ulimit: Operation not permitted`,
  컨테이너 보안 경계라 우회 불가 — 환경이 바뀌었으면 다시 확인해봐도 된다).
- Docker 없이 `apt-get install mysql-server`로 실제 MySQL을 직접 설치하는 건 가능함을 확인했다.
- k6/Gatling은 설치 경로가 막혀 있었지만, npm 레지스트리 기반 `autocannon`(Node HTTP 부하테스트
  도구)은 설치 가능함을 확인했다.

planner 서브에이전트에게 스코프를 물어봤고, 다음을 제안받았다(사용자가 최종 승인은 아직 안 함 —
이 세션 시작 시 먼저 확인받아라):
- **이번 단위로 할 것**: GitHub Actions CI에 실제 MySQL service container를 붙여서 기존 테스트를
  돈다. 로컬 MySQL 설치는 별도 산출물로 만들지 않고, 이 워크플로를 작성하는 과정에서 기존
  테스트가 MySQL 방언에서도 통과하는지 1회 사전 점검하는 용도로만 쓴다.
- **다음으로 미룰 것**: `PurchaseReserveBenchmark`를 autocannon 기반 실제 HTTP 부하테스트로
  바꾸는 것(새 툴체인 도입이라 원칙 1 대상 — 별도 `/decide` 필요), MySQL 고유 락 동작(gap lock
  등)을 겨냥한 신규 테스트, 테스트 피라미드 공식화.
- 사용자에게 "이 제안 그대로 승인 vs (로컬 MySQL 사전 점검을 포함한) 통합 여부를 /decide로 먼저
  비교" 중 뭘 원하는지 아직 못 물어봤다 — 이 세션 시작 시 확인해라.

## 지켜야 할 것
1. **서브에이전트를 적극 활용해라.** `.claude/agents/`의 architect/developer/qa/planner 정의에
   이미 `model: sonnet`이 설정돼 있다 — 사용자가 "서브에이전트를 Sonnet 5로 고정해서 돌려라"고
   지시했다. Agent 호출 시에도 `model: "sonnet"`을 명시적으로 넘겨서 이중으로 보장해라.
2. 원칙 2(범위 과다 금지)를 지켜라 — 위에서 이미 분리해둔 스코프를 임의로 넓히지 마라.
3. 원칙 3(테스트 생략 금지)과 이전 세션의 방침을 이어라 — "테스트를 못 하는 이유"가 나오면
   먼저 해결책이 있는지 조사하고, 없으면 왜 없는지 명확히 설명해라(그냥 건너뛰지 마라).

## 진행 순서 (사용자 승인 후)
1. architect에게 넘길 것: MySQL 전용 Spring 프로필(URL/계정/스키마 초기화 방식)과 GitHub
   Actions MySQL service container 설정의 구체적 형태.
2. developer에게 넘길 것: 워크플로 yml + 프로필 설정 작성, (필요하면) 로컬 MySQL 1회 사전
   점검, push 후 실제 GitHub Actions 실행 결과를 확인해서 그린인지 확인 — YAML만 작성하고
   끝내지 마라.
3. qa에게 넘길 것: 새로 생긴 MySQL 프로필/워크플로가 기존 30여 개 테스트의 커버리지나 동작을
   깨뜨리지 않았는지 점검.
4. 끝나면 /checkpoint로 요약해라.

지금 진행해도 좋은지 먼저 계획만 3~5줄로 보여주고, 내가 승인하면 시작해라.

---

# Phase 3 후속 세션 킥오프 프롬프트 (status 컬럼 ENUM → VARCHAR 전환)

Phase 2 후속(테스트를 실제 MySQL로 검증)이 끝나 PR #3으로 올라가 있는 다음 세션에서 이어서 쓴다.

---

이 프로젝트는 CLAUDE.md에 정의된 원칙을 따라 진행한다.
PR #3(MySQL CI 전환 + Flyway 도입)이 올라가 있으니 머지 여부와 리뷰 코멘트를 먼저 확인해라.

이전 세션에서 `docs/decisions.md`에 다음 결정을 기록해뒀다 (`[2026-09-15] status 컬럼
(InventoryUnit.status, PurchaseAttempt.status) 타입` 항목 참고, 기록만 하고 구현은 하지 않음):
- `InventoryUnit.status`, `PurchaseAttempt.status`가 현재 MySQL 네이티브 `ENUM(...)`으로
  매핑돼 있는데(Flyway baseline `V1__baseline_schema.sql`이 Hibernate 자동 생성 결과를 그대로
  옮긴 부작용), `VARCHAR(20)` + Kotlin enum(애플리케이션 레벨 검증) 방식으로 바꾸기로 결정했다.
- 이유: 단일 애플리케이션만 이 DB에 쓰는 구조라 ENUM의 DB 레벨 방어 실익이 낮고, ENUM은
  상태값 추가 시 코드/DB 이중 관리가 필요한데 그 동기화가 깨져도 `ddl-auto: validate`가
  잡아주지 못한다는 걸 QA가 실험으로 확인했다(테스트가 조용히 통과함).

## 이번 세션 범위
1. Flyway `V2__*.sql` 마이그레이션 작성: `inventory_unit.status`, `purchase_attempt.status`를
   `VARCHAR(20) NOT NULL`로 변경.
2. 기존 39개 테스트가 이 변경 후에도 전부 통과하는지 실제 MySQL 위에서 확인 (H2로 돌리지
   마라 — Phase 2 후속에서 이미 테스트 프로필을 MySQL로 전환해뒀다).
3. `/checkpoint`로 요약.

## 스코프 밖 (건드리지 마라, 이미 두 세션째 미뤄진 항목)
- `PurchaseReserveBenchmark`를 autocannon 기반 실부하테스트로 전환 (새 툴체인 도입, 별도 `/decide` 필요)
- MySQL 고유 락(gap lock 등)을 겨냥한 신규 동시성 테스트
- 테스트 피라미드 공식화
- 이 세 가지를 계속 미룰지, 이번엔 착수할지는 이 세션 시작 시 사용자에게 먼저 물어봐라
  (두 세션 연속 미뤄진 상태라 계속 미루는 게 맞는지 확인이 필요하다).

지금 진행해도 좋은지 먼저 계획만 3~5줄로 보여주고, 내가 승인하면 시작해라.

---

# Phase 4 킥오프 프롬프트 (Waiting 모듈, 첫 단위)

Phase 3 후속(status 컬럼 VARCHAR 전환)과 그 다음 세션(gap lock A2/A3, autocannon 실부하테스트,
테스트 피라미드 공식화 — 스코프 밖에 두 세션째 미뤄졌던 세 항목이 이 세션에서 전부 마무리됨)이
끝난 뒤 다음 세션에서 이어서 쓴다.

---

이 프로젝트는 CLAUDE.md에 정의된 원칙을 따라 진행한다.
이번 세션은 신규 모듈 **Waiting(대기열)**의 첫 단위를 다룬다. `docs/decisions.md`와
`docs/agent-workflow.md`(세션 스코프 컨벤션)를 먼저 읽어라.

## 왜 지금 Waiting인가
`src/main/kotlin/com/twshop/`에는 아직 `product/`, `purchase/`만 있고 `user/`, `auth/`,
`sale/`, `waiting/`은 전혀 없다. 미착수 3개 모듈(User/Auth, Sale, Waiting) 중 Waiting을
먼저 고른 이유:
- `docs/decisions.md`의 "InventoryUnit.reserve() 동시성 제어 락 전략" ADR(2026-09-15)이
  "Waiting 모듈과 락을 연계해야 하는 시점에 Redis 분산락으로 전환 검토"를 재검토 조건으로
  명시적으로 지목해뒀다 — 이 모듈이 기존 락 전략 결정을 다시 흔들 첫 지점이다.
- 면접 서사 흐름상 "DB 락으로 시작한 동시성 제어를 대기열/분산 환경으로 확장해가는 이야기"가
  자연스럽다는 게 이전 세션의 판단이었다.

## 이번 세션 범위 (반드시 지켜라 — 이미 사용자가 명시적으로 좁혀둔 스코프다)
**대기열 진입 API + 내 순번 조회 API까지만.** 순번이 됐을 때 reserve를 어떻게 허용할지
(대기열↔reserve 락 연계, 그로 인한 Redis 분산락 전환 여부)는 **다음 단위로 명시적으로
미룬다** — 이번 세션에서 거기까지 손대면 원칙 2 위반이다. 이탈/포기 처리(TTL, leave API),
대기열 크기 제한 등도 다음 단위.

## architect가 이미 정리해둔 후보 비교 (이번 세션 시작 시 사용자에게 다시 보여주고 확정받아라 — 아직 선택 안 됨)

**축 1: 대기열 저장소/자료구조**
- A. MySQL 테이블(순번 컬럼 + 정렬/카운트 쿼리) — 신규 인프라 없음, 대신 순위 계산이
  부자연스럽고 reserve와 비슷한 hot-row 경합 우려
- B. Redis Sorted Set(ZADD/ZRANK) — 정렬 유지+순위 조회가 자료구조 자체의 기본 연산이라
  대기열 도메인에 정석적. `build.gradle.kts`에 Redis 의존성이 아직 없어(CLAUDE.md 스택
  목록에는 있지만 실사용 코드는 처음) 이 모듈이 Redis 실사용의 첫 지점이 됨. 다음 단위에서
  reserve 락을 Redis 분산락으로 전환할지 재검토할 때도 인프라가 이미 붙어 있어 유리
  (원칙 8 — 인터페이스 경계만 열어두고 구현은 늘리지 않는다는 것과 일치)
- C. Redis List/Queue — "내 순번 조회"에 임의 위치 조회가 O(N)이라 사실상 탈락 후보

**축 2: 순번 발급 방식** (축 1과 결합됨 — B를 고르면 "진입 시 INCR로 유일 score 발급 +
조회 시 ZRANK로 순위 계산"을 자연스럽게 같이 가져감)
- 원자적 증가(Redis INCR/DB 시퀀스): 구현 단순, 단 이탈자가 생겨도 카운터가 안 줄어들어
  "발급 번호=현재 순위"가 아니게 됨(이번 세션엔 이탈 처리가 없어 이 차이가 아직 안 드러남)
- 정렬 기반(ZADD score=진입순서, ZRANK로 순위 재계산): 이탈자가 ZREM되면 순위가 자동으로
  당겨짐 — 그 장점은 다음 단위(이탈 처리)에서 발휘됨

**축 3 (확인 수준, 후보 비교 불필요)**: productId별 독립 대기열. 기존 `InventoryUnit.product`
FK 설계와 결이 같다.

**축 4: 순번 조회의 정합성**: 스냅샷(진입 시 확정, 안 바뀜) vs 실시간 재계산(조회할 때마다
현재 대기열 기준 재계산). 이탈 처리가 없는 이번 범위에서는 실시간 재계산의 실익이 아직
안 드러난다 — 다음 단위와 맞물리는 항목.

**architect 추천안**: B(Redis Sorted Set) + 정렬 기반 순위 계산. 단점(신규 인프라 학습
곡선, 영속성 취약)은 인정하고 감수하는 선택이며, A(MySQL 테이블, 신규 인프라 없음)도
합리적인 대안이라 최종 선택은 사용자 판단으로 남겨뒀다.

**개략적 엔티티/API 스케치 (B 기준, 확정 전)**
- Redis 키: `waiting:{productId}` (ZSET, member=buyerId, score는 `waiting:{productId}:seq`
  키의 INCR로 발급한 단조증가 값)
- 애플리케이션 경계는 `WaitingQueueService` 하나로 캡슐화(자료구조 교체 시 변경 지점을
  그 서비스로 한정 — reserve() 때와 같은 전략, 사전 추상화/전략 패턴은 만들지 않음)
- `POST /api/waiting/{productId}/enter` body `{buyerId}` → `{productId, buyerId, rank}`
- `GET /api/waiting/{productId}/rank?buyerId=...` → `{rank, aheadCount}`
- 중복 진입 정책(같은 buyerId가 재요청하면 새 번호 발급 대신 기존 rank 반환할지, 에러를
  낼지)은 아직 미확정 — 세션 시작 시 확정해라.

## 지켜야 할 것
1. **서브에이전트를 적극 활용해라** — 이 프로젝트는 이제 "메인 에이전트는 감독만, 실제
   설계/구현/검증은 서브에이전트가 한다"는 방식으로 진행 중이다. architect(위 후보 확정 및
   `/decide` 기록) → developer(구현) → qa(검증) 순서로 위임해라. `.claude/agents/`의
   서브에이전트는 `model: sonnet`으로 고정해서 호출해라.
2. 원칙 1: 위 후보 중 하나를 사용자가 고르거나 "네가 골라"라고 할 때까지 코드 작성 보류.
3. 원칙 2: 위에서 이미 좁혀둔 스코프(진입+순번조회)를 임의로 넓히지 마라.
4. 원칙 3: 새 로직에는 테스트를 함께 작성해라. 동시 진입 시 순번 유일성/원자성을 검증하는
   동시성 테스트가 필요할 가능성이 높다(이 프로젝트가 지금까지 이런 테스트를 중요하게
   다뤄왔다).
5. 원칙 4: `/decide`로 축 1/2 선택을 `docs/decisions.md`에 기록해라.

## 진행 순서 (사용자 확인 후)
1. 위 후보를 사용자에게 다시 제시하고 확정받는다 (또는 "네가 골라").
2. architect에게 최종 확정된 설계로 `/decide` 기록을 넘긴다.
3. developer에게 엔티티/서비스/API/테스트 구현을 넘긴다.
4. qa에게 동시성/엣지 케이스 검증을 넘긴다.
5. `/checkpoint`로 요약.

지금 진행해도 좋은지 먼저 계획만 3~5줄로 보여주고, 내가 승인하면 시작해라.

---

# Phase 5 킥오프 프롬프트 (Waiting 모듈, 다음 단위)

Phase 4(대기열 진입/순번 조회, Redis Sorted Set)가 끝나 PR #6로 올라간 다음 세션에서
이어서 쓴다.

---

이 프로젝트는 CLAUDE.md에 정의된 원칙을 따라 진행한다.

## 시작 전에 반드시 할 것 — 브랜치 컨벤션
`docs/agent-workflow.md`의 "브랜치 컨벤션(2026-09-16)" 절을 먼저 읽어라. **세션마다 새
브랜치를 판다** — PR #6이 머지됐는지 먼저 확인하고, 트렁크(`claude/harness-engineering-setup-8aeqsu`)에서
새 브랜치(예: `claude/waiting-reserve-integration` 등, 이번 세션이 뭘 고르느냐에 따라
이름 결정)를 파고 시작해라. 트렁크에 바로 커밋하지 마라.

## 배경
`docs/decisions.md`의 "[2026-09-16] Waiting 모듈 대기열 설계" ADR과 "구현 메모"를 먼저
읽어라. 이번 세션이 시작할 수 있는 다음 단위 후보 3개가 그 ADR의 재검토 조건에 이미
나열되어 있다:

1. **순번 도달 시 reserve 연계** — 대기열 순번이 됐을 때만 reserve를 허용하도록 Waiting과
   Purchase를 잇는 것. 이게 붙는 순간 `InventoryUnit.reserve()`의 락 전략(2026-09-15 ADR,
   현재 비관적 DB row lock)을 Redis 분산락으로 전환할지 재검토해야 하는 바로 그 트리거다
   — 원래 그 ADR이 "Waiting 모듈과 락을 연계해야 하는 시점"으로 지목해둔 지점이 지금이다.
2. **이탈/포기 처리(TTL, leave API)** — 지금은 한번 대기열에 들어가면 영원히 안 빠진다.
   "연결 유지=대기 중"이라는 재진입 정책(Phase 4에서 확정)과 자연스럽게 이어지는 다음
   이야기.
3. **대기열 크기 제한** — "선착순 N명" 같은 정책.

이전 세션(Phase 4)에서 사용자에게 우선순위를 물어본 결과, 셋 다 유효한 후보이고 순서상
1번(reserve 연계 → 락 전략 재검토)이 이 프로젝트의 핵심 학습 서사(DB 락에서 분산 환경
동시성 제어로 확장)와 가장 잘 이어진다는 의견이 나왔었다 — 단, 확정은 아니었으니 이번
세션 시작 시 사용자에게 다시 물어봐라.

## 지켜야 할 것
1. 브랜치 컨벤션(위 참고) — 세션마다 새 브랜치.
2. **서브에이전트를 적극 활용해라** — architect(설계/락 전략 재검토 필요 시 후보 비교) →
   developer(구현) → qa(검증) 순서. 메인 에이전트는 감독만 한다. `.claude/agents/`의
   서브에이전트는 `model: sonnet`으로 고정해서 호출해라.
3. 원칙 1: 1번(reserve 연계)을 고르면 "Redis 분산락 전환 여부"라는 새로운 락 전략 결정이
   따라온다 — 반드시 후보 비교 후 사용자 선택을 받아라. 기존 비관적 락을 유지하면서
   Waiting과 어떻게 연계할지도 후보가 될 수 있다(예: 순번 확인만 애플리케이션 레벨에서
   하고 실제 재고 락은 그대로 DB row lock 유지 — 분산락 전환이 필수는 아닐 수 있다는
   것도 열어두고 비교해라).
4. 원칙 2: 이번 세션이 고른 단위 하나만 다뤄라. 나머지 두 후보는 다시 다음 세션으로.
5. 원칙 3: 동시성 관련 로직이면 테스트 필수(Phase 4의 `WaitingServiceConcurrencyTest`
   패턴 참고).

## 진행 순서 (사용자 확인 후)
1. 위 세 후보 중 하나를 사용자에게 확정받는다.
2. (1번을 골랐다면) architect에게 락 전략 재검토 후보 비교를 넘긴다 — `/decide`로
   `docs/decisions.md`에 기록.
3. developer에게 구현을 넘긴다.
4. qa에게 검증을 넘긴다.
5. `/checkpoint`로 요약 → PR 생성.

지금 진행해도 좋은지 먼저 계획만 3~5줄로 보여주고, 내가 승인하면 시작해라.
