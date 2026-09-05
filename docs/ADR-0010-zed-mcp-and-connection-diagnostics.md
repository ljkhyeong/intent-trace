# ADR-0010: Zed MCP 중계와 연결 진단

## 상태

채택. 0.9.0에 구현한다.

## 결정

- Zed Agent는 로컬 stdio MCP 서버 설정으로 IntentTrace에 연결한다. 저장소의 Node.js 중계기는 공식 MCP TypeScript SDK의 고정 버전을 사용하고 서버의 도구 목록·호출을 Streamable HTTP로 전달한다.
- 중계기는 `INTENT_TRACE_SESSION_TOKEN`의 `its_`만 받는다. GitHub OAuth 수행, token 파일 저장, 사용자 Zed 설정을 확인 없이 덮어쓰지는 않는다. 설정 생성 결과에는 실행 경로·MCP 주소만 넣는다.
- MCP 주소는 HTTPS 또는 loopback HTTP `/mcp`로 제한한다. URL 인증 정보·쿼리·fragment와 HTTP redirect를 거부한다. 자격 증명은 대상 서버의 인증 헤더에만 넣고 오류 원문을 콘솔에 출력하지 않는다.
- MCP 출력 스키마의 최상위 값은 객체다. 배열 목록은 `items`로 감싼다. 표준 SDK가 실제 서버의 도구 목록과 호출 응답을 해석하는 통합 테스트로 확인한다.
- `GET /api/v1/connection-diagnostics`와 `diagnose_connection`은 `repositoryKey`, 선택 전체 `revision`·`pullNumber`를 받는다. 인증은 기존 REST·MCP 필터를 거친다.
- 저장소 읽기·쓰기 권한을 확인하고, 읽기 권한이 있을 때만 선택 PR·Git 트리를 조회한다. revision을 생략하고 PR 조회가 성공하면 해당 HEAD로 트리를 읽는다. 트리 접근 성공은 모든 blob이나 기록 근거 해시 확인 성공을 뜻하지 않는다.
- 결과는 `VERIFIED`, `FAILED`, `CONFIGURED_UNVERIFIED`, `NOT_CONFIGURED`, `NOT_CHECKED`를 구분한다. 서버 게시 키는 존재 여부만 확인하며 실제 키 유효성·App 설치·Checks 쓰기를 검증하지 않는다. 관리자용 별도 사전 점검은 ADR-0003을 따른다. 진단 중 GitHub 호출 제한은 기존 429·대기 시간 계약으로 처리한다.

## 영향

Zed에서는 설정 생성 후 사용자 설정에 연결을 추가하고 세션을 상속하는 환경에서 실행해야 한다. 서버 재시작·세션 만료 후 다시 로그인한다. Agent 도구 연결을 지원하며 인라인 코드 UI·자동 기록 수집은 별도 범위다. Zed 실제 앱·사용자 계정의 승인 완료는 표준 연결 통합 테스트와 구분한다.

## 0.10.0 JSONC 등록 도구

`configure`는 현재 Node·중계기 경로와 서버 주소로 연결 정의를 미리 보여준다. 명시적 `--apply`에서만 JSONC 구문 편집으로 `context_servers.intent-trace`를 교체한다. 다른 설정과 연결 주석은 보존하고 다른 연결의 본문은 출력하지 않는다. 처음 등록·주소 변경·Node 경로 변경·중복 실행을 지원한다. JSONC 문법 오류와 중복 연결 키는 덮어쓰지 않는다.

새 설정 파일은 제한된 권한으로 만들고 기존 파일은 권한을 유지한다. 임시 파일 작성 후 교체하며 직전 읽은 내용과 달라졌으면 재실행을 요구한다. token을 설정에 저장하지 않는 기존 경계를 유지한다. 실제 Zed 앱 검증과 로컬 테스트 응답의 사용 범위는 `docs/clients/zed.md`에 남긴다.

## 0.11.0 오류 전달

HTTP 오류 본문은 읽지 않고 버린다. 상태 코드로 401은 `AUTHENTICATION_REQUIRED`, 403은 `ACCESS_DENIED`, 429는 `RATE_LIMITED`, 5xx는 `UPSTREAM_UNAVAILABLE`로 분류한다. 요청 시간 초과는 `REQUEST_TIMEOUT`, 나머지 전송 실패는 `CONNECTION_FAILED`다. SDK가 처리하는 SSE 조회·연결 종료의 405는 그대로 전달한다.

429의 `Retry-After`는 정수 초 또는 HTTP 날짜 형식을 확인하고 0~604800초 범위만 전달한다. 없거나 잘못된 값은 대기 시간 미확인으로 표시한다. 도구 호출 오류의 MCP `data`에는 정해진 `code`와 선택 `retryAfterSeconds`만 넣는다. 초기 연결과 `check`도 같은 분류를 사용하며 자식 프로세스에서는 정해진 오류 코드 줄만 해석한다.

인증 오류는 재로그인을, 호출 제한은 대기를 안내한다. 시간 초과·연결 실패·외부 장애에서 변경 요청은 기록 또는 게시 상태를 먼저 조회하도록 안내한다. 오류 분류 때문에 도구 호출을 자동 재시도하지 않는다. 정상 MCP 응답의 업무 오류 내용은 기존 전달 계약을 유지한다.

## 0.12.0 설치 패키지

- `scripts/package-zed.mjs`는 `clients/zed`의 실행 파일·세션 토큰 입력 도구·사용 안내와 Node 의존성을 묶은 `.tgz` 및 SHA-256 파일을 만든다. 0.12.1부터 의존성은 아래의 잠금 파일 설치 절차로 준비한다. 저장소 전체·테스트·사용자 설정은 포함하지 않는다. 생성은 macOS·Linux에서 지원한다.
- 기본 패키지는 로컬 설치용 `private: true`다. `intent-trace-zed` 실행 명령을 제공하며 심볼릭 링크로 설치된 명령도 실제 실행 파일에서 시작한다. 설정 생성은 설치된 경로를 사용한다.
- `launch`는 패키지의 Python 실행 도구로 Zed에 세션을 전달한다. 저장소에서 실행할 때는 기존 스크립트를 사용한다. `INTENT_TRACE_MCP_URL`은 선택 서버 주소이며 명령 인자의 주소가 우선한다.
- npm 이름·MCP 이름·GitHub 저장소를 함께 지정하면 별도 배포 패키지에 `mcpName`과 저장소 정보를 넣고 `server.json`을 생성한다. 제출용 token 입력은 비밀값으로 선언하며 실제 값을 포함하지 않는다. 이 생성 작업은 외부 게시를 하지 않는다.
- Zed는 MCP 확장 플러그인에서 공식 MCP 레지스트리로 전환할 계획을 안내한다. 배포 자료는 공식 레지스트리 형식에 맞추고 기존 사용자 지정 stdio 연결을 유지한다. [Zed 공식 안내](https://zed.dev/docs/extensions/mcp-extensions), [MCP 레지스트리 게시 절차](https://modelcontextprotocol.io/registry/quickstart)

## 0.12.1 입력 보호와 배포 의존성

- Python `GetPassWarning`을 예외로 처리해 토큰을 화면에서 숨길 수 없을 때 표준 입력 대체 전에 실패 종료한다. EOF·입력 취소도 한국어 안내만 출력하고 실행하지 않는다. 환경 변수에 이미 제공한 `its_`는 비대화형 실행에 그대로 사용한다. [Python getpass 문서](https://docs.python.org/3/library/getpass.html)
- 생성기는 `package.json`과 `package-lock.json`을 임시 폴더에 복사하고 `npm ci --ignore-scripts`로 운영 의존성을 설치한다. 개발 폴더의 `node_modules`는 사용하거나 변경하지 않는다. 설치 스크립트·의존성 감사 호출을 끄고, 준비 실패 시 배포 파일 생성을 중단한다. 잠금 파일과 선언이 다르면 임의로 갱신하지 않는다. [npm ci 문서](https://docs.npmjs.com/cli/v11/commands/npm-ci/)
- 생성에는 npm 레지스트리 또는 필요한 항목이 있는 캐시가 필요하다. 완성된 패키지의 소비자 오프라인 설치는 유지한다. 배포 대상 운영체제에 따라 npm이 선택하는 선택 의존성은 달라질 수 있으므로 플랫폼 간 바이트 동일성을 보장하지 않는다.
- 패키지 안의 `build-info.json`에 패키지 이름·버전·입력 잠금 파일 SHA-256을 넣는다. 옆의 `.tgz.build.json`에는 같은 정보와 압축 파일 SHA-256을 기록한다. 계정·개인 경로·인증 정보는 넣지 않는다. 이 정보는 생성 기준을 확인하는 용도이며 배포자 서명은 아니다.
