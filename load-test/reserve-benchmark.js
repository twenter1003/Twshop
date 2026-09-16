#!/usr/bin/env node
'use strict'

/**
 * POST /api/purchase-attempts에 대한 실HTTP 부하테스트.
 *
 * `PurchaseReserveBenchmark.kt`(인메모리, 서비스 메서드 직접 호출)와 답하는 질문이 다르다 —
 * 이 스크립트는 Tomcat 스레드풀/커넥션 수립/직렬화까지 포함한 "실제 서비스가 이 트래픽을
 * 견디는가"를 측정한다 (2026-09-16 결정, docs/decisions.md 참고).
 *
 * autocannon을 CLI 도구가 아니라 Node API로 불러 쓴 이유: CLI 인자를 그대로 이 스크립트의
 * 인자로 받고, 결과를 이 스크립트가 직접 가공(JSON 출력)해서 나중에 서버 기동까지 자동화하는
 * 스크립트가 생기면 이 파일을 그대로 child_process로 감싸 재사용할 수 있게 하기 위함이다.
 * 지금 이 세션에서 그 자동화(서버 기동/헬스체크/종료)까지 만들지는 않는다 — 그건 별도
 * 스코프라고 판단했다(ADR의 "재검토 조건" 참고).
 *
 * 사용법: node reserve-benchmark.js --product-id <ID> [--url <BASE_URL>] [--connections <N>] [--duration <SEC>]
 * 자세한 사전 준비(상품 생성 등)는 load-test/README.md 참고.
 */

const autocannon = require('autocannon')

/**
 * `--key value` 형태의 인자만 지원하는 최소 파서.
 *
 * yargs/minimist 같은 의존성을 추가하는 대신 직접 짠 이유: 이 스크립트가 받는 옵션이 4개뿐이라
 * 파싱 라이브러리를 추가하는 비용이 이득보다 크다고 판단했다(불필요한 의존성 추가 지양).
 */
function parseArgs(argv) {
  const args = {}
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (!token.startsWith('--')) continue
    const key = token.slice(2)
    const value = argv[i + 1]
    args[key] = value
    i += 1
  }
  return args
}

function printUsageAndExit() {
  console.error('사용법: node reserve-benchmark.js --product-id <ID> [--url <BASE_URL>] [--connections <N>] [--duration <SEC>]')
  console.error('예시: node reserve-benchmark.js --product-id 1 --url http://localhost:8081 --connections 50 --duration 10')
  process.exit(1)
}

function main() {
  const args = parseArgs(process.argv.slice(2))

  if (!args['product-id']) {
    printUsageAndExit()
  }

  const productId = Number(args['product-id'])
  const baseUrl = args.url ?? 'http://localhost:8081'
  const connections = Number(args.connections ?? 50)
  const duration = Number(args.duration ?? 10)

  // buyerId는 재고 선점 로직에서 유일성 제약이 없어(PurchaseService.reserve 참고) 모든 요청이
  // 같은 값을 써도 측정 대상인 "처리량"에는 영향이 없다. 연결마다 다른 값을 생성하는 로직을
  // 추가하는 대신 고정값을 써서 스크립트를 단순하게 유지했다.
  const requestBody = JSON.stringify({ productId, buyerId: 'load-test-buyer' })

  const instance = autocannon(
    {
      url: `${baseUrl}/api/purchase-attempts`,
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: requestBody,
      connections,
      duration,
    },
    (err, result) => {
      if (err) {
        console.error('부하테스트 실행 중 오류:', err)
        process.exit(1)
      }
      // 사람이 읽는 진행률(progress bar)은 아래 autocannon.track이 stderr 유사 출력으로 보여주고,
      // 최종 결과는 stdout에 JSON 그대로 찍는다 — 나중에 자동화 스크립트가 이 stdout을 파싱해
      // 재사용하기 쉽게 하기 위함이다(사람은 파일로 리다이렉트해 docs/benchmarks/에 옮겨 적으면 됨).
      console.log(JSON.stringify(result, null, 2))
    },
  )

  autocannon.track(instance, { renderProgressBar: true })
}

if (require.main === module) {
  main()
}

module.exports = { parseArgs }
