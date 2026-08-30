# ADR-0007: IntelliJ 플러그인은 기존 조회 API의 얇은 보안 클라이언트로 둔다

## 상태

채택

## 배경

현재 줄에서 변경 의도를 바로 확인하려면 IntelliJ 편집기와 Git 문맥이 필요합니다. 이 편의를 서버에 추가하면 서버가 로컬 작업 트리와 IDE 상태를 알아야 하고, IntelliJ 플러그인에 기록 수명주기 정책을 복제하면 REST·MCP와 공개 범위가 달라질 수 있습니다.

## 결정

- `intellij-plugin/`을 서버와 분리된 Gradle 프로젝트로 둔다.
- 플러그인은 Git4Idea에서 현재 저장소, `origin`, 전체 HEAD와 파일 경로를 읽고 편집기에서 1부터 시작하는 줄 번호를 계산한다.
- 파일이 HEAD와 다르면 조회를 중단한다. 작업 트리 줄을 commit 근거 줄로 추정하지 않는다.
- 서버의 `GET /api/v1/change-records/lookup`을 그대로 사용하며 공개 상태와 저장소 읽기 권한은 서버가 결정한다.
- GitHub App user access·refresh token은 플러그인에 전달하지 않는다. 플러그인은 `its_` session token만 IntelliJ PasswordSafe 또는 환경 변수에서 읽는다.
- server URL은 `INTENT_TRACE_URL`로 바꾸며 기본값은 `http://127.0.0.1:8080`이다. HTTP는 loopback host에만 허용하고 그 밖의 주소는 HTTPS만 허용한다.
- 플러그인의 승인 시작 액션은 정규화한 server URL의 `/auth/github/start`만 시스템 브라우저로 연다. OAuth callback과 token 교환은 서버가 계속 담당한다.
- 연결 해제 액션은 PasswordSafe 자격 증명만 삭제한다. 환경 변수 token이 있으면 해당 연결이 남아 있음을 안내한다.
- PasswordSafe와 HTTP 요청은 background task에서 실행한다.
- HTTP 연결과 스트림 정리는 IntelliJ `HttpRequests`에 맡긴다. 연결 제한은 5초, 응답 읽기 제한은 10초이며 redirect를 따라가지 않는다. 성공 응답만 최대 1,000,000바이트까지 읽고, 오류 응답은 본문 없이 상태 코드로 분류한다.
- JSON 응답 파싱이 실패하면 고정된 형식 오류 안내만 전달하고, 응답 원문을 포함할 수 있는 원인 예외는 전달하지 않는다.
- 결과 창은 읽기 전용이며 원문 대화, 프롬프트, 코드 본문과 token을 표시하거나 저장하지 않는다.

## 영향

- 서버 도메인·DB·REST 계약을 바꾸지 않고 IntelliJ 사용 흐름을 추가할 수 있다.
- IDE가 시작된 뒤 server session이 사라지면 사용자는 OAuth를 다시 수행하고 새 `its_` token을 저장해야 한다.
- GitHub가 아닌 remote, remote가 없는 저장소와 커밋되지 않은 현재 파일은 자동 조회할 수 없다.
- IntelliJ Platform SDK는 서버 빌드와 별도로 내려받고 검증한다.

## 대안

- IntelliJ가 서버에 파일 경로만 보내고 서버가 Git을 읽는 방식: 서버가 사용자의 로컬 저장소를 볼 수 없어 제외했다.
- 작업 트리 diff로 줄 번호를 HEAD에 다시 매핑하는 방식: rename·복합 hunk에서 잘못된 근거를 보여줄 수 있어 현재 단계에서 제외했다.
- token을 일반 IDE 설정 XML에 저장하는 방식: Bearer token이 평문 설정과 동기화 대상에 들어갈 수 있어 제외했다.
- IntelliJ에서 OAuth callback과 token 가져오기까지 처리하는 방식: callback·state·PKCE 책임이 중복되므로 기존 서버 승인 흐름을 유지한다.
