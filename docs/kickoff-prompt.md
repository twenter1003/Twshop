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
