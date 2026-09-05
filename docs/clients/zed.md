# Zed에서 IntentTrace 사용하기

확인일: 2026-09-05 · IntentTrace 0.11.0

## 지원 범위

Zed Agent에서 IntentTrace의 조회·초안·확인·공개·PR 기록·연결 진단 도구를 사용한다. [Zed의 로컬 MCP 서버 설정](https://zed.dev/docs/ai/mcp)에 저장소의 연결 도구를 등록한다. Node.js 22 이상과 Python 3이 필요하다.

코드 줄의 인라인 메뉴, 자동 기록 수집, Codex 훅 실행은 제공하지 않는다. 사용자가 Agent에 저장소·전체 커밋·상대 경로·줄을 전달한다. Codex 플러그인 파일을 Zed 확장으로 설치할 필요는 없다.

## 처음 연결하기

1. IntentTrace 서버를 실행하고 브라우저에서 `/auth/github/start`를 열어 `its_` 세션을 받는다. `/records`의 로그인 cookie는 MCP용이 아니다.
2. IntentTrace 저장소에서 의존성을 설치하고 설정 변경을 미리 확인한다.

```bash
npm ci --prefix clients/zed --ignore-scripts
node clients/zed/intent-trace.mjs configure
```

팀 서버는 두 번째 명령의 끝에 `https://intent.example.com/mcp`처럼 실제 서버 주소를 넣는다. 설정에는 현재 Node와 연결 도구의 절대 경로가 들어가며 token은 포함하지 않는다. Node 설치 경로나 저장소 위치를 바꾸면 등록 명령을 다시 실행한다.

3. 미리보기를 확인한 뒤 같은 명령에 `--apply`를 붙여 저장한다.

```bash
node clients/zed/intent-trace.mjs configure --apply
```

JSONC 주석·다른 MCP 서버·화면 설정을 보존하고 `context_servers.intent-trace` 항목만 추가하거나 교체한다. 같은 설정을 다시 등록하면 파일을 쓰지 않는다. Node 경로나 서버 주소가 바뀌면 새 실행 값으로 교체한다. 잘못된 JSONC·중복 연결 키·일반 파일이 아닌 설정은 덮어쓰지 않는다. IntentTrace 항목 안의 별도 설정도 교체되므로 필요한 경우 저장 후 다시 조정한다.

`--settings /설정파일경로/settings.json`으로 다른 설정 파일을 지정할 수 있다. 기본값은 macOS의 `~/.config/zed/settings.json`, Linux의 `$XDG_CONFIG_HOME/zed/settings.json` 또는 `~/.config/zed/settings.json`, Windows의 `%APPDATA%\Zed\settings.json`이다. `--user-data-dir`로 Zed를 실행한다면 그 폴더의 `config/settings.json`을 지정한다. 직접 편집하려면 기존 `config` 명령의 JSON을 사용할 수 있다. token은 계속 `env`에 쓰지 않는다.
4. Zed를 완전히 종료하고 다음 실행 도구를 사용한다. Zed CLI가 없으면 Zed 명령 팔레트에서 `cli: install`을 먼저 실행한다.

```bash
python3 scripts/zed-with-intent-trace.py .
```

숨긴 입력에 로그인 화면의 `its_`를 붙여 넣으면 Zed에 환경 변수로 전달한다. 토큰을 파일이나 명령 인자에 저장하지 않는다. [Zed 환경 변수 문서](https://zed.dev/docs/environment)는 CLI 실행 환경을 상속하는 동작을 설명한다.

5. Settings → AI → MCP Servers에서 `intent-trace`가 활성화되는지 확인한다. Agent에 다음처럼 요청한다.

> IntentTrace 연결을 acme/project 저장소 기준으로 진단해줘.

> IntentTrace에서 acme/project의 12번 PR에 연결된 기록과 현재 커밋에 맞지 않는 기록을 보여줘.

## 연결만 먼저 점검하기

환경 변수로 `INTENT_TRACE_SESSION_TOKEN`이 전달된 터미널에서 실행한다. 실제 token을 명령에 적지 않는다.

```bash
node clients/zed/intent-trace.mjs check http://127.0.0.1:8080/mcp acme/project
```

Zed와 같은 stdio 연결로 초기화·도구 목록·저장소 진단을 호출한다. MCP 연결에 성공하면 도구 개수를 표시하고 각 진단의 상태만 출력한다. 저장소 이름을 생략하면 초기화와 도구 목록만 확인한다. 인증 또는 진단 실패 시 종료 코드는 1이다. 서버 게시 키가 미설정이어도 기록 조회와 초안 기능은 사용할 수 있다.

## 기록할 때 지킬 내용

- 원문 대화·숨은 추론·비밀값을 기록하지 않는다. 토큰은 도구 인자나 코드 근거에 넣지 않는다.
- 확인·공개·GitHub 게시는 사용자가 요청한 범위에서 수행한다. 실행한 검증만 기록한다.
- `create_successor_draft`는 새 근거로 초안을 만들고 검증·확인 상태를 비운다. 공개 후 기존 기록 대체는 별도 요청이다.
- `compare_change_record`로 원본과 후속 내용을 확인한다. `check_publication_credentials`는 저장소 관리자만 실행하며 실제 게시를 하지 않는다.
- `compare_change_record`의 `details`로 출처·순서·추가·삭제를 확인하고, `list_record_activities`로 작업 시각과 버전을 조회한다. 작성자에게는 전체, 팀원에게는 공개·대체 작업만 표시된다.
- 이전 기록 조회에서 `complete=false`이면 `failures`를 확인하고 실패한 `recordId`를 `retryRecordId`로 재조회한다. 호출 제한은 안내된 대기 시간을 지킨다.
- `find_change_intent`의 목록은 `items`에서 읽는다. 이전 기록 탐색의 다음 커서와 원본·현재 줄 범위를 확인하고 과거 테스트를 현재 검증으로 설명하지 않는다.
- 필요한 상세 절차는 [IntentTrace 사용 스킬](../../skills/intent-trace/SKILL.md)을 Zed의 지침에서 참고한다.

## 연결이 안 될 때

0.11.0부터 연결 초기화·도구 호출·`check`에서 오류를 구분한다. 도구 호출 오류의 `data.code`와 선택 `retryAfterSeconds`를 사용하며 외부 오류 본문은 출력하지 않는다.

| 오류 코드 | 처리 방법 |
|---|---|
| `AUTHENTICATION_REQUIRED` | 다시 로그인한 `its_` 세션으로 연결한다. |
| `ACCESS_DENIED` | 저장소 권한을 확인한다. |
| `RATE_LIMITED` | 안내한 초만큼 기다린다. 시간이 없으면 즉시 반복하지 않는다. |
| `UPSTREAM_UNAVAILABLE` | 서버의 GitHub 등 외부 연동 상태를 확인한다. |
| `REQUEST_TIMEOUT` | 변경 요청은 기록·게시 상태를 먼저 확인한다. |
| `CONNECTION_FAILED` | 서버 주소·연결을 확인하고 변경 요청의 처리 상태를 먼저 읽는다. |

`Retry-After`는 정수 초 또는 HTTP 날짜의 0~604800초 범위만 전달한다. 잘못된 값은 대기 시간 미확인으로 표시한다. 연결 도구는 변경 요청을 자동으로 재실행하지 않는다. CLI의 `INTENT_TRACE_ERROR` 줄에도 정해진 코드와 대기 시간만 출력한다.

- `its_` 세션이 없거나 만료됐으면 `/auth/github/start`에서 로그인하고 실행 도구로 다시 연결한다. 서버 재시작 시 기존 세션은 사라진다.
- `ghu_`, `ghr_`, `itb_`는 연결 도구가 받지 않는다.
- `NOT_CONFIGURED`는 서버 게시 자격 증명이 없다는 뜻이다. `CONFIGURED_UNVERIFIED`도 실제 게시 성공을 보장하지 않는다.
- IntentTrace의 GitHub 로그인은 MCP 표준 OAuth discovery와 다르므로 Zed에 원격 URL만 넣어서 자동 로그인하는 방식은 지원하지 않는다.
- 중계기는 HTTPS 또는 로컬 HTTP `/mcp`만 연결하며 redirect를 따르지 않는다. 프록시가 주소를 바꾸면 최종 `/mcp` 주소를 설정한다.

## 검증 범위

0.11.0은 로컬 오류 응답의 분류·대기 시간 전달, 표준 SDK와 실제 Spring 서버 연결을 검증했다. 아래 실제 Zed 앱 확인은 0.10.0 당시 결과이며 이번 변경에서 앱의 실제 사용자 승인을 다시 수행하지 않았다.

공식 [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk)의 stdio 클라이언트 → 이 중계기 → 실제 Spring 서버에서 메모리 `its_` 인증·도구 목록·진단 호출을 통합 테스트한다. 설정의 token 미노출·원격 HTTP 거부·인증 실패 응답 미노출도 확인한다. 설정의 JSONC 보존·미리보기·반복 등록·경로 및 주소 갱신도 확인한다.

2026-09-05 공식 배포 Zed 1.18.1을 임시 사용자 데이터와 프로젝트로 실행했다. 실제 앱에서 MCP 활성 상태, `list_change_records` 승인 화면, 공개 기록 결과, 세션 폐기 후 오류, 새 세션을 환경 변수로 전달해 재실행한 뒤 재조회를 확인했다. IntentTrace는 실제 Spring 서버와 메모리 DB를 사용했다. GitHub 응답과 도구 호출을 요청하는 모델 응답만 로컬 stub으로 대체했다. 실제 GitHub 사용자 승인, 유료 모델, 실제 저장소 Check Run 게시는 이 검증에 포함하지 않았다. 시간 경과에 의한 세션 만료 대신 명시적 세션 폐기로 인증 실패 경로를 확인했다.

```bash
npm test --prefix clients/zed
./gradlew test --tests '*ZedBridgeIntegrationTest'
```
