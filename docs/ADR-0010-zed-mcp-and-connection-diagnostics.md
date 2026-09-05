# ADR-0010: Zed MCP 중계와 연결 진단

## 상태

채택. 0.9.0에 구현한다.

## 결정

- Zed Agent는 로컬 stdio MCP 서버 설정으로 IntentTrace에 연결한다. 저장소의 Node.js 중계기는 공식 MCP TypeScript SDK의 고정 버전을 사용하고 서버의 도구 목록·호출을 Streamable HTTP로 전달한다.
- 중계기는 `INTENT_TRACE_SESSION_TOKEN`의 `its_`만 받는다. GitHub OAuth 수행, token 파일 저장, 사용자 Zed 설정 자동 덮어쓰기는 하지 않는다. 설정 생성 결과에는 실행 경로·MCP 주소만 넣는다.
- MCP 주소는 HTTPS 또는 loopback HTTP `/mcp`로 제한한다. URL 인증 정보·쿼리·fragment와 HTTP redirect를 거부한다. 자격 증명은 대상 서버의 인증 헤더에만 넣고 오류 원문을 콘솔에 출력하지 않는다.
- MCP 출력 스키마의 최상위 값은 객체다. 배열 목록은 `items`로 감싼다. 표준 SDK가 실제 서버의 도구 목록과 호출 응답을 해석하는 통합 테스트로 확인한다.
- `GET /api/v1/connection-diagnostics`와 `diagnose_connection`은 `repositoryKey`, 선택 전체 `revision`·`pullNumber`를 받는다. 인증은 기존 REST·MCP 필터를 거친다.
- 저장소 읽기·쓰기 권한을 확인하고, 읽기 권한이 있을 때만 선택 PR·Git 트리를 조회한다. revision을 생략하고 PR 조회가 성공하면 해당 HEAD로 트리를 읽는다. 트리 접근 성공은 모든 blob이나 기록 근거 해시 확인 성공을 뜻하지 않는다.
- 결과는 `VERIFIED`, `FAILED`, `CONFIGURED_UNVERIFIED`, `NOT_CONFIGURED`, `NOT_CHECKED`를 구분한다. 서버 게시 키는 존재 여부만 확인하며 실제 키 유효성·App 설치·Checks 쓰기를 검증하지 않는다. 진단 중 GitHub 호출 제한은 기존 429·대기 시간 계약으로 처리한다.

## 영향

Zed에서는 설정 생성 후 사용자 설정에 연결을 추가하고 세션을 상속하는 환경에서 실행해야 한다. 서버 재시작·세션 만료 후 다시 로그인한다. Agent 도구 연결을 지원하며 인라인 코드 UI·자동 기록 수집은 별도 범위다. Zed 실제 앱·사용자 계정의 승인 완료는 표준 연결 통합 테스트와 구분한다.
