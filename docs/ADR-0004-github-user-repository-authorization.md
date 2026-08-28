# ADR-0004: GitHub 사용자와 저장소 권한으로 팀 접근을 결정한다

## 상태

채택

## 배경

IntentTrace 기록의 신뢰성은 “누가 작성·확인했는가”와 “누가 볼 수 있는가”에 달려 있습니다. 별도 계정 체계를 먼저 만들면 GitHub 저장소 구성원과 권한을 다시 동기화해야 하고, 요청의 작성자 문자열을 믿으면 다른 사용자를 가장할 수 있습니다.

## 결정

- `ghu_`로 시작하는 GitHub App user access token을 REST·MCP Bearer 자격 증명으로 사용한다.
- 매 요청에서 GitHub `/user`를 호출해 사용자 숫자 ID와 현재 login을 확인한다.
- `github:<user-id>`를 안정적인 작성자 subject, login을 표시값으로 저장한다.
- `GET /user/repos`를 `owner,collaborator,organization_member` affiliation으로 조회해 명시적 접근 저장소만 인정하고, 대상의 `permissions`를 `READER`, `CONTRIBUTOR`, `MAINTAINER`로 축약한다.
- 읽기 작업은 `READER`, 기록 생성·수명주기·GitHub 게시는 `CONTRIBUTOR` 이상을 요구한다.
- 비공개 상태는 작성자만 조회하며, `PUBLISHED`와 `SUPERSEDED`도 익명 공개하지 않고 저장소 읽기 권한이 있는 팀원에게만 공개한다.
- user access token은 요청 속성에만 두고 처리 후 제거한다. 설치 token, JWT와 마찬가지로 영구 저장하거나 로그에 쓰지 않는다.
- 기존 `created_by` 값은 V3에서 `legacy:<lowercase-login>` subject로 보존하며 자동 계정 연결은 하지 않는다.

## 영향

- 모든 API·MCP 호출에는 GitHub 네트워크 왕복이 최소 한 번, 저장소 작업에는 권한 조회가 한 번 더 필요하다.
- 저장소가 많은 사용자는 `/user/repos` 페이지를 더 조회한다. 현재 상한은 100페이지이며 초과하면 권한 없음으로 오인하지 않고 의존 서비스 오류로 중단한다.
- GitHub 장애는 보호 작업에 영향을 준다. 현재는 오래된 권한으로 통과시키지 않고 실패를 반환한다.
- user access token의 발급과 갱신은 클라이언트 책임이다. Codex는 환경변수에서 token을 읽어 헤더로 전달한다.
- 서버 주도 Check Run 게시의 installation token과 요청 사용자 token은 목적과 권한 범위가 다르므로 별도로 유지한다.
- V3 이전 비공개 기록은 명시적 계정 연결 기능이 생기기 전까지 수명주기를 계속 진행할 수 없다.

## 대안

- 클라이언트 작성자 문자열 신뢰: 구현은 단순하지만 가장을 막을 수 없어 제외했다.
- 자체 계정·역할 DB: GitHub 팀 권한과 중복되고 동기화 책임이 커져 현재 단계에서 제외했다.
- GitHub login을 소유권 키로 사용: 사용자가 login을 변경할 수 있어 안정적인 귀속을 보장하지 못하므로 제외했다.
- 공개 상태를 인터넷 전체에 노출: private 저장소의 요청과 판단이 유출될 수 있어 제외했다.
- installation token으로 사용자 요청 인증: App 설치를 식별할 뿐 실제 행위자를 식별하지 못하므로 제외했다.
