# IntentTrace

IntentTrace는 AI 코드의 변경 이유, 관련 커밋·코드, 검증 결과를 기록합니다. 작성자가 확인한 기록을 팀 리뷰와 인수인계에 활용합니다.

## 현재 MVP

- 초안 생성과 중복 요청 처리
- 초안 수정·확인 취소·폐기와 작성자 전용 목록
- 내 공개 기록으로 새 초안 생성과 원본 비교
- 저장소·파일·작성자별 팀 기록 요약과 커서 페이지 조회
- 브라우저 로그인 후 기록 열람과 키워드·상태·파일·작성자 검색
- 웹 파일·줄 조회, 코드 확인 불가 사유, 변경된 항목만 보는 세부 비교
- 기록 상태: 초안 → 작성자 확인 → 팀 공개 → 새 기록으로 대체
- 기록을 커밋 해시와 스냅샷 해시에 연결
- 변경 전후 커밋·파일·줄·콘텐츠 해시 기반 코드 근거와 이름 변경 연결
- GitHub 전체 트리·blob으로 코드 근거를 확인하는 읽기 API
- 이전 커밋의 관련 기록·파일 이름 변경·줄 이동 조회와 제한에 따른 중단·재개
- 원문 출력을 저장하지 않는 로컬 검증 실행 도구
- 실제 검증 명령, 종료 코드, 실행 시간, 출력 해시, 결과 요약
- 작성자가 명시한 목적과 AI 추론·미확인 목적 구분
- REST API와 Spring AI Streamable HTTP MCP 도구
- PR 설명에 붙일 수 있는 Markdown 출력
- PR HEAD 검증 후 neutral GitHub Check Run 게시와 멱등 갱신
- GitHub 응답의 head·base 저장소 확인과 Fork PR 게시 거부
- GitHub 게시 시도 조회·응답 유실 복구와 기존 Check Run 대체 안내
- PR별 기록 목록·현재 HEAD 일치 여부와 연결·권한·설정 진단
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신
- GitHub 사용자 인증과 저장소 권한 기반 팀 접근 제어
- GitHub 로그인·세션 발급·사용자 토큰 자동 갱신(메모리 보관)
- 내 세션 조회·선택 폐기·전체 폐기
- 웹에서 내 연결 조회·선택 종료·전체 로그아웃
- 기록 변경 이력과 작성자·팀원별 노출 범위
- GitHub 호출 제한 대기 시간 안내와 기능별 Micrometer 지표
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 팀 배포와 backup·restore
- Codex 스킬과 세션 시작 안내 훅
- Zed Agent용 MCP 중계기·설정·연결 점검과 의존성을 포함한 설치 패키지
- IntelliJ 현재 줄의 공개 변경 의도 조회 플러그인
- 저장소·파일·상태별 팀 공개 기록과 내 비공개 기록함, IntelliJ 과거 커밋·코드·대체 기록 탐색

원문 대화와 숨은 추론 과정은 저장하지 않습니다. 검증 원문 출력도 저장하지 않고 해시와 요약만 기록합니다.
초안 생성 시 설명 필드와 코드 심벌 이름(`symbolName`)의 token·비밀값·개인 home 절대 경로를 제거합니다. 따옴표로 감싼 비밀값 안의 이스케이프된 따옴표와 역슬래시도 처리해 값의 뒷부분이 남지 않도록 합니다. 멱등 키인 `requestId`에서 같은 민감정보가 감지되면 값을 치환해 저장하지 않고 입력 오류로 거부합니다.

## 빠른 시작

Java 21과 Codex CLI가 필요합니다. 먼저 저장소를 받고 애플리케이션을 준비합니다.

```bash
git clone https://github.com/ljkhyeong/intent-trace.git
cd intent-trace
```

GitHub Developer settings에서 GitHub App을 만들고 사용할 저장소에 설치합니다. 사용자 승인 callback은 `http://127.0.0.1:8080/auth/github/callback`으로 등록하고 `Expiring user authorization tokens`를 활성화합니다. App에는 `Metadata: read`, `Pull requests: read`, `Checks: write` 권한만 부여합니다.

App 설정과 private key를 환경 변수로 전달한 뒤 서버를 실행합니다. private key 파일은 저장소 밖에 둡니다.

```bash
export INTENT_TRACE_GITHUB_APP_CLIENT_ID='Iv1.example'
export INTENT_TRACE_GITHUB_APP_CLIENT_SECRET='GitHub-App-client-secret'
export INTENT_TRACE_GITHUB_APP_PRIVATE_KEY_BASE64="$(base64 < /저장소-밖/private-key.pem | tr -d '\n')"
export INTENT_TRACE_GITHUB_CALLBACK_URL='http://127.0.0.1:8080/auth/github/callback'
./gradlew bootRun
```

브라우저에서 `http://127.0.0.1:8080/auth/github/start`를 열어 승인합니다. callback 화면에 한 번 표시되는 `its_` session token을 새 terminal의 환경 변수에 넣고 Codex MCP 연결을 추가합니다.

```bash
export INTENT_TRACE_SESSION_TOKEN='its_로컬-session-token'
codex mcp add intent-trace \
  --url http://127.0.0.1:8080/mcp \
  --bearer-token-env-var INTENT_TRACE_SESSION_TOKEN
codex mcp list
```

기록할 Git 저장소에서 Codex를 새로 시작한 뒤 다음처럼 요청합니다.

```text
현재 commit의 사용자 요청, 확인 가능한 판단, 최소 코드 근거와 실제 검증을
IntentTrace 비공개 초안으로 만들어 줘. 원문 대화와 숨은 추론은 포함하지 마.
```

Codex가 보여준 초안을 확인한 뒤에만 작성자 확인과 팀 공개를 요청합니다. GitHub PR에 게시하려면 기록의 전체 commit ID가 PR HEAD와 같아야 합니다.

### 릴리스 JAR 실행

`v0.6.0`부터는 GitHub Release에서 실행 JAR과 SHA-256 파일을 함께 제공합니다.

```bash
curl -LO https://github.com/ljkhyeong/intent-trace/releases/download/v0.6.0/intent-trace-0.6.0.jar
curl -LO https://github.com/ljkhyeong/intent-trace/releases/download/v0.6.0/intent-trace-0.6.0.jar.sha256
shasum -a 256 -c intent-trace-0.6.0.jar.sha256
java -jar intent-trace-0.6.0.jar
```

`v0.7.0`부터는 같은 release에 `intent-trace-intellij-<version>.zip`과 SHA-256 파일도 함께 제공합니다. 정식 version 변경, 실제 IntelliJ 확인, tag 발행 순서는 [`docs/operations/release.md`](docs/operations/release.md)를 따릅니다.

## 실행

Java 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 서버는 `127.0.0.1:8080`에만 바인딩됩니다.

- REST API: `http://127.0.0.1:8080/api/v1/change-records`
- MCP: `http://127.0.0.1:8080/mcp`
- 상태 확인: `http://127.0.0.1:8080/actuator/health`
- H2 콘솔: `http://127.0.0.1:8080/h2-console`

기본 데이터는 `.intent-trace/data`에 저장됩니다. PostgreSQL을 사용할 때는 환경 변수를 설정하고 `postgres` 프로필을 켭니다.

```bash
export INTENT_TRACE_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/intent_trace'
export INTENT_TRACE_DATABASE_USERNAME='intent_trace'
export INTENT_TRACE_DATABASE_PASSWORD='로컬-비밀번호'
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

## 팀 배포

Docker Compose는 PostgreSQL, IntentTrace와 Caddy를 단일 인스턴스로 실행합니다. 외부에는 Caddy의 80·443만 공개하고 애플리케이션과 데이터베이스는 Docker network 안에서만 접근합니다.

```bash
cp .env.team.example .env.team
chmod 600 .env.team
docker compose --env-file .env.team build app
docker compose --env-file .env.team up -d --no-build
curl http://localhost:8080/actuator/health
```

기본 예시는 로컬 HTTP 검증용입니다. 팀 domain을 사용할 때는 `.env.team`의 `INTENT_TRACE_SITE_ADDRESS`, 공개 port와 `INTENT_TRACE_GITHUB_CALLBACK_URL`을 실제 HTTPS origin으로 바꾸고, `INTENT_TRACE_IMAGE_TAG`에는 `git rev-parse HEAD`가 출력한 전체 commit ID를 저장합니다. callback은 GitHub App에 등록한 값과 정확히 같아야 합니다. 자세한 기동·TLS·backup·restore·rollback 절차는 [`docs/operations/team-deployment.md`](docs/operations/team-deployment.md)에 있습니다.

PostgreSQL에는 변경 기록과 게시 이력만 저장합니다. GitHub access·refresh token과 `its_` session은 계속 애플리케이션 메모리에만 있으므로 app container를 다시 만들면 사용자가 GitHub 승인을 다시 해야 합니다.

REST와 MCP 요청에는 IntentTrace 로컬 세션이 필요합니다. 먼저 GitHub App 설정에서 다음 항목을 준비합니다.

- 사용자 승인 callback URL: `http://127.0.0.1:8080/auth/github/callback`
- `Expiring user authorization tokens`: 활성화
- App client ID와 client secret

callback URL은 GitHub App에 등록한 값과 환경 변수 값을 정확히 맞추고 wildcard callback은 사용하지 않습니다. IntentTrace는 `state`와 PKCE `S256`을 함께 검증합니다. 설정한 뒤 서버를 시작하고 브라우저에서 `http://127.0.0.1:8080/auth/github/start`를 엽니다.

미완료 승인 요청은 기본 1,000개로 제한합니다. 단일 팀 환경에서 조정이 필요하면 `INTENT_TRACE_GITHUB_MAX_PENDING_STATES`를 1 이상 100,000 이하로 설정합니다.

```bash
export INTENT_TRACE_GITHUB_APP_CLIENT_ID='Iv1.example'
export INTENT_TRACE_GITHUB_APP_CLIENT_SECRET='GitHub-App-client-secret'
export INTENT_TRACE_GITHUB_CALLBACK_URL='http://127.0.0.1:8080/auth/github/callback'
./gradlew bootRun
```

승인을 마치면 callback 화면이 `its_`로 시작하는 로컬 세션 token을 한 번 표시합니다. 이 값을 IntentTrace를 호출하는 프로세스에 전달합니다.

```bash
export INTENT_TRACE_SESSION_TOKEN='its_로컬-session-token'
curl -H "Authorization: Bearer $INTENT_TRACE_SESSION_TOKEN" \
  http://127.0.0.1:8080/api/v1/change-records/기록-UUID
```

Codex는 프로젝트의 `.codex/config.toml`과 플러그인의 `.mcp.json`에서 `INTENT_TRACE_SESSION_TOKEN`을 읽어 MCP `Authorization` 헤더에 넣습니다. Codex를 이미 실행 중이었다면 환경 변수를 읽도록 새 프로세스나 세션에서 다시 연결합니다. session token도 Bearer 자격 증명이므로 설정 파일, 도구 인자와 변경 기록에 직접 넣지 않습니다.

팀 서버에 연결할 때는 프로젝트 `.codex/config.toml`에 로컬 서버와 다른 이름을 사용합니다.

```toml
[mcp_servers.intent-trace-team]
url = "https://intent.example.com/mcp"
bearer_token_env_var = "INTENT_TRACE_SESSION_TOKEN"
```

`codex mcp list`로 연결 대상을 확인합니다. 플러그인이 제공한 로컬 `intent-trace` 서버와 팀 서버를 동시에 쓸 필요가 없으면 Codex의 MCP 서버 설정에서 로컬 서버를 비활성화합니다. 자세한 설정 형식은 [Codex MCP 문서](https://learn.chatgpt.com/docs/extend/mcp)를 따릅니다.

IntentTrace는 GitHub `ghu_` access token과 `ghr_` refresh token을 프로세스 메모리에만 보관합니다. access token 만료가 가까우면 새 token 쌍으로 한 번 갱신하고 사용자가 같은지 다시 확인합니다. 서버를 재시작하면 로컬 세션이 사라지므로 다시 승인해야 합니다. 기존 REST 클라이언트는 호환을 위해 `ghu_` user access token을 직접 Bearer로 보낼 수 있지만 Codex 기본 연결에는 `its_` 세션을 사용합니다.

token 갱신이 거부되거나 갱신 응답 수신·파싱·token 값 변환에 실패하면 세션을 폐기하고 `401`로 재승인을 요구합니다. 같은 refresh token은 재전송하지 않으며, 대기 중이던 요청도 폐기된 세션을 사용하지 않습니다. 단순한 GitHub 사용자 조회 장애는 `502`로 구분하고 세션을 유지합니다.

사용자별 활성 세션은 기본 5개이며 `INTENT_TRACE_GITHUB_MAX_SESSIONS_PER_USER`로 1~100 범위에서 조정할 수 있습니다. 새 세션이 상한을 넘으면 가장 오래된 세션을 폐기합니다. 현재 `its_` 세션은 `DELETE /api/v1/session`으로 즉시 폐기할 수 있으며, 이후 같은 token 요청은 `401`을 반환합니다. 호환용 `ghu_` token은 IntentTrace가 발급한 세션이 아니므로 이 API의 대상이 아닙니다.

서버는 매 요청에서 GitHub `/user`로 사용자를 확인하고, 기록의 `repositoryKey`에 대한 권한을 GitHub의 사용자별 단건 권한 API로 조회합니다. 권한 응답의 숫자 사용자 ID가 현재 세션 주체와 일치해야 하며, 읽기 권한은 팀 공개 기록 조회, 쓰기 권한은 초안 생성과 작성자 수명주기 처리에 필요합니다. 권한 없음과 404는 접근 거부로 처리하고 public 저장소의 일반 공개 여부만으로 팀 접근을 허용하지 않습니다. `health`, `info`, 로컬 H2 콘솔은 이 필터 대상이 아닙니다.

GitHub PR에 게시할 때는 GitHub App의 client ID와 private key를 환경 변수로 전달합니다. App에는 대상 저장소의 `Metadata: read`, `Pull requests: read`, `Checks: write` 권한이 필요합니다. IntentTrace가 저장소 설치를 찾고 한 시간짜리 installation token을 자동으로 발급·갱신합니다.

```bash
export INTENT_TRACE_GITHUB_APP_CLIENT_ID='Iv1.example'
export INTENT_TRACE_GITHUB_APP_PRIVATE_KEY_BASE64="$(base64 < ~/.config/intent-trace/private-key.pem | tr -d '\n')"
./gradlew bootRun
```

기존 방식이 필요한 로컬 환경에서는 `INTENT_TRACE_GITHUB_TOKEN`에 직접 발급한 token을 넣을 수 있습니다. 이 값이 있으면 GitHub App 자동 발급보다 우선합니다.

변경 기록의 `repositoryKey`는 게시 대상과 같은 `owner/repository` 형식이어야 하며 저장할 때 소문자로 정규화합니다. 코드 근거 경로는 저장소 상대 경로만 받으며 `./`, 중복 `/`, 끝 `/`을 제거해 저장과 라인 조회에 같은 값을 사용합니다. 정규화한 경로의 구분자는 서버 운영체제와 관계없이 `/`로 유지합니다. client secret, private key와 token은 DB, 로그나 Check Run 본문에 저장하지 않습니다. private key 파일은 저장소 밖에 두고 저장소의 ignore 규칙도 방어선으로 유지합니다. 서버 게시 자격 증명과 사용자 요청 자격 증명은 서로 대체하지 않습니다. 자세한 인증 계약은 [GitHub App JWT](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app), [installation token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app), [user access token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [user token 갱신](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)을 기준으로 합니다.

## 기록 흐름

```text
코드·검증 완료
    ↓
비공개 초안 생성
    ↓ 작성자가 내용과 전체 커밋 확인
작성자 확인
    ↓ 현재 스냅샷이 같을 때만 공개
저장소 권한이 있는 팀 공개 기록
    ↓ 더 나은 공개 기록으로만 대체
대체됨
```

저장소와 코드 근거 해시는 다음 스크립트로 계산합니다.

```bash
scripts/git-evidence.sh snapshot "$(git rev-parse HEAD)"
scripts/git-evidence.sh anchor "$(git rev-parse HEAD)" src/main/kotlin/example/File.kt 10 25
```

`snapshot`은 `core.quotePath=true`로 고정한 Git 트리 출력의 SHA-256을 계산합니다. 개인 Git 설정과 관계없이 한글 파일명도 같은 해시를 만들며, Git 기본 설정으로 만든 기존 해시는 유지합니다. `anchor`는 `git cat-file blob`으로 해당 커밋의 파일 객체와 내용을 한 번에 확인하고 실제 줄 범위만 받습니다. 두 작업 모두 Git 출력과 줄 추출이 성공한 뒤에만 해시를 출력합니다. Git 객체를 읽지 못하거나 줄 추출에 실패하면 빈 내용의 해시를 출력하지 않고 실패합니다. 줄 번호는 도메인과 helper가 함께 적용하는 1~10,000,000 범위이며 시작 줄이 끝 줄보다 클 수 없습니다. 디렉터리·없는 파일·비교할 수 없는 큰 줄 번호도 해시를 출력하지 않고 실패합니다.

예전에 `core.quotePath=false`에서 한글 등 비ASCII 파일명이 포함된 기록을 만들었다면 기본 계산값이 달라질 수 있습니다. 당시 사용한 전체 커밋 ID로 아래 명령을 실행해 예전 해시를 재현합니다. 결과가 저장된 `snapshotDigest`와 같은 경우에만 그 기록의 확인·공개에 사용하며, 새 기록은 위의 `snapshot` 스크립트를 사용합니다. 기존 기록의 해시를 덮어쓰거나 불일치를 무시하지 않습니다.

```bash
legacy_revision=$(git rev-parse --verify '작성-당시-전체-커밋-ID^{commit}') &&
  git -c core.quotePath=false ls-tree -r --full-tree "$legacy_revision" | shasum -a 256 | awk '{print $1}'
```

검증을 실행하며 결과를 수집하려면 다음 도구를 사용합니다. 실행 전후 HEAD가 같고 수정·미추적 파일이 없어야 하며, 표준 출력에는 원문 대신 검증 JSON만 나옵니다. 검증 명령의 실패 종료 코드도 그대로 전달합니다.

```bash
python3 scripts/run-verification.py "$(git rev-parse HEAD)" --summary '회귀 테스트 결과 수집' -- ./gradlew test
```

서버 코드 확인과 이전 커밋의 파일 비교에는 GitHub App의 사용자 권한에 `Contents: read`를 추가해야 합니다. 이 권한이 없어도 기존 기록 조회는 사용할 수 있습니다. 서버 코드 확인은 테스트 실행 자체를 증명하지 않습니다.

## API

브라우저에서는 `/records`에서 로그인하고 저장소·검색어·상태·파일·팀 작성자 GitHub 숫자 ID로 기록을 찾습니다. 내 비공개 범위에서 폐기 상태를 선택하면 본인의 폐기 기록도 읽을 수 있습니다. `/records/{UUID}` 링크는 로그인 후 해당 기록으로 돌아옵니다. 브라우저 연결은 8시간 뒤 만료되며 서버 재시작 시 다시 로그인해야 합니다. GitHub에 생성하는 기본·대체 기록 링크도 이 화면을 엽니다.

`/records/pull-requests`에서 PR 기록과 최신 커밋의 일치 여부를, `/records/connection`에서 연결 상태를 확인합니다. 새 기록의 `/records/{UUID}/comparison`에서는 원본과 바뀐 구현 결정·출처·관련 코드·검증을 나란히 읽습니다.

`/records/history`에서는 저장소·커밋 해시·파일 경로·줄 번호로 관련 기록을 찾고 확인하지 못한 기록만 재조회할 수 있습니다. 기록 화면의 ‘GitHub 코드와 비교’는 `/records/{UUID}/evidence`를 엽니다. 서버의 코드 해시 일치와 테스트 실행 증명은 구분합니다. 용량 제한·일부 트리·지원하지 않는 Git 객체는 HTTP 422와 확인 불가 사유로 안내합니다.

`/records/sessions`에서는 내 연결의 최근 사용·만료를 보고 선택 또는 전체 종료합니다. 현재 연결을 종료하면 로그아웃됩니다. `/records/{UUID}/activities`는 작성자에게 전체 작업, 팀원에게 공개·대체 작업만 보여줍니다. 이전 본문과 수집 시작 전 이력은 복원하지 않습니다.

검색어 `q`는 REST·MCP 목록에서도 사용할 수 있습니다. 최대 200자이며 제목·요청·판단·판단 근거에서 대소문자를 구분하지 않고 찾습니다. `%`와 `_`는 입력한 문자 그대로 검색합니다. 기존 파일·작성자·상태 조건과 페이지 조회를 함께 사용할 수 있습니다.

- `GET /api/v1/change-records?repositoryKey=owner/repo&scope=TEAM`: 팀 기록 목록 (`MINE`: 내 초안)
- `GET /api/v1/change-records/{id}/comparison`: 원본과 새 기록의 내용·변경 항목 조회
- `GET /api/v1/change-records/{id}/activities`: 기록 변경 이력 (`beforeVersion`으로 이전 50개 조회)
- `POST /api/v1/change-records/{id}/successor`: 내 공개 기록과 새 관련 코드로 초안 생성
- `POST /api/v1/change-records/{id}/revise`: 초안 수정 (`expectedVersion`, 생성 요청 형식의 `content`)
- `POST /api/v1/change-records/{id}/reopen`: 비공개 확인 취소
- `POST /api/v1/change-records/{id}/discard`: 비공개 기록 폐기
- `POST /api/v1/change-records`: 비공개 초안 생성
- `GET /api/v1/change-records/{id}`: 기록 조회
- `POST /api/v1/change-records/{id}/confirm`: 작성자 확인과 커밋 해시 연결
- `POST /api/v1/change-records/{id}/publish`: 스냅샷 재확인 후 공개
- `POST /api/v1/change-records/{id}/supersede`: 새 공개 기록으로 대체
- `GET /api/v1/change-records/lookup`: 커밋·파일·줄로 공개 기록 조회
- `GET /api/v1/change-records/{id}/evidence-check`: GitHub 코드 해시 확인
- `GET /api/v1/change-records/history`: 현재 커밋·파일·줄의 관련 기록 조회
- `GET /api/v1/change-records/{id}/markdown`: 팀 공유용 Markdown 출력
- `POST /api/v1/change-records/{id}/github-pull-request`: 같은 HEAD 커밋의 PR에 Check Run 게시
- `GET /api/v1/change-records/{id}/github-pull-request`: 게시 대상별 결과·시도 이력 조회
- `POST /api/v1/change-records/{id}/github-pull-request/supersession`: 기존 Check Run에 대체 안내 반영
- `GET /api/v1/github-pull-request/records?owner=...&repository=...&pullNumber=...`: PR에 게시·시도한 기록과 HEAD 일치 조회
- `GET /api/v1/connection-diagnostics?repositoryKey=owner/repo`: 연결·권한 진단 (`revision`, `pullNumber` 선택)
- `POST /api/v1/publication-preflight?repositoryKey=owner/repo`: 관리자용 App 키·설치·발급 범위·권한 사전 점검
- `GET /api/v1/me/sessions`: 내 세션 조회
- `DELETE /api/v1/me/sessions/current`: 현재 연결 폐기
- `DELETE /api/v1/me/sessions/{sessionId}`: 선택한 내 연결 폐기
- `DELETE /api/v1/me/sessions`: 내 전체 연결 폐기

MCP는 REST와 같은 애플리케이션 서비스를 사용합니다. 기록 생성·조회·확인·공개·목록·수정·확인 취소·폐기·대체 도구와 `find_change_intent`, `find_related_change_intent`, `check_change_record_evidence`, `publish_change_record_to_github_pr`를 제공합니다. 게시 상태 조회·대체 안내는 `get_github_publication_status`, `sync_superseded_record_to_github_pr`, 세션 관리는 `list_my_sessions`, `revoke_my_session`, `revoke_all_my_sessions`를 사용합니다.

내 공개 기록으로 새 초안을 만들 때는 `create_successor_draft`를 사용합니다. 원본의 구현 결정을 복사하고 새 스냅샷 해시·관련 코드를 받으며 검증·확인 상태는 비웁니다. 원본 대체는 새 초안을 공개한 뒤 별도로 요청합니다. PR 기록 목록은 `list_pull_request_records`, 연결 진단은 `diagnose_connection`을 사용합니다.
공개 기록을 대체할 때는 후속 기록을 먼저 확인·공개한 뒤 `supersede_change_record(recordId, expectedVersion, replacementRecordId)`를 호출합니다. 같은 작성자·저장소의 공개 기록끼리만 연결하며 기존 본문·증거는 유지합니다. `expectedVersion`은 기존 기록을 조회한 값입니다. 결과가 불확실하면 상태를 다시 조회하고, 새 버전으로 무조건 재시도하지 않습니다. 이 작업은 GitHub Check Run을 자동 갱신하지 않습니다.

목록은 `repositoryKey`가 필수이며 `scope=TEAM`(기본값)은 공개·대체 기록, `scope=MY_DRAFTS`는 현재 사용자의 초안·작성자 확인 기록을 반환합니다. 선택 `path`는 코드 근거의 정확한 상대 경로, `status`는 해당 기록함 안의 상태입니다. `page`는 0부터, `size`는 기본 20·최대 50이며 응답에는 `items`, `page`, `size`, `hasNext`, `nextCursor`가 있습니다. 생성 시각·UUID 내림차순으로 조회하고 작성자·공개 상태를 SQL에서 먼저 제한합니다. 동시 생성·공개 시 페이지 구성은 달라질 수 있습니다.


새 클라이언트는 `scope=MINE`과 `cursor`·`limit`(기본 20, 최대 100)으로 다음 목록을 조회합니다. `authorId`·`q` 검색은 이 방식에서 사용할 수 있습니다. 기존 IntelliJ와 MCP 요청의 `MY_DRAFTS`·`page`·`size`도 지원하며 두 조회 방식의 입력을 섞으면 오류를 반환합니다.

예를 들어 MCP에 `list_change_records(repositoryKey="owner/repository", scope="MY_DRAFTS")`를 요청하면 내 비공개 기록을 찾습니다. `scope="TEAM", path="src/App.kt"`는 같은 파일의 여러 커밋에 남은 공개 이력을 찾습니다. 상세는 기존 `get_change_record`로 조회합니다.

REST와 MCP는 같은 생성 입력 길이·목록·중첩 값 제약을 적용합니다. 조회와 작성자 확인에 사용하는 revision은 두 경로 모두 40자 또는 64자 전체 Git 커밋 ID만 받습니다. MCP의 잘못된 변경 기록 UUID 오류에는 전달받은 원문을 포함하지 않습니다.

검증 시작·종료 시각은 DB와 같은 마이크로초 정밀도로 반올림해 저장하고 동일 요청을 비교합니다. 비밀값 제거 후 저장 길이를 초과하면 내용을 자르지 않고 입력 오류로 반환합니다. 서로 반대 방향의 동시 대체 요청은 하나만 성공하도록 두 기록을 같은 DB 트랜잭션에서 잠급니다.

같은 공개 기록을 HEAD가 같은 여러 PR에 동시에 게시해도 단일 서버에서는 Check Run을 한 번만 생성합니다. 각 PR의 HEAD 확인과 게시 이력은 따로 유지합니다.

GitHub 게시 전 PR HEAD는 전체 커밋 ID인지 확인하고, Check Run 생성·수정 응답의 ID·HEAD·`external_id`와 HTTPS URL이 요청과 일치할 때만 게시 이력을 저장합니다. 팀 공유 Markdown에서 제목·요약·판단·질문은 Markdown 문법이 아닌 일반 문장으로 처리하며, 명령·경로·심벌에 백틱이 포함돼도 하나의 코드 표현으로 유지합니다.

원본 비교는 `compare_change_record`, 관리자용 게시 사전 점검은 `check_publication_credentials`를 사용합니다. 사전 점검은 GitHub에서 설치 토큰을 발급받고 응답 권한을 확인합니다. 토큰은 메모리에만 보관하며 Check Run을 만들지 않습니다. 고정 토큰은 `CONFIGURED_UNVERIFIED`로 남깁니다.

0.12.0의 MCP 도구는 24개입니다. `list_record_activities`는 작업·처리 시각·버전을 조회하며 `nextBeforeVersion`으로 이전 이력을 읽습니다. 비교 응답의 `details`는 추가·삭제·출처·순서 변경을 보여줍니다. 중복 항목은 대응 불명확으로 남기며 브라우저에서는 `changesOnly=true`로 바뀐 필드만 볼 수 있습니다.

이전 기록 조회는 기본 30초·GitHub 코드 HTTP 호출 40회에서 중단합니다. `stopReason`이 있으면 같은 조건과 `nextCursor`로 미완료 근거부터 이어 읽고 반환된 결과에 추가합니다. `failures`의 기록을 `retryRecordId`로 다시 확인할 때는 해당 후보의 기존 결과를 교체합니다. `complete`는 이번 후보 처리 상태이며 전체 저장소 탐색 완료를 뜻하지 않습니다. 인증·권한·호출 제한 실패는 부분 결과로 숨기지 않습니다. [중단·재개 계약](docs/ADR-0007-evidence-check-and-history.md)을 참고하세요.

0.9.0부터 MCP `find_change_intent` 결과는 `{ "items": [...] }`입니다. 표준 MCP 클라이언트가 전체 도구 목록을 읽도록 최상위 출력 객체 규칙을 적용했습니다. REST `/lookup`의 배열 응답은 유지합니다.

REST와 MCP는 같은 생성·수정 입력 길이·목록·중첩 값 제약을 적용합니다. 조회와 작성자 확인에 사용하는 revision은 두 경로 모두 40자 또는 64자 전체 Git 커밋 ID만 받습니다.

작성자는 인증된 GitHub 사용자의 숫자 ID를 `github:<id>` subject로 저장하고 현재 login은 표시용으로 보존합니다. 팀 목록의 `authorId`는 조회 필터이며 작성자를 지정하는 입력이 아닙니다. `DRAFT`, `AUTHOR_CONFIRMED`, `DISCARDED`는 만든 사용자만 볼 수 있으며, `PUBLISHED`와 `SUPERSEDED`는 해당 저장소의 읽기 권한이 있는 사용자에게만 보입니다.

## Codex 플러그인

저장소 루트가 플러그인 루트입니다.

- `.codex-plugin/plugin.json`: 플러그인 메타데이터
- `.mcp.json`: 플러그인용 로컬 IntentTrace 서버와 Bearer 환경변수 연결
- `.codex/config.toml`: 프로젝트용 로컬 MCP와 Bearer 환경변수 연결
- `skills/intent-trace/SKILL.md`: 기록·조회 절차
- `skills/intent-trace-flows/SKILL.md`: 저장소 개발·GitHub 게시 불변식
- `hooks/hooks.json`: 세션 시작 시 개인정보·공개 규칙 안내

플러그인 훅은 원문 프롬프트나 도구 출력을 수집하지 않습니다. Codex에 기록 원칙만 전달합니다.
설치하거나 갱신한 뒤에는 Codex의 `/hooks`에서 `hooks/hooks.json` 내용을 확인하고 신뢰해야 세션 시작 훅이 실행됩니다. 훅을 신뢰하지 않아도 REST와 MCP 기능에는 영향이 없습니다.

## IntelliJ 플러그인

Java 21에서 플러그인 설치 ZIP을 만듭니다. 기본 빌드는 IntelliJ IDEA 2025.3.2 SDK를 내려받으므로 첫 실행에 시간이 걸릴 수 있습니다.

```bash
./gradlew --no-daemon -p intellij-plugin buildPlugin
```

IntelliJ의 `Settings > Plugins > Install Plugin from Disk`에서 `intellij-plugin/build/distributions/intent-trace-intellij-*.zip`을 선택합니다.

`Settings > Tools > IntentTrace` 또는 `Tools > IntentTrace 서버 설정`에서 연결할 서버를 지정합니다. 외부 서버는 HTTPS, 로컬 서버는 loopback HTTP를 허용합니다. `적용` 또는 `확인`을 누르면 IDE 재시작 없이 다음 요청부터 새 주소를 사용하며, 적용 전 취소한 내용은 저장하지 않습니다. 이 설정은 모든 프로젝트에 적용되고 IDE 간 설정 동기화에서는 제외합니다.

주소를 비우면 IDE를 시작할 때 전달한 `INTENT_TRACE_URL` 환경 변수를 사용하고, 그것도 없으면 `http://127.0.0.1:8080`에 연결합니다. `연결 확인`은 입력 중인 주소의 `/actuator/health`를 인증 정보 없이 호출해 `UP` 상태만 확인합니다. 주소를 저장하거나 로그인·저장소 권한·서버의 신원을 확인하는 기능은 아닙니다.

1. IntelliJ의 `Tools > IntentTrace GitHub 승인 시작`을 실행합니다. 또는 브라우저에서 서버의 `/auth/github/start`를 직접 엽니다.
2. GitHub 승인을 마치고 callback에 한 번 표시된 `its_` session token을 복사합니다.
3. IntelliJ의 `Tools > IntentTrace 세션 연결`에서 token을 저장합니다. token은 IntelliJ PasswordSafe에 보관합니다.
4. 커밋된 파일에서 줄을 선택한 뒤 편집기 우클릭 메뉴 또는 `Tools > 현재 줄 변경 의도 조회`를 실행합니다.
5. 저장소 파일을 선택하고 `Tools > IntentTrace 기록함 열기`에서 팀 공개 기록·내 비공개 기록·상태·현재 파일 필터를 고른 뒤 `조회`합니다. 기록을 선택하면 원래 커밋·당시 코드·대체 기록을 열 수 있습니다.

기록함 조회에 실패하면 필터를 마지막 성공 조건으로 되돌리고 기존 목록·선택·페이지를 유지합니다. 새 조건은 조회에 성공한 뒤 적용됩니다.

PasswordSafe 세션은 서버 주소별로 보관합니다. 주소를 바꿔도 기존 서버의 세션을 복사하거나 삭제하지 않으므로 새 서버에서 발급받은 세션을 연결해야 합니다. 연결을 지우려면 `Tools > IntentTrace 저장 세션 삭제`를 실행합니다. 플러그인은 저장된 세션을 서버에서 먼저 폐기하고 로컬 자격 증명을 삭제합니다. 서버 장애로 폐기하지 못하면 token을 유지해 다시 시도할 수 있게 합니다.

`INTENT_TRACE_SESSION_TOKEN` 환경 변수는 선택한 주소가 `INTENT_TRACE_URL`의 주소와 같을 때만 PasswordSafe의 대체 수단으로 사용합니다. `INTENT_TRACE_URL`이 없으면 기본 서버에만 적용합니다. 이 경우 PasswordSafe를 지운 뒤에도 환경 변수 세션이 남아 있음을 안내합니다.

플러그인은 현재 GitHub remote, 전체 HEAD commit, 저장소 상대 경로와 1부터 시작하는 줄 번호로 기존 공개 기록 조회 API를 호출합니다. 현재 파일에 커밋되지 않은 변경이 있으면 HEAD의 줄과 편집기 줄이 어긋날 수 있으므로 조회하지 않습니다. GitHub access·refresh token은 받거나 저장하지 않습니다.

파일 이력은 수정 중인 파일에서도 조회할 수 있습니다. 현재 줄 결과가 없으면 `이 파일의 과거 기록 보기`를 선택합니다. 이력의 코드 링크는 기록에 저장된 전체 커밋과 줄에 고정되며 현재 편집기 줄로 재해석하지 않습니다. 검증 결과도 기록 스냅샷과의 일치 여부만 뜻합니다. 이름이 바뀐 파일은 자동 추적하지 않으므로 저장소 전체 목록에서 찾아야 합니다.

연결 대기는 최대 5초로 제한합니다. 응답 데이터가 10초 동안 도착하지 않으면 조회를 중단합니다. redirect는 따라가지 않고, 성공 응답은 최대 4MiB(4,194,304바이트)까지 읽습니다. 서버의 필드별 입력 상한으로 만든 단건 기록은 한글·JSON 이스케이프를 포함해 이 범위에서 조회할 수 있습니다. 여러 기록을 함께 반환하는 현재 줄 조회의 합계가 상한을 넘으면 응답을 자르지 않고 거부합니다. 이때는 기록함에서 필요한 기록을 개별 조회합니다.
GitHub 연동과 IntelliJ 조회에서 응답 파싱이 실패하면 안전한 오류 안내만 전달하며, 응답 원문을 포함할 수 있는 원인 예외는 연결하지 않습니다.

Zed Agent에서는 공식 MCP SDK 중계기로 연결합니다. 0.12.0부터 의존성이 포함된 `.tgz`를 저장소 밖에 설치할 수 있습니다. [패키지 설치 안내](clients/zed/README.md)와 [배포 파일 생성·레지스트리 준비](docs/clients/zed-distribution.md)를 제공합니다.

저장소에서 직접 실행하려면 다음 절차를 사용합니다. Node.js 22 이상에서 설정을 생성하고 Zed 사용자 설정에 추가한 뒤 세션 입력 도구로 실행합니다. 자세한 절차와 연결 점검은 [Zed 사용 안내](docs/clients/zed.md)에 있습니다.

```bash
npm ci --prefix clients/zed --ignore-scripts
node clients/zed/intent-trace.mjs configure
node clients/zed/intent-trace.mjs configure --apply
python3 scripts/zed-with-intent-trace.py .
```

마지막 명령은 Zed CLI 설치 후 사용합니다. 입력한 토큰은 화면에 표시하지 않고 설정 파일·명령 인자에 저장하지 않습니다.

0.12.1부터 입력을 숨길 수 없으면 실행을 중단합니다. 터미널에서 실행하거나 세션을 환경 변수로 미리 전달해 주세요. 배포 파일은 작업 폴더의 설치 상태와 관계없이 잠금 파일로 의존성을 준비하고 생성 기준 해시를 함께 제공합니다.

## 검증

```bash
npm ci --prefix clients/zed --ignore-scripts
npm test --prefix clients/zed
./gradlew test
./gradlew --no-daemon -p intellij-plugin test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
scripts/validate-plugin.sh
scripts/verify-postgres.sh
python3 scripts/validate-compose.py .env.team.example
./gradlew bootJar && python3 scripts/validate-release-version.py
python3 scripts/test_validate_release_version.py
python3 scripts/test_backup_postgres.py
```

기본 테스트는 H2 PostgreSQL 호환 모드에서 실행합니다. `scripts/verify-postgres.sh`는 별도 PostgreSQL 17 container에서 Flyway·JDBC와 backup/restore 왕복을 확인합니다. `scripts/test_backup_postgres.py`는 같은 경로를 사용하는 백업이 겹쳐도 먼저 완료된 파일을 덮어쓰거나 삭제하지 않는지와 종료 신호를 받은 백업이 완료 처리되지 않는지를 확인합니다. Compose 검증은 service·network·host port·외부 image digest 경계를 확인합니다. GitHub Actions는 pull request와 `main` push에서 같은 검증과 Caddy 설정 확인을 실행합니다.

## 현재 제한

- GitHub App 등록·저장소 설치와 private key 회전은 아직 운영자가 수행해야 합니다.
- 사용자 자격 증명과 `its_` 세션은 메모리 전용이므로 서버 재시작·다중 인스턴스 간에 유지되지 않습니다.
- GitHub 승인 폐기 webhook은 제공하지 않습니다. 본인 연결 조회·폐기는 웹·REST·MCP에서 사용할 수 있습니다.
- GitHub 권한은 같은 인증 요청 안에서만 재사용하고 새 요청에서 다시 확인합니다. 요청 간 캐시와 webhook 무효화는 없습니다.
- V3 이전 초안은 `legacy:<login>` 작성자로 보존되어 자동으로 현재 GitHub 계정에 귀속되지 않습니다.
- Fork에서 생성된 PR의 Check Run 게시는 현재 지원하지 않습니다.
- IntelliJ 플러그인은 현재 줄 조회와 기록함·파일 이력을 지원하지만 callback token 자동 가져오기, 기록 생성·수정과 파일 rename 추적은 지원하지 않습니다.
- 팀 배포는 단일 인스턴스 Docker Compose만 지원하며 무중단 rolling 배포와 공유 session은 제공하지 않습니다.
- 기록 변경·게시 시도 이력은 저장하지만 인증·운영 전체 감사 로그와 자동 보존 정책은 제공하지 않습니다. 이력 수집 이전 작업과 과거 본문은 복원하지 않으며 폐기한 비공개 기록은 작성자에게 남습니다.
- 코드 확인은 일부 트리·2 MiB 초과 blob을 지원하지 않으며 테스트 실행 자체를 증명하지 않습니다.
- 이전 기록 탐색은 동일 blob의 고유한 이름 변경과 원본·현재 파일에서 한 곳에만 있는 전체 줄 조각을 연결합니다. 수정과 이름 변경이 함께 일어나거나 조각이 중복되면 자동으로 연결하지 않습니다. 후보를 페이지로 살피므로 결과가 비어 있어도 다음 커서를 확인해야 합니다.
- 과거 조회의 호출 수 설정은 7~200회입니다. 기한 안에 근거 하나도 처리하지 못하면 `resumeBlocked=true`로 안내합니다. 같은 커서를 반복하기 전에 서버 조회 제한·GitHub 응답 지연을 확인해야 합니다. 기본값은 30초·40회이며 상세 설정은 ADR-0007을 따릅니다.
- Zed Agent 연결 도구를 제공하며 인라인 IDE 메뉴·자동 기록 수집은 제공하지 않습니다. Zed 1.18.1에서 등록·도구 승인·기록 조회·세션 폐기와 재연결을 로컬 테스트 응답으로 확인했습니다. 실제 사용자 GitHub 승인은 별도 설정이 필요합니다.
- Micrometer 지표의 외부 수집기와 대시보드는 별도 연결이 필요합니다.

## 문서

- `docs/PRD-0001-intent-trace-mvp.md`
- `docs/ADR-0001-evidence-bound-change-record.md`
- `docs/PRD-0002-github-pr-publication.md`
- `docs/ADR-0002-github-check-run-publication.md`
- `docs/ADR-0003-github-app-installation-auth.md`
- `docs/PRD-0003-team-identity-and-repository-access.md`
- `docs/PRD-0004-record-management-and-evidence.md`
- `docs/ADR-0004-github-user-repository-authorization.md`
- `docs/ADR-0005-github-web-oauth-memory-session.md`
- `docs/ADR-0006-single-instance-team-deployment.md`
- `docs/ADR-0007-evidence-check-and-history.md`
- `docs/ADR-0008-publication-recovery.md`
- `docs/ADR-0009-browser-record-access.md`
- `docs/ADR-0010-zed-mcp-and-connection-diagnostics.md`
- `docs/ADR-0011-record-activity-history.md`
- `docs/clients/zed.md`
- `docs/PRD-0004-intellij-line-intent.md`
- `docs/ADR-0007-intellij-plugin-client-boundary.md`
- `docs/PRD-0005-record-browser.md`
- `docs/operations/team-deployment.md`
- `CHANGELOG.md`
- `SECURITY.md`
- `THIRD_PARTY_NOTICES.md`
- `HANDOFF.md`

## 라이선스

IntentTrace는 [Apache License 2.0](LICENSE)으로 배포합니다.
Hope HTML에 포함된 렌더러와 글꼴은 [제3자 소프트웨어 고지](THIRD_PARTY_NOTICES.md)에 적힌 별도 라이선스를 따릅니다.
