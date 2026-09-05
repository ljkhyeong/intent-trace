# 조회 반복과 SQL 매개변수 추가 검토

최초 검토 기준: `dd11ddf`. 앞서 반영한 표준 API 개선 다섯 건을 제외하고 목록·게시 이력·검증 흐름을 확인했다. 아래 설명과 행 번호는 검토 당시 상태이며, 후속 구현과 검증은 마지막 반영 결과에 정리했다.

## 1. PR 기록 목록의 반복 조회와 불필요한 이력 조회

- 위치: [PullRequestOverviewService.kt:45](../../src/main/kotlin/io/intenttrace/publication/application/PullRequestOverviewService.kt#L45), [JdbcGitHubPublicationRepository.kt:23](../../src/main/kotlin/io/intenttrace/publication/adapter/out/persistence/JdbcGitHubPublicationRepository.kt#L23), [JdbcGitHubPublicationTracking.kt:40](../../src/main/kotlin/io/intenttrace/publication/adapter/out/persistence/JdbcGitHubPublicationTracking.kt#L40)
- 현재: 목록을 읽은 뒤 기록마다 게시 정보와 최근 게시 시도를 각각 조회한다. 목록에 N건이 있으면 이 경로에서 SQL이 `1 + 2N`회 실행된다. 20건이면 41회, 100건이면 201회다. 호출 경로로 계산한 수이며 실행 시간 측정값은 아니다.
- 추가 낭비: `recent()`는 기록마다 최대 20건을 읽지만 PR 목록은 `firstOrNull()`로 최신 한 건만 사용한다.
- 제안: 페이지의 기록 ID를 묶어 게시 정보와 기록별 최신 시도를 각각 한 번에 조회한다. 목록을 포함해 비어 있지 않은 페이지의 조회를 3회로 줄일 수 있다. 최신 시도는 DB에서 기록별 한 건을 선택한다. 상세 화면의 최근 20건 조회는 유지한다.
- 유지할 조건: 저장소·PR 범위, 게시 시도의 `started_at desc, id desc` 순서, 시도만 있고 게시 결과가 없는 기록, 기존 페이지 순서를 보존한다. 빈 페이지에서는 후속 조회를 생략한다.
- 구현 후 확인: 기존 PR 목록 통합 테스트를 보완해 여러 기록·다른 PR·같은 시각의 시도를 넣고 최신 결과와 조회 횟수를 확인한다. H2와 PostgreSQL에서 같은 결과가 나오는지 확인한다.

## 2. 검색 SQL의 위치 기반 매개변수 수동 관리

- 위치: [JdbcChangeRecordCatalog.kt:17](../../src/main/kotlin/io/intenttrace/record/adapter/out/persistence/JdbcChangeRecordCatalog.kt#L17)
- 현재: 상태 수에 맞춰 `?`를 생성하고, 검색어는 같은 값을 네 번 추가한다. 커서 시각도 두 번 넣는다. SQL 조건과 매개변수 배열의 순서를 함께 관리해야 한다.
- 제안: `NamedParameterJdbcTemplate`로 바꾸고 `:statuses`, `:keyword`, `:createdAt` 같은 이름을 사용한다. 목록의 자리 표시자 생성과 반복 매개변수 처리를 Spring에 맡긴다. [Spring의 이름 기반 매개변수](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html), [IN 목록 지원](https://docs.spring.io/spring-framework/reference/data-access/jdbc/parameter-handling.html)
- 기존 [JdbcChangeRecordRepository.kt:37](../../src/main/kotlin/io/intenttrace/record/adapter/out/persistence/JdbcChangeRecordRepository.kt#L37)도 같은 API를 사용한다. 새 의존성이나 범용 쿼리 빌더는 필요 없다. 현재 SQL도 값을 바인딩하므로 SQL 삽입 취약점을 발견했다는 뜻은 아니다.
- 유지할 조건: 검색어의 `%`·`_`·`!` 이스케이프, 작성자·상태·경로·PR 필터와 커서 정렬은 유지한다.
- 구현 후 확인: 기존 검색·페이지 조회 통합 테스트에서 필터 조합과 검색 특수문자를 확인한다. 별도 SQL 빌더 테스트 계층은 추가하지 않는다.

## 검증 로직 판단

PR 조회와 공통 목록 서비스의 권한·크기 검사는 각각의 진입 경로에 필요하다. 커서 형식 검사, 브라우저 복귀 경로 허용 목록, 버전·작성자 검증도 제거 대상으로 보지 않았다. 추가 확인 범위에서는 앞선 저장소 빈 값 검사처럼 바로 삭제할 만한 중복 검증을 더 찾지 못했다.

최초 검토에서는 코드와 공식 JDBC 문서를 대조했다. 당시에는 제품 코드 변경이 없어 테스트를 다시 실행하지 않았으며 문서 링크와 변경 형식만 확인했다.

## 반영 결과

- PR 목록은 `findAll()`과 `latest()`로 게시 정보와 최신 시도를 각각 일괄 조회한다. 단건 게시 정보 조회도 같은 SQL을 재사용한다. 기존 최근 시도 20건 조회와 응답 필드는 유지했다.
- 최신 시도는 `row_number()`로 기록별 한 건만 선택한다. H2·PostgreSQL이 공유하는 저장 테스트에서 같은 시각의 시도, 다른 저장소·PR, 요청하지 않은 기록과 빈 입력을 확인했다.
- H2 PR 목록 통합 테스트에서 기록 20건을 읽을 때 JDBC 조회 3회, 빈 페이지는 1회임을 확인했다. 기존 코드의 같은 목록 조회는 `1 + 2 × 20 = 41`회 구조였다. 실행 시간 개선율을 측정한 결과는 아니다.
- 검색 SQL은 `NamedParameterJdbcTemplate`로 전환했다. 기존 검색·페이지 테스트로 네 검색 필드, `%`·`_`·`!`, 작성자 범위와 커서를 확인했다.
- 관련 테스트 24개, `scripts/verify-postgres.sh`의 PostgreSQL 5개와 백업·복구, 마지막 `./gradlew test`의 서버 전체 169개가 통과했다. 복구 전후 기록 15건·변경 이력 34건이 일치했다. 부분 테스트 24개는 서버 전체에 포함된다.
- DB 스키마·외부 API·IDE 구현은 변경하지 않았다. 실제 GitHub 게시·배포·IDE 화면 검증은 이번 범위에 포함하지 않았다.
