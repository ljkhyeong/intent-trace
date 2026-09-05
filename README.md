# IntentTrace

IntentTrace는 AI가 만든 코드에 **어떤 요청과 판단이 반영됐고, 어느 코드와 커밋에 연결되며, 무엇으로 검증했는지**를 남기는 Kotlin/Spring 프로젝트입니다. 개인이 코드를 다시 이해하는 일을 먼저 해결하고, 작성자가 확인한 기록을 팀 리뷰와 인수인계에 재사용하는 것이 목표입니다.

## 현재 MVP

- 변경 의도 초안 생성과 최초 내용 해시 기반 멱등 처리
- 초안 수정·확인 취소·폐기와 작성자 전용 목록
- 내 공개 기록의 판단을 재사용하는 후속 초안과 원본 비교
- 저장소·파일·작성자별 팀 기록 요약과 커서 페이지 조회
- 브라우저 로그인 후 기록 열람과 제목·요청·판단 내용 검색
- 웹 파일·줄 조회, 코드 근거 확인, 변경된 항목만 보는 세부 비교
- `DRAFT → AUTHOR_CONFIRMED → PUBLISHED → SUPERSEDED` 수명주기
- 전체 Git 커밋 ID와 SHA-256 저장소 스냅샷 결박
- 변경 전후 커밋·파일·줄·콘텐츠 해시 기반 코드 근거와 이름 변경 연결
- GitHub 전체 트리·blob으로 코드 근거를 확인하는 읽기 API
- 이전 커밋의 관련 기록·파일 이름 변경·같은 코드 조각의 줄 이동 조회
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
- GitHub 웹 승인과 메모리 전용 `its_` 세션·user token 자동 갱신
- 내 세션 조회·선택 폐기·전체 폐기
- 웹에서 내 연결 조회·선택 종료·전체 로그아웃
- 기록 변경 이력과 작성자·팀원별 노출 범위
- GitHub 호출 제한 대기 시간 안내와 기능별 Micrometer 지표
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 팀 배포와 backup·restore
- Codex 스킬과 세션 시작 안내 훅
- Zed Agent용 MCP 중계기·설정 생성·연결 점검·세션 입력 실행 도구

원문 대화와 숨은 추론 과정은 저장하지 않습니다. 검증 원문 출력도 저장하지 않고 해시와 요약만 기록합니다.

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
docker compose --env-file .env.team up -d --build
curl http://localhost:8080/actuator/health
```

기본 예시는 로컬 HTTP 검증용입니다. 팀 domain을 사용할 때는 `.env.team`의 `INTENT_TRACE_SITE_ADDRESS`, 공개 port와 `INTENT_TRACE_GITHUB_CALLBACK_URL`을 실제 HTTPS origin으로 바꿉니다. callback은 GitHub App에 등록한 값과 정확히 같아야 합니다. 자세한 기동·TLS·backup·restore 절차는 [`docs/operations/team-deployment.md`](docs/operations/team-deployment.md)에 있습니다.

PostgreSQL에는 변경 기록과 게시 이력만 저장합니다. GitHub access·refresh token과 `its_` session은 계속 애플리케이션 메모리에만 있으므로 app container를 다시 만들면 사용자가 GitHub 승인을 다시 해야 합니다.

REST와 MCP 요청에는 IntentTrace 로컬 세션이 필요합니다. 먼저 GitHub App 설정에서 다음 항목을 준비합니다.

- 사용자 승인 callback URL: `http://127.0.0.1:8080/auth/github/callback`
- `Expiring user authorization tokens`: 활성화
- App client ID와 client secret

callback URL은 GitHub App에 등록한 값과 환경 변수 값을 정확히 맞추고 wildcard callback은 사용하지 않습니다. IntentTrace는 `state`와 PKCE `S256`을 함께 검증합니다. 설정한 뒤 서버를 시작하고 브라우저에서 `http://127.0.0.1:8080/auth/github/start`를 엽니다.

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

서버는 매 요청에서 GitHub `/user`로 사용자를 확인하고, 기록의 `repositoryKey`에 대한 GitHub 저장소 권한을 조회합니다. 읽기 권한은 팀 공개 기록 조회, 쓰기 권한은 초안 생성과 작성자 수명주기 처리에 필요합니다. `health`, `info`, 로컬 H2 콘솔은 이 필터 대상이 아닙니다.

GitHub PR에 게시할 때는 GitHub App의 client ID와 private key를 환경 변수로 전달합니다. App에는 대상 저장소의 `Metadata: read`, `Pull requests: read`, `Checks: write` 권한이 필요합니다. IntentTrace가 저장소 설치를 찾고 한 시간짜리 installation token을 자동으로 발급·갱신합니다.

```bash
export INTENT_TRACE_GITHUB_APP_CLIENT_ID='Iv1.example'
export INTENT_TRACE_GITHUB_APP_PRIVATE_KEY_BASE64="$(base64 < ~/.config/intent-trace/private-key.pem | tr -d '\n')"
./gradlew bootRun
```

기존 방식이 필요한 로컬 환경에서는 `INTENT_TRACE_GITHUB_TOKEN`에 직접 발급한 token을 넣을 수 있습니다. 이 값이 있으면 GitHub App 자동 발급보다 우선합니다.

변경 기록의 `repositoryKey`는 게시 대상과 같은 `owner/repository` 형식이어야 하며 저장할 때 소문자로 정규화합니다. client secret, private key와 token은 DB, 로그나 Check Run 본문에 저장하지 않습니다. private key 파일은 저장소 밖에 두고 저장소의 ignore 규칙도 방어선으로 유지합니다. 서버 게시 자격 증명과 사용자 요청 자격 증명은 서로 대체하지 않습니다. 자세한 인증 계약은 [GitHub App JWT](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app), [installation token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app), [user access token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [user token 갱신](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)을 기준으로 합니다.

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

검증을 실행하며 결과를 수집하려면 다음 도구를 사용합니다. 실행 전후 HEAD가 같고 수정·미추적 파일이 없어야 하며, 표준 출력에는 원문 대신 검증 JSON만 나옵니다. 검증 명령의 실패 종료 코드도 그대로 전달합니다.

```bash
python3 scripts/run-verification.py "$(git rev-parse HEAD)" --summary '회귀 테스트 결과 수집' -- ./gradlew test
```

서버 코드 확인과 이전 커밋의 파일 비교에는 GitHub App의 사용자 권한에 `Contents: read`를 추가해야 합니다. 이 권한이 없어도 기존 기록 조회는 사용할 수 있습니다. 서버 코드 확인은 테스트 실행 자체를 증명하지 않습니다.

## API

브라우저에서는 `/records`에서 로그인하고 저장소·검색어로 기록을 찾습니다. `/records/{UUID}` 링크는 로그인 후 해당 기록으로 돌아옵니다. 브라우저 연결은 8시간 뒤 만료되며 서버 재시작 시 다시 로그인해야 합니다. GitHub에 생성하는 기본·대체 기록 링크도 이 화면을 엽니다.

`/records/pull-requests`에서 PR 기록과 현재 커밋 일치를, `/records/connection`에서 연결 상태를 확인합니다. 후속 기록의 `/records/{UUID}/comparison`에서는 원본과 바뀐 판단·출처·근거·검증을 나란히 읽습니다.

`/records/history`에서는 저장소·전체 커밋·파일·줄로 관련 기록을 찾고 확인하지 못한 후보만 재조회할 수 있습니다. 기록 화면의 코드 확인 링크는 `/records/{UUID}/evidence`를 엽니다. 서버의 코드 해시 일치와 테스트 실행 증명은 구분합니다.

`/records/sessions`에서는 내 연결의 최근 사용·만료를 보고 선택 또는 전체 종료합니다. 현재 연결을 종료하면 로그아웃됩니다. `/records/{UUID}/activities`는 작성자에게 전체 작업, 팀원에게 공개·대체 작업만 보여줍니다. 이전 본문과 수집 시작 전 이력은 복원하지 않습니다.

검색어 `q`는 REST·MCP 목록에서도 사용할 수 있습니다. 최대 200자이며 제목·요청·판단·판단 근거에서 대소문자를 구분하지 않고 찾습니다. `%`와 `_`는 입력한 문자 그대로 검색합니다. 기존 파일·작성자·상태 조건과 페이지 조회를 함께 사용할 수 있습니다.

- `GET /api/v1/change-records?repositoryKey=owner/repo&scope=TEAM`: 팀 기록 목록 (`MINE`: 내 초안)
- `GET /api/v1/change-records/{id}/comparison`: 원본·후속 기록 내용과 변경 항목 조회
- `GET /api/v1/change-records/{id}/activities`: 기록 변경 이력 (`beforeVersion`으로 이전 50개 조회)
- `POST /api/v1/change-records/{id}/successor`: 내 공개 기록에서 새 근거의 후속 초안 생성
- `POST /api/v1/change-records/{id}/revise`: 초안 수정 (`expectedVersion`, 생성 요청 형식의 `content`)
- `POST /api/v1/change-records/{id}/reopen`: 비공개 확인 취소
- `POST /api/v1/change-records/{id}/discard`: 비공개 기록 폐기
- `POST /api/v1/change-records`: 비공개 초안 생성
- `GET /api/v1/change-records/{id}`: 기록 조회
- `POST /api/v1/change-records/{id}/confirm`: 작성자 확인과 전체 커밋 결박
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

후속 초안은 `create_successor_draft`, PR 기록 목록은 `list_pull_request_records`, 연결 진단은 `diagnose_connection`을 사용합니다. 후속 초안은 원본의 판단을 복사하고 새 스냅샷·코드 근거를 받으며 검증·확인 상태는 비웁니다. 원본 대체는 새 초안을 공개한 뒤 별도로 요청합니다.

원본 비교는 `compare_change_record`, 관리자용 게시 사전 점검은 `check_publication_credentials`를 사용합니다. 사전 점검은 메모리에서 설치 token을 발급하고 응답 권한을 확인하며 Check Run을 만들지 않습니다. 고정 token은 `CONFIGURED_UNVERIFIED`로 남깁니다.

0.11.0의 MCP 도구는 24개입니다. `list_record_activities`는 작업·처리 시각·버전을 조회하며 `nextBeforeVersion`으로 이전 이력을 읽습니다. 비교 응답의 `details`는 추가·삭제·출처·순서 변경을 보여줍니다. 중복 항목은 대응 불명확으로 남기며 브라우저에서는 `changesOnly=true`로 바뀐 필드만 볼 수 있습니다.

이전 기록 조회의 `complete`는 현재 후보 페이지에 확인 실패가 없는지 표시합니다. `failures`의 기록 ID와 사유를 확인하고 같은 조회 조건에 `retryRecordId`를 지정해 해당 기록만 재조회할 수 있습니다. 재조회에는 `cursor`를 함께 넣지 않습니다. 인증·권한·호출 제한 실패는 부분 결과로 숨기지 않습니다.

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

Zed Agent에서는 저장소의 공식 MCP SDK 중계기로 연결합니다. Node.js 22 이상에서 설정을 생성하고 Zed 사용자 설정에 추가한 뒤 세션 입력 도구로 실행합니다. 자세한 절차와 연결 점검은 [Zed 사용 안내](docs/clients/zed.md)에 있습니다.

```bash
npm ci --prefix clients/zed --ignore-scripts
node clients/zed/intent-trace.mjs configure
node clients/zed/intent-trace.mjs configure --apply
python3 scripts/zed-with-intent-trace.py .
```

마지막 명령은 Zed CLI 설치 후 사용합니다. 실제 token은 숨긴 입력으로 받고 설정 파일·명령 인자에 넣지 않습니다.

## 검증

```bash
npm ci --prefix clients/zed --ignore-scripts
npm test --prefix clients/zed
./gradlew test
scripts/validate-plugin.sh
scripts/verify-postgres.sh
```

기본 테스트는 H2 PostgreSQL 호환 모드에서 실행합니다. `scripts/verify-postgres.sh`는 별도 PostgreSQL 17 container에서 Flyway·JDBC와 backup/restore 왕복을 확인합니다. GitHub Actions는 pull request와 `main` push에서 이 검증을 실행합니다.

## 현재 제한

- GitHub App 등록·저장소 설치와 private key 회전은 아직 운영자가 수행해야 합니다.
- 사용자 자격 증명과 `its_` 세션은 메모리 전용이므로 서버 재시작·다중 인스턴스 간에 유지되지 않습니다.
- GitHub 승인 폐기 webhook은 제공하지 않습니다. 본인 연결 조회·폐기는 웹·REST·MCP에서 사용할 수 있습니다.
- GitHub 권한은 같은 인증 요청 안에서만 재사용하고 새 요청에서 다시 확인합니다. 요청 간 캐시와 webhook 무효화는 없습니다.
- V3 이전 초안은 `legacy:<login>` 작성자로 보존되어 자동으로 현재 GitHub 계정에 귀속되지 않습니다.
- Fork에서 생성된 PR의 Check Run 게시는 현재 지원하지 않습니다.
- IntelliJ 라인 조회 플러그인은 다음 단계입니다.
- 팀 배포는 단일 인스턴스 Docker Compose만 지원하며 무중단 rolling 배포와 공유 session은 제공하지 않습니다.
- 기록 변경·게시 시도 이력은 저장하지만 인증·운영 전체 감사 로그와 자동 보존 정책은 제공하지 않습니다. 이력 수집 이전 작업과 과거 본문은 복원하지 않으며 폐기한 비공개 기록은 작성자에게 남습니다.
- 코드 확인은 일부 트리·2 MiB 초과 blob을 지원하지 않으며 테스트 실행 자체를 증명하지 않습니다.
- 이전 기록 탐색은 동일 blob의 고유한 이름 변경과 원본·현재 파일에서 한 곳에만 있는 전체 줄 조각을 연결합니다. 수정과 이름 변경이 함께 일어나거나 조각이 중복되면 자동으로 연결하지 않습니다. 후보를 페이지로 살피므로 결과가 비어 있어도 다음 커서를 확인해야 합니다.
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
- `docs/operations/team-deployment.md`
- `HANDOFF.md`
