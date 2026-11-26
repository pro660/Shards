# 버킷 재배치 전후의 주문 조회 성능

2026년 11월 26일 로컬 실험에서 버킷 05를 A에서 C로 옮겼다. 같은 주문 목록을 조회했을 때 전체 p95는 이동 전 29.64 ms, 복사본이 있는 상태에서 29.43 ms, 완료 후 27.57 ms였다. 세 구간의 본측정 3,000건은 모두 HTTP 200이었다.

## 측정 조건

- 한 PC에서 Spring Boot와 PostgreSQL 18 컨테이너 네 개(메타·A·B·C)를 실행했다. 주문 API를 호출해 응답 본문을 끝까지 읽는 데 걸린 시간을 측정했다.
- 사용자 3은 버킷 05의 이동 대상, 사용자 101은 줄곧 B에 있는 버킷 10의 대조군이다. 두 사용자의 주문은 각 한 건이다. 같은 05에 속하는 사용자 10의 주문 100건을 더해 이동 단위를 101건으로 만들었다. 세 구간에서 두 측정 사용자의 주문 목록 전체가 이동 전과 같음을 확인했다.
- 구간마다 예열 40건을 먼저 보냈다. 본측정은 동시 요청 2개, 회당 200건, 5회였다. 매회 난수 시드 42로 같은 사용자 순서를 재생했다. 각 구간에서 사용자 3이 515건, 사용자 101이 485건을 차지했다.
- 복사 구간은 100건과 1건을 복사하고 다음 진행 버튼을 누르기 전이다. A와 C에 각각 101건이 있고, 읽기는 여전히 A가 담당한다. 복사 SQL이 실행되는 순간의 부하는 이 측정에서 제외했다.

## 실측 결과

| 구간 | HTTP 200 | 처리량 (요청/초) | 전체 p50 (ms) | 전체 p95 (ms) | 사용자 3 p95 (ms) | 사용자 101 p95 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 이동 전 · A 소유 | 1,000/1,000 | 185.83 | 4.97 | 29.64 | 29.15 | 29.63 |
| 복사 직후 · A 소유 | 1,000/1,000 | 181.62 | 5.40 | 29.43 | 28.75 | 29.63 |
| 완료 후 · C 소유 | 1,000/1,000 | 221.74 | 3.62 | 27.57 | 27.07 | 29.46 |

p50은 요청의 절반이, p95는 요청의 95%가 응답을 받은 시간이다. 표의 처리량과 지연 시간은 각 회차에서 구한 값 다섯 개의 중앙값이다. 성공 건수는 다섯 회차의 합계다. 처리량은 이동 전 152.72~198.65, 복사 직후 165.13~233.88, 완료 후 191.40~262.74 요청/초 범위에서 변동했다.

이 실행에서는 완료 후 이동 사용자의 p95가 2.08 ms 줄었고, 같은 B에 머문 사용자의 p95도 0.17 ms 줄었다. 측정 순서는 이동 전부터 완료 후까지 고정됐고 모든 DB가 한 PC의 CPU와 디스크를 공유했다. 별도 장비로 부하가 분산될 때의 효과와 장비 간 통신 비용은 원격 C로 같은 실험을 반복해야 평가할 수 있다.

## PowerShell로 재현

1. [시연 순서](demo.md#브라우저-재배치)에 따라 A가 버킷 05, B가 버킷 10을 소유하는 개발용 환경에 시연 주문을 먼저 준비한다. 위 데이터 규모를 재현할 때는 사용자 3·101이 각각 한 건, 사용자 10이 총 100건이 되게 준비한다. 마지막 측정까지 다른 주문 생성·수정·삭제와 테스트 실행을 멈춘다.
2. 프로젝트 루트에서 아래 함수를 한 번 정의한다. 실행마다 새 폴더에 저장하므로 이전 측정 파일을 보존할 수 있다.

   ```powershell
   $api = 'http://127.0.0.1:8080'
   $measurementRoot = Join-Path $env:LOCALAPPDATA ('shards-lab\measurements\' + [guid]::NewGuid().ToString('N'))

   function Measure-DemoPhase([ValidateSet('before','copying','after')][string]$Phase) {
       foreach ($round in 0..5) {
           # 0번은 예열이고, 1~5번이 비교할 본측정입니다.
           $samples = if ($round -eq 0) { 40 } else { 200 }
           $output = Join-Path $measurementRoot "$Phase-$round.json"
           python -B scripts/shards_experiment.py measure --url $api --label $Phase --users 3 101 --samples $samples --workers 2 --seed 42 --output $output
           if ($LASTEXITCODE -ne 0) { throw "Measurement failed for $Phase round $round" }
           $report = Get-Content -Raw -LiteralPath $output | ConvertFrom-Json
           if ($report.statuses.'200' -ne $samples) { throw "Inspect HTTP results in $output" }
       }
   }
   ```

3. 아래 세 명령은 각각 해당 시점에 실행한다. 복사 건수가 여러 번에 걸쳐 증가하면 마지막 배치가 화면에 반영된 뒤 `copying`을 측정한다.

   | 시점 | 터미널 3에서 실행 |
   | --- | --- |
   | 주문 준비가 끝나고 재배치 작업을 등록하기 전 | `Measure-DemoPhase before` |
   | `COPYING`에서 행을 복사한 뒤, 다음 **Advance one step**을 누르기 전 | `Measure-DemoPhase copying` |
   | `COMPLETED` 직후, 복원이나 장애 시연을 시작하기 전 | `Measure-DemoPhase after` |

4. 동일 회차의 원본 JSON에서 `requests_per_second`, `latency_ms`, `statuses`, `by_user`를 확인한다. 다음 예시는 완료 후 첫 회차에서 두 사용자의 p95를 나란히 출력한다.

   ```powershell
   $run = Get-Content -Raw -LiteralPath (Join-Path $measurementRoot 'after-1.json') | ConvertFrom-Json
   $run.by_user.'3', $run.by_user.'101' | Select-Object requests, error_rate, @{Name='p95_ms'; Expression={$_.latency_ms.p95}}
   ```
