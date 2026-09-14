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
