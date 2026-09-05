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
