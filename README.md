# 데이터 재배치를 지원하는 소형 DB 샤딩 시스템

사용자 ID를 해시해 주문을 16개 버킷으로 나누고, 서비스 실행 중에 버킷 단위로 주문을 다른 PostgreSQL 샤드로 옮기는 데이터베이스 캡스톤 프로젝트다. 처음에는 A가 버킷 0~7, B가 8~15를 담당하고 C는 비어 있다. 주문 번호와 버킷 번호를 유지한 채 담당 샤드를 변경할 수 있다.

발표에서는 사용자 3과 10의 주문이 같은 버킷으로 묶여 A에서 C로 이동하는 과정을 보여준다. [시연 순서와 명령](docs/demo.md) · [로컬 성능 측정](docs/performance.md) · [백엔드 운영 가이드](docs/backend-operations.md) · [초기 기획서](docs/project-plan.md)

## 개발 환경

- Java 21
- Spring Boot 4.1.1
- Gradle 9.8.1 Wrapper
- PostgreSQL 18과 Docker Compose
- React, TypeScript, Vite와 Node.js 22.12 이상
- Python 3.11 이상과 PostgreSQL 18 클라이언트 도구로 백업·복원·실험 실행

## 주요 코드

| 파일 | 역할 |
| --- | --- |
| [order](src/main/java/com/example/shards/order), [sharding](src/main/java/com/example/shards/sharding) | 주문 API, 사용자 ID 해시, 버킷의 현재 샤드로 연결 |
| [meta](src/main/java/com/example/shards/meta), [migration](src/main/java/com/example/shards/migration) | 메타 DB 매핑, 재배치 작업과 단계별 실행 |
| `db/` | PostgreSQL 스키마와 초기 버킷 매핑 |
| `frontend/` | 샤드 현황판과 버킷 재배치 화면 |
| `scripts/` | 백업·복원, 장애 수집, 배치 검증과 요청 측정 |

일반 주문 요청은 `사용자 ID → 해시로 버킷 번호 계산 → 메타 DB에서 소유자 조회 → 담당 샤드의 orders 테이블` 순서로 처리한다. 같은 버킷 안에서도 사용자별 목록은 `user_id`로 구분한다. 메타 DB는 모든 경로가 공유하는 단일 지점이어서 메타 DB가 중단되면 각 샤드가 실행 중이어도 주문 라우팅이 멈춘다.

## 실행과 확인

프로젝트 루트에서 Windows PowerShell을 연다. 먼저 같은 위치에 `.env`를 만들고 아래 네 DB의 이름·사용자·비밀번호를 설정한다. 예를 들어 메타 DB에는 `META_DB_NAME`, `META_DB_USER`, `META_DB_PASSWORD`가 필요하다. DB 계정과 별도로 `APP_ADMIN_READ_TOKEN`과 `APP_ADMIN_WRITE_TOKEN`에 서로 다른 임의 값을 지정한다.

| DB | 환경 변수 접두사 | 권장 DB 이름 | pgAdmin 접속 주소 (기본값) |
| --- | --- | --- | --- |
| 메타 | `META_DB_` | `shards_meta` | `127.0.0.1:15432` |
| 샤드 A | `SHARD_A_DB_` | `shards_a` | `127.0.0.1:15433` |
| 샤드 B | `SHARD_B_DB_` | `shards_b` | `127.0.0.1:15434` |
| 샤드 C | `SHARD_C_DB_` | `shards_c` | `127.0.0.1:15435` |

```powershell
docker compose up -d --wait
docker compose -f compose.shard-c.yaml up -d --wait
```

새 볼륨은 첫 기동 시 스키마와 초기 매핑을 생성한다. 최초 시연에서는 네 DB 기동 후 메타 DB에서 A·B·C를 한 번 활성화하고, 아래 명령으로 Spring을 실행한다. 기존 볼륨에서는 저장된 매핑으로 시작한다. [샤드 활성화 SQL과 앱 로그 설정](docs/demo.md#첫-실행)

```powershell
.\gradlew.bat bootRun
```

다른 터미널에서 확인한다.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
```

응답 `{"status":"UP"}`는 Spring HTTP 서버가 요청을 받는다는 뜻이다. 샤드 가용성은 해당 샤드가 소유한 주문 조회로 확인한다. 빌드와 통합 테스트는 작업이 끝난 개발용 DB에서 실행한다.

```powershell
$env:RUN_SHARD_C_TEST = 'true'
.\gradlew.bat build --rerun-tasks
python -B -m unittest discover -s scripts -p test_shards_ops.py
```

Gradle은 현재 매핑으로 테스트를 다시 실행하고 JAR를 만든다. 보고서는 `build/reports/tests/test/index.html`에 생성된다.

## IntelliJ 실행

1. 프로젝트를 열고 `build.gradle`을 Gradle 프로젝트로 연결한다.
2. Project SDK와 Gradle JVM을 JDK 21로 설정한다.
3. Gradle 배포는 Wrapper를 사용하고 동기화한다.
4. 작업 디렉터리를 프로젝트 루트로 두고 `ShardsProApplication.main()`을 실행한다. 활성 프로필을 비워 두면 기본 프로필 `shard-c`가 적용되어 A·B·C 연결 풀이 등록된다. 다른 프로필을 명시해서 실행할 때도 C를 사용하려면 활성 프로필 목록에 `shard-c`를 포함한다.

8080이 사용 중이면 `.\gradlew.bat bootRun --args="--server.port=8081"`로 실행할 수 있다.
이 경우 확인 URL도 8081로 바꾼다.

## 샤드 현황 화면

Spring Boot가 실행 중인 상태에서 새 PowerShell을 열고 프로젝트 루트에서 아래 명령을 실행한다. C가 버킷을 소유한 실습 환경에서는 C 컨테이너도 실행한다. Node.js 22.12 이상 또는 Vite가 지원하는 Node.js 20.19 이상이 필요하다.

```powershell
cd frontend
npm ci
npm run dev
```

브라우저의 `http://127.0.0.1:5173`에서 Spring Boot에 설정한 `APP_ADMIN_READ_TOKEN` 값을 입력하고 **Load topology**를 누른다. 조회 토큰을 새로 추가했다면 Spring Boot를 재시작한다. Vite는 `/api/topology`를 로컬 Spring Boot의 `GET /admin/migrations/topology`로 전달한다. Spring Boot 포트가 8081이면 프론트엔드 실행 전에 `$env:SHARDS_BACKEND_ORIGIN = 'http://127.0.0.1:8081'`을 설정한다.

화면의 `ACTIVE`·`INACTIVE`는 메타 DB에 등록된 상태다. C가 `ACTIVE`인데 C 소유 주문만 `503`이면 C 컨테이너 상태와 Spring 시작 로그에 기본 또는 활성 프로필로 `shard-c`가 표시되는지 확인한다. `frontend`에서 `npm run build`를 실행하면 타입 검사와 배포용 빌드를 수행한다.

**Bucket migration**에서 버킷과 도착 샤드를 고르고, `APP_ADMIN_WRITE_TOKEN`으로 작업을 등록한다. 등록 후 **Advance one step**으로 한 단계 또는 배치씩 진행한다. **Cancel pending job**은 복사를 시작하기 전에 사용할 수 있다. 화면을 다시 열면 조회 토큰으로 미완료 작업을 찾아 이어간다. 전체 확인 순서는 [브라우저 재배치](docs/demo.md#브라우저-재배치)를 참고한다.

**Create order**에서 사용자 ID와 금액을 입력하면 서버가 해시와 현재 매핑을 바탕으로 저장할 샤드를 선택한다. 응답에 표시된 주문 UUID로 **SQL terminal**에서 실제 행을 찾을 수 있다. 터미널은 조회 토큰으로 열고 메타·A·B·C 중 조회할 DB를 골라 사용한다. [브라우저에서 주문 생성 및 SQL 조회](docs/demo.md#브라우저에서-주문-생성과-sql-조회)

`JOIN`, `WITH`, DDL·DML과 다중 SQL을 연습하려면 `.\scripts\Start-SqlLab.ps1`을 실행하고 Spring을 재시작한다. 터미널의 **SQL Lab**을 선택한 뒤 쓰기 토큰으로 실행한다. 실습 DB는 실제 주문 DB와 분리되어 있고 컨테이너를 중단하면 초기화된다. [SQL Lab 실행과 예제](docs/demo.md#sql-lab에서-postgresql-스크립트-실행)

실행 시 사용하는 설정 원본은 `src/main/resources/`에 있다. 로컬 자격 증명이 있는 `.env`·`.local/`과 IDE·빌드 생성물은 Git에서 제외한다.

## 시연에서 확인할 수 있는 결과

- **재배치** · `PENDING → COPYING → VERIFYING → SWITCHING → CLEANING → COMPLETED` 순으로 진행한다. 복사 중에는 출발 샤드가 읽기를 담당하고 해당 버킷의 쓰기를 일시 제한한다. 검증 후 소유자가 바뀌면 도착 샤드가 읽기·쓰기를 맡는다. 전체 주문 수는 각 버킷의 현재 소유자에서만 집계한다. [단계별 시연과 물리 위치 확인](docs/demo.md#브라우저-재배치)
- **백업·장애 대응** · 로컬 폴더를 외장하드 대용으로 사용한다. 정상 시점의 네 DB를 한 세트로 백업하고 별도 DB에 복원해 매핑·건수·주문 내용을 대조한다. 장애 시에는 앱 로그와 접근 가능한 DB의 덤프를 별도로 수집한다. [백업·복원과 장애 시연](docs/demo.md#백업과-장애-대응)
- **성능** · 한 PC의 PostgreSQL 컨테이너 네 개로 같은 주문 목록을 구간별 1,000회 조회했다. 전체 p95는 이동 전 29.64 ms, 복사 직후 29.43 ms, 완료 후 27.57 ms였다. 별도 장비로 C를 분리했을 때의 네트워크 비용과 성능은 추가 측정 대상이다. [측정 조건과 결과](docs/performance.md)
