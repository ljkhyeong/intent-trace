# ADR-0007: IntelliJ 플러그인은 기존 조회 API의 얇은 보안 클라이언트로 둔다

## 상태

채택

## 배경

현재 줄에서 변경 의도를 바로 확인하려면 IntelliJ 편집기와 Git 문맥이 필요합니다. 이 편의를 서버에 추가하면 서버가 로컬 작업 트리와 IDE 상태를 알아야 하고, IntelliJ 플러그인에 기록 수명주기 정책을 복제하면 REST·MCP와 공개 범위가 달라질 수 있습니다.

## 결정

- `intellij-plugin/`을 서버와 분리된 Gradle 프로젝트로 둔다.
- 플러그인은 Git4Idea에서 현재 저장소, `origin`, 전체 HEAD와 파일 경로를 읽고 편집기에서 1부터 시작하는 줄 번호를 계산한다.
- 현재 줄 조회는 파일이 HEAD와 다르면 중단한다. 작업 트리 줄을 commit 근거 줄로 추정하지 않는다.
- 서버의 `GET /api/v1/change-records/lookup`을 그대로 사용하며 공개 상태와 저장소 읽기 권한은 서버가 결정한다.
- PRD-0005의 기록함은 공용 목록·단건 조회 API를 사용한다. 파일 이력은 수정 중인 파일에서도 조회하되 현재 줄 조회와 분리한다. 비공개 작성자 필터와 공개 상태는 서버가 페이지 처리 전에 적용한다.
- 과거 기록에서 코드로 이동할 때는 GitHub의 전체 커밋 SHA와 기록의 코드 근거를 사용한다. 현재 편집기 줄로 자동 이동하거나 현재 검증으로 표시하지 않는다.
- GitHub App user access·refresh token은 플러그인에 전달하지 않는다. 플러그인은 `its_` session token만 IntelliJ PasswordSafe 또는 환경 변수에서 읽는다.
- server URL은 `Settings > Tools > IntentTrace`의 값, `INTENT_TRACE_URL`, 기본값 `http://127.0.0.1:8080` 순서로 선택한다. 빈 설정은 환경 변수 또는 기본값으로 돌아가며, 적용 뒤 다음 요청부터 반영한다. HTTP는 loopback host에만 허용하고 그 밖의 주소는 HTTPS만 허용한다.
- 서버 주소는 IDE 공용 로컬 설정으로 둔다. IntelliJ `SimplePersistentStateComponent`와 `BaseState`로 정규화한 주소만 저장하고 `RoamingType.DISABLED`로 동기화에서 제외한다. 프로젝트 파일에서 자격 증명의 전송 대상을 바꿀 수 없게 한다. 설정 화면은 `BoundConfigurable`과 Kotlin UI DSL로 구성한다.
- PasswordSafe 세션은 서버 주소별로 보관하며 주소 변경 시 복사하거나 삭제하지 않는다. 환경 변수 세션은 현재 서버가 `INTENT_TRACE_URL`의 서버(미설정 시 기본 서버)와 같을 때만 사용한다.
- 연결 확인은 입력 중인 주소로 기존 `GET /actuator/health`를 호출하며 인증 정보를 읽거나 전달하지 않는다. HTTP 200과 JSON `status: UP`만 성공으로 표시하고, 설정 저장이나 로그인·저장소 권한·서버 신원 확인으로 해석하지 않는다.
- 플러그인의 승인 시작 액션은 정규화한 server URL의 `/auth/github/start`만 시스템 브라우저로 연다. OAuth callback과 token 교환은 서버가 계속 담당한다.
- 연결 해제 액션은 PasswordSafe 자격 증명만 삭제한다. 해당 서버에 사용할 환경 변수 token이 있으면 연결이 남아 있음을 안내한다.
- PasswordSafe와 HTTP 요청은 UI thread 밖에서 실행한다. 현재 줄 조회는 background task, 기록함의 명시적 조회와 연결 확인은 SDK의 modal progress task를 사용한다. 별도 스레드·상태 동기화 계층은 만들지 않는다.
- HTTP 연결과 스트림 정리는 IntelliJ `HttpRequests`에 맡긴다. 연결 제한은 5초, 응답 읽기 제한은 10초이며 redirect를 따라가지 않는다. 성공 응답만 최대 1,000,000바이트까지 읽고, 오류 응답은 본문 없이 상태 코드로 분류한다.
- JSON 응답 파싱이 실패하면 고정된 형식 오류 안내만 전달하고, 응답 원문을 포함할 수 있는 원인 예외는 전달하지 않는다.
- 결과 창은 읽기 전용이며 원문 대화, 프롬프트, 코드 본문과 token을 표시하거나 저장하지 않는다.

## 영향

- 현재 줄 조회는 기존 REST 계약을 사용한다. 기록함의 새 목록 계약은 REST·MCP가 함께 사용하며 IntelliJ 전용 API를 만들지 않는다.
- IDE가 시작된 뒤 server session이 사라지면 사용자는 OAuth를 다시 수행하고 새 `its_` token을 저장해야 한다.
- GitHub가 아닌 remote와 remote가 없는 저장소는 자동 조회할 수 없다. 커밋되지 않은 파일은 현재 줄 조회만 제한한다.
- IntelliJ Platform SDK는 서버 빌드와 별도로 내려받고 검증한다.

## 대안

- IntelliJ가 서버에 파일 경로만 보내고 서버가 Git을 읽는 방식: 서버가 사용자의 로컬 저장소를 볼 수 없어 제외했다.
- 작업 트리 diff로 줄 번호를 HEAD에 다시 매핑하는 방식: rename·복합 hunk에서 잘못된 근거를 보여줄 수 있어 현재 단계에서 제외했다.
- token을 일반 IDE 설정 XML에 저장하는 방식: Bearer token이 평문 설정과 동기화 대상에 들어갈 수 있어 제외했다.
- IntelliJ에서 OAuth callback과 token 가져오기까지 처리하는 방식: callback·state·PKCE 책임이 중복되므로 기존 서버 승인 흐름을 유지한다.
