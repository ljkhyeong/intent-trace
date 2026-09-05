# IntentTrace 인수인계

## 현재 구현

- Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- H2 기본 저장소와 PostgreSQL 프로필
- Flyway 초기 스키마
- Flyway V2 GitHub 게시 이력, V3 GitHub 작성자 subject, V4 저장소 키 정규화 스키마
- 변경 의도 생성·확인·공개·대체·라인 조회
- 팀 공유용 Markdown 출력
- PR HEAD 커밋 검증과 neutral GitHub Check Run 게시·재시도 갱신
- GitHub 응답의 head·base 저장소 확인과 Fork PR 게시 거부
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신·401 복구
- GitHub user access token 인증과 저장소별 READER·CONTRIBUTOR·MAINTAINER 역할 판정
- GitHub Web Application Flow와 callback `state`·cookie·TTL·일회성 검증
- GitHub access·refresh token 메모리 보관과 만료 전 token 쌍 자동 갱신
- SHA-256 digest로 조회하는 `its_` 로컬 세션과 Codex MCP 인증
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 Docker Compose
- 비root·읽기 전용 app container와 분리된 data·edge network
- PostgreSQL 17 migration·JDBC·backup·restore 왕복과 GitHub Actions 검증
- 초안 작성자 소유권과 저장소 권한 기반 팀 공개 조회
- 브라우저 기록 열람·로그인 복귀·로그아웃과 8시간 전용 세션
- REST·MCP·브라우저의 제목·요청·판단 키워드 검색
- Zed Agent용 공식 MCP SDK 중계기·설정 생성·연결 점검·숨긴 세션 입력 실행 도구
- 실제 Spring 서버와 stdio MCP 초기화·도구 목록·진단 호출 통합 검증
- Flyway V8 후속 초안의 원본 공개 기록 연결과 검증·확인 상태 초기화
- 고유한 동일 blob 이름 변경과 원본 코드 조각의 줄 이동 추적
- PR별 게시·시도 기록 목록과 현재 HEAD 비교, 연결·권한·설정 진단
- 새 GitHub JWT 형식 설치 token 전체 제거, 요청 안의 권한·Git 객체 조회 재사용
- 과거 기록 후보별 지원 불가 사유·완전 여부·실패 ID 재조회
- 관리자용 App 키 서명·원격 인증·설치·실제 발급 범위·권한 사전 점검
- 원본·후속 기록 비교와 브라우저 PR 기록·연결 진단 화면
- JSONC 설정 미리보기·등록·갱신과 실제 Zed 1.18.1의 도구 승인·조회·세션 폐기·재연결 확인
- Streamable HTTP MCP 도구 24개, 줄 조회의 최상위 `items` 객체 응답
- Zed 초기화·도구 호출·연결 점검의 안전한 오류 분류와 호출 제한 대기 시간 전달
- 웹 파일·줄 조회와 실패 후보 재조회, 별도 코드 근거 확인
- 웹의 코드 확인 불가 사유와 본인 폐기 기록·파일·팀 작성자 필터
- 과거 조회의 기본 30초·40회 HTTP 제한, 근거 순서별 중단·재개
- Zed 의존성 포함 설치 패키지와 버전별 설치·업데이트·등록 자료 생성
- 웹 본인 연결 조회·선택·전체 종료와 현재 연결 로그아웃
- 비교의 추가·삭제·출처·상대 순서 변경 표시와 중복 항목의 전체 내용 확인
- Flyway V9 기록 변경 이력과 기록 저장의 원자성, 작성자 전체·팀 공개 작업 조회
- 초안 수정·확인 취소·폐기, 최초 내용 해시 멱등성, 저장소별 내 초안·팀 공개 요약 목록
- MCP 기록 대체와 Markdown의 후속 기록 링크
- Flyway V7 게시 시도 이력, 게시 결과 조회·응답 유실 복구·기존 Check Run 대체 안내
- 내 세션 목록과 선택·전체 폐기, 갱신 도중 폐기 처리
- GitHub 호출 제한 429·Retry-After와 기능별 Micrometer 지표
- Flyway V5 최초 생성 내용 해시와 목록 조회 인덱스, V6 코드 근거 BASE·TARGET과 실행 결과 출처
- GitHub 코드 해시 확인과 이전 커밋의 동일 파일·관련 기록 조회
- 로컬 실행 도구의 종료 코드·시각·출력 해시 수집과 변경 파일 감지
- REST·MCP 공통 생성 입력 검증과 전체 Git commit 값 객체
- GitHub token·private key·client secret의 안전한 문자열 표현
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- IntentTrace 저장소 전용 개발 스킬

## 확인할 불변식

- 초안은 만든 작성자만 확인한다.
- 초안·확인·폐기 기록은 만든 작성자만 조회하고, 공개·대체 기록은 저장소 읽기 권한이 있는 팀원만 조회한다.
- 작성자는 요청 본문이 아니라 `/user`에서 확인한 GitHub 숫자 ID로 결정한다.
- 브라우저에는 기록 화면에서만 쓰는 `itb_` cookie를 발급하며 REST·MCP에서 사용하지 않는다.
- Codex에는 GitHub token 대신 `its_` session token만 전달하고 GitHub token 쌍은 메모리 밖으로 노출하지 않는다.
- callback은 같은 브라우저의 cookie와 미사용 `state`가 일치할 때만 code를 교환한다.
- refresh token은 한 번 사용한 뒤 새 access·refresh token 쌍으로 함께 교체하고, 사용자 subject가 바뀌면 세션을 폐기한다.
- 생성·확인·공개·대체·GitHub 게시는 저장소 쓰기 권한이 필요하다.
- 확인 시 전체 Git 커밋 ID가 필요하다.
- 확인과 공개 시 현재 스냅샷이 기록의 스냅샷과 같아야 한다.
- 공개된 본문과 근거는 수정하지 않고 새 공개 기록으로 대체한다.
- 팀 조회에는 공개 또는 대체된 기록만 노출한다.
- 새 기록을 GitHub에 게시할 때 기록 저장소와 PR 저장소, 기록 커밋과 PR `head.sha`가 각각 일치해야 한다. 기존 Check Run의 대체 안내는 원래 커밋을 확인하고 진행된 PR HEAD를 허용한다.
- Check Run은 `intent-trace:<변경 기록 UUID>` `external_id`로 재사용하고 GitHub 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- GitHub 저장소 식별자는 소문자 `owner/repository`로 정규화해 권한·멱등성·조회·게시에서 같은 값으로 비교한다.
- 팀 배포는 Caddy만 host port를 열고 app·PostgreSQL은 Docker network 안에 둔다.
- PostgreSQL에는 제품 데이터만 저장하며 GitHub access·refresh token과 `its_` session은 app 메모리에만 둔다.
- restore는 app 중지와 명시적 `--confirm-replace` 없이는 실행하지 않는다.
- MCP 생성·수정 도구는 Jakarta Validator를 명시적으로 실행하고 전체 Git commit 형식은 도메인 값 객체에서 검증한다. 선택 입력은 MCP 명세에도 선택값으로 등록한다.
- GitHub 자격 증명 보유 객체의 `toString()`에는 실제 비밀값을 넣지 않는다.

## 다음 작업 후보

[0.9.0 후속 개선 검토](docs/reviews/2026-09-05-v0.9-follow-up-review.md)의 7개 항목을 0.10.0에 반영했다. 구현 내용과 검증 범위는 [0.10.0 검증 기록](docs/reviews/2026-09-05-v0.10-verification.md)을 따른다.

[0.10.0 후속 개선 검토](docs/reviews/2026-09-05-v0.10-follow-up-review.md)의 5개 항목을 0.11.0에 반영했다. 범위와 검증은 [0.11.0 검증 기록](docs/reviews/2026-09-05-v0.11-verification.md), 변경 이력의 저장·노출 정책은 ADR-0011을 따른다.

[0.11.0 후속 개선 검토](docs/reviews/2026-09-05-v0.11-follow-up-review.md)의 4개 항목을 0.12.0에 반영했다. [0.12.0 검증 기록](docs/reviews/2026-09-05-v0.12-verification.md)을 따른다. Zed는 로컬 설치 패키지와 레지스트리 제출 자료 생성까지 완료했고 실제 외부 게시·등록은 수행하지 않았다.

2026-09-05 [추가·개선 기능 검토](docs/reviews/2026-09-05-feature-review.md)의 9개 항목은 REST·MCP·로컬 실행 도구의 최소 기능을 구현했다. 계약은 PRD-0004, ADR-0007·0008과 기존 PRD의 확장 절을 따른다. 검색·브라우저 열람·후속 초안·코드 이동·PR 목록·진단과 Zed Agent 연결까지 확장했다. 외부 지표 대시보드와 편집기 인라인 UI는 후속 작업이다.

1. IntelliJ·Zed에서 현재 줄의 공개 변경 의도를 편집기 UI로 조회한다. Zed Agent MCP 연결 도구와 사용 안내는 구현했다.
2. 실제 운영 결과를 바탕으로 encrypted session 저장 필요성을 다시 결정한다.
3. 코드 근거를 Check Run line annotation으로 선택 게시한다.
4. GitHub App webhook으로 사용자 승인·설치 제거와 권한 변경을 반영한다.

## 현재 제한

- 사용자 token 쌍과 `its_` 세션은 메모리 전용이라 재시작과 다중 인스턴스 간에 유지되지 않는다.
- 승인 폐기 webhook은 없다. 웹·REST·MCP에서 본인 세션을 조회·폐기할 수 있다. 연결 이름은 기기를 추정하지 않고 브라우저·Agent/API 채널로 구분한다.
- 팀 배포는 단일 app만 지원하며 무중단 rolling 배포와 여러 host의 session 공유가 없다.
- GitHub 사용자 인증은 요청마다 확인한다. 저장소 권한은 같은 인증 요청 안에서만 재사용하며 새 요청에서는 다시 확인한다. 요청 간 캐시와 webhook 무효화는 없다.
- V3 이전 기록은 `legacy:<login>` subject로 남아 현재 GitHub 계정이 수정할 수 없다.
- 서버 코드 확인은 별도 요청에서 GitHub 객체를 읽으며 `Contents: read` 권한이 필요하다. 결과는 저장하지 않고 호출 시 계산한다. 일부 트리·2 MiB 초과 blob은 확인하지 않는다.
- 코드 이동은 동일 blob의 고유한 이름 변경 또는 원본·현재 파일에서 고유한 전체 줄 조각에 한정한다. 수정·이름 변경 동시 발생과 중복 조각은 자동 연결하지 않는다. 조회는 후보 단위 페이지이며 빈 결과에서도 다음 커서가 있을 수 있다.
- GitHub App 등록·설치와 private key 회전은 운영자가 수행해야 한다.
- installation token 캐시는 프로세스 메모리에만 있어 여러 인스턴스가 공유하지 않는다.
- Fork PR Check Run과 GitHub webhook은 아직 지원하지 않는다.
- 실제 GitHub 저장소 쓰기는 자동 테스트하지 않고 로컬 HTTP 계약으로 검증한다.
- Zed Agent MCP 연결을 구현했다. 인라인 IDE 메뉴와 자동 기록 수집은 없다. 실제 Zed 1.18.1에서 로컬 테스트 응답을 사용해 앱 연결·도구 승인·공개 기록 조회·세션 폐기 후 오류·새 세션 재연결을 확인했다. 실제 GitHub 사용자 승인은 확인하지 않았다.
- 일반 연결 진단은 게시 설정 존재만 확인한다. 관리자용 별도 사전 점검은 App 키·설치·실제 발급 범위·권한을 확인한다. 고정 token은 미확인으로 남기며 실제 Check Run 성공까지 보장하지 않는다.
- 기록 변경·게시 시도 이력을 저장한다. 인증·운영 전체 감사 로그와 자동 보존 정책은 없다. 수집 이전 이력과 과거 본문은 복원하지 않는다.
- Micrometer 지표는 수집하지만 외부 수집기와 대시보드는 별도 연결해야 한다.
- 과거 조회의 기한·호출 수와 전달된 interrupt를 처리한다. 모든 브라우저·MCP 연결 종료가 서버 스레드에 즉시 전달되는 것은 아니다. 원격 인증과 전체 HTTP 응답 시간을 보장하는 SLA는 별도다.
- Zed 배포 패키지는 로컬 설치용이다. 공개 패키지 이름·배포 계정·라이선스를 정한 뒤 등록 자료를 생성할 수 있으며 npm·MCP 레지스트리에 게시하지 않았다.

## 0.10.0 검증 결과

- 전체 서버 테스트: 85개 중 84개 통과, PostgreSQL 전용 1개는 기본 실행에서 제외.
- Zed 연결·JSONC 설정 테스트 5개 통과. 고정 의존성 새 설치와 실제 Spring 서버의 표준 SDK 중계 통합 테스트 통과.
- 플러그인과 사용·개발 스킬 검증 통과. 의존성 감사 결과 알려진 취약점 0개.
- 실제 Zed 1.18.1에서 승인 전 인자 확인, 공개 기록 반환, 폐기된 세션의 오류, 새 세션을 전달한 재실행 후 같은 기록 조회 통과. GitHub와 모델 응답은 로컬 stub이며 사용자 계정·실제 GitHub 게시를 사용하지 않았다.
- 브라우저 비교·진단 화면을 390px 너비에서 확인했다. 로그인 복귀·비공개 비교·PR 게시 결과 미확인은 서버 통합 테스트에 포함한다.
- DB 스키마·JDBC·backup/restore는 변경하지 않았다. 이번 버전에서 별도 PostgreSQL 검증은 재실행하지 않았다.

## 0.11.0 검증 결과

- 전체 서버 테스트 90개 중 89개 통과, PostgreSQL 전용 1개는 기본 실행에서 제외했다.
- 별도 PostgreSQL 17에서 V9·기록 변경 이력 JDBC 조회·backup·restore를 확인했다. 복구 후 변경 기록 2건과 이력 4건이 일치했다.
- Zed 연결·오류·JSONC 테스트 8개 통과. 실제 Spring 서버의 stdio MCP 연결과 새 이력 도구 호출도 통과했다. 고정 의존성 설치·감사에서 알려진 취약점 0개다.
- 플러그인과 사용·개발 스킬 검증 통과. 기록 갱신 도중 이력 저장 실패 시 전체 취소, 버전·생성 재시도, 50개 이력 페이지와 작성자·팀 노출을 확인했다.
- 브라우저 본인 연결 종료의 동일 출처·소유권·cookie 제거와 코드 조회·부분 실패 재조회·비공개 접근·세부 비교를 통합 테스트했다.
- 실제 Chromium에서 서버가 생성한 조회·비교·연결 목록 화면을 390px로 확인했다. 화면 이동·인증·데이터 처리는 서버 통합 테스트 범위다. 이번 버전에서 실제 Zed 앱과 실제 GitHub 사용자는 다시 확인하지 않았다.

## 0.12.0 검증 결과

- 전체 서버 테스트 93개 중 92개 통과, PostgreSQL 전용 1개는 기본 실행에서 제외했다. JDBC·migration·backup·restore는 변경하지 않아 PostgreSQL 별도 검증은 재실행하지 않았다.
- 로컬 HTTP 지연으로 전체 기한·실제 호출 수·interrupt 후 추가 호출 중단을 확인했다. 기록 안의 미완료 근거부터 재개하고 다음 후보를 빠짐없이 읽는 통합 테스트를 통과했다.
- 웹 필터·폐기 기록·로그인 복귀·다음 페이지·지원 불가 안내·조회 중단 화면을 검증했다. 인증된 실제 MCP 요청에서도 선택 입력을 생략한 history 응답을 확인했다.
- Zed 테스트 9개 통과. 빈 npm 캐시와 저장소 밖 폴더에서 오프라인 설치·설정·표준 MCP 연결·Zed 실행 환경 전달을 확인했다. 실제 Spring 서버와 stdio 중계기 연결도 통과했다.
- 플러그인과 사용·개발 스킬 검증 통과. 예시 계정으로 만든 레지스트리 제출 자료는 공식 2025-12-11 JSON 스키마를 통과했다. 공식 레지스트리의 원격 소유권 확인과 게시는 수행하지 않았다.
- 실제 Chromium의 모바일 화면을 확인했다. 페이지 내용은 서버 테스트가 생성한 HTML이며 로그인·검색·데이터 처리는 서버 통합 테스트 범위다. 실제 Zed 앱과 GitHub 사용자 승인·게시는 다시 확인하지 않았다.
