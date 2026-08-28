# PRD-0003: 팀 사용자와 저장소 접근

## 문제

클라이언트가 작성자 문자열을 보내고 공개 기록을 인증 없이 읽을 수 있으면 누가 기록을 확인했는지 신뢰할 수 없고, private 저장소의 변경 의도가 팀 밖으로 노출될 수 있습니다.

## 목표

GitHub App user access token으로 사용자를 확인하고 대상 저장소의 실제 GitHub 권한으로 기록 생성·수명주기·팀 조회를 통제합니다. 작성자는 변경 가능한 login이 아니라 GitHub 숫자 ID에 연결합니다.

## 사용자 흐름

1. 사용자가 GitHub App을 승인하고 user access token을 준비한다.
2. Codex나 REST 클라이언트가 token을 Bearer 헤더로 보낸다.
3. IntentTrace가 GitHub `/user`에서 숫자 ID와 login을 확인한다.
4. IntentTrace가 사용자의 명시적 접근 저장소 목록에서 `repositoryKey`와 권한을 조회한다.
5. 쓰기 권한이 있는 사용자는 자기 초안을 만들고 확인·공개·대체한다.
6. 읽기 권한이 있는 팀원은 공개·대체 기록을 조회한다.

## 권한 계약

| GitHub 응답 | 내부 역할 | 허용 작업 |
|---|---|---|
| `pull` 또는 `triage` | `READER` | 공개·대체 기록 조회 |
| `push` | `CONTRIBUTOR` | `READER` 작업과 초안 생성·자기 기록 변경·PR 게시 요청 |
| `maintain` 또는 `admin` | `MAINTAINER` | 현재는 `CONTRIBUTOR`와 같고 후속 운영 기능 확장점 |

권한 판정은 GitHub `/user/repos`를 `owner,collaborator,organization_member` 범위로 조회한 결과만 사용합니다. 따라서 public 저장소를 누구나 읽을 수 있다는 사실만으로 팀원이라고 판단하지 않습니다.

## 불변식

- REST `/api/v1/**`와 MCP `/mcp`는 `ghu_`로 시작하는 GitHub App user access Bearer token이 필요하다.
- 작성자 subject는 `/user.id`로 만든 `github:<id>`이며 login은 표시용이다.
- 작성자 값을 요청 본문이나 MCP 도구 인자로 받지 않는다.
- `DRAFT`와 `AUTHOR_CONFIRMED`는 만든 작성자만 조회·변경한다.
- `PUBLISHED`와 `SUPERSEDED`는 저장소 `READER` 이상에게만 노출한다.
- 생성·확인·공개·대체·GitHub PR 게시는 `CONTRIBUTOR` 이상인 작성자만 수행한다.
- 사용자 token은 DB·로그·오류 본문·MCP 도구 인자에 저장하거나 노출하지 않는다.
- 같은 `requestId`를 다른 사용자나 저장소가 재사용하면 기존 기록을 반환하지 않고 충돌로 처리한다.

## 성공 기준

- 인증이 없거나 GitHub가 token을 거부하면 보호 경로가 `401`을 반환한다.
- GitHub 사용자 조회 장애는 자격 증명 실패와 구분해 `502`로 처리한다.
- 읽기 전용 사용자는 공개 기록을 볼 수 있지만 초안을 만들 수 없다.
- 다른 팀원은 작성자의 초안을 볼 수 없고 공개 기록만 볼 수 있다.
- 기존 작성자 문자열은 V3에서 `legacy:<lowercase-login>` subject로 손실 없이 보존된다.
- Codex MCP 초기화가 환경변수 Bearer token으로 성공한다.

## 제외

- GitHub App 승인 화면과 user access token 발급·갱신·폐기 UI
- refresh token 저장과 암호화
- 권한 캐시와 webhook 기반 즉시 무효화
- 조직 SSO·팀별 추가 정책과 관리자 소유권 강제 이전
- 감사 로그와 기록 보존 정책
