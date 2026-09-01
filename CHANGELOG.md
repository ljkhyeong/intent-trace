# 변경 이력

IntentTrace의 사용자와 운영자에게 영향을 주는 변경을 기록합니다.

## 미출시

### 추가

- 저장소·파일·상태별 팀 공개 기록과 내 비공개 기록 목록 REST·MCP (`list_change_records`)
- 기존 공개 기록을 후속 공개 기록으로 연결하는 MCP `supersede_change_record`와 Codex 대체 절차
- IntelliJ 기록함, 현재 줄 결과가 없을 때 파일 이력 탐색, 원래 커밋·코드 근거·대체 기록 이동
- IntelliJ 서버 주소 설정과 인증 정보 없는 서버 상태 확인, IDE 재시작 없는 주소 적용

### 변경

- 같은 경로의 PostgreSQL backup이 동시에 실행될 때 성공한 파일을 덮어쓰거나 다른 실행의 실패 정리에서 삭제하던 문제 수정
- 코드 근거 생성 시 셸 정수 범위를 넘는 줄 번호가 빈 내용의 해시로 성공 처리되던 문제를 수정하고 서버와 같은 줄 번호 상한 적용
- Git 스냅샷의 파일명 표시 설정을 고정해 한글 파일명이 있어도 개인 Git 설정에 따라 해시가 달라지지 않도록 수정
- 코드 근거 생성 시 Git `blob` 타입을 확인해 디렉터리 목록이 파일 내용으로 해싱되던 문제 수정
- 서버가 허용한 큰 기록을 IntelliJ에서도 조회할 수 있도록 응답 상한을 4MiB로 조정하고 상한 초과 거부 유지
- Windows 서버에서 정규화한 저장소 경로에 역슬래시가 들어가 기록 생성이 실패하던 문제 수정
- IntelliJ 기록함 조회 실패 시 마지막 성공 필터를 복원하고 기존 목록·선택·페이지 유지
- IntelliJ 검증 결과를 현재 IDE 코드가 아닌 기록 스냅샷과의 일치 여부로 표시
- IntelliJ 조회 응답을 Kotlin serialization으로 읽고, 잘못된 필드 자료형을 응답 형식 오류로 처리
- IntelliJ에서 IPv6 loopback HTTP 주소를 거부하던 검사 수정
- IntelliJ 조회·세션 작업의 완료·실패 처리를 Task 콜백으로 통일하고 취소 예외를 플랫폼에 전달
- IntelliJ HTTP 요청을 SDK API로 전환해 응답 본문이 멈춰도 읽기 제한 시간이 적용되도록 수정
- OAuth callback에서 긴 IPv6 loopback 주소 표기 허용
- 나노초 검증 시각을 DB와 같은 마이크로초 정밀도로 반올림해 동일 요청 재시도 충돌 수정
- 대체 대상 두 기록을 같은 트랜잭션에서 잠가 동시 요청의 순환 참조 방지
- 비밀값 제거 후 저장 길이를 초과하면 DB 오류 대신 원문 없는 입력 오류 반환
- 같은 기록을 서로 다른 PR에 동시에 게시해도 Check Run이 중복 생성되지 않도록 잠금을 기록 단위로 변경
- OAuth 갱신 응답 수신·파싱·token 값 변환에 실패하면 세션을 폐기하고 `401`로 재승인을 요청하도록 변경
- 잠금을 기다리던 요청이 앞선 요청에서 폐기한 세션과 refresh token을 다시 사용하지 않도록 수정

### 보안

- 이스케이프된 따옴표 뒤의 비밀값이 제거되지 않고 남던 문제 수정
- IntelliJ에서 서버 주소를 바꿔도 다른 서버의 환경 변수 세션이 전송되지 않도록 사용 범위 제한
- OAuth 응답 파싱 실패 시 원인 예외에 응답 원문이 남지 않도록 처리
- 코드 심벌 이름(`symbolName`)의 비밀값·개인 home 절대 경로를 저장 전에 제거
- GitHub 사용자·저장소 권한·Check Run과 IntelliJ 응답 파싱 오류에서 원문을 포함한 원인 예외 제거

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
