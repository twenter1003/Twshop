# reserve-benchmark: `POST /api/purchase-attempts` 실HTTP 부하테스트

- 관련 결정: `docs/decisions.md`의 `[2026-09-16] PurchaseReserveBenchmark를 autocannon 기반 실부하테스트로 전환`
- 결과 기록: `docs/benchmarks/2026-09-16-autocannon-reserve-http.md`
- 이 도구는 `src/test/kotlin/com/twshop/purchase/application/PurchaseReserveBenchmark.kt`(서비스
  메서드를 직접 호출하는 인메모리 벤치마크, 순수 락 경합 비교용)를 대체하지 않는다. 이쪽은
  Tomcat 스레드풀/커넥션 수립/직렬화를 포함한 HTTP 스택 전체 처리량을 본다.

## 왜 별도 Node 프로젝트인가

Gradle 루트 프로젝트와 의존성이 섞이지 않도록 `load-test/` 아래에 독립된 `package.json`을 뒀다.
Kotlin 빌드와 무관한 devDependency(autocannon)라 루트에 두면 빌드 그래프를 불필요하게 오염시킨다.

## 사전 준비

1. MySQL 기동 (이미 떠 있지 않다면):
   ```
   docker compose up -d
   ```
2. 앱 기동. **주의**: 로컬 8080 포트를 다른 프로세스가 쓰고 있을 수 있으니, 필요하면
   `SERVER_PORT` 환경변수로 포트를 바꿔서 띄운다.
   ```
   SERVER_PORT=8081 ./gradlew bootRun
   ```
3. 재고를 충분히 크게 잡은 상품 생성 (예: `initialQuantity`를 부하테스트 중 절대 소진되지
   않을 만큼 크게 — 연결 수 × 초당 처리량 추정치 × duration보다 넉넉히 크게 잡을 것):
   ```
   curl -X POST http://localhost:8081/api/products \
     -H "Content-Type: application/json" \
     -d '{"name":"load-test-product","description":null,"price":10.00,"initialQuantity":50000}'
   ```
   응답의 `id`를 아래 `--product-id`에 쓴다.

   **재고가 부족하면 안 되는 이유**: `POST /api/purchase-attempts`는 재고 선점에 성공하든
   실패하든 항상 HTTP 201을 반환하고, 성공/실패는 body의 `status` 필드(`RESERVED`/`FAILED`)로만
   구분된다. 즉 재고가 중간에 바닥나면 이후 요청들은 (서버 입장에서는 정상 처리됐지만) 전부
   `FAILED`로 찍히는데, autocannon은 HTTP status code만 보고 성공/실패를 센다. 결과 통계의
   `2xx`/`non2xx` 카운트만 봐서는 이 시나리오와 "요청이 실제로 재고 선점에 성공한 처리량"을
   구분할 수 없다 — 순수 처리량을 재려면 테스트 내내 재고가 넉넉해야 한다.

## 실행

```
cd load-test
npm install
node reserve-benchmark.js --product-id <ID> --url http://localhost:8081 --connections 50 --duration 10
```

옵션 (모두 선택, 기본값은 스크립트 참고):
- `--product-id` (필수): 위에서 만든 상품 ID
- `--url`: 앱 base URL (기본 `http://localhost:8081`)
- `--connections`: 동시 연결 수 (기본 50)
- `--duration`: 실행 시간(초) (기본 10)

결과는 autocannon의 원본 결과 객체를 JSON으로 stdout에 출력한다(진행률 표시는 별도로 화면에
찍힘). 리다이렉트해서 파일로 남긴 뒤 `docs/benchmarks/`에 사람이 읽을 수 있는 형태로 옮겨 적는다.

## 실행 후 정리

부하테스트가 끝나면 앱을 종료한다(`Ctrl+C` 또는 백그라운드로 띄웠다면 해당 프로세스 kill).
테스트 중 생성된 `PurchaseAttempt`/`InventoryUnit` row는 로컬 DB에 남는데, 이 프로젝트가
포트폴리오 목적의 로컬 개발 DB라 별도로 치우지 않아도 무방하다.
