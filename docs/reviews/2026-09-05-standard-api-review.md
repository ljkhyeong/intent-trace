# Java·Spring API와 중복 검증 검토

최초 검토 기준: `47a3521`. 서버의 HTTP·인증·저장·입력 검증을 중심으로 코드를 확인하고, IntelliJ 클라이언트의 HTTP·JSON 처리도 대조했다. Java 21·Spring Boot 4.1.1 기준이다. 아래 후보 설명과 행 번호는 검토 당시 상태이며, 후속 구현은 마지막 반영 결과에 정리했다.

## 정리할 후보

### 1. 인증 필터의 오류 JSON 직접 조립

- 위치: [GitHubUserAuthenticationFilter.kt:86](../../src/main/kotlin/io/intenttrace/identity/adapter/in/web/GitHubUserAuthenticationFilter.kt#L86)
- 현재: `status`와 `title`을 문자열 템플릿으로 JSON에 넣는다. 다른 HTTP 응답은 Jackson을 사용한다.
- 제안: 주입받은 `ObjectMapper`로 두 필드만 가진 DTO나 Map을 직렬화한다. 문자열 이스케이프를 직접 관리할 필요가 없다. 현재 제목은 고정 문구나 숫자를 포함한 안내이므로, 확인된 외부 입력 취약점으로 분류하지는 않는다.
- 주의: 필터 오류는 MVC 컨트롤러 밖에서 발생한다. `@ControllerAdvice` 추가만으로 해결된다고 가정하지 않는다. [Spring `ProblemDetail`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)을 사용한다면 기존 `status`·`title`과 추가되는 필드의 호환성을 확인한다.
- 수정 후 검증: 기존 인증 필터 테스트에서 401·429·502 상태와 JSON 필드, `Retry-After`를 확인한다.

### 2. GitHub API 클라이언트 다섯 곳의 공통 설정

- 위치: [GitHubUserRestClient.kt:24](../../src/main/kotlin/io/intenttrace/identity/adapter/out/github/GitHubUserRestClient.kt#L24), [GitHubRestClient.kt:29](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubRestClient.kt#L29), [GitHubAppInstallationClient.kt:36](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAppInstallationClient.kt#L36), [GitHubUserPullRequestClient.kt:28](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubUserPullRequestClient.kt#L28), [GitHubGitEvidenceClient.kt:33](../../src/main/kotlin/io/intenttrace/record/adapter/out/github/GitHubGitEvidenceClient.kt#L33)
- 현재: API 주소·`Accept`·`X-GitHub-Api-Version` 설정이 반복된다. 정책을 바꿀 때 다섯 곳을 확인해야 한다.
- 제안: Boot가 주입한 `RestClient.Builder`로 GitHub API 전용 `RestClient` Bean 하나를 구성해 주입한다. 완성된 클라이언트는 여러 스레드에서 공유할 수 있다. [Spring RestClient 문서](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
- 주의: 인증 토큰은 요청마다 설정하고, 다른 주소를 쓰는 OAuth 클라이언트는 분리한다. 기존 호출 제한·지표 인터셉터와 코드 조회의 요청별 시간 제한을 유지한다. 범용 HTTP 래퍼를 새로 만들 필요는 없다.
- 수정 후 검증: 기존 GitHub 클라이언트 테스트로 요청 헤더·인증·시간 제한을 확인한다. 설정 중복을 줄이는 변경이며, 현재 요청이 잘못된다는 지적은 아니다.

### 3. 값 객체와 겹치는 저장소 빈 값 검사

- 위치: [ChangeRecordFacade.kt:191](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordFacade.kt#L191), [GitHubRepository.parse:40](../../src/main/kotlin/io/intenttrace/identity/domain/GitHubIdentity.kt#L40)
- 현재: `validateCreate()`가 `repositoryKey.isNotBlank()`를 검사한다. 생성은 바로 뒤에서, 수정은 앞에서 `GitHubRepository.parse()`를 호출한다. 이 값 객체도 빈 값과 잘못된 `owner/repository` 형식을 거부한다.
- 제안: 서비스의 빈 값 검사 한 줄을 제거하고 값 객체에 맡긴다. 빈 값의 오류 문구가 달라질 수 있으므로 기존 계약만 확인한다. 다른 필드의 검증까지 일괄 제거할 근거는 아니다.
- 수정 후 검증: 기존 생성·수정 테스트의 잘못된 저장소 입력을 확인한다. 이 한 줄을 위한 별도 테스트 계층은 추가하지 않는다.

### 4. Duration 양수 검사 네 곳

- 위치: [GitHubProperties.kt:46](../../src/main/kotlin/io/intenttrace/config/GitHubProperties.kt#L46)의 46·81·91행, [EvidenceReadBudget.kt:21](../../src/main/kotlin/io/intenttrace/record/application/EvidenceReadBudget.kt#L21)
- 현재: `!duration.isNegative && !duration.isZero`를 반복한다.
- 제안: Java 21에서 제공하는 `duration.isPositive`로 바꾼다. 기존 최대 시간 검사는 유지한다. 작은 가독성 개선이며 과도한 검증 비용 문제는 아니다. [Java Duration API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Duration.html#isPositive())
- 수정 후 검증: 컴파일과 기존 설정·시간 제한 테스트를 사용한다. JDK 메서드 동작을 재검증하는 테스트는 추가하지 않는다.

### 5. JWT 직접 조립과 서명 — 의존성 추가를 고려할 항목

- 위치: [GitHubAppJwtFactory.kt:35](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAppJwtFactory.kt#L35)
- 현재: JSON 헤더·클레임, Base64 URL 인코딩, 서명 입력 연결, RS256 서명과 최종 토큰을 직접 구성한다. 암호 연산은 JDK `Signature`를 사용하고 있다.
- 제안: `spring-security-oauth2-jose`의 [`NimbusJwtEncoder`](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.html)에 토큰 직렬화와 서명을 맡길 수 있다. `iss`·`iat`·`exp`는 애플리케이션에서 설정한다. 현재 빌드에 이 모듈의 직접 의존성이 없으므로 앞의 네 항목보다 변경 비용이 크다.
- 주의: 인코더 교체만으로 [PKCS1→PKCS8 변환:72](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAppJwtFactory.kt#L72)이 없어지지는 않는다. [ADR-0003](../ADR-0003-github-app-installation-auth.md)의 두 개인 키 형식 지원을 유지해야 한다. 키 파싱까지 줄이려면 호환되는 파서 도입을 별도로 판단한다. JWT 생성을 위해 전체 웹 인증 구성을 교체할 필요는 없다.
- 수정 후 검증: 기존 JWT·설치 토큰 테스트를 사용해 PKCS1·PKCS8 입력, RS256 서명, 고정 시계의 클레임과 안전한 오류 응답을 확인한다.

## 유지할 코드

| 대상 | 유지할 이유 |
| --- | --- |
| REST DTO와 MCP의 입력 검증 | REST의 `@Valid`와 MCP의 명시적 `Validator` 호출은 서로 다른 입력 경로를 검사한다. MCP의 세 호출부에서 반복되는 두 줄은 새 검증 계층을 만들 만큼 크지 않다. |
| 도메인의 줄 범위·해시·시각 검사 | 내부 호출과 DB에서 객체를 만들 때도 필요한 조건이다. DTO 애너테이션만으로 모든 경로를 보호할 수 없다. |
| 경로 검증과 정규화 | `CodeAnchor` 생성자는 유효성만 검사하고, 서비스는 정규화한 경로를 저장한다. 반복 호출만 보고 서비스를 삭제하면 저장 값이 달라진다. Windows 경로 검사는 서버 운영체제와 관계없이 필요하다. |
| 서비스의 버전 검사와 SQL 버전 조건 | 전자는 잘못된 수정 요청을 거부하고, 후자는 조회 이후 발생한 동시 수정을 막는다. |
| 비밀값 제거 후 길이 검사 | 입력 길이 검사와 대상이 다르다. 치환된 문자열이 저장 길이 제한을 넘는지 확인한다. |
| 작성자·저장소 권한, 전체 커밋·스냅샷 확인 | 공개 범위와 실제 검증 대상을 보장하는 제품 규칙이다. |
| GitHub 응답 크기·조회 시간·호출 수 제한 | 외부 응답과 반복 조회의 자원 사용을 제한한다. 단순 `body()` 호출로 바꾸면서 제거하면 안 된다. |
| 고정 형식의 내용 해시, OAuth state·세션 갱신 제어 | 저장된 멱등성 해시와 일회성·동시성 계약이 있다. JSON 직렬화나 일반 캐시로 바꾸면 동작이 달라질 수 있다. |

HTTP는 이미 `RestClient`, 자식 행 저장은 `JdbcTemplate.batchUpdate`, HTML 이스케이프는 `HtmlUtils`, 쿠키는 `ResponseCookie`, URI 구성은 `UriComponentsBuilder`를 사용한다. IntelliJ도 SDK `HttpRequests`와 Kotlin Serialization을 사용한다. 이 부분을 다시 감싸는 공통 클래스를 추가할 필요는 없다.

## 최초 검토에서 확인한 내용

호출 경로·기존 테스트 코드·공식 API 문서를 대조했다. 제품 코드와 의존성을 바꾸지 않아 서버·IDE·DB 테스트는 실행하지 않았다. 문서의 로컬 링크 13개와 HANDOFF 연결을 확인했다. 위 테스트 항목은 후속 수정 시 필요한 확인 범위이며, 이번에 실행한 결과가 아니다.

## 반영 결과

- 다섯 항목을 반영했다. 인증 오류는 기존 두 필드의 JSON 형식을 유지하고, GitHub API 클라이언트 다섯 곳은 공통 Bean을 주입받는다. 요청별 토큰·시간 제한·호출 제한·지표는 유지했다.
- JWT 직렬화·서명은 `NimbusJwtEncoder`에 맡겼다. 키 쌍 Builder는 `kid`를 자동 생성하므로, 기존 헤더를 유지하도록 키 식별자가 없는 `JWKSet`을 전달하는 생성자를 사용했다. 두 PEM 형식의 키 변환은 유지했다.
- 마지막 부분 검증은 JWT 테스트 3개가 통과했다. 이어서 `./gradlew test`로 서버 전체 168개가 통과했고 건너뛴 테스트는 없다. 부분 검증 3개는 전체에 포함된다.
- 공통 HTTP 헤더와 서로 다른 요청의 토큰, 401·429·502 JSON, 고정 시계의 JWT 클레임과 실제 RS256 서명을 확인했다. REST·MCP·Zed 중계기와 서버 연결 검증은 전체 서버 테스트에 포함됐다.
- DB·IDE 구현은 바꾸지 않아 PostgreSQL·IntelliJ 테스트를 다시 실행하지 않았다. 실제 GitHub 게시·배포·IDE 화면 검증은 수행하지 않았다.
