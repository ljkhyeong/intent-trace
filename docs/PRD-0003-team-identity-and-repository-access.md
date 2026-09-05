# PRD-0003: 팀 사용자와 저장소 접근

## 문제

클라이언트가 작성자 문자열을 보내고 공개 기록을 인증 없이 읽을 수 있으면 누가 기록을 확인했는지 신뢰할 수 없고, private 저장소의 변경 의도가 팀 밖으로 노출될 수 있습니다.

## 목표

GitHub App 웹 승인으로 로컬 세션을 발급하고, user access token으로 사용자를 확인해 대상 저장소의 실제 GitHub 권한으로 기록 생성·수명주기·팀 조회를 통제합니다. 작성자는 변경 가능한 login이 아니라 GitHub 숫자 ID에 연결합니다.

## 사용자 흐름

1. 사용자가 IntentTrace의 `/auth/github/start`에서 GitHub App을 승인한다.
2. callback이 일회성 `state`를 검증하고 code를 만료되는 user access·refresh token 쌍으로 교환한다.
3. IntentTrace가 GitHub `/user`에서 숫자 ID와 login을 확인하고 메모리 세션을 만든다.
4. Codex나 REST 클라이언트가 `its_` session token을 Bearer 헤더로 보낸다.
5. IntentTrace가 session의 GitHub token으로 사용자와 명시적 접근 저장소 권한을 확인한다.
6. 쓰기 권한이 있는 사용자는 자기 초안을 만들고 확인·공개·대체한다.
7. 읽기 권한이 있는 팀원은 공개·대체 기록을 조회한다.
8. access token 만료가 가까우면 refresh token으로 token 쌍을 교체하고 같은 사용자인지 다시 확인한다.

## 권한 계약

| GitHub 응답 | 내부 역할 | 허용 작업 |
|---|---|---|
| `pull` 또는 `triage` | `READER` | 공개·대체 기록 조회 |
| `push` | `CONTRIBUTOR` | `READER` 작업과 초안 생성·자기 기록 변경·PR 게시 요청 |
| `maintain` 또는 `admin` | `MAINTAINER` | 현재는 `CONTRIBUTOR`와 같고 후속 운영 기능 확장점 |

권한 판정은 GitHub `/user/repos`를 `owner,collaborator,organization_member` 범위로 조회한 결과만 사용합니다. 따라서 public 저장소를 누구나 읽을 수 있다는 사실만으로 팀원이라고 판단하지 않습니다.

## 불변식

- REST `/api/v1/**`와 MCP `/mcp`는 기본적으로 `its_` 로컬 session Bearer token이 필요하며 기존 `ghu_` 직접 인증도 허용한다.
- callback은 같은 브라우저의 HttpOnly·SameSite cookie와 TTL 안의 미사용 `state`, PKCE `S256` verifier가 모두 일치할 때만 code를 교환한다.
- 작성자 subject는 `/user.id`로 만든 `github:<id>`이며 login은 표시용이다.
- 작성자 값을 요청 본문이나 MCP 도구 인자로 받지 않는다.
- `DRAFT`와 `AUTHOR_CONFIRMED`는 만든 작성자만 조회·변경한다.
- `PUBLISHED`와 `SUPERSEDED`는 저장소 `READER` 이상에게만 노출한다.
- 생성·확인·공개·대체·GitHub PR 게시는 `CONTRIBUTOR` 이상인 작성자만 수행한다.
- GitHub access·refresh token은 메모리에만 두고 DB·URL·cookie·로그·오류 본문·MCP 도구 인자에 저장하거나 노출하지 않는다.
- GitHub token, private key와 client secret을 보유한 객체의 문자열 표현에는 비밀값을 포함하지 않는다.
- `its_` 원문은 callback 성공 본문에서 한 번만 표시하고 서버에는 SHA-256 digest만 인덱스로 저장한다.
- refresh는 세션별로 한 번만 수행하고 새 token 쌍을 함께 교체한다. 거부되거나 사용자 subject가 바뀌면 세션을 폐기한다.
- 같은 `requestId`를 다른 사용자나 저장소가 재사용하면 기존 기록을 반환하지 않고 충돌로 처리한다.

## 성공 기준

- 인증이 없거나 GitHub가 token을 거부하면 보호 경로가 `401`을 반환한다.
- GitHub 사용자 조회 장애는 자격 증명 실패와 구분해 `502`로 처리한다.
- 읽기 전용 사용자는 공개 기록을 볼 수 있지만 초안을 만들 수 없다.
- 다른 팀원은 작성자의 초안을 볼 수 없고 공개 기록만 볼 수 있다.
- 기존 작성자 문자열은 V3에서 `legacy:<lowercase-login>` subject로 손실 없이 보존된다.
- Codex MCP 초기화가 환경변수 Bearer token으로 성공한다.
- 만료된·재사용된·cookie와 다른 OAuth `state`는 code 교환 전에 거부한다.
- access token 만료 전 갱신과 동시 요청이 refresh token 한 번만 사용한다.
- callback 성공 응답과 실패 응답은 `no-store`와 `no-referrer` 보안 header를 반환한다.

## 제외

- GitHub token·세션의 영구 저장과 암호화 key 회전
- 서버 재시작 뒤 세션 복구와 여러 인스턴스 간 공유
- GitHub 승인 폐기 webhook과 관리자 세션 UI
- device flow와 MCP OAuth discovery
- 권한 캐시와 webhook 기반 즉시 무효화
- 조직 SSO·팀별 추가 정책과 관리자 소유권 강제 이전
- 인증·운영 전체 감사 로그와 자동 기록 보존 정책. 기록 수명주기 이력은 ADR-0011을 따른다.

## 내 로컬 세션 관리

- `GET /api/v1/me/sessions`는 현재 사용자·인증 방식과 본인의 세션 ID·생성·최근 사용·만료 시각을 반환한다. token·digest는 반환하지 않으며 응답을 캐시하지 않는다.
- `DELETE /api/v1/me/sessions/current`, `DELETE /api/v1/me/sessions/{id}`, `DELETE /api/v1/me/sessions`로 현재 연결·선택 연결·본인의 전체 연결을 폐기한다. 결과는 `revokedCount`다. 다른 사용자의 ID나 이미 없는 ID는 0을 반환해 소유권 정보를 노출하지 않는다.
- 직접 `ghu_` 인증에는 현재 로컬 세션 ID가 없으므로 현재 연결 폐기는 `400`이다. 본인의 목록과 선택·전체 로컬 세션 폐기는 가능하다. GitHub App 승인 자체를 취소하는 기능은 아니다.
- MCP는 `list_my_sessions`, `revoke_my_session`, `revoke_all_my_sessions`다. token을 도구 인자로 받지 않는다.
- 폐기 후의 새 인증은 거부한다. token 갱신 중 폐기해도 세션을 다시 활성화하지 않는다. 이미 인증을 끝내 처리 중인 요청의 작업을 취소하지는 않는다.
- GitHub 호출 제한은 REST·MCP 인증 경로에서도 `429`와 `Retry-After`로 안내한다. 도구 실행 중 제한은 오류 메시지에도 대기 시간을 담는다.

## 브라우저 기록 열람 인증

- `GET /auth/github/start`에 선택 `returnTo`를 지정하면 ADR-0009의 브라우저 세션을 발급하고 기록 화면으로 돌아온다. 생략하면 기존 CLI용 `its_` 표시를 유지한다.
- 브라우저 cookie에는 GitHub token 대신 `itb_`를 넣으며 기록 화면에서만 사용한다. REST·MCP는 cookie나 `itb_` Bearer를 받지 않는다.
- 세션 목록의 `channel`은 `CLIENT` 또는 `BROWSER`, `expiresAt`은 로컬 세션의 실제 만료 시각이다. `accessExpiresAt`·`refreshExpiresAt`은 GitHub token 수명이고 로컬 세션 수명과 구분한다.
- 본인의 선택·전체 세션 폐기는 브라우저 연결에도 적용한다. 브라우저 로그아웃은 현재 연결만 폐기한다.
