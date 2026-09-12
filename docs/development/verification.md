# 로컬 검증과 작업 재개

## 편집 중에는 관련 테스트, 마지막에는 필요한 전체 검증

1. `git rev-parse HEAD`로 작업 시작 커밋을 기록하고 변경할 기능의 검증을 고른다. 파일 경로는 `rg --files`로 찾는다.
2. 파일 작성 직후 `python3 scripts/feedback.py files <파일...>`로 지역 검사한다. 한 번에 함께 수정한 파일은 같은 호출에 넣어 모듈별 컴파일을 한 번만 실행한다. 같은 변경에 훅이 실행한 검사는 생략한다.
3. 서버 동작을 수정하는 동안에는 `./gradlew focusedTest --tests '*대상테스트명'`을 사용한다. 실패 원인을 고친 뒤 해당 범위만 재실행한다.
4. 코드·기대값 수정을 마치면 필요한 전체 테스트를 한 번 실행한다. 서버의 `./gradlew test`는 구조 검사도 포함한다.
5. 종료 전 `python3 scripts/feedback.py finish --base <시작 커밋>`을 실행하고 출력된 `review.diff` 전체를 읽는다. 코드·테스트·설정·문서가 함께 맞는지, 누락·계층 의존·불필요한 변경이 없는지 확인한다. 검토 후 수정했다면 해당 지역 검사와 최종 검사를 다시 적용한다.
6. 결과 정리에는 `python3 scripts/test-summary.py server architecture focused postgres`에서 실행한 대상만 지정한다. 같은 구조 검사 입력은 Gradle의 증분 결과를 재사용한다.

## 파일별 검사와 구조 검사

| 시점 | 자동 검사 |
| --- | --- |
| 파일 작성 직후 | Git 공백 오류, Python·JSON·TOML 구문, JavaScript·셸 구문, 변경 모듈의 Kotlin 컴파일 |
| 작업 종료 직전 | 시작 커밋 이후 전체 diff와 새 파일, 서버 변경이 있으면 ArchUnit 구조 검사 |
| CI 서버 테스트 | ArchUnit과 기존 동작 테스트, 검증 루프 자체의 회귀 테스트 |

Markdown·CSS·YAML 등 별도 구문 검사가 없는 파일은 공백만 검사한다. 문서 링크·화면·기능별 테스트는 개발 스킬에 따라 확인한다. 지역·구조 검사는 동작 테스트를 대신하지 않는다.

최종 diff에는 작업 중 커밋한 변경, stage, 작업 파일, 미추적 파일을 포함한다. 삭제·이름 변경도 확인하며 `.gitignore` 대상은 제외한다. `build/feedback/`에 전체 diff와 검사 로그를 보관하고 원문 대화·도구 인자·세션 토큰은 저장하지 않는다. 수동 검사의 diff는 `build/feedback/manual/review.diff`다.

[ArchUnit](https://www.archunit.org/userguide/html/000_Index.html)은 서버의 컴파일된 클래스에서 다음 규칙을 검사한다. 테스트 fixture는 제품 검사에서 제외하고, 의도적으로 잘못 연결한 fixture로 규칙이 실패하는지도 확인한다.

- `adapter.in`의 Controller·MCP는 `application`의 `*Repository`와 `adapter.out`·DB API를 직접 참조하지 않는다. 도메인의 `GitHubRepository` 값 객체는 허용한다.
- `domain`은 `application`·`adapter`·`config`와 Spring·DB API에 의존하지 않는다.
- `application`은 `adapter`·인프라 구현체·DB API에 의존하지 않는다. 기존 Spring 서비스·트랜잭션과 설정 주입은 허용한다.

규칙은 `src/test/kotlin/io/intenttrace/architecture/ArchitectureTest.kt` 한 곳에서 관리한다. import 문만 찾는 정규식 검사 대신 필드·생성자·메서드·상속 등 실제 클래스 의존을 검사한다.

## Codex 자동 실행

프로젝트의 `.codex/config.toml`에 [공식 command hook](https://learn.chatgpt.com/docs/hooks)을 등록한다. 추가 모델 호출이나 유료 서비스는 사용하지 않는다.

- `UserPromptSubmit`: 작업 시작 커밋과 파일 해시를 보관한다. 실패 후 이어지는 요청은 같은 기준을 유지한다.
- `PostToolUse`: `apply_patch`와 셸 실행 뒤 실제 내용이 달라진 파일만 지역 검사한다. 실패를 읽기만 하는 후속 호출에는 같은 검사를 반복하지 않는다.
- `Stop`: 전체 diff와 구조를 검사하고 실패하면 수정을 요청한다. 이미 연장된 턴에서도 실패하면 무한 반복 없이 실패를 알리고 최종 응답에 남기도록 한다.

상태는 세션 ID의 해시로 구분하고 동시 훅 검사는 잠금으로 직렬 실행한다. 셸에서 직접 실행한 Gradle과 훅 검사를 겹치지 않도록 수정·검증을 순서대로 실행한다. Python 3.11 이상과 macOS·Linux 환경을 사용한다.

Codex CLI의 `/hooks`에서 새 훅의 정의를 검토하고 신뢰 승인한 뒤 자동 실행된다. 이는 공식 훅 보안 절차이며 설정을 저장하는 것만으로 승인되지 않는다. 새 설정을 불러오지 않은 세션이나 훅 미지원 도구에서는 같은 수동 명령을 사용한다. 이때 시작 커밋을 현재 HEAD로 바꾸어 이전 변경을 누락하지 않는다. 훅은 모든 파일 저장을 감시하는 데몬이 아니며 IDE에서 직접 저장한 파일도 종료 전 전체 diff에 포함해 검사한다.

## 결과와 Gradle 증분 실행

| 명령 | 실행 범위 | XML 결과 경로 |
| --- | --- | --- |
| `./gradlew focusedTest --tests '*대상테스트명'` | 지정한 서버 테스트 | `build/test-results/focusedTest` |
| `./gradlew test` | PostgreSQL 전용을 제외한 서버 전체 | `build/test-results/test` |
| `./gradlew architectureTest` | 서버 계층 의존 규칙과 위반 탐지 확인 | `build/test-results/architectureTest` |
| `scripts/verify-postgres.sh` | 새 PostgreSQL의 스키마·JDBC·백업·복구 | `build/test-results/postgresTest` |
| `./gradlew -p intellij-plugin test` | IntelliJ 테스트 | `intellij-plugin/build/test-results/test` |

부분·구조·PostgreSQL 검증은 전체 테스트 결과를 덮어쓰지 않는다. `test`는 `architectureTest`를 선행 실행하고 일반 테스트에서 해당 클래스를 제외해 중복을 막는다. 일반 테스트는 소스·테스트·클래스 경로와 등록된 입력이 같으면 Gradle의 `UP-TO-DATE` 판단을 따른다. Zed 실행 파일·의존성 명세·SDK 설치 여부와 검증 스크립트도 입력에 포함한다. PostgreSQL은 매번 새 DB를 준비하므로 결과 캐시를 사용하지 않는다. 별도 테스트 작업은 [Gradle의 표준 Test 작업](https://docs.gradle.org/current/userguide/java_testing.html)을 사용한다.

`test-summary.py`는 저장된 XML만 읽는다. 현재 코드의 검증 여부를 새로 판정하지 않으며, 이전 결과를 이번에 실행한 테스트로 보고하지 않는다. 실패·건너뜀을 구분하고 결과가 없거나 읽을 수 없으면 종료 코드 1을 반환한다.

## 반복을 줄이는 기준

- `clean`, `--rerun-tasks`, `--no-daemon`을 로컬 기본 명령에 붙이지 않는다. 산출물 손상·실행 환경 변경·daemon 문제가 확인됐을 때 해당 복구 옵션을 사용한다. CI의 `--no-daemon` 설정은 유지한다.
- 같은 작업 폴더에서 Gradle 실행을 겹치지 않는다. 실행 중인 명령의 결과를 기다리고, 입력을 수정한 뒤 다음 검증을 시작한다.
- PostgreSQL 검증은 Docker가 배정한 빈 로컬 포트를 사용한다. 고정 포트가 필요한 경우에만 `INTENT_TRACE_POSTGRES_SMOKE_PORT`를 지정한다. 다른 작업의 DB를 종료해 포트를 확보하지 않는다.
- `npm ci --prefix clients/zed --ignore-scripts`는 의존성이 없거나 `package.json`·잠금 파일이 바뀌었을 때 실행한다. 일반 Zed 수정은 `npm test --prefix clients/zed`로 확인한다. 배포 패키지의 빈 캐시 설치 검증은 별도 배포 절차를 따른다.
- 전체 서버 테스트에서 `ZedBridgeIntegrationTest`가 통과했다면 같은 코드·환경에서 따로 반복하지 않는다. 건너뛴 경우에는 의존성을 준비한 뒤 `./gradlew focusedTest --tests '*ZedBridgeIntegrationTest'`로 확인한다.
- 문서·스킬 수정에는 해당 구조·링크 검사만 실행한다. 검증기 자체를 바꾸지 않았다면 공식 검사와 로컬 대체 검사를 둘 다 반복할 필요가 없다.

## 다음 작업에 남길 내용

HANDOFF에는 변경한 기능·검증 대상 코드·실행 명령과 결과·남은 제한만 짧게 남긴다. 이전 내용을 다시 읽을 때는 관련 항목만 확인한다. 같은 코드·환경·범위의 성공 결과는 재사용하고, 다르거나 확인할 수 없으면 관련 검증을 실행한다. 이번 실행, 이전 결과 재사용, 미실행을 구분한다.

이전 작업을 검토할 때는 같은 프로젝트의 최근 작업에서 제목·변경 파일·명령·종료 상태를 먼저 추린다. 필요한 실패 전후만 상세히 읽고, 원문 대화나 숨은 추론을 검증 문서에 복사하지 않는다.

인증·외부 도구 실패는 실패 단계와 입력 전달 경로를 확인한 뒤 재시도한다. 브라우저 자동화 클립보드와 셸 `pbpaste`가 공유된다고 가정하지 않는다. 비밀값을 재발급하기 전에 비민감 표식으로 전달 경로부터 확인한다.
