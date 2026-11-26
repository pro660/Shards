# 백엔드 실습 가이드

41~64회차의 코드, SQL, 실행 절차를 정리한다. 명령은 프로젝트 루트의 Windows PowerShell에서 실행한다. 이 문서의 첫 재배치 예시는 초기 A·B 매핑과 사용자 1로 시작한다. 작업 7을 마친 개발 DB에서 발표할 때는 사용자 3·10과 버킷 05를 사용한다. [현재 시연 순서](demo.md)

## 41~50회차 재배치

| 회차 | 코드 | 동작 |
|---|---|---|
| 41 | [OperationLocks](../src/main/java/com/example/shards/meta/OperationLocks.java), [OrderService](../src/main/java/com/example/shards/order/OrderService.java) | 주문 쓰기는 메타 DB의 버킷별 공유 잠금을 얻고, 샤드 SQL이 끝날 때까지 유지한다. |
| 42 | [ShardRouter](../src/main/java/com/example/shards/sharding/ShardRouter.java), [OrderQueryService](../src/main/java/com/example/shards/order/OrderQueryService.java) | 읽기에 별도 잠금을 사용한다. 전체 조회는 16개 버킷 모두에 읽기 잠금을 얻는다. |
| 43 | [MigrationRunner](../src/main/java/com/example/shards/migration/MigrationRunner.java) | 첫 실행에서 진행 중인 쓰기가 모두 끝난 버킷을 `MIGRATING`으로 바꾸고 작업을 `COPYING`으로 전환한다. |
| 44 | [BucketOrderRepository](../src/main/java/com/example/shards/order/BucketOrderRepository.java), [배치 인덱스](../db/009-index-orders-by-bucket.sql) | 출발 샤드에서 마지막 UUID 이후 최대 100건을 가져온다. 복사 순서는 PostgreSQL의 UUID 정렬을 기준으로 한다. |
| 45 | [BucketOrderRepository](../src/main/java/com/example/shards/order/BucketOrderRepository.java) | 목적지의 같은 UUID는 주문 필드 전부가 일치할 때 허용한다. 내용이 다르면 그 배치의 목적지 트랜잭션을 롤백한다. |
| 46 | [MigrationJobRepository](../src/main/java/com/example/shards/meta/MigrationJobRepository.java) | 목적지 커밋 후 마지막 UUID와 복사 건수를 메타 DB에 기록한다. 체크포인트 전 재시작하면 같은 배치가 다시 비교된다. |
| 47 | [MigrationRunner](../src/main/java/com/example/shards/migration/MigrationRunner.java) | 출발·목적지 양쪽을 끝까지 페이지로 비교해 누락, 추가 행, 필드 불일치를 검사한다. |
| 48 | [BucketMappingRepository](../src/main/java/com/example/shards/meta/BucketMappingRepository.java) | 컷오버 직전에 다시 비교한다. 활성 읽기가 끝나면 소유자, 버전, 상태와 작업 단계를 하나의 메타 DB 트랜잭션으로 갱신한다. |
| 49 | [MigrationRunner](../src/main/java/com/example/shards/migration/MigrationRunner.java) | 목적지 접근과 전환된 소유권을 확인한 뒤 출발지의 해당 버킷만 배치로 정리한다. 남은 행이 0건이면 `COMPLETED`로 기록한다. |
| 50 | [MigrationController](../src/main/java/com/example/shards/migration/MigrationController.java), [실행기 테스트](../src/test/java/com/example/shards/migration/MigrationRunnerTest.java) | 작업 ID로 현재 상태를 확인하고 한 단계씩 진행한다. 요청이나 앱이 중단되면 같은 ID의 DB 기록으로 이어간다. |

잠금은 메타 DB 트랜잭션의 생명주기를 따른다. 잠금을 바로 얻을 수 없는 요청은 `503`을 받고 다시 시도할 수 있다. 복사 중에는 버킷 쓰기를 제한하고 읽기는 출발 샤드로 보내며, 소유권 전환 순간에는 그 버킷 읽기도 잠시 제한한다. 다른 버킷의 개별 주문 요청은 계속 가능하다. 잠금에 참여하는 모든 애플리케이션 인스턴스는 같은 메타 DB를 사용해야 한다. 실습 중 직접 SQL 변경은 멈춘다. [PostgreSQL advisory lock 문서](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS)

### 로컬에서 한 버킷 이동

1. 새 볼륨은 Compose가 메타 DB에 `001~004`·`007`, A·B·C에 `005`·`009`를 적용한다. 아래 인덱스 명령은 기존 개발용 볼륨에서도 반복 실행할 수 있다. [현재 환경의 시연 순서](demo.md)

   ```powershell
   docker compose up -d --wait meta-db shard-a shard-b
   docker compose -f compose.shard-c.yaml up -d --wait
   foreach ($service in @('shard-a', 'shard-b', 'shard-c')) {
       $composeOptions = @()
       if ($service -eq 'shard-c') { $composeOptions = @('-f', 'compose.shard-c.yaml') }
       $dbUser = docker compose @composeOptions exec -T $service printenv POSTGRES_USER
       $dbName = docker compose @composeOptions exec -T $service printenv POSTGRES_DB
       Get-Content -Raw -Encoding UTF8 db/009-index-orders-by-bucket.sql |
           docker compose @composeOptions exec -T $service psql -X -U $dbUser -d $dbName -v ON_ERROR_STOP=1
       if ($LASTEXITCODE -ne 0) { throw "Index setup failed for $service" }
   }
   ```

2. C에 접속 가능함을 37회차 테스트로 확인한 뒤 메타 DB에서 실습용 A·B·C를 활성화한다.

   ```powershell
   $metaUser = docker compose exec -T meta-db printenv POSTGRES_USER
   $metaName = docker compose exec -T meta-db printenv POSTGRES_DB
   docker compose exec -T meta-db psql -X -U $metaUser -d $metaName -v ON_ERROR_STOP=1 -c "UPDATE public.shards SET status = 'ACTIVE' WHERE connection_key IN ('shardA', 'shardB', 'shardC');"
   ```

3. 로컬 `.env`의 `APP_ADMIN_READ_TOKEN`, `APP_ADMIN_WRITE_TOKEN`에 서로 다른 난수 토큰을 넣는다. 이 토큰은 프로세스 환경 변수로도 공급할 수 있다. 애플리케이션은 기본적으로 루프백에서 요청을 받는다. 토큰은 Git에 기록하지 않는다.

   ```powershell
   $labRoot = Join-Path $env:LOCALAPPDATA 'shards-lab'
   New-Item -ItemType Directory -Path $labRoot -Force | Out-Null
   $env:LOGGING_FILE_NAME = Join-Path $labRoot 'application.log'
   .\gradlew.bat bootRun --args="--spring.profiles.active=shard-c"
   ```

4. 별도 PowerShell에서 쓰기 토큰을 입력해 주문 한 건과 재배치 작업을 만든다. 초기 매핑에서 사용자 1은 A가 소유한 버킷 3에 속하고, 샤드 C의 번호는 3이다.

   ```powershell
   $api = 'http://127.0.0.1:8080'
   $secureToken = Read-Host '관리자 쓰기 토큰' -AsSecureString
   $token = [System.Net.NetworkCredential]::new('', $secureToken).Password
   $headers = @{ Authorization = "Bearer $token" }
   $testOrder = Invoke-RestMethod "$api/orders" -Method Post -ContentType 'application/json' -Body '{"userId":1,"amount":15000}'
   $job = Invoke-RestMethod "$api/admin/migrations" -Headers $headers -Method Post -ContentType 'application/json' -Body '{"bucketId":3,"targetShardId":3}'
   $job | Select-Object jobId, bucketId, sourceShardId, targetShardId, phase
   ```

5. 아래 명령을 호출할 때마다 상태 또는 복사·삭제 배치 한 개가 진행된다. `COPYING` 중에는 같은 주문을 읽을 수 있어야 한다. 이 시점에 새 주문을 쓰면 `503`이 예상된다. 앱을 재시작했다면 `GET /admin/migrations/unfinished`에서 작업 번호를 찾아 동일한 호출을 계속한다.

   ```powershell
   $jobUrl = "$api/admin/migrations/$($job.jobId)"
   Invoke-RestMethod "$jobUrl/advance" -Method Post -Headers $headers |
       Select-Object phase, lastCopiedOrderId, copiedOrderCount, finishedAt
   Invoke-RestMethod "$api/orders/$($testOrder.orderId)?userId=1"
   ```

6. `COMPLETED` 후 상세 조회 응답의 주문 UUID와 내용이 유지되는지 확인한다. 아래 실측 도구의 `verify-order`로 메타 DB와 실제 A·B·C를 직접 대조한다.

재배치 단계는 `PENDING → COPYING → VERIFYING → SWITCHING → CLEANING → COMPLETED` 순서다. 한 번의 검증 호출은 전체 버킷을 읽으므로 주문량이 많으면 오래 걸릴 수 있다. 소유권 전환 뒤 C에 정상 쓰기가 들어오므로, 정리 전에 목적지와 예전 원본을 다시 전체 비교하는 단계는 두지 않는다. 복사가 시작된 작업은 현재 단계에서 원인 해결 후 이어간다. PENDING 작업은 관리 API로 취소할 수 있다.

## 51~60회차 권한, 백업, 복원, 장애 수집

| 회차 | 코드 | 동작 |
|---|---|---|
| 51 | [AdminAccess](../src/main/java/com/example/shards/admin/AdminAccess.java) | 조회용·쓰기용 Bearer 토큰을 분리한다. 조회 토큰으로 변경 요청을 보내면 `403`, 누락·불일치 토큰은 `401`이다. |
| 52 | [MigrationController](../src/main/java/com/example/shards/migration/MigrationController.java) | 샤드·버킷 구성 조회와 복사 전 작업 취소를 제공한다. |
| 53 | [BackupLock](../scripts/pg_ops.py) | 메타 DB의 전역 변경 잠금을 보유하며, 변경 중이거나 미완료 작업이 있으면 백업을 중단한다. |
| 54 | [backup](../scripts/shards_backup.py) | `pg_dump -Fc`로 메타·A·B·C의 네 덤프를 만든다. |
| 55 | [backup](../scripts/shards_backup.py) | 체크섬·파일 크기·매핑·주문 검증 기준을 manifest에 저장한 뒤 완료 폴더를 게시한다. |
| 56 | [restore](../scripts/shards_backup.py) | 파일 해시와 크기를 먼저 확인하고 고유한 이름의 새 DB 네 개에 복원한다. |
| 57 | [audit](../scripts/shards_backup.py) | 복원한 주문의 사용자 해시, 버킷 소유자, 건수와 내용 SHA-256을 백업 기준과 비교한다. |
| 58 | [capture](../scripts/shards_incident.py) | 사고마다 별도 폴더에 안전하게 추린 로그, 작업 상태, 접근 가능한 DB 덤프를 남긴다. |
| 59 | [운영 도구 테스트](../scripts/test_shards_ops.py) | 훼손된 덤프, 미완료 세트, 진단 로그 민감값 제외, 백분위 계산을 검사한다. |
| 60 | [PostgreSQL 클라이언트 설정](../scripts/pg_ops.py), [C 실행 설정](../src/main/resources/application-shard-c.properties) | 로컬과 원격 C 실습에 같은 도구를 쓰며, SSH 터널을 앱 및 운영 도구에 연결할 수 있다. |

### 관리 API

| 호출 | 토큰 | 정상 결과 |
|---|---|---|
| `POST /admin/migrations` | 쓰기 | `201`, `PENDING` 작업 |
| `GET /admin/migrations/{jobId}` | 조회 또는 쓰기 | `200`, 해당 작업 |
| `GET /admin/migrations/unfinished` | 조회 또는 쓰기 | `200` 또는 `204` |
| `GET /admin/migrations/topology` | 조회 또는 쓰기 | `200`, 샤드 목록과 버킷 매핑 |
| `POST /admin/migrations/{jobId}/advance` | 쓰기 | `200`, 진행 후 작업 |
| `POST /admin/migrations/{jobId}/cancel` | 쓰기 | `200`, 취소된 작업 |

### 백업·복원 확인

Python 3.11 이상과 PostgreSQL 18 클라이언트의 `psql`, `pg_dump`, `pg_restore`, `createdb`가 PATH에 있어야 한다. 운영 도구는 프로젝트의 `.env`와 현재 프로세스 환경 변수를 읽으며, 같은 이름의 값은 프로세스 환경 변수가 우선한다. `.env`에는 단순한 `KEY=value` 구문을 사용한다. 경로는 OneDrive 작업 폴더 바깥을 사용한다. 복원은 직접 만든 신뢰 가능한 세트로 실행한다. [PostgreSQL pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html) · [PostgreSQL pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html)

```powershell
Get-Command python, psql, pg_dump, pg_restore, createdb
$labRoot = Join-Path $env:LOCALAPPDATA 'shards-lab'
$backupRoot = Join-Path $labRoot 'backups'
$setPath = python -B scripts/shards_ops.py backup --root $backupRoot
if ($LASTEXITCODE -ne 0) { throw 'Backup failed' }
python -B scripts/shards_ops.py restore --set $setPath
```

완료 세트에는 덤프 네 개와 `manifest.json`이 있고, 복원 검증 후에는 `verified-restore_*.json`이 백업 루트에 나타난다. 복원은 고유한 `restore_...` DB 이름을 생성한다. 기본 연결 계정에 `CREATEDB` 권한이 필요하다. 별도 테스트용 PostgreSQL 서버·볼륨을 사용하려면 `RESTORE_META_DB_HOST`, `RESTORE_SHARD_C_DB_PORT`와 같은 `RESTORE_` 접두 설정으로 각 복원 대상의 호스트·포트·계정을 지정한다. 복원 DB로 앱을 실행할 때는 별도 PowerShell에서 `META_DB`, `SHARD_A_DB`, `SHARD_B_DB`, `SHARD_C_DB` 각각의 `_NAME`에 복원 보고서의 이름을, `_HOST`·`_PORT`에는 해당 주소를 설정한다. 역할과 권한은 서버에서 미리 준비한다.

`backup`이 중단되면 해당 세트는 `.incomplete-` 폴더에 남는다. 동시 주문 쓰기는 백업 잠금을 얻는 동안 `503`을 받는다. 직접 SQL 쓰기나 스키마 변경도 백업 구간 동안 중지해야 세트 전체가 같은 상태를 표현한다.

### 장애 자료 확인

```powershell
python -B scripts/shards_ops.py incident --root (Join-Path $labRoot 'incidents') --app-log (Join-Path $labRoot 'application.log')
```

`incident.json`의 `results`는 자료별 수집 결과를 기록한다. DB를 열 수 없으면 해당 덤프가 실패해도 나머지 DB 수집을 계속한다. 파일 로그는 재배치 및 알려진 샤드 연결 이벤트만 남기며, 로컬 Docker DB 로그는 상태 이벤트 집계만 저장한다. 덤프를 제외한 확인은 `--logs-only`, SSH로 연결한 C의 Docker 로그를 원격 호스트에서 따로 확인할 때는 `--remote-shard-c`를 붙인다. 사고 덤프에는 주문 데이터가 포함되므로 접근을 사용자 본인과 허가된 팀원으로 제한한다.

## 61~64회차 배치 검증과 두 장비 실험

| 회차 | 내용 |
|---|---|
| 61 | 메타 DB, A, B, C의 조회 결과를 대조하여 주문의 물리 위치를 검증한다. |
| 62 | 원격 C의 중단, 앱의 재시작, 동일 작업 ID의 재개를 같은 절차로 확인한다. |
| 63 | 같은 사용자·요청 수·시드·동시성으로 총 응답 수, HTTP 상태, 실패율, 처리량, p50·p95를 기록한다. |
| 64 | 장비 조건과 실행 시점, 재배치 상태, 백업 복원 보고서를 함께 보관하고 프론트엔드에 사용할 API 계약을 확인한다. |

### 별도 PC의 C

허가된 실험 장비에서 `compose.shard-c.yaml`, `db/005-create-orders.sql`, `db/009-index-orders-by-bucket.sql`을 같은 상대 경로로 준비하고 C 전용 `.env`로 실행한다. 그 장비에서 PostgreSQL 포트는 루프백에 바인딩된다. 별도 장비에서 C 소유 버킷이 0개인 초기 테스트 매핑으로 시작한다.

호스트 키를 확인할 수 있고 접속이 허가된 OpenSSH 서버를 사용한다. 앱이 실행되는 PC에서 별도 PowerShell을 열어 터널을 유지한다.

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:25435:127.0.0.1:15435 user@shard-c-host
```

앱과 운영 도구를 실행할 PowerShell에서 `SHARD_C_DB_HOST=127.0.0.1`, `SHARD_C_DB_PORT=25435`를 설정하고, C의 DB 이름·사용자·비밀번호를 원격 장비와 맞춘다. 로컬 터널과 실제 DB 인증을 모두 검증하려면 37회차의 `ShardCConnectionIntegrationTest`를 실행한다. [Windows OpenSSH](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse) · [OpenSSH 포트 포워딩](https://man.openbsd.org/ssh#L)

### 정확성과 성능을 기록하는 명령

```powershell
# 이 문서의 사용자 1·버킷 03 예시를 완료한 직후 실행합니다.
python -B scripts/shards_experiment.py verify-order --user 1 --bucket 3 --owner shardC --order-id $testOrder.orderId
```

주문 데이터를 먼저 준비하고 같은 목록으로 이동 전·복사 중·완료 후를 측정한다. `by_user`에는 사용자별 성공·오류와 지연 시간이 기록된다. 현재 시연은 버킷 05의 사용자 3과 버킷 10의 사용자 101을 사용한다. 각 구간의 실행 횟수, 시드, 결과 해석은 [성능 측정 문서](performance.md)를 따른다. 장애 실험은 C를 다시 연결한 뒤 같은 주문 번호로 조회하고, 미완료 작업이 있다면 같은 작업 번호로 재개한다.

### 회귀 테스트

```powershell
$env:SPRING_PROFILES_ACTIVE = 'shard-c'
$env:RUN_SHARD_C_TEST = 'true'
.\gradlew.bat test --rerun
python -B -m unittest discover -s scripts -p test_shards_ops.py
```

초기 배치와 버킷 03·05를 C로 이동한 배치에서 Java 테스트 64개씩을 통과했다. 로컬 재배치·장애·복원 결과는 시연 기록에, 같은 주문으로 측정한 지연 시간과 처리량은 성능 문서에 있다. 별도 장비의 네트워크와 성능은 해당 장비에서 추가 측정한다. [로컬 검증 결과](demo.md#로컬-검증-결과) · [성능 측정 결과](performance.md)
