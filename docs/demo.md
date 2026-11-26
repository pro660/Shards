# 버킷 재배치 시연

주문이 있는 버킷을 A에서 C로 옮긴 뒤, 같은 주문 번호로 다시 조회하고 실제 저장 위치까지 확인한다. 로컬 시연에서는 메타 DB와 A·B·C를 한 PC의 PostgreSQL 컨테이너 네 개로 실행한다.

## 첫 실행

준비물은 Java 21, Docker Compose, Node.js 22.12 이상, Python 3.11 이상과 PostgreSQL 18 클라이언트다. 프로젝트 루트의 로컬 `.env`에서 `META_DB`, `SHARD_A_DB`, `SHARD_B_DB`, `SHARD_C_DB` 각각의 `_NAME`, `_USER`, `_PASSWORD`를 설정한다. 관리 API에는 서로 다른 `APP_ADMIN_READ_TOKEN`과 `APP_ADMIN_WRITE_TOKEN`을 사용한다. 토큰을 Spring을 실행할 터미널의 환경 변수로 공급해도 된다.

**터미널 1**에서 DB를 기동한다. 빈 볼륨의 첫 시작에 메타 DB는 SQL 001·002·003·004·007을, A·B·C는 SQL 005·009를 자동 적용한다. 기존 볼륨은 저장된 매핑과 주문으로 시작한다. 이전 실습에서 일부 SQL만 적용한 볼륨은 해당 테이블과 인덱스를 확인한 뒤 빠진 SQL만 적용한다.

```powershell
docker compose up -d --wait
docker compose -f compose.shard-c.yaml up -d --wait
```

새 메타 DB의 샤드 등록 상태는 `INACTIVE`다. **최초 준비 때만** 네 컨테이너가 준비됐는지 확인하고 아래 명령으로 실습용 샤드를 활성화한다. 현재 개발 DB처럼 이미 세 샤드가 활성화된 환경은 Spring 실행으로 이동한다.

```powershell
$metaUser = docker compose exec -T meta-db printenv POSTGRES_USER
$metaDb = docker compose exec -T meta-db printenv POSTGRES_DB
docker compose exec -T meta-db psql -X -U $metaUser -d $metaDb -v ON_ERROR_STOP=1 -c "UPDATE public.shards SET status = 'ACTIVE' WHERE connection_key IN ('shardA','shardB','shardC');"
```

같은 터미널에서 앱 로그 경로를 지정하고 Spring을 실행한다. 기본 프로필 `shard-c`는 관리 화면과 주문 API가 C의 DB 연결을 사용할 수 있게 한다.

```powershell
$labRoot = Join-Path $env:LOCALAPPDATA 'shards-lab'
New-Item -ItemType Directory -Path $labRoot -Force | Out-Null
$env:LOGGING_FILE_NAME = Join-Path $labRoot 'application.log'
.\gradlew.bat bootRun
```

**터미널 2**에서 React를 실행한다. 의존성을 처음 설치하거나 잠금 파일이 바뀐 경우 `npm ci`를 실행한다.

```powershell
Set-Location frontend
npm ci
npm run dev
```

## 브라우저 재배치

1. **현재 소유권을 읽는다.** `http://127.0.0.1:5173`에서 읽기 토큰을 입력하고 **Load topology**를 누른다. 버킷은 여러 사용자의 주문을 묶는 논리적 번호이고, 샤드는 그 행들을 저장하는 PostgreSQL DB다. 한 버킷은 한 시점에 한 샤드가 소유한다. `ACTIVE`는 메타 DB에 등록된 사용 상태다.
2. **이동할 주문과 대조 주문을 만든다.** 현재 개발 DB에서 이전 작업 7로 버킷 `03`은 C·v2에 있다. 이 시연은 A·v1인 **버킷 05**와 B·v1인 **버킷 10**을 사용한다. 사용자 3과 10은 같은 버킷 05, 사용자 101은 버킷 10에 속한다. 발표 직전에 화면에서 해당 소유자를 확인한다. **터미널 3**을 프로젝트 루트에서 열고 아래 명령을 한 번 실행한다. 실행할 때마다 시연 주문 세 건이 생긴다.

   ```powershell
   $api = 'http://127.0.0.1:8080'
   function New-DemoOrder([long]$UserId) {
       $body = @{ userId = $UserId; amount = 15000 } | ConvertTo-Json
       Invoke-RestMethod "$api/orders" -Method Post -ContentType 'application/json' -Body $body -ErrorAction Stop
   }
   $demoOrder = New-DemoOrder 3
   $peerOrder = New-DemoOrder 10
   $controlOrder = New-DemoOrder 101
   @($demoOrder, $peerOrder, $controlOrder) | Format-Table userId, bucketId, orderId

   # 주문의 소유 사용자와 주문 번호가 함께 상세 조회 주소를 구성합니다.
   $detailUrl = "$api/orders/$($demoOrder.orderId)?userId=3"
   $beforeOrder = Invoke-RestMethod $detailUrl -ErrorAction Stop
   $beforeCount = (Invoke-RestMethod "$api/orders/count" -ErrorAction Stop).totalCount
   ```

   주문 CRUD도 보여줄 때는 별도의 연습 주문 한 건으로 아래를 실행한다. `POST`는 주문을 만들고 `PATCH`는 금액을 바꾸며 `DELETE`는 해당 주문을 삭제한다. 삭제한 주문의 재조회는 `404`다. 앞에서 번호를 보관한 시연 주문 세 건은 이후 비교에 사용한다.

   ```powershell
   $practice = New-DemoOrder 3
   $practiceUrl = "$api/orders/$($practice.orderId)"
   Invoke-RestMethod "$practiceUrl/amount?userId=3" -Method Patch -ContentType 'application/json' -Body '{"amount":17000}' -ErrorAction Stop
   Invoke-RestMethod "$($practiceUrl)?userId=3" -ErrorAction Stop | Select-Object orderId, amount
   Invoke-RestMethod "$($practiceUrl)?userId=3" -Method Delete -ErrorAction Stop
   curl.exe -s -o NUL -w "HTTP %{http_code}\n" "$($practiceUrl)?userId=3"
   ```

   성능도 시연한다면 연습 주문 삭제 후 `before`를 측정한다. 이동 전·복사 중·이동 후 모두 같은 사용자와 주문 데이터로 조회한다. [측정 조건과 명령](performance.md)

3. **작업을 등록한다.** **Bucket migration**에서 `05 · A`와 도착지 `C`를 선택하고 쓰기 토큰으로 **Register migration**을 누른다. `PENDING`에서 작업 번호와 경로 A → C를 확인한다. 등록은 메타 DB에 작업 한 건을 남긴다. 이 단계에서 **Cancel pending job**으로 취소할 수 있다.
4. **복사와 소유권 전환을 보여준다.** **Advance one step**을 한 번씩 누른다. 표시한 단계는 서버가 저장한 현재 상태다.

   | 현재 단계 | 화면 또는 API에서 볼 내용 | 설명할 개념 |
   | --- | --- | --- |
   | `PENDING` | 작업 번호가 생기고 05의 소유자는 A | 메타 DB가 시작 버전과 양쪽 샤드를 기록한다. |
   | `COPYING` | 05는 A·`migrating`이다. 진행을 누르면 복사 건수가 늘어난다. | 최대 100건씩 복사한다. C 커밋 후 마지막 UUID를 체크포인트로 남겨 중단 후 이어간다. 버킷 05의 쓰기는 제한되고 읽기는 A가 처리한다. |
   | `VERIFYING` | 다음에 복사할 행이 없어 비교 단계에 도착했다. | 양쪽의 UUID와 주문 필드를 비교한다. |
   | `SWITCHING` | 첫 비교가 끝났고 소유자는 아직 A다. | 다음 진행에서 재검증하고 기존 읽기가 끝나면 소유자·버전·작업 단계를 한 메타 DB 트랜잭션에서 변경한다. |
   | `CLEANING` | 05가 C·`normal`이 되고 버전이 하나 오른다. | 이제 C가 읽기와 쓰기를 담당한다. A의 이전 행은 배치로 정리한다. |
   | `COMPLETED` | 작업이 끝나고 새 작업을 등록할 수 있다. | A에서 이 버킷에 남은 행이 없음을 확인했다. |

   `COPYING`에서 복사 건수가 늘어난 직후 아래 명령으로 설명을 보탤 수 있다. 다른 주문 변경이 없는 시연 환경에서 전체 건수는 이동 전과 같다. 성능 측정의 `copying`도 이 시점에 실행한다. 페이지를 다시 열면 읽기 토큰을 입력해 미완료 작업 번호를 다시 볼 수 있다.

   ```powershell
   Invoke-RestMethod $detailUrl                         # A에서 기존 주문을 계속 읽습니다.
   (Invoke-RestMethod "$api/orders/count").totalCount   # 소유 샤드만 세므로 복사본만큼 늘지 않습니다.
   $beforeCount
   # 새로운 UUID의 삭제 요청으로, 해당 버킷의 쓰기가 SQL 실행 전에 503을 받는지 확인합니다.
   $unusedId = [guid]::NewGuid()
   curl.exe -s -o NUL -w "HTTP %{http_code}\n" -X DELETE "$api/orders/${unusedId}?userId=3"
   ```

   복사본을 직접 보여주려면 터미널 3에서 다음 함수를 정의한다. `COPYING` 중 **복사 건수가 1 이상일 때**, 그리고 `COMPLETED` 후에 같은 명령을 실행하면 A에 남은 행 수의 차이가 보인다. 이 SQL은 버킷 05의 실제 행만 센다.

   ```powershell
   function Show-DemoBucketRows {
       foreach ($service in 'shard-a','shard-c') {
           $options = @()
           if ($service -eq 'shard-c') { $options = @('-f','compose.shard-c.yaml') }
           $dbUser = docker compose @options exec -T $service printenv POSTGRES_USER
           $dbName = docker compose @options exec -T $service printenv POSTGRES_DB
           $rows = docker compose @options exec -T $service psql -X -A -t -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM public.orders WHERE bucket_id = 5'
           if ($LASTEXITCODE -ne 0) { throw "Could not read bucket 05 on $service" }
           [pscustomobject]@{ Shard = $service; Bucket05Rows = [long]$rows }
       }
   }
   Show-DemoBucketRows
   ```

5. **같은 주문이 C에 도착했음을 검증한다.** `COMPLETED`에서 `after`를 측정하고 이동 전후 주문의 모든 필드와 건수를 비교한다. 독립 검증 도구가 메타 DB와 A·B·C의 실제 테이블을 직접 읽는다.

   ```powershell
   $afterOrder = Invoke-RestMethod $detailUrl -ErrorAction Stop
   $fields = 'orderId','userId','bucketId','amount','status','createdAt','updatedAt'
   if (Compare-Object $beforeOrder $afterOrder -Property $fields) { throw 'Order changed during migration' }
   if ((Invoke-RestMethod "$api/orders/count").totalCount -ne $beforeCount) { throw 'Order count changed' }

   $pgBin = 'C:\Program Files\PostgreSQL\18\bin'
   if (Test-Path (Join-Path $pgBin 'psql.exe')) { $env:Path = "$pgBin;$env:Path" }
   foreach ($order in @($demoOrder, $peerOrder)) {
       python -B scripts/shards_experiment.py verify-order --user $order.userId --bucket $order.bucketId --owner shardC --order-id $order.orderId
       if ($LASTEXITCODE -ne 0) { throw 'Physical order check failed' }
   }
   python -B scripts/shards_experiment.py verify-order --user $controlOrder.userId --bucket $controlOrder.bucketId --owner shardB --order-id $controlOrder.orderId
   ```

   사용자 3과 10의 두 결과 모두 `verified / shardC`이고, 사용자 101의 결과는 `verified / shardB`다. 버킷 번호와 주문 UUID는 그대로이고 버킷 05의 담당 DB가 바뀌었다. 이후 같은 환경에서 A → C를 다시 시연하려면 먼저 화면에서 05의 현재 소유자를 확인한다. 이미 C라면 정상 재배치 절차로 05를 C → A로 돌려보낸 다음 새 작업을 등록할 수 있다. 각 전환 때 버전이 1씩 오른다.

## 브라우저에서 주문 생성과 SQL 조회

**Create order**에 사용자 ID와 금액을 입력하고 주문을 생성한다. 사용자 3과 10이면 버킷 `05`, 사용자 101이면 `10`이 응답에 표시된다. 저장할 샤드는 Spring이 현재 버킷 매핑을 조회해 선택한다.

1. 읽기 토큰으로 **Load topology**를 누르고 **SQL terminal**에서 **Metadata**를 선택한다. 버킷 매핑을 먼저 확인한다.

   ```sql
   SELECT bucket_id, shard_id, state, version
   FROM bucket_mapping
   WHERE bucket_id = 5;
   ```

2. A나 C로 DB를 바꾸고 다음 쿼리를 각각 실행한다. 정리 완료 전후의 결과를 비교할 수 있다.

   ```sql
   SELECT order_id, user_id, bucket_id, amount, status
   FROM orders
   WHERE bucket_id = 5
   ORDER BY created_at DESC
   LIMIT 20;
   ```

3. 버킷 05에 몇 건이 실제로 있는지 보려면 각 샤드에서 `SELECT COUNT(*) AS bucket_orders FROM orders WHERE bucket_id = 5;`를 실행한다. 복사 직후에는 요청을 담당하는 샤드와 별개로 두 DB에 행이 보일 수 있다.

메타 DB와 실제 샤드에서는 한 번에 한 개의 조회문을 실행한다. 한 테이블의 열 목록, `*`, `COUNT(*)`, `WHERE` 비교 조건 및 `AND`, `ORDER BY`, `LIMIT 1~100`을 지원한다. 실제 주문은 **Create order**에서 추가해 현재 버킷 소유자에게 보내도록 한다.

### SQL Lab에서 PostgreSQL 스크립트 실행

프로젝트 루트의 PowerShell에서 아래 명령을 한 번 실행한다. 사용 중인 Spring Boot가 있으면 종료 후 다시 시작한다.

```powershell
.\scripts\Start-SqlLab.ps1
.\gradlew.bat bootRun
```

**SQL terminal**의 DB 메뉴에서 **SQL Lab**을 선택하고 쓰기 토큰을 입력한다. 초기 화면에 쓰기 토큰으로 로그인했다면 해당 토큰을 그대로 사용한다. **Load example**을 누르면 아래와 같은 다중 구문 예제가 준비된다.

```sql
CREATE TABLE IF NOT EXISTS demo_orders (id integer PRIMARY KEY, amount numeric);
INSERT INTO demo_orders VALUES (1, 15000) ON CONFLICT (id) DO NOTHING;
SELECT count(*) AS orders, sum(amount) AS total FROM demo_orders;
```

SQL Lab은 별도 PostgreSQL 컨테이너에서 낮은 권한으로 실행된다. `JOIN`, `WITH`, 집계, `CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`와 여러 문장을 PostgreSQL이 직접 처리한다. 관리자 권한이 필요한 명령은 PostgreSQL이 거절한다. 터미널은 SQL을 입력받으며 `psql` 전용 백슬래시 명령은 사용할 수 없다. 연결은 실행마다 새로 열리므로 `BEGIN`부터 `COMMIT`까지 한 번에 제출한다. 테이블과 커밋한 데이터는 Lab 컨테이너를 중단하기 전까지 유지된다.

**Enter**는 실행, **Shift+Enter**는 줄바꿈, **↑/↓**는 현재 DB의 입력 이력이다. 결과 표는 최대 100행씩 표시하며, 최대 8개 결과를 보여준다.

## 백업과 장애 대응

6. **정상 백업과 복원을 시연한다.** 모든 DB가 실행 중이고 미완료 작업이 없는 시점에 메타·A·B·C를 한 세트로 백업한다. 외장하드 역할은 로컬 폴더가 맡는다. 복원 명령은 각 서버에 고유 이름의 새 DB를 생성하므로 해당 계정에 `CREATEDB` 권한이 필요하다. 매핑·건수·주문 내용의 해시를 대조한 결과가 `verified-restore_*.json`에 기록된다.

   ```powershell
   # 터미널 3에서도 경로를 설정합니다. PowerShell 세션마다 변수는 따로 유지됩니다.
   $labRoot = Join-Path $env:LOCALAPPDATA 'shards-lab'
   $setPath = python -B scripts/shards_ops.py backup --root (Join-Path $labRoot 'backups')
   if ($LASTEXITCODE -ne 0) { throw 'Backup failed' }
   python -B scripts/shards_ops.py restore --set $setPath
   if ($LASTEXITCODE -ne 0) { throw 'Restore verification failed' }
   ```

7. **C 중단의 범위와 복구를 보여준다.** 아래 명령은 로컬 시연용 C를 잠시 중단한다. C가 소유한 사용자 3의 주문과 전체 집계는 `503`, B의 사용자 101 목록은 `200`이 예상된다. `incidents`에는 앱 로그·메타 상태·접근 가능한 DB의 덤프와 수집 실패 항목이 기록된다. C를 다시 시작한 뒤 같은 UUID가 조회되는지 확인한다.

   ```powershell
   try {
       docker compose -f compose.shard-c.yaml stop shard-c
       if ($LASTEXITCODE -ne 0) { throw 'Could not stop the demo shard' }
       curl.exe -s -o NUL -w "C order: %{http_code}\n" $detailUrl
       curl.exe -s -o NUL -w "B list: %{http_code}\n" "$api/orders?userId=101"
       curl.exe -s -o NUL -w "All orders: %{http_code}\n" "$api/orders/count"
       python -B scripts/shards_ops.py incident --root (Join-Path $labRoot 'incidents') --app-log (Join-Path $labRoot 'application.log')
   } finally {
       docker compose -f compose.shard-c.yaml up -d --wait shard-c
   }
   Invoke-RestMethod $detailUrl -ErrorAction Stop
   ```

별도 PC 실험에서는 메타 DB와 Spring, React, A·B를 내 PC에 두고 원격 장비에 C를 둔다. C가 소유권을 받기 전의 실습 매핑에서 시작해 동일한 순서로 측정한다. [원격 연결과 복구 절차](backend-operations.md#별도-pc의-c)

## 로컬 검증 결과

2026년 11월 26일, 별도 로컬 실습 DB에서 버킷 03과 05를 차례로 A에서 C로 옮겼다. 사용자 개발 DB의 작업 7과 독립된 실험이다.

| 검증 | 관찰 결과 |
| --- | --- |
| 재배치 화면 | 작업을 등록·취소·재등록하고, 페이지를 다시 열어 같은 미완료 작업을 이어갔다. 권한 오류 `401`·`403`, 중복 등록 `409`, 잠금 충돌 `503`도 확인했다. |
| 데이터 정확성 | 버킷 `03`은 C 소유 `NORMAL`, 버전 `2`가 됐다. A·B의 해당 버킷 주문은 각 0건, C는 1건이었다. 이동 전후 UUID와 주문 응답이 같았다. |
| 두 배치와 중복 집계 | 버킷 `05`의 101건을 100건과 1건으로 나눠 복사했다. 복사 직후 A·C에는 각각 101건이 있었고 완료 뒤 A에는 0건, C에는 101건이 남았다. 전체 주문 수는 세 구간 모두 103건이었다. |
| C 중단 및 재연결 | C 소유 주문과 전체 집계는 `503`을 반환했다. B 소유 사용자의 목록은 `200`을 유지했고 C 재시작 뒤 기존 주문을 다시 조회했다. |
| 앱 재시작과 메타 장애 | C가 꺼진 상태에서도 Spring을 다시 실행해 B 주문을 읽었다. 메타 DB를 중단하면 주문 요청은 첫 실패부터 `503`이었고, 재연결하면 같은 주문이 조회됐다. |
| 장애 자료 | C를 중단한 동안 별도 장애 폴더에 앱 로그, C 종료 이벤트, 메타 상태와 메타·A·B 덤프를 남겼다. C 덤프는 접속 불가로 기록됐다. |
| 백업·복원 | 외장하드 대체 폴더에 덤프 네 개를 생성했다. 새 DB 네 개로 복원한 뒤 매핑, 건수, 주문 내용의 해시 비교를 통과했다. |

두 배치 전후의 조회 성능은 동일 주문으로 구간마다 1,000건씩 측정했다. 별도 PC의 C 연결·장애·성능 실험 결과는 해당 장비에서 실행한 뒤 기록한다. [측정 수치와 실험 조건](performance.md)
