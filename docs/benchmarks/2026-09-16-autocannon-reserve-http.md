# POST /api/purchase-attempts 실HTTP 부하테스트 (autocannon)

- 관련 결정: `docs/decisions.md`의 `[2026-09-16] PurchaseReserveBenchmark를 autocannon 기반 실부하테스트로 전환`
- 도구: `load-test/reserve-benchmark.js` (사용법은 `load-test/README.md`)
- **다른 문서와의 관계**: 이 문서는 HTTP 스택 전체(Tomcat 스레드풀, 커넥션 수립, 직렬화,
  DB 커넥션 풀 포함)를 관통하는 처리량을 잰 것이다. `reserve()` 안에서 락 전략(비관적 락 vs
  락 없음)이 처리량에 미치는 영향만 순수하게 비교하려면
  `docs/benchmarks/2026-09-15-reserve-lock-strategy.md`를 보라 — 그쪽은 서비스 메서드를 직접
  호출해 HTTP 계층을 배제한 인메모리 벤치마크다.

## 측정 환경

- macOS, Apple M5 (10코어: P4/E6), 메모리 24GB — 부하 생성기(autocannon)와 서버(Spring Boot)가
  **같은 머신**에서 동시에 돌았다. 즉 이 수치는 순수 서버 성능이 아니라 "부하 생성기와 서버가
  자원을 나눠 쓴" 상태의 처리량이다 — 별도 머신에서 부하를 쏘면 수치가 달라질 수 있다.
- MySQL 8.0 (docker, `twshop-mysql` 컨테이너), 앱은 `SERVER_PORT=8081 ./gradlew bootRun`으로
  로컬 기동 (8080은 다른 프로세스가 점유 중이라 8081 사용)
- HikariCP/Tomcat 스레드풀 등은 `application.yml`에 별도 설정이 없어 모두 Spring Boot
  기본값(HikariCP 최대 풀 크기 10, Tomcat 최대 스레드 200)을 그대로 썼다.
- 테스트 상품: 재고 50,000개(`initialQuantity`)로 생성 — 두 라운드 합산 7,750건이 선점됐지만
  전 구간 재고가 넉넉해 `FAILED` 응답은 0건이었다(재고 소진에 의한 처리량 왜곡 없음).

## 결과

| 동시 연결 수 | duration | 총 요청(2xx) | 평균 req/s | p50 지연(ms) | p99 지연(ms) | non-2xx / 에러 |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 10s | 4,660 | 466.0 | 106 | 146 | 0 |
| 200 | 10s | 2,840 | 284.0 | 699 | 801 | 0 |

원본 autocannon JSON 결과(latency/requests/throughput 전체 percentile)는 실행 로그에서 위 표로
요약했고, 재현은 `load-test/README.md`의 절차와 아래 커맨드로 가능하다:

```
node reserve-benchmark.js --product-id <ID> --url http://localhost:8081 --connections 50 --duration 10
node reserve-benchmark.js --product-id <ID> --url http://localhost:8081 --connections 200 --duration 10
```

## 해석

- **연결 수를 50 → 200으로 늘렸더니 처리량이 오히려 떨어졌다** (466 → 284 req/s), 지연은
  급증했다(p50 106ms → 699ms). 이는 "연결을 늘리면 처리량도 는다"는 순진한 기대와 반대되는
  결과다.
- **원인 추정(가설, 추가 계측 없이 확정하지 않음)**: HikariCP 기본 풀 크기가 10인데 반해 200개
  연결이 동시에 DB 커넥션을 요구하면서, 초과 요청들이 커넥션을 기다리며 대기 큐에 쌓였을
  가능성이 높다. 재고가 50,000개로 넉넉해 `reserve()`의 row-level 락 경합(hot row 경쟁)은 이
  실험에서 사실상 없었다 — 매 요청이 서로 다른 재고 row를 잡으므로, 200 연결 시나리오의 지연
  증가는 InnoDB 락 대기가 아니라 애플리케이션/커넥션 풀 계층의 큐잉으로 보는 것이 더
  합리적이다. 다만 이번 벤치마크는 HikariCP 풀 사용률이나 대기 큐 길이를 직접 계측하지
  않았으므로 이는 추정이며, 확정하려면 HikariCP 메트릭(예: `HikariPoolMXBean`)을 추가로
  노출해 재측정해야 한다.
- 이 결과는 "동시성 처리량을 늘리려면 무작정 연결 수를 늘리기보다 DB 커넥션 풀 크기 같은
  하위 리소스 한도를 함께 조정해야 한다"는, 흔히 알려진 원칙을 이 프로젝트에서 실측으로
  확인한 사례로 남긴다. 커넥션 풀 크기를 조정하는 것은 이번 세션 스코프가 아니므로 여기서는
  변경하지 않는다.

## 한계

- 부하 생성기와 서버가 같은 머신을 공유해 CPU/네트워크 자원을 나눠 썼다 — 절대 수치(req/s)를
  "이 서버의 최대 처리량"으로 해석하면 안 되고, 같은 조건에서의 상대 비교(연결 수 50 vs 200)
  용도로만 사용한다.
- HikariCP 풀 크기가 병목이라는 해석은 추정이며, 직접 계측하지 않았다(위 해석 항목 참고).
- 재고 소진에 의한 `FAILED` 혼입은 이번 측정에서 발생하지 않았지만, 재고를 적게 잡고 재측정하면
  `2xx`이면서도 `status=FAILED`인 응답이 섞여 순수 처리량 해석이 어려워진다는 점을
  `load-test/README.md`에 별도로 남겼다.
