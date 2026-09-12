# IntentTrace

IntentTrace는 AI 코드의 변경 이유, 관련 커밋·코드, 검증 결과를 기록합니다. 작성자가 확인한 기록을 팀 리뷰와 인수인계에 활용합니다.

## 주요 기능

- 요청·구현 결정·관련 코드·실제 검증 결과 기록
- 초안 생성·수정, 작성자 확인, 팀 공개와 후속 기록으로 대체
- 저장소·파일·줄 검색과 과거 코드의 변경 기록 조회
- 원본과 후속 기록 비교, 기록 변경 이력 조회
- GitHub PR에 기록 게시, 게시 결과 조회와 복구
- 초안에 사용할 이슈·PR 내용과 기존 GitHub Actions 결과 조회
- 웹·Codex·IntelliJ·Zed에서 기록 사용
- GitHub 인증·세션 관리와 Docker Compose 팀 배포

원문 대화와 숨은 추론 과정은 저장하지 않습니다. 검증 원문 출력도 저장하지 않고 해시와 요약만 기록합니다.
토큰·비밀값·개인 절대 경로는 기록 전에 제거합니다. 필드별 처리는 [기록 저장 규칙](docs/ADR-0001-evidence-bound-change-record.md)을 따릅니다.

웹 기록 상세의 **Markdown 저장**으로 현재 기록을 파일로 내려받을 수 있습니다. 비공개 기록은 작성자만, 공개·대체 기록은 저장소 읽기 권한이 있는 팀원만 저장할 수 있습니다.

검색 목록에서 연 기록의 **검색 결과로 돌아가기**는 검색어·필터·페이지를 유지합니다. 변경 이력·원본 비교·코드 비교를 보거나 원본·대체 기록으로 이동해도 유지됩니다. **파일·줄 조회** 결과에서는 **당시 코드 열기**로 원본을 확인하고, 현재 위치가 확인된 경우 **조회한 커밋의 코드 열기**로 해당 줄을 확인할 수 있습니다.

## 빠른 시작

Java 21과 Codex CLI가 필요합니다. 먼저 저장소를 받고 애플리케이션을 준비합니다.

```bash
git clone https://github.com/ljkhyeong/intent-trace.git
cd intent-trace
```

GitHub Developer settings에서 GitHub App을 만들고 사용할 저장소에 설치합니다. 사용자 승인 callback은 `http://127.0.0.1:8080/auth/github/callback`으로 등록하고 `Expiring user authorization tokens`를 활성화합니다. 사용할 기능에 맞춰 [GitHub App 권한](#github-app-권한)을 부여합니다.

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
현재 커밋의 요청, 구현 결정과 이유, 관련 코드, 실행한 검증 결과를
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

## 이슈·PR 내용과 CI 결과 조회

로그인 후 기록 화면의 **이슈·PR·CI**(`/records/github`)에서 저장소와 이슈·PR 번호를 입력하면 제목·본문 발췌·출처 링크를 가져옵니다. 내용을 검토한 뒤 Agent에서 초안 작성에 활용합니다. 커밋 해시(전체 길이)로 이미 실행된 Actions 결과도 조회할 수 있습니다.

PR 내용에서 `이 PR의 변경 기록 보기`로 게시 기록을 열고, PR 기록 화면에서는 `PR 내용 가져오기`로 돌아갈 수 있습니다. CI 결과의 이전·다음 페이지와 `결과 새로고침`은 같은 저장소·커밋을 조회합니다. 새로고침은 현재 페이지를 다시 읽으며 CI를 실행하지 않습니다.

**PR 기록** 화면에서는 **이 커밋의 CI 결과 조회**로 바로 이동합니다. 링크는 화면에 표시한 PR 커밋을 기준으로 하며, 갱신된 PR의 결과가 필요하면 PR 기록을 다시 조회합니다.

- Agent: `get_github_request_context(repositoryKey, number)`, `list_github_actions_runs(repositoryKey, revision, page?)`
- REST: `GET /api/v1/github/request-context?repositoryKey=owner/repository&number=7`, `GET /api/v1/github/actions?repositoryKey=owner/repository&revision=<전체-커밋>`
- 기능별 읽기 권한은 [GitHub App 권한](#github-app-권한)을 참고하세요.

추가 서비스 가입이나 유료 API 키 없이 기존 GitHub 연결을 사용합니다. 새 CI 실행·재실행과 로그·아티팩트 저장은 하지 않으며, 기존 워크플로 실행과 서버 비용은 별개입니다. CI 결과는 로컬 검증 기록과 구분해 표시합니다. [응답·권한·출처 규칙](docs/ADR-0012-github-context-read.md)

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

홈서버 k3s는 [배포 준비 안내](docs/operations/k3s-deployment.md)를 따릅니다. 앱 1개·PostgreSQL PVC·Traefik Ingress와 환경변수 예시를 제공합니다. 이미지 빌드·DNS·공유기·TLS·GitHub App 등록·클러스터 적용은 운영자가 수행합니다.

`POST /webhooks/github`는 GitHub 승인 취소 이벤트를 받아 해당 사용자의 세션을 정리합니다. `INTENT_TRACE_GITHUB_WEBHOOK_SECRET`과 GitHub App의 Webhook URL·Secret을 설정해야 하며 비밀값을 비워 두면 수신을 거부합니다.

## 인증과 GitHub 권한

App 등록·환경 변수·로그인은 [빠른 시작](#빠른-시작)을 따릅니다. REST·MCP에는 로그인 후 발급받은 `its_` 세션 토큰을 전달합니다.

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

토큰 갱신에 실패하면 세션을 폐기하고 `401`로 재로그인을 안내합니다. GitHub 사용자 조회의 일시 장애는 `502`를 반환하며 세션을 유지합니다. 로그인 검증·갱신·대기 요청 제한은 [세션 관리 규칙](docs/ADR-0005-github-web-oauth-memory-session.md)을 참고하세요.

사용자별 활성 세션은 기본 5개이며 `INTENT_TRACE_GITHUB_MAX_SESSIONS_PER_USER`로 1~100 범위에서 조정할 수 있습니다. 새 세션이 상한을 넘으면 가장 오래된 세션을 폐기합니다. 현재 `its_` 세션은 `DELETE /api/v1/session`으로 즉시 폐기할 수 있으며, 이후 같은 token 요청은 `401`을 반환합니다. 호환용 `ghu_` token은 IntentTrace가 발급한 세션이 아니므로 이 API의 대상이 아닙니다.

서버는 매 요청에서 GitHub `/user`로 사용자를 확인하고 대상 저장소의 권한을 조회합니다. 권한 응답의 사용자 ID가 현재 사용자와 일치해야 합니다. 팀 공개 기록 조회에는 읽기 권한, 본인 기록 생성·관리에는 쓰기 권한이 필요합니다. 권한 없음과 404는 접근 거부로 처리하며, GitHub 공개 저장소도 같은 권한 검사를 거칩니다. `health`, `info`, 로컬 H2 콘솔은 이 필터 대상이 아닙니다.

### GitHub App 권한

기본 `Metadata: read`에 사용할 기능의 권한을 추가합니다. 권한을 변경하면 GitHub App 설치에 반영하고 필요하면 다시 로그인합니다.

| 기능 | 추가 권한 |
| --- | --- |
| PR 조회·내용 가져오기 | `Pull requests: read` |
| PR에 기록 게시 | `Pull requests: read`, `Checks: write` |
| GitHub 코드 비교·과거 파일 조회 | `Contents: read` |
| 이슈 내용 가져오기 | `Issues: read` |
| CI 결과 조회 | `Actions: read` |

PR 게시에는 GitHub App의 client ID와 private key가 필요합니다. [빠른 시작](#빠른-시작)의 환경 변수를 사용하며, IntentTrace가 게시할 저장소의 installation token을 자동으로 발급·갱신합니다.

기존 방식이 필요한 로컬 환경에서는 `INTENT_TRACE_GITHUB_TOKEN`에 직접 발급한 token을 넣을 수 있습니다. 이 값이 있으면 GitHub App 자동 발급보다 우선합니다.

`repositoryKey`는 소문자 `owner/repository`, 코드 경로는 저장소 상대 경로로 저장합니다. 입력 형식은 [기록 요구사항](docs/PRD-0001-intent-trace-mvp.md)을 참고하세요. 사용자 인증과 게시용 인증은 별개이며, 게시 설정은 [GitHub App 인증](docs/ADR-0003-github-app-installation-auth.md)을 따릅니다.

## 기록 흐름

```text
코드·검증 완료
    ↓
비공개 초안 생성
    ↓ 작성자가 내용과 전체 커밋 확인
작성자 확인
    ↓ 현재 스냅샷이 같을 때만 공개
저장소 권한이 있는 팀 공개 기록
    ↓ 후속 기록을 공개한 뒤 대체
대체됨
```

저장소와 코드 근거 해시는 다음 스크립트로 계산합니다.

```bash
scripts/git-evidence.sh snapshot "$(git rev-parse HEAD)"
scripts/git-evidence.sh anchor "$(git rev-parse HEAD)" src/main/kotlin/example/File.kt 10 25
```

`snapshot`은 저장소 트리, `anchor`는 지정한 코드 줄의 SHA-256을 계산합니다. 줄 범위는 `1 ≤ 시작 줄 ≤ 끝 줄 ≤ 10,000,000`입니다. 계산 규칙과 `core.quotePath=false`로 만든 [기존 해시의 재현 방법](docs/ADR-0001-evidence-bound-change-record.md#기존-스냅샷-해시-재현)은 설계 문서를 참고하세요.

검증을 실행하며 결과를 수집하려면 다음 도구를 사용합니다. 실행 전후 HEAD가 같고 수정·미추적 파일이 없어야 하며, 표준 출력에는 원문 대신 검증 JSON만 나옵니다. 검증 명령의 실패 종료 코드도 그대로 전달합니다.

```bash
python3 scripts/run-verification.py "$(git rev-parse HEAD)" --summary '회귀 테스트 결과 수집' -- ./gradlew test
```

서버 코드 확인에는 [별도 읽기 권한](#github-app-권한)이 필요합니다. 이 권한이 없어도 저장된 기록은 조회할 수 있습니다. 코드 확인은 테스트 실행 자체를 증명하지 않습니다.

## API

브라우저에서는 `/records`에서 로그인하고 저장소·검색어·상태·파일·팀 작성자 GitHub 숫자 ID로 기록을 찾습니다. 내 비공개 범위에서 폐기 상태를 선택하면 본인의 폐기 기록도 읽을 수 있습니다. `/records/{UUID}` 링크는 로그인 후 해당 기록으로 돌아옵니다. 브라우저 연결은 8시간 뒤 만료되며 서버 재시작 시 다시 로그인해야 합니다. GitHub에 생성하는 기본·대체 기록 링크도 이 화면을 엽니다.

팀 공개 범위의 `내 공개 기록만 보기`를 누르면 로그인한 작성자의 기록만 찾습니다. `작성자 필터 해제`로 팀 기록을 다시 볼 수 있습니다. 검색어·파일·상태 조건은 유지하고 첫 페이지부터 조회합니다.

GitHub 일시 장애나 호출 제한이 발생하면 오류 화면의 `다시 조회`로 같은 조건과 페이지를 다시 엽니다. 호출 제한은 안내된 대기 시간 뒤에 눌러 주세요.

`/records/pull-requests`에서 PR 기록과 최신 커밋의 일치 여부를, `/records/connection`에서 연결 상태를 확인합니다. 새 기록의 `/records/{UUID}/comparison`에서는 원본과 바뀐 구현 결정·출처·관련 코드·검증을 나란히 읽습니다.

`/records/history`에서는 저장소·커밋 해시·파일 경로·줄 번호로 관련 기록을 찾고 확인하지 못한 기록만 재조회할 수 있습니다. 기록 화면의 ‘GitHub 코드와 비교’는 `/records/{UUID}/evidence`를 엽니다. 서버의 코드 해시 일치와 테스트 실행 증명은 구분합니다. 용량 제한·일부 트리·지원하지 않는 Git 객체는 HTTP 422와 확인 불가 사유로 안내합니다.

파일·줄 조회나 PR 기록에서 상세를 열면 상단 링크로 원래 조회 조건과 페이지에 돌아갈 수 있습니다. 원본 비교·코드 확인·변경 이력을 보거나 다시 로그인한 뒤에도 유지합니다.

`/records/sessions`에서는 내 연결의 최근 사용·만료를 보고 선택 또는 전체 종료합니다. 현재 연결을 종료하면 로그아웃됩니다. `/records/{UUID}/activities`는 작성자에게 전체 작업, 팀원에게 공개·대체 작업만 보여줍니다. 이전 본문과 수집 시작 전 이력은 복원하지 않습니다.

검색어 `q`는 REST·MCP 목록에서도 사용할 수 있습니다. 최대 200자이며 제목·요청·구현 결정·이유에서 대소문자를 구분하지 않고 찾습니다. `%`와 `_`는 입력한 문자 그대로 검색합니다. 기존 파일·작성자·상태 조건과 페이지 조회를 함께 사용할 수 있습니다.

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

### MCP 도구

MCP는 REST와 같은 기능과 권한 규칙을 사용합니다.

| 작업 | 도구 |
| --- | --- |
| 초안 생성·수정 | `create_change_record`, `revise_change_record` |
| 확인·공개·폐기·대체 | `confirm_change_record`, `reopen_change_record`, `publish_change_record`, `discard_change_record`, `supersede_change_record` |
| 기록 조회·검색 | `list_change_records`, `get_change_record`, `find_change_intent`, `find_related_change_intent` |
| 후속 초안·원본 비교 | `create_successor_draft`, `compare_change_record` |
| 코드 확인·처리 이력 | `check_change_record_evidence`, `list_record_activities` |
| PR 게시·결과·복구 | `publish_change_record_to_github_pr`, `get_github_publication_status`, `sync_superseded_record_to_github_pr`, `list_pull_request_records` |
| 이슈·PR 내용과 CI 결과 | `get_github_request_context`, `list_github_actions_runs` |
| 연결 진단·게시 사전 점검 | `diagnose_connection`, `check_publication_credentials` |
| 내 세션 관리 | `list_my_sessions`, `revoke_my_session`, `revoke_all_my_sessions` |

내 공개 기록으로 새 초안을 만들 때는 `create_successor_draft`를 사용합니다. 원본의 구현 결정을 복사하고 새 스냅샷 해시·관련 코드를 받으며 검증·확인 상태는 비웁니다. 원본 대체는 새 초안을 공개한 뒤 별도로 요청합니다.
공개 기록을 대체할 때는 후속 기록을 먼저 확인·공개한 뒤 `supersede_change_record(recordId, expectedVersion, replacementRecordId)`를 호출합니다. 같은 작성자·저장소의 공개 기록끼리만 연결하며 기존 본문·코드 근거·검증 결과는 유지합니다. `expectedVersion`은 기존 기록을 조회한 값입니다. 결과가 불확실하면 상태를 다시 조회하고, 새 버전으로 무조건 재시도하지 않습니다. 이 작업은 GitHub Check Run을 자동 갱신하지 않습니다.

### 목록 조회

`repositoryKey`는 필수입니다. `scope=TEAM`(기본값)은 공개·대체 기록, `scope=MINE`은 내 초안·작성자 확인 기록을 조회합니다. `MINE`에서 `status=DISCARDED`를 지정하면 내 폐기 기록을 조회합니다. `path`는 관련 코드의 정확한 상대 경로, `status`는 선택한 범위 안의 상태로 검색합니다.

| 방식 | 입력 | 다음 목록 |
| --- | --- | --- |
| 기본 커서 조회 | `cursor`, `limit`(기본 20·최대 100), 선택 `authorId`·`q` | 응답의 `nextCursor`를 다음 요청의 `cursor`에 전달 |
| 이전 클라이언트의 페이지 번호 조회 | `MY_DRAFTS` 또는 `page`(0부터)·`size`(기본 20·최대 50) | 응답의 `hasNext`를 확인하고 `page`를 1 증가 |

두 방식의 입력은 섞지 않습니다. 페이지 번호 방식은 `items`, `page`, `size`, `hasNext`, `nextCursor` 응답을 유지합니다. 생성 시각·UUID 내림차순으로 조회하며, 조회 사이에 기록을 생성·공개하면 목록이 달라질 수 있습니다.

MCP의 `list_change_records(repositoryKey="owner/repository", scope="MINE")`은 내 비공개 기록을 찾습니다. `scope="TEAM", path="src/App.kt"`는 같은 파일의 공개 이력을 찾습니다. 상세는 `get_change_record`로 조회합니다.

REST·MCP의 생성·수정 요청에 같은 입력 제한을 적용합니다. 조회와 작성자 확인의 `revision`은 40자 또는 64자 커밋 해시만 받습니다. MCP의 잘못된 변경 기록 UUID 오류에는 입력 원문을 포함하지 않습니다.

저장값 처리와 동시 수정은 [기록 저장 규칙](docs/ADR-0001-evidence-bound-change-record.md), 중복 게시와 GitHub 응답 확인은 [Check Run 게시 규칙](docs/ADR-0002-github-check-run-publication.md)을 참고하세요.

게시 사전 점검 `check_publication_credentials`에는 `MAINTAINER` 권한이 필요합니다. GitHub에서 설치 토큰을 발급받고 응답 권한을 확인합니다. 토큰은 메모리에만 보관하며 Check Run을 만들지 않습니다. 고정 토큰은 `CONFIGURED_UNVERIFIED`로 남깁니다.

`list_record_activities`는 작업·처리 시각·버전을 조회하며 `nextBeforeVersion`으로 이전 이력을 읽습니다. 비교 응답의 `details`는 추가·삭제·출처·순서 변경을 보여줍니다. 중복 항목은 대응 불명확으로 남기며 브라우저에서는 `changesOnly=true`로 바뀐 필드만 볼 수 있습니다.

이전 기록 조회는 기본 30초·GitHub 코드 HTTP 호출 40회에서 중단합니다. `stopReason`이 있으면 같은 조건과 `nextCursor`로 미완료 근거부터 이어 읽고 반환된 결과에 추가합니다. `failures`의 기록을 `retryRecordId`로 다시 확인할 때는 해당 후보의 기존 결과를 교체합니다. `complete`는 이번 후보 처리 상태이며 전체 저장소 탐색 완료를 뜻하지 않습니다. 인증·권한·호출 제한 실패는 부분 결과로 숨기지 않습니다. [중단·재개 계약](docs/ADR-0007-evidence-check-and-history.md)을 참고하세요.

MCP `find_change_intent`는 `{ "items": [...] }`, REST `/lookup`은 배열을 반환합니다.

작성자는 인증된 GitHub 사용자의 숫자 ID를 `github:<id>` subject로 저장하고 현재 login은 표시용으로 보존합니다. 팀 목록의 `authorId`는 조회 필터이며 작성자를 지정하는 입력이 아닙니다. `DRAFT`, `AUTHOR_CONFIRMED`, `DISCARDED`는 만든 사용자만 볼 수 있으며, `PUBLISHED`와 `SUPERSEDED`는 해당 저장소의 읽기 권한이 있는 사용자에게만 보입니다.

## Codex 플러그인

저장소 루트가 플러그인 루트입니다.

- `.codex-plugin/plugin.json`: 플러그인 메타데이터
- `.mcp.json`: 플러그인용 로컬 IntentTrace 서버와 Bearer 환경변수 연결
- `.codex/config.toml`: 프로젝트용 로컬 MCP와 Bearer 환경변수 연결
- `skills/intent-trace/SKILL.md`: 기록·조회 절차
- `skills/intent-trace-flows/SKILL.md`: 기능별 개발 문서와 검증 명령

[AGENTS.md](AGENTS.md)는 공통 작업 규칙, 개발 스킬은 기능별 설계·검증, 사용 스킬은 요청받은 기록 작업을 안내합니다. 이력 조회와 게시 복구 절차는 사용 스킬의 참고 문서에서 필요할 때 읽습니다.

[GPT-6 Astra 가이드](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra)의 지시 충돌·재확인·검증 범위 권고에 따라 중복 규칙과 모든 문서를 매번 읽는 절차를 정리했습니다. 세션 시작 훅을 제거해 일반 코드 작업마다 기록 생성을 제안하지 않습니다.

## IntelliJ 플러그인

Java 21에서 플러그인 설치 ZIP을 만듭니다. 기본 빌드는 IntelliJ IDEA 2025.3.2 SDK를 내려받으므로 첫 실행에 시간이 걸릴 수 있습니다.

```bash
./gradlew -p intellij-plugin buildPlugin
```

IntelliJ의 `Settings > Plugins > Install Plugin from Disk`에서 `intellij-plugin/build/distributions/intent-trace-intellij-*.zip`을 선택합니다.

`Settings > Tools > IntentTrace` 또는 `Tools > IntentTrace 서버 설정`에서 연결할 서버를 지정합니다. 외부 서버는 HTTPS, 로컬 서버는 loopback HTTP를 허용합니다. `적용` 또는 `확인`을 누르면 IDE 재시작 없이 다음 요청부터 새 주소를 사용하며, 적용 전 취소한 내용은 저장하지 않습니다. 이 설정은 모든 프로젝트에 적용되고 IDE 간 설정 동기화에서는 제외합니다.

주소를 비우면 IDE를 시작할 때 전달한 `INTENT_TRACE_URL` 환경 변수를 사용하고, 그것도 없으면 `http://127.0.0.1:8080`에 연결합니다. `연결 확인`은 입력 중인 주소의 `/actuator/health`를 인증 정보 없이 호출해 `UP` 상태만 확인합니다. 주소를 저장하거나 로그인·저장소 권한·서버의 신원을 확인하는 기능은 아닙니다.

1. IntelliJ의 `Tools > IntentTrace GitHub 승인 시작`을 실행합니다. 또는 브라우저에서 서버의 `/auth/github/start`를 직접 엽니다.
2. GitHub 로그인 완료 화면에 한 번 표시되는 `its_` 세션 토큰을 복사합니다.
3. IntelliJ의 `Tools > IntentTrace 세션 연결`에 토큰을 입력합니다. 서버에서 계정을 확인한 뒤 PasswordSafe에 저장하고 GitHub 계정을 표시합니다. 세션 만료나 서버 장애로 확인하지 못하면 기존 저장 세션을 유지합니다.
4. 커밋된 파일에서 줄을 선택한 뒤 편집기 우클릭 메뉴 또는 `Tools > 현재 줄 변경 의도 조회`를 실행합니다. 결과 창에서 기록을 고르고 `선택 기록 열기`를 누르면 상세에서 원래 커밋·당시 코드·원본·대체 기록을 확인할 수 있습니다.
5. 저장소 파일을 선택하고 `Tools > IntentTrace 기록함 열기`에서 팀 공개 기록·내 비공개 기록·상태·현재 파일 필터를 고릅니다. 필요하면 검색어를 입력하고 `조회` 또는 Enter로 검색합니다. 기록을 선택하면 같은 상세 화면을 엽니다.

후속 기록의 `원본 기록 열기`는 초안을 만들 때 참고한 기록을 엽니다. `대체 기록 열기`는 해당 공개 기록을 대신하는 새 기록을 엽니다. 연결 정보가 있는 버튼만 사용할 수 있습니다.

상세의 `웹에서 기록 열기`로 같은 기록의 변경 이력·원본 비교·Markdown 저장을 사용할 수 있습니다. 기록을 조회한 서버의 웹 화면을 열며, 웹에 로그인하지 않았다면 먼저 로그인합니다. 커밋 없는 초안도 열 수 있습니다.

검색은 제목·요청 요약·구현 결정·결정 이유에서 최대 200자의 검색어를 찾습니다. 검색어를 비우면 검색 조건을 해제합니다. `내 비공개 기록 · 폐기`로 본인이 폐기한 기록도 확인할 수 있습니다.

기록함의 이전·다음 페이지와 `새로고침`은 현재 검색 조건을 유지합니다. 새로고침 후 선택한 기록이 목록에 남아 있으면 선택도 유지합니다. 검색어·필터를 바꿨다면 `조회`로 적용해 첫 페이지부터 확인하세요. 조회에 실패하면 마지막 성공 조건과 기존 목록·선택·페이지를 유지합니다.

설정의 `로그인 확인`을 누르면 입력한 서버의 저장 세션 또는 환경 변수 세션으로 GitHub 계정을 확인합니다. 세션이 없으면 연결 방법을, 만료됐다면 재로그인을 안내합니다. 확인만으로 서버 주소를 저장하지 않으며 저장소 권한은 기록 조회 시 확인합니다.

PasswordSafe 세션은 서버 주소별로 보관합니다. 주소를 바꿔도 기존 서버의 세션을 복사하거나 삭제하지 않으므로 새 서버에서 발급받은 세션을 연결해야 합니다. 연결을 지우려면 `Tools > IntentTrace 저장 세션 삭제`를 실행합니다. 플러그인은 저장된 세션을 서버에서 먼저 폐기하고 로컬 자격 증명을 삭제합니다. 이미 만료된 세션은 로컬 토큰만 삭제하고, 저장 세션이 없으면 서버 요청 없이 안내합니다. 서버 장애로 폐기하지 못하면 토큰을 유지해 다시 시도할 수 있게 합니다.

`INTENT_TRACE_SESSION_TOKEN` 환경 변수는 선택한 주소가 `INTENT_TRACE_URL`의 주소와 같을 때만 PasswordSafe의 대체 수단으로 사용합니다. `INTENT_TRACE_URL`이 없으면 기본 서버에만 적용합니다. 이 경우 PasswordSafe를 지운 뒤에도 환경 변수 세션이 남아 있음을 안내합니다.

플러그인은 현재 GitHub remote, 전체 HEAD commit, 저장소 상대 경로와 1부터 시작하는 줄 번호로 기존 공개 기록 조회 API를 호출합니다. 현재 파일에 커밋되지 않은 변경이 있으면 HEAD의 줄과 편집기 줄이 어긋날 수 있으므로 조회하지 않습니다. GitHub access·refresh token은 받거나 저장하지 않습니다.

파일 이력은 수정 중인 파일에서도 조회할 수 있습니다. 현재 줄 결과 창에서 `이 파일의 과거 기록 보기`를 누르면 같은 파일 경로의 과거 기록을 엽니다. 이력의 코드 링크는 기록에 저장된 전체 커밋과 줄을 가리킵니다.

파일 이름이나 줄 위치가 바뀌었다면 `웹에서 줄 이동·이름 변경 찾기`를 누르세요. 조회 당시 서버의 웹 화면에 저장소·전체 커밋·파일·줄을 전달해 기존 코드 비교를 실행합니다. 웹 로그인이 필요할 수 있으며 과거 테스트를 현재 코드의 검증으로 표시하지 않습니다. 현재 줄의 결과가 없어도 두 이력 버튼을 사용할 수 있습니다.

현재 줄 조회의 검증 결과는 조회 커밋이 다르면 `다른 커밋의 결과`로 표시합니다. 기록 상세의 스냅샷 일치 여부와 구분하세요. 검증마다 로컬 실행 도구 수집·클라이언트 제출·출처 미확인을 표시하며, 서버가 테스트 실행을 확인한 것은 아닙니다.

코드 목록의 `변경 전`은 변경 전 커밋을, `변경 후`는 변경 후 커밋을 엽니다. 선택한 쪽의 커밋이 없으면 `당시 코드 열기`가 비활성화됩니다. 변경 전 커밋이 있는 초안은 작성자 확인 전에도 이전 코드를 열 수 있습니다.

연결 대기는 최대 5초로 제한합니다. 응답 데이터가 10초 동안 도착하지 않으면 조회를 중단합니다. redirect는 따라가지 않고, 성공 응답은 최대 4MiB(4,194,304바이트)까지 읽습니다. 서버의 필드별 입력 상한으로 만든 단건 기록은 한글·JSON 이스케이프를 포함해 이 범위에서 조회할 수 있습니다. 여러 기록을 함께 반환하는 현재 줄 조회의 합계가 상한을 넘으면 응답을 자르지 않고 거부합니다. 이때는 기록함에서 필요한 기록을 개별 조회합니다.
호출 제한이 발생하면 서버가 알려준 대기 시간 뒤에 직접 다시 시도하세요. 대기 시간을 읽을 수 없으면 잠시 후 재시도하도록 안내하며 자동으로 요청하지 않습니다. 세션 해제가 호출 제한으로 실패하면 저장된 세션은 유지됩니다.
GitHub 연동과 IntelliJ 조회에서 응답 파싱이 실패하면 응답 원문 없이 형식 오류만 안내합니다.

Zed Agent에서는 공식 MCP SDK 중계기로 연결합니다. 0.12.0부터 의존성이 포함된 `.tgz`를 저장소 밖에 설치할 수 있습니다. [패키지 설치 안내](clients/zed/README.md)와 [배포 파일 생성·레지스트리 준비](docs/clients/zed-distribution.md)를 제공합니다.

Zed 연결 점검의 `check <MCP 주소> <owner/repo>`에 `--pr <번호>`와 `--revision <전체 커밋 해시>`를 추가하면 PR·코드 읽기 권한도 확인할 수 있습니다. PR만 지정하면 해당 PR의 현재 커밋으로 확인합니다.

`node clients/zed/intent-trace.mjs --help`로 전체 사용법을, `check --help`로 점검 옵션을 확인하세요. 도움말에는 서버 연결이나 세션이 필요 없습니다.

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

로컬 수정·실패 재현·작업 재개는 [로컬 검증 절차](docs/development/verification.md)를 따릅니다. 아래는 릴리스·CI의 전체 검증 목록입니다.

파일 작성 직후에는 `python3 scripts/feedback.py files <파일...>`, 작업 종료 전에는 `python3 scripts/feedback.py finish --base <작업 시작 커밋>`을 실행합니다. 최종 검사에는 작업 중 커밋·stage·작업 파일·새 파일과 서버 계층 의존 규칙을 포함합니다. 출력된 `review.diff` 전체를 검토한 뒤 작업을 마칩니다. Codex 자동 실행은 프로젝트 훅을 불러오고 `/hooks`에서 신뢰 승인한 뒤 적용됩니다.

```bash
npm ci --prefix clients/zed --ignore-scripts
npm test --prefix clients/zed
./gradlew test
./gradlew -p intellij-plugin test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
scripts/validate-plugin.sh
scripts/verify-postgres.sh
python3 scripts/validate-compose.py .env.team.example
./gradlew bootJar && python3 scripts/validate-release-version.py
python3 scripts/test_validate_release_version.py
python3 scripts/test_backup_postgres.py
python3 scripts/test_test_summary.py
python3 scripts/test_feedback.py
```

기본 테스트는 H2 PostgreSQL 호환 모드에서 실행합니다. `scripts/verify-postgres.sh`는 PostgreSQL 17에서 Flyway·JDBC와 백업·복구를 확인합니다. 백업 검증은 동시 실행과 중단 시 기존 파일이 보존되는지 확인합니다. Compose 검증은 서비스·네트워크 구성, 외부 포트와 이미지 해시를 확인합니다. GitHub Actions는 PR과 `main` push에서 같은 검증과 Caddy 설정 확인을 실행합니다.

## 현재 제한

- GitHub App 등록·저장소 설치와 private key 교체는 운영자가 해야 합니다.
- 사용자 자격 증명과 `its_` 세션은 메모리 전용이므로 서버 재시작·다중 인스턴스 간에 유지되지 않습니다.
- GitHub 웹훅은 사용자 승인 취소만 처리합니다. PR·CI 이벤트의 자동 기록 생성·게시는 제공하지 않습니다.
- GitHub 권한은 같은 인증 요청 안에서만 재사용하고 새 요청에서 다시 확인합니다. 요청 간 권한 캐시는 없습니다.
- V3 이전 초안의 작성자는 `legacy:<login>`으로 남으며 현재 GitHub 계정과 자동으로 연결되지 않습니다.
- Fork에서 생성된 PR의 Check Run 게시는 현재 지원하지 않습니다.
- IntelliJ 플러그인은 현재 줄 조회·기록함·파일 이력과 웹 코드 이동 조회를 지원합니다. 로그인 토큰 자동 가져오기와 기록 생성·수정은 지원하지 않습니다.
- Compose와 k3s 배포는 앱 1개만 지원하며 무중단 롤링 배포와 서버 간 세션 공유는 제공하지 않습니다.
- 기록 변경·게시 시도 이력은 저장하지만 인증·운영 전체 감사 로그와 자동 보존 정책은 제공하지 않습니다. 이력 수집 이전 작업과 과거 본문은 복원하지 않으며 폐기한 비공개 기록은 작성자에게 남습니다.
- 코드 확인은 일부 트리·2 MiB 초과 blob을 지원하지 않으며 테스트 실행 자체를 증명하지 않습니다.
- 이전 기록 탐색은 동일 blob의 고유한 이름 변경과 원본·현재 파일에서 한 곳에만 있는 전체 줄 조각을 연결합니다. 수정과 이름 변경이 함께 일어나거나 조각이 중복되면 자동으로 연결하지 않습니다. 후보를 페이지로 살피므로 결과가 비어 있어도 다음 커서를 확인해야 합니다.
- 과거 조회의 호출 수 설정은 7~200회입니다. 기한 안에 근거 하나도 처리하지 못하면 `resumeBlocked=true`로 안내합니다. 같은 커서를 반복하기 전에 서버 조회 제한·GitHub 응답 지연을 확인해야 합니다. 기본값은 30초·40회이며 상세 설정은 [코드 확인과 이전 기록 조회](docs/ADR-0007-evidence-check-and-history.md)를 따릅니다.
- Zed Agent 연결 도구를 제공하며 인라인 IDE 메뉴·자동 기록 수집은 제공하지 않습니다. Zed 1.18.1에서 등록·도구 승인·기록 조회·세션 폐기와 재연결을 로컬 테스트 응답으로 확인했습니다. 실제 사용자 GitHub 승인은 별도 설정이 필요합니다.
- Micrometer 지표의 외부 수집기와 대시보드는 별도 연결이 필요합니다.

## 문서

- [MVP 요구사항](docs/PRD-0001-intent-trace-mvp.md)
- [커밋·코드 해시와 기록 저장](docs/ADR-0001-evidence-bound-change-record.md)
- [GitHub PR 게시 요구사항](docs/PRD-0002-github-pr-publication.md)
- [Check Run 게시 규칙](docs/ADR-0002-github-check-run-publication.md)
- [GitHub App 인증](docs/ADR-0003-github-app-installation-auth.md)
- [사용자·저장소 권한](docs/PRD-0003-team-identity-and-repository-access.md)
- [기록 관리와 조회](docs/PRD-0004-record-management-and-evidence.md)
- [GitHub 사용자 인증](docs/ADR-0004-github-user-repository-authorization.md)
- [메모리 세션](docs/ADR-0005-github-web-oauth-memory-session.md)
- [단일 서버 배포 구성](docs/ADR-0006-single-instance-team-deployment.md)
- [코드 확인과 이전 기록 조회](docs/ADR-0007-evidence-check-and-history.md)
- [GitHub 게시 복구](docs/ADR-0008-publication-recovery.md)
- [브라우저 기록 열람](docs/ADR-0009-browser-record-access.md)
- [Zed MCP 연결과 진단](docs/ADR-0010-zed-mcp-and-connection-diagnostics.md)
- [기록 변경 이력](docs/ADR-0011-record-activity-history.md)
- [이슈·PR 내용과 CI 결과 조회](docs/ADR-0012-github-context-read.md)
- [Zed 사용 안내](docs/clients/zed.md)
- [IntelliJ 현재 줄 조회](docs/PRD-0004-intellij-line-intent.md)
- [IntelliJ 통신·인증](docs/ADR-0007-intellij-plugin-client-boundary.md)
- [기록함과 파일 이력](docs/PRD-0005-record-browser.md)
- [팀 배포 운영](docs/operations/team-deployment.md)
- [변경 이력](CHANGELOG.md)
- [보안 정책](SECURITY.md)
- [제3자 소프트웨어 고지](THIRD_PARTY_NOTICES.md)
- [작업 인계](HANDOFF.md)

## 라이선스

IntentTrace는 [Apache License 2.0](LICENSE)으로 배포합니다.
Hope HTML에 포함된 렌더러와 글꼴은 [제3자 소프트웨어 고지](THIRD_PARTY_NOTICES.md)에 적힌 별도 라이선스를 따릅니다.
