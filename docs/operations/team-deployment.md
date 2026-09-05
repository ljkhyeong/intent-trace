# IntentTrace 팀 배포 운영 절차

## 전제 조건

- Docker Engine과 Docker Compose plugin이 설치돼 있어야 한다.
- 실제 HTTPS 배포는 domain의 DNS가 host를 가리키고 외부 80·443이 Caddy에 도달해야 한다.
- GitHub App callback URL은 배포할 `https://domain/auth/github/callback`과 정확히 같아야 한다.
- 이 구성은 app 한 개만 지원한다. app이 재시작되면 모든 사용자가 GitHub 승인을 다시 해야 한다.

## 환경 파일

```bash
cp .env.team.example .env.team
chmod 600 .env.team
```

실제 domain 배포에서는 다음 값을 함께 맞춘다.

```dotenv
INTENT_TRACE_SITE_ADDRESS=intent.example.com
INTENT_TRACE_HTTP_PORT=80
INTENT_TRACE_HTTPS_PORT=443
INTENT_TRACE_GITHUB_CALLBACK_URL=https://intent.example.com/auth/github/callback
```

`INTENT_TRACE_DATABASE_PASSWORD`, GitHub App client secret과 Base64 private key는 실제 값으로 교체한다. `.env.team`을 Git, issue, 채팅이나 backup에 넣지 않는다.

## 기동과 확인

```bash
docker compose --env-file .env.team config --quiet
docker compose --env-file .env.team up -d --build
docker compose --env-file .env.team ps
curl --fail https://intent.example.com/actuator/health
```

로컬에서는 `.env.team.example`의 기본값을 사용하고 `http://localhost:8080/actuator/health`로 확인한다. app과 PostgreSQL은 host port가 없으므로 Caddy를 통해서만 접근한다.

GitHub 승인은 `https://intent.example.com/auth/github/start`에서 시작한다. callback 화면의 `its_` token을 Codex가 시작되는 환경의 `INTENT_TRACE_SESSION_TOKEN`에 넣는다.

팀 Codex 프로젝트의 `.codex/config.toml`에는 로컬 플러그인 서버와 구분되는 이름으로 팀 MCP를 등록한다.

```toml
[mcp_servers.intent-trace-team]
url = "https://intent.example.com/mcp"
bearer_token_env_var = "INTENT_TRACE_SESSION_TOKEN"
```

`codex mcp list`에서 URL과 서버 이름을 확인한다. 로컬 플러그인의 `intent-trace` 서버가 필요하지 않으면 Codex MCP 서버 설정에서 비활성화해 요청 대상을 하나로 유지한다. session token 원문을 TOML에 직접 쓰지 않는다.

## 상태와 로그

```bash
docker compose --env-file .env.team ps
docker compose --env-file .env.team logs --tail=200 app
docker compose --env-file .env.team logs --tail=200 caddy
docker compose --env-file .env.team logs --tail=200 postgres
```

로그를 공유하기 전에 Authorization header, callback query, 환경 변수와 token이 없는지 확인한다.

## Backup

```bash
INTENT_TRACE_ENV_FILE=.env.team scripts/backup-postgres.sh
```

기본 출력은 `backups/intent-trace-<UTC 시각>.dump`이며 기존 파일을 덮어쓰지 않는다. backup에는 변경 요청·판단·검증 요약이 포함될 수 있으므로 별도 암호화 저장소로 옮기고 접근 권한을 제한한다. GitHub access·refresh token과 `its_` session은 DB에 없으므로 포함되지 않는다.

## Restore

복구는 현재 DB object를 교체한다. 먼저 새 backup을 만들고 app과 Caddy를 중지한다.

```bash
INTENT_TRACE_ENV_FILE=.env.team scripts/backup-postgres.sh backups/before-restore.dump
docker compose --env-file .env.team stop caddy app
INTENT_TRACE_ENV_FILE=.env.team scripts/restore-postgres.sh backups/restore-target.dump --confirm-replace
docker compose --env-file .env.team up -d app caddy
curl --fail https://intent.example.com/actuator/health
```

복구 뒤 Flyway version과 주요 변경 기록을 확인한다. app을 다시 시작했으므로 사용자는 GitHub 승인을 다시 해야 한다.

## Upgrade

```bash
INTENT_TRACE_ENV_FILE=.env.team scripts/backup-postgres.sh
git pull --ff-only
docker compose --env-file .env.team up -d --build
docker compose --env-file .env.team ps
```

현재 구성은 rolling 배포가 아니다. image 교체 동안 짧은 중단이 발생하고 기존 `its_` session은 사라진다.

## 호출 제한과 기능 지표

GitHub 호출 제한은 `429`와 `Retry-After` 초 단위 값으로 반환한다. 표시한 대기 시간이 지난 뒤 원래 요청을 다시 시도한다. `403`만으로 호출 제한이라고 판단하지 않으며 GitHub의 제한 관련 헤더도 함께 확인한다. 자동으로 반복 호출하지 않는다.

애플리케이션은 Micrometer에 다음 지표를 수집한다. 저장소·사용자·token·원문 요청·코드 경로는 label에 넣지 않는다.

- `intenttrace.github.request`: GitHub 작업 종류와 결과별 호출 수·응답 헤더까지의 지연. `outcome=rate_limited`로 호출 제한을 구분한다.
- `intenttrace.record.operation`: 생성·수정·확인 취소·폐기·확인·공개·대체의 성공 수. 같은 생성 요청의 재시도는 새 생성으로 집계하지 않는다.
- `intenttrace.publication.attempt`: 게시·대체 안내별 성공·실패·결과 미확인 수.

현재 외부 Actuator 노출은 health·info이며 지표 외부 수집기와 대시보드는 별도로 연결해야 한다. 지표는 영구 감사 로그를 대신하지 않는다. 세션은 `/records/sessions`, `/api/v1/me/sessions`와 MCP에서 본인이 직접 폐기할 수 있으며 DB backup에는 포함되지 않는다.

0.11.0의 Flyway V9는 기록 변경 이력을 제품 데이터로 추가한다. 기존 backup에 함께 포함되며 검증 스크립트는 복구 전후 기록 수와 변경 이력 수를 모두 비교한다. 기존 기록의 수집 이전 이력은 소급 생성하지 않는다. 자격 증명·세션은 계속 메모리에만 둔다.

`RESULT_UNKNOWN` 게시 시도는 실패로 확정된 상태가 아니다. 기록의 게시 상태를 조회하고 원래 게시 도구를 재실행하면 기존 Check Run을 찾아 갱신한다. `SUPERSEDED` 기록의 안내 갱신은 별도 supersession 경로로 재시도한다.
