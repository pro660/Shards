# 데이터 재배치를 지원하는 소형 DB 샤딩 시스템

Spring Boot와 PostgreSQL로 주문을 버킷 단위로 분산하고, 메타 DB에 저장한 재배치 작업으로 소유권을 전환한다. 실행 환경은 메타 DB, 샤드 A·B, 선택적 샤드 C로 구성한다.

전체 설계는 [프로젝트 기획서](docs/project-plan.md)에 정리했다. 재배치, 백업·복원, 장애 수집, 별도 PC의 샤드 C 실험은 [백엔드 실습 가이드](docs/backend-operations.md)를 따른다.

## 개발 환경

- Java 21
- Spring Boot 4.1.1
- Gradle 9.8.1 Wrapper

## 기본 코드

| 파일 | 역할 |
| --- | --- |
| `ShardsProApplication.java` | Spring Boot 서버의 실행 진입점 |
| `health/HealthController.java` | `/health` 요청에 `{"status":"UP"}` 응답 |
| `application.properties` | 애플리케이션 이름과 8080 포트 설정 |
| `ShardsProApplicationTests.java` | 실제 HTTP 서버를 띄워 헬스 체크 검증 |
| `build.gradle` | Java 버전, 웹·테스트 의존성, 빌드 설정 |

## 실행과 확인

메타 DB에 `db/001`부터 `db/004`를, A·B에는 `db/005`를 처음 한 번 적용하고, 프로젝트 루트의 PowerShell에서 실행한다.

```powershell
docker compose up -d meta-db shard-a shard-b
.\gradlew.bat bootRun
```

다른 터미널에서 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/health
```

응답은 `{"status":"UP"}`이다. 서버 종료는 실행한 터미널에서 Ctrl+C를 누른다.

```powershell
.\gradlew.bat clean build
```

이 명령은 이전 빌드 결과를 정리하고 컴파일·기본 테스트·JAR 생성을 수행한다.
테스트 보고서는 `build/reports/tests/test/index.html`에 생성된다.

## IntelliJ 실행

1. 프로젝트를 열고 `build.gradle`을 Gradle 프로젝트로 연결한다.
2. Project SDK와 Gradle JVM을 JDK 21로 설정한다.
3. Gradle 배포는 Wrapper를 사용하고 동기화한다.
4. `ShardsProApplication.main()`을 실행한다. 작업 디렉터리는 프로젝트 루트로 두고 로컬 `.env`에서 DB 연결 설정을 읽도록 한다.

8080이 사용 중이면 기존 서버를 종료하거나 `bootRun --args="--server.port=8081"`로 실행한다.
이 경우 확인 URL도 8081로 바꾼다.

## 팀 작업 진행 방식

- 한 번에 핵심 개념 하나와 작은 변경만 다룬다.
- 변경 코드와 각 구문의 의미를 채팅에서도 설명한다.
- 실행 결과를 함께 확인한 뒤 다음 작업 요청을 기다린다.
- 팀원이 맡을 작업은 대상 파일·할 일·완료 조건으로 정리하고 대신 구현하지 않는다.

이전 DB·샤딩 구현은 `.local/backups/`에 보관했다. `.local/`에는 로컬 비밀번호도 있으므로 Git과 팀 공유 대상에서 제외한다.
기존 DB 볼륨과 로컬 비밀번호 파일은 삭제하지 않았다. 현재 기본 코드에서는 사용하지 않는다.
