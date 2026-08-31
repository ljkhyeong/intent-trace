# IntentTrace

IntentTrace는 AI가 만든 코드에 **어떤 요청과 판단이 반영됐고, 어느 코드와 커밋에 연결되며, 무엇으로 검증했는지**를 남기는 Kotlin/Spring 프로젝트입니다. 개인이 코드를 다시 이해하는 일을 먼저 해결하고, 작성자가 확인한 기록을 팀 리뷰와 인수인계에 재사용하는 것이 목표입니다.

## 현재 MVP

- 변경 의도 초안 생성과 요청 ID 기반 멱등 처리
- `DRAFT → AUTHOR_CONFIRMED → PUBLISHED → SUPERSEDED` 수명주기
- 전체 Git 커밋 ID와 SHA-256 저장소 스냅샷 결박
- 파일·줄·콘텐츠 해시 기반 코드 근거
- 실제 검증 명령, 종료 코드, 실행 시간, 출력 해시, 결과 요약
- 작성자가 명시한 목적과 AI 추론·미확인 목적 구분
- REST API와 Spring AI Streamable HTTP MCP 도구
- PR 설명에 붙일 수 있는 Markdown 출력
- PR HEAD 검증 후 neutral GitHub Check Run 게시와 멱등 갱신
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신
- GitHub 사용자 인증과 저장소 권한 기반 팀 접근 제어
- GitHub 웹 승인과 메모리 전용 `its_` 세션·user token 자동 갱신
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 팀 배포와 backup·restore
- Codex 스킬과 세션 시작 안내 훅
- IntelliJ 현재 줄의 공개 변경 의도 조회 플러그인
- 저장소·파일·상태별 팀 공개 기록과 내 비공개 기록함, IntelliJ 과거 커밋·코드·대체 기록 탐색

원문 대화와 숨은 추론 과정은 저장하지 않습니다. 검증 원문 출력도 저장하지 않고 해시와 요약만 기록합니다.
초안 생성 시 설명 필드와 코드 심벌 이름(`symbolName`)의 token·비밀값·개인 home 절대 경로를 제거합니다. 따옴표로 감싼 비밀값 안의 이스케이프된 따옴표와 역슬래시도 처리해 값의 뒷부분이 남지 않도록 합니다.

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

서버는 매 요청에서 GitHub `/user`로 사용자를 확인하고, 기록의 `repositoryKey`에 대한 GitHub 저장소 권한을 조회합니다. 읽기 권한은 팀 공개 기록 조회, 쓰기 권한은 초안 생성과 작성자 수명주기 처리에 필요합니다. `health`, `info`, 로컬 H2 콘솔은 이 필터 대상이 아닙니다.

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

`snapshot`은 `core.quotePath=true`로 고정한 Git 트리 출력의 SHA-256을 계산합니다. 개인 Git 설정과 관계없이 한글 파일명도 같은 해시를 만들며, Git 기본 설정으로 만든 기존 해시는 유지합니다. `anchor`는 해당 커밋의 파일 객체(`blob`)와 실제 줄 범위만 받습니다. 줄 번호는 서버와 같은 1~10,000,000 범위이며 시작 줄이 끝 줄보다 클 수 없습니다. 디렉터리·없는 파일·비교할 수 없는 큰 줄 번호는 해시를 출력하지 않고 실패합니다.

예전에 `core.quotePath=false`에서 한글 등 비ASCII 파일명이 포함된 기록을 만들었다면 기본 계산값이 달라질 수 있습니다. 당시 사용한 전체 커밋 ID로 아래 명령을 실행해 예전 해시를 재현합니다. 결과가 저장된 `snapshotDigest`와 같은 경우에만 그 기록의 확인·공개에 사용하며, 새 기록은 위의 `snapshot` 스크립트를 사용합니다. 기존 기록의 해시를 덮어쓰거나 불일치를 무시하지 않습니다.

```bash
legacy_revision=$(git rev-parse --verify '작성-당시-전체-커밋-ID^{commit}') &&
  git -c core.quotePath=false ls-tree -r --full-tree "$legacy_revision" | shasum -a 256 | awk '{print $1}'
```

## API

- `GET /api/v1/change-records`: 팀 공개 기록 또는 내 비공개 기록의 페이지 조회
- `POST /api/v1/change-records`: 비공개 초안 생성
- `GET /api/v1/change-records/{id}`: 기록 조회
- `POST /api/v1/change-records/{id}/confirm`: 작성자 확인과 전체 커밋 결박
- `POST /api/v1/change-records/{id}/publish`: 스냅샷 재확인 후 공개
- `POST /api/v1/change-records/{id}/supersede`: 새 공개 기록으로 대체
- `GET /api/v1/change-records/lookup`: 커밋·파일·줄로 공개 기록 조회
- `GET /api/v1/change-records/{id}/markdown`: 팀 공유용 Markdown 출력
- `POST /api/v1/change-records/{id}/github-pull-request`: 같은 HEAD 커밋의 PR에 Check Run 게시

MCP는 같은 애플리케이션 서비스를 사용하며 `create_change_record`, `get_change_record`, `list_change_records`, `confirm_change_record`, `publish_change_record`, `supersede_change_record`, `find_change_intent`, `publish_change_record_to_github_pr`를 제공합니다.

공개 기록을 대체할 때는 후속 기록을 먼저 확인·공개한 뒤 `supersede_change_record(recordId, expectedVersion, replacementRecordId)`를 호출합니다. 같은 작성자·저장소의 공개 기록끼리만 연결하며 기존 본문·증거는 유지합니다. `expectedVersion`은 기존 기록을 조회한 값입니다. 결과가 불확실하면 상태를 다시 조회하고, 새 버전으로 무조건 재시도하지 않습니다. 이 작업은 GitHub Check Run을 자동 갱신하지 않습니다.

목록은 `repositoryKey`가 필수이며 `scope=TEAM`(기본값)은 공개·대체 기록, `scope=MY_DRAFTS`는 현재 사용자의 초안·작성자 확인 기록을 반환합니다. 선택 `path`는 코드 근거의 정확한 상대 경로, `status`는 해당 기록함 안의 상태입니다. `page`는 0부터, `size`는 기본 20·최대 50이며 응답은 `{items, page, size, hasNext}`입니다. 생성 시각·UUID 내림차순으로 조회하고 작성자·공개 상태를 SQL에서 먼저 제한합니다. 동시 생성·공개 시 페이지 구성은 달라질 수 있습니다.

예를 들어 MCP에 `list_change_records(repositoryKey="owner/repository", scope="MY_DRAFTS")`를 요청하면 내 비공개 기록을 찾습니다. `scope="TEAM", path="src/App.kt"`는 같은 파일의 여러 커밋에 남은 공개 이력을 찾습니다. 상세는 기존 `get_change_record`로 조회합니다.

REST와 MCP는 같은 생성 입력 길이·목록·중첩 값 제약을 적용합니다. 조회와 작성자 확인에 사용하는 revision은 두 경로 모두 40자 또는 64자 전체 Git 커밋 ID만 받습니다.

검증 시작·종료 시각은 DB와 같은 마이크로초 정밀도로 반올림해 저장하고 동일 요청을 비교합니다. 비밀값 제거 후 저장 길이를 초과하면 내용을 자르지 않고 입력 오류로 반환합니다. 서로 반대 방향의 동시 대체 요청은 하나만 성공하도록 두 기록을 같은 DB 트랜잭션에서 잠급니다.

같은 공개 기록을 HEAD가 같은 여러 PR에 동시에 게시해도 단일 서버에서는 Check Run을 한 번만 생성합니다. 각 PR의 HEAD 확인과 게시 이력은 따로 유지합니다.

모든 API와 MCP 입력에서 작성자 필드는 받지 않습니다. 작성자는 인증된 GitHub 사용자의 숫자 ID를 `github:<id>` subject로 저장하고 현재 login은 표시용으로 보존합니다. `DRAFT`와 `AUTHOR_CONFIRMED`는 만든 사용자만 볼 수 있으며, `PUBLISHED`와 `SUPERSEDED`는 해당 저장소의 읽기 권한이 있는 사용자에게만 보입니다.

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

PasswordSafe 세션은 서버 주소별로 보관합니다. 주소를 바꿔도 기존 서버의 세션을 복사하거나 삭제하지 않으므로 새 서버에서 발급받은 세션을 연결해야 합니다. 연결을 지우려면 `Tools > IntentTrace 저장 세션 삭제`를 실행합니다.

`INTENT_TRACE_SESSION_TOKEN` 환경 변수는 선택한 주소가 `INTENT_TRACE_URL`의 주소와 같을 때만 PasswordSafe의 대체 수단으로 사용합니다. `INTENT_TRACE_URL`이 없으면 기본 서버에만 적용합니다. 이 경우 PasswordSafe를 지운 뒤에도 환경 변수 세션이 남아 있음을 안내합니다.

플러그인은 현재 GitHub remote, 전체 HEAD commit, 저장소 상대 경로와 1부터 시작하는 줄 번호로 기존 공개 기록 조회 API를 호출합니다. 현재 파일에 커밋되지 않은 변경이 있으면 HEAD의 줄과 편집기 줄이 어긋날 수 있으므로 조회하지 않습니다. GitHub access·refresh token은 받거나 저장하지 않습니다.

파일 이력은 수정 중인 파일에서도 조회할 수 있습니다. 현재 줄 결과가 없으면 `이 파일의 과거 기록 보기`를 선택합니다. 이력의 코드 링크는 기록에 저장된 전체 커밋과 줄에 고정되며 현재 편집기 줄로 재해석하지 않습니다. 검증 결과도 기록 스냅샷과의 일치 여부만 뜻합니다. 이름이 바뀐 파일은 자동 추적하지 않으므로 저장소 전체 목록에서 찾아야 합니다.

연결 대기는 최대 5초로 제한합니다. 응답 데이터가 10초 동안 도착하지 않으면 조회를 중단합니다. redirect는 따라가지 않고, 성공 응답은 최대 4MiB(4,194,304바이트)까지 읽습니다. 서버의 필드별 입력 상한으로 만든 단건 기록은 한글·JSON 이스케이프를 포함해 이 범위에서 조회할 수 있습니다. 여러 기록을 함께 반환하는 현재 줄 조회의 합계가 상한을 넘으면 응답을 자르지 않고 거부합니다. 이때는 기록함에서 필요한 기록을 개별 조회합니다.
GitHub 연동과 IntelliJ 조회에서 응답 파싱이 실패하면 안전한 오류 안내만 전달하며, 응답 원문을 포함할 수 있는 원인 예외는 연결하지 않습니다.

## 검증

```bash
./gradlew test
./gradlew --no-daemon -p intellij-plugin test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
scripts/validate-plugin.sh
scripts/verify-postgres.sh
python3 scripts/validate-compose.py .env.team.example
./gradlew bootJar && python3 scripts/validate-release-version.py
python3 scripts/test_validate_release_version.py
```

기본 테스트는 H2 PostgreSQL 호환 모드에서 실행합니다. `scripts/verify-postgres.sh`는 별도 PostgreSQL 17 container에서 Flyway·JDBC와 backup/restore 왕복을 확인합니다. Compose 검증은 service·network·host port·외부 image digest 경계를 확인합니다. GitHub Actions는 pull request와 `main` push에서 같은 검증과 Caddy 설정 확인을 실행합니다.

## 현재 제한

- GitHub App 등록·저장소 설치와 private key 회전은 아직 운영자가 수행해야 합니다.
- 사용자 자격 증명과 `its_` 세션은 메모리 전용이므로 서버 재시작·다중 인스턴스 간에 유지되지 않습니다.
- GitHub 승인 폐기 webhook과 세션 관리 UI는 아직 제공하지 않습니다.
- GitHub 권한은 요청마다 조회하며 짧은 캐시나 webhook 기반 무효화는 아직 없습니다.
- V3 이전 초안은 `legacy:<login>` 작성자로 보존되어 자동으로 현재 GitHub 계정에 귀속되지 않습니다.
- Fork에서 생성된 PR의 Check Run 게시는 현재 지원하지 않습니다.
- IntelliJ 플러그인은 현재 줄 조회와 기록함·파일 이력을 지원하지만 callback token 자동 가져오기, 기록 생성·수정과 파일 rename 추적은 지원하지 않습니다.
- 팀 배포는 단일 인스턴스 Docker Compose만 지원하며 무중단 rolling 배포와 공유 session은 제공하지 않습니다.
- 감사 로그와 보존 정책은 아직 구현하지 않았습니다.

## 문서

- `docs/PRD-0001-intent-trace-mvp.md`
- `docs/ADR-0001-evidence-bound-change-record.md`
- `docs/PRD-0002-github-pr-publication.md`
- `docs/ADR-0002-github-check-run-publication.md`
- `docs/ADR-0003-github-app-installation-auth.md`
- `docs/PRD-0003-team-identity-and-repository-access.md`
- `docs/ADR-0004-github-user-repository-authorization.md`
- `docs/ADR-0005-github-web-oauth-memory-session.md`
- `docs/ADR-0006-single-instance-team-deployment.md`
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
