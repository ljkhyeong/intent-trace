# 변경 이력

IntentTrace의 사용자와 운영자에게 영향을 주는 변경을 기록합니다.

## 미출시

### 추가

- 없음

### 변경

- IntelliJ 조회 응답을 Kotlin serialization으로 읽고, 잘못된 필드 자료형을 응답 형식 오류로 처리
- IntelliJ에서 IPv6 loopback HTTP 주소를 거부하던 검사 수정
- IntelliJ 조회·세션 작업의 완료·실패 처리를 Task 콜백으로 통일하고 취소 예외를 플랫폼에 전달

## 0.7.0 - 2026-08-30

### 추가

- IntelliJ 2025.3+에서 현재 줄에 연결된 공개 변경 의도를 조회하는 플러그인
- `its_` session을 IntelliJ PasswordSafe에 저장하고 환경 변수로 서버를 선택하는 연결 절차
- IntelliJ에서 GitHub 승인 페이지를 열고 PasswordSafe 세션을 삭제하는 액션

### 변경

- `main` 정식 릴리스 후보에서 서버/JAR, IntelliJ 플러그인, 플러그인 메타 버전을 `0.7.0`으로 통일

## 0.6.0 - 2026-08-29

첫 공개 MVP 릴리스입니다.

### 추가

- 변경 의도 초안 생성과 `DRAFT → AUTHOR_CONFIRMED → PUBLISHED → SUPERSEDED` 수명주기
- 전체 Git commit, 저장소 snapshot, 파일·줄·콘텐츠 hash와 실제 검증 결과 연결
- REST API와 Spring AI Streamable HTTP MCP 도구
- GitHub 사용자 인증, 저장소 권한 확인과 메모리 전용 `its_` session
- PR HEAD 검증 후 GitHub Check Run 게시와 재시도 시 기존 Check Run 갱신
- PostgreSQL·Caddy 기반 단일 인스턴스 팀 배포와 backup·restore 절차
- Codex skill과 개인정보를 수집하지 않는 SessionStart 안내 hook

### 변경

- 저장소 식별자와 코드 근거 경로를 입력 시점에 정규화
- 같은 요청 ID의 저장 내용까지 비교하도록 생성 멱등성 강화
- JDBC 하위 행 저장·조회를 일괄 처리
- 사용하지 않는 `baseRevision` API·도메인·DB 열 제거
- 외부 container image를 digest, GitHub Actions를 commit SHA로 고정

### 보안

- GitHub access·refresh token과 `its_` session을 프로세스 메모리에만 저장
- OAuth callback에 HttpOnly·SameSite cookie, 일회성 `state`, TTL과 PKCE `S256` 적용
- OAuth 승인 대기 요청에 전역 개수 상한 적용
- token, private key, client secret과 개인 절대 경로 redaction 강화
- 공개·대체 기록과 GitHub 게시에 저장소 권한 및 commit 일치 확인 적용
