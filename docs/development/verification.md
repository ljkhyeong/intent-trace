# 로컬 검증과 작업 재개

## 편집 중에는 관련 테스트, 마지막에는 필요한 전체 검증

1. 변경 파일과 실패 가능 지점을 확인하고 개발 스킬의 검증 목록에서 대상을 고른다. 파일 경로는 `rg --files`로 찾고 필요한 부분만 읽는다.
2. 서버 수정 중에는 `./gradlew focusedTest --tests '*대상테스트명'`을 사용한다. 실패하면 해당 테스트와 원인을 확인하고 수정한 뒤 같은 대상을 실행한다.
3. 서버 코드와 테스트 기대값 수정을 마치면 `./gradlew test`를 한 번 실행한다. 전체 검증 뒤 코드를 다시 고쳤다면 영향받는 검증을 다시 수행한다.
4. 결과 정리만 남으면 테스트 대신 `python3 scripts/test-summary.py server focused postgres`로 필요한 결과를 읽는다. 범위별 결과는 따로 집계하며 합산하지 않는다. 실행하지 않은 대상은 명령에서 뺀다.

## 결과와 Gradle 증분 실행

| 명령 | 실행 범위 | XML 결과 경로 |
| --- | --- | --- |
| `./gradlew focusedTest --tests '*대상테스트명'` | 지정한 서버 테스트 | `build/test-results/focusedTest` |
| `./gradlew test` | PostgreSQL 전용을 제외한 서버 전체 | `build/test-results/test` |
| `scripts/verify-postgres.sh` | 새 PostgreSQL의 스키마·JDBC·백업·복구 | `build/test-results/postgresTest` |
| `./gradlew -p intellij-plugin test` | IntelliJ 테스트 | `intellij-plugin/build/test-results/test` |

부분 테스트와 PostgreSQL 검증은 전체 테스트 결과를 덮어쓰지 않는다. 일반 테스트는 소스·테스트·클래스 경로와 등록된 입력이 같으면 Gradle의 `UP-TO-DATE` 판단을 따른다. Zed 실행 파일·의존성 명세·SDK 설치 여부와 검증 스크립트도 입력에 포함한다. PostgreSQL은 매번 새 DB를 준비하므로 결과 캐시를 사용하지 않는다. 별도 테스트 작업은 [Gradle의 표준 Test 작업](https://docs.gradle.org/current/userguide/java_testing.html)을 사용한다.

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
