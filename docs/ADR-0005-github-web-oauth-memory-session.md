# ADR-0005: GitHub 웹 승인 token을 메모리 세션으로 관리한다

## 상태

채택

## 배경

`ADR-0004`는 GitHub user access token으로 실제 사용자를 확인하는 경계를 정했지만, 사용자가 `ghu_` token을 외부에서 발급해 Codex 환경 변수에 넣고 만료 때마다 교체해야 했습니다. refresh token을 클라이언트에 전달하면 GitHub 자격 증명의 노출 범위와 갱신 책임도 커집니다.

## 결정

- GitHub Web Application Flow를 사용하며 `GET /auth/github/start`와 `GET /auth/github/callback`을 제공한다.
- 승인 시작 시 256비트 무작위 `state`와 PKCE code verifier를 발급한다. 서버에는 `state`의 SHA-256 digest·10분 TTL과 verifier를 두고 `state` 원문만 callback 경로에 한정된 HttpOnly·SameSite=Lax cookie로 전달한다.
- 미완료 승인 상태는 기본 1,000개로 제한한다. 만료 상태를 먼저 제거하고도 상한에 도달하면 새 승인 시작을 `429 Too Many Requests`로 거부한다.
- authorize 요청에는 PKCE `S256` code challenge를 포함한다. callback은 query와 cookie의 `state`, TTL, 일회성 사용 여부를 모두 확인한 뒤 client ID·client secret·정확한 redirect URI·code verifier로 code를 교환한다.
- GitHub App의 expiring user authorization token을 필수로 하고 `ghu_` access token과 `ghr_` refresh token 쌍을 프로세스 메모리에만 저장한다.
- 클라이언트에는 별도 256비트 무작위 `its_` session token을 callback 성공 본문에서 한 번 표시한다. 메모리 store의 조회 key에는 session 원문이 아니라 SHA-256 digest를 사용한다.
- access token 만료 5분 전부터 세션 단위 잠금 안에서 refresh를 한 번 수행하고, GitHub가 회전해 준 access·refresh token 쌍을 함께 교체한다.
- 매 요청에서 `/user`를 다시 확인한다. 갱신 거부, token 거부 또는 GitHub 숫자 사용자 ID 변경 시 세션을 폐기하고 재로그인을 요구한다.
- 기존 `ghu_` 직접 Bearer 인증은 REST 호환 경로로 유지하되 Codex 프로젝트와 플러그인은 `INTENT_TRACE_SESSION_TOKEN`을 사용한다.
- 승인 HTML에는 `no-store`, `no-referrer`, 제한된 CSP와 `nosniff`를 적용하고 GitHub token, client secret과 외부 오류 본문을 응답에 넣지 않는다.
- access·refresh·session token과 client secret을 보유한 객체의 문자열 표현에는 비밀값을 포함하지 않는다.

## 영향

- Codex는 GitHub token 수명과 회전을 알 필요 없이 같은 `its_` token을 계속 사용할 수 있다.
- 서버 재시작 시 GitHub token 쌍과 로컬 세션이 모두 사라져 사용자가 다시 승인해야 한다.
- 여러 서버 인스턴스는 세션을 공유하지 못한다. 현재 로컬 단일 인스턴스 범위에서는 sticky session이나 공유 저장소를 추가하지 않는다.
- `its_` token도 보유자가 사용자를 대신해 IntentTrace를 호출할 수 있는 Bearer 자격 증명이므로 환경 변수로 전달하고 로그·기록·도구 인자에 넣지 않는다.
- callback URL은 컨트롤러 경로와 정확히 같아야 하며 HTTP는 loopback host에서만 허용한다. 팀 배포에서는 HTTPS callback을 사용해야 한다.

## 대안

- `ghu_` token을 계속 직접 전달: 구현은 단순하지만 사용자가 만료와 갱신을 관리하고 GitHub token이 Codex 환경까지 노출되므로 기본 경로로 선택하지 않았다.
- refresh token을 클라이언트에 전달: 서버 상태는 줄지만 장기 자격 증명 노출과 동시 갱신 충돌을 클라이언트마다 해결해야 하므로 제외했다.
- token 쌍을 DB에 암호화 저장: 재시작과 다중 인스턴스를 지원하지만 암호화 key 회전·백업·폐기 책임이 추가돼 로컬 MVP에서 제외했다.
- GitHub device flow: headless 환경에는 적합하지만 현재 로컬 서버가 callback을 받을 수 있고 브라우저 계정 선택 흐름이 더 직접적이므로 선택하지 않았다.
- GitHub token을 cookie에 저장: 브라우저와 HTTP 왕복에 장기 자격 증명이 노출되므로 제외했다.
