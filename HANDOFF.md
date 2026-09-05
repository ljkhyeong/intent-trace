# IntentTrace 인수인계

## 현재 구현

- IntentTrace 개발 version 0.8.0-SNAPSHOT, 최신 공개 release 0.7.0, Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- H2 기본 저장소와 PostgreSQL 프로필
- Flyway 초기 스키마
- Flyway V2 GitHub 게시 이력, V3 GitHub 작성자 subject, V4 저장소 키, V5 코드 경로 정규화와 V6 미사용 기준 revision 제거 스키마
- 변경 의도 생성·확인·공개·대체·라인 조회
- 저장소·파일·상태별 공개 목록과 내 비공개 기록함, 생성 시각·UUID 순서의 페이지 조회
- 팀 공유용 Markdown 출력
- PR HEAD 커밋 검증과 neutral GitHub Check Run 게시·재시도 갱신
- GitHub 응답의 head·base 저장소 확인과 Fork PR 게시 거부
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신·401 복구
- GitHub user access token 인증과 저장소별 READER·CONTRIBUTOR·MAINTAINER 역할 판정
- GitHub Web Application Flow와 callback `state`·cookie·TTL·일회성 검증
- GitHub access·refresh token 메모리 보관과 만료 전 token 쌍 자동 갱신
- SHA-256 digest로 조회하는 `its_` 로컬 세션과 Codex MCP 인증
- 현재 `its_` 세션 폐기, 사용자별 활성 세션 기본 5개 상한과 오래된 세션 자동 폐기
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 Docker Compose
- 외부 container digest 고정과 전체 Git commit 기반 app image tag·rollback 절차
- 비root·읽기 전용 app container와 분리된 data·edge network
- PostgreSQL 17 migration·JDBC·backup·restore 왕복과 GitHub Actions 검증
- GitHub Actions commit SHA 고정, 중복 실행 취소와 Dependabot 정기 갱신
- 초안 작성자 소유권과 저장소 권한 기반 팀 공개 조회
- 브라우저 기록 열람·로그인 복귀·로그아웃과 8시간 전용 세션
- REST·MCP·브라우저의 제목·요청·판단 키워드 검색
- Zed Agent용 공식 MCP SDK 중계기·설정 생성·연결 점검·세션 토큰 입력 도구
- 실제 Spring 서버와 stdio MCP 초기화·도구 목록·진단 호출 통합 검증
- Flyway V10 후속 초안의 원본 공개 기록 연결과 검증·확인 상태 초기화
- 고유한 동일 blob 이름 변경과 원본 코드 조각의 줄 이동 추적
- PR별 게시·시도 기록 목록과 현재 HEAD 비교, 연결·권한·설정 진단
- 새 GitHub JWT 형식 설치 token 전체 제거, 요청 안의 권한·Git 객체 조회 재사용
- 이전 기록별 확인 불가 사유·처리 완료 여부·실패한 기록 재조회
- 관리자용 App 키 서명·원격 인증·설치·실제 발급 범위·권한 사전 점검
- 원본·후속 기록 비교와 브라우저 PR 기록·연결 진단 화면
- JSONC 설정 미리보기·등록·갱신과 실제 Zed 1.18.1의 도구 승인·조회·세션 폐기·재연결 확인
- Streamable HTTP MCP 도구 24개, 줄 조회의 최상위 `items` 객체 응답
- Zed 초기화·도구 호출·연결 점검의 안전한 오류 분류와 호출 제한 대기 시간 전달
- 웹 파일·줄 조회와 실패 후보 재조회, 별도 코드 근거 확인
- 웹의 코드 확인 불가 사유와 본인 폐기 기록·파일·팀 작성자 필터
- 과거 조회의 기본 30초·40회 HTTP 제한, 근거 순서별 중단·재개
- Zed 의존성 포함 설치 패키지와 버전별 설치·업데이트·등록 자료 생성
- 토큰 입력 보호 실패·취소 시 실행 중단과 잠금 파일 기준 배포 의존성 준비·생성 기준 해시
- 과거 조회 최소 7회 설정과 근거를 처리하지 못한 재개의 `resumeBlocked`·원인 확인 안내
- 웹 본인 연결 조회·선택·전체 종료와 현재 연결 로그아웃
- 비교의 추가·삭제·출처·상대 순서 변경 표시와 중복 항목의 전체 내용 확인
- Flyway V11 기록 변경 이력과 기록 저장의 원자성, 작성자 전체·팀 공개 작업 조회
- 초안 수정·확인 취소·폐기, 최초 내용 해시 멱등성, 저장소별 내 초안·팀 공개 요약 목록
- MCP 기록 대체와 Markdown의 후속 기록 링크
- Flyway V9 게시 시도 이력, 게시 결과 조회·응답 유실 복구·기존 Check Run 대체 안내
- 내 세션 목록과 선택·전체 폐기, 갱신 도중 폐기 처리
- GitHub 호출 제한 429·Retry-After와 기능별 Micrometer 지표
- Flyway V7 최초 생성 내용 해시와 목록 조회 인덱스, V8 코드 근거 BASE·TARGET과 실행 결과 출처
- GitHub 코드 해시 확인과 이전 커밋의 동일 파일·관련 기록 조회
- 로컬 실행 도구의 종료 코드·시각·출력 해시 수집과 변경 파일 감지
- REST·MCP 공통 생성 입력 검증과 전체 Git commit 값 객체
- GitHub token·private key·client secret의 안전한 문자열 표현
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- Codex 조회 스킬의 정확한 줄·기록함·파일 이력 분기, 페이지·상세·대체 기록 조회 안내
- Codex에서 사용자 요청에 따른 공개 기록 대체와 결과 불확실 시 재조회 안내
- IntentTrace 저장소 전용 개발 스킬
- 화면·Markdown·MCP·Zed·문서의 문구 개선안 52개 반영과 모바일 안내의 단어 단위 줄바꿈
- IntelliJ 2025.3+ 현재 줄 공개 변경 의도 조회와 PasswordSafe 세션 저장
- IntelliJ에서 기존 GitHub 승인 페이지 열기와 PasswordSafe 세션의 서버 폐기·삭제
- IntelliJ 공용 서버 주소 설정, 재시작 없는 주소 적용과 인증 정보 없는 연결 확인
- IntelliJ 기록함과 파일 이력, 전체 커밋·당시 코드·대체 기록 탐색
- IntelliJ 기록함 조회 실패 시 마지막 성공 필터 복원과 기존 목록·선택·페이지 유지
- tag와 프로젝트 version을 확인한 뒤 서버 JAR·IntelliJ ZIP·SHA-256 파일을 함께 발행하는 GitHub Actions
- Apache License 2.0과 Hope HTML의 MIT·OFL-1.1 제3자 라이선스 고지
- SECURITY 정책과 0.6.0 변경 이력

## 반드시 지킬 규칙

- 초안은 만든 작성자만 확인한다.
- 초안·확인·폐기 기록은 만든 작성자만 조회하고, 공개·대체 기록은 저장소 읽기 권한이 있는 팀원만 조회한다.
- 작성자는 요청 본문이 아니라 `/user`에서 확인한 GitHub 숫자 ID로 결정한다.
- 브라우저에는 기록 화면에서만 쓰는 `itb_` cookie를 발급하며 REST·MCP에서 사용하지 않는다.
- Codex에는 GitHub token 대신 `its_` session token만 전달하고 GitHub token 쌍은 메모리 밖으로 노출하지 않는다.
- callback은 같은 브라우저의 cookie와 미사용 `state`가 일치할 때만 code를 교환한다.
- 미완료 OAuth `state`는 TTL과 전역 개수 상한으로 제한하고 상한 도달 시 새 승인을 거부한다.
- refresh token은 한 번 사용한 뒤 새 access·refresh token 쌍으로 함께 교체하고, 사용자 subject가 바뀌면 세션을 폐기한다.
- 갱신 거부 또는 응답 수신·파싱·token 값 변환 실패 시 세션을 폐기하고 `401`로 재승인을 요구한다. 잠금을 기다리던 요청도 폐기된 세션을 사용하지 않는다. 단순 사용자 조회 장애는 `502`로 구분하고 세션을 유지한다.
- 사용자별 활성 세션은 기본 5개로 제한하고 새 세션 발급 시 가장 오래된 세션을 폐기한다. `DELETE /api/v1/session`은 현재 `its_` 세션만 폐기한다.
- 생성·확인·공개·대체·GitHub 게시는 저장소 쓰기 권한이 필요하다.
- 확인 시 전체 Git 커밋 ID가 필요하다.
- 확인과 공개 시 현재 스냅샷이 기록의 스냅샷과 같아야 한다.
- 공개된 본문과 근거는 수정하지 않고 새 공개 기록으로 대체한다.
- 팀 조회에는 공개 또는 대체된 기록만 노출한다.
- 새 기록을 GitHub에 게시할 때 기록 저장소와 PR 저장소, 기록 커밋과 PR `head.sha`가 각각 일치해야 한다. 기존 Check Run의 대체 안내는 원래 커밋을 확인하고 진행된 PR HEAD를 허용한다.
- 목록은 저장소 읽기 권한을 먼저 확인하고 SQL에서 공개 상태 또는 현재 작성자의 비공개 상태를 제한한 뒤 페이지로 나눈다.
- 파일 이력은 정확한 상대 경로로만 조회하며 과거 줄·검증을 현재 편집기 코드의 근거로 자동 해석하지 않는다.
- Check Run은 `intent-trace:<변경 기록 UUID>` `external_id`로 재사용하고 GitHub 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- 같은 기록의 게시 요청은 PR 번호가 달라도 단일 app에서 직렬화한다. PR별 HEAD 확인과 게시 이력은 따로 유지하고, Check Run 검색 한도를 다 채우면 중복 생성하지 않는다.
- GitHub 저장소 식별자는 소문자 `owner/repository`로 정규화해 권한·멱등성·조회·게시에서 같은 값으로 비교한다.
- 코드 근거 경로는 `./`, 중복 `/`, 끝 `/`을 제거해 저장과 라인 조회에서 같은 값으로 비교한다. 정규화 결과는 서버 운영체제와 관계없이 `/`로 연결한다.
- 스냅샷 helper는 `core.quotePath=true`의 기존 줄바꿈 출력을 사용해 개인 Git 설정의 영향을 제거한다. 예전 `false` 설정의 해시는 README의 명시적인 호환 명령으로 재현하며 저장값과 비교 규칙은 변경하지 않는다.
- 코드 근거 helper는 Git `blob`과 실제 파일의 줄 범위만 받으며 디렉터리(`tree`)는 거부한다.
- 코드 심벌 이름(`symbolName`)도 설명 필드와 같은 비밀값·개인 home 절대 경로 제거를 거쳐 저장한다.
- 같은 `requestId`는 작성자·저장소와 정규화된 저장 내용이 모두 같은 재시도에만 기존 기록을 반환한다.
- 검증 시작·종료 시각은 DB와 같은 마이크로초 반올림을 적용해 비교·저장한다. 이전 저장 방식의 재시도도 같은 정밀도로 비교한다.
- 비밀값 제거 후 문자열 길이가 저장 한도를 넘으면 원문을 포함하지 않은 입력 오류로 거부하고 내용을 자르지 않는다.
- 기록 대체는 두 기록을 ID 순서로 잠그는 DB 트랜잭션 안에서 최신 상태를 확인한다. GitHub 권한 조회는 트랜잭션 밖에서 수행한다.
- 팀 배포는 Caddy만 host port를 열고 app·PostgreSQL은 Docker network 안에 둔다.
- PostgreSQL에는 제품 데이터만 저장하며 GitHub access·refresh token과 `its_` session은 app 메모리에만 둔다.
- restore는 app 중지와 명시적 `--confirm-replace` 없이는 실행하지 않는다.
- MCP 생성·수정 도구는 Jakarta Validator를 명시적으로 실행하고 전체 Git commit 형식은 도메인 값 객체에서 검증한다. 선택 입력은 MCP 명세에도 선택값으로 등록한다.
- GitHub 자격 증명 보유 객체의 `toString()`에는 실제 비밀값을 넣지 않는다.
- GitHub 연동과 IntelliJ 응답 파싱 오류는 응답 원문을 포함할 수 있는 원인 예외를 전달하지 않는다.
- IntelliJ 플러그인은 `its_` session만 PasswordSafe에 저장하고 GitHub token을 입력받지 않는다.
- IntelliJ 현재 줄 조회는 커밋된 파일과 전체 HEAD commit만 사용한다.
- IntelliJ HTTP 조회는 연결 5초·응답 읽기 10초 제한, redirect 금지와 4MiB(4,194,304바이트) 응답 상한을 유지한다. 서버 입력 상한의 단건 기록은 수용하고, 여러 기록의 합계가 상한을 넘으면 기록함에서 개별 조회한다.
- IntelliJ 승인 시작은 기존 서버 URL만 열고 callback·state·PKCE는 서버가 처리한다.
- IntelliJ 서버 주소는 로컬 IDE 설정, 환경 변수, 기본 loopback 주소 순서로 선택하며 설정 동기화에서 제외한다. 연결 확인은 인증 정보 없이 health만 조회하고 설정을 저장하지 않는다.
- PasswordSafe와 환경 변수 세션은 해당 서버에서만 사용한다. PasswordSafe 세션은 서버 폐기 성공 또는 이미 만료된 `401` 뒤 삭제하고, 서버 장애 때는 다시 시도할 수 있도록 유지한다. 환경 변수 세션이 있으면 연결은 유지된다고 안내한다.

## IntelliJ 설치와 화면 검증 (2026-08-30)

- IntelliJ IDEA 2025.3.2에 서버 설정 기능 추가 전의 `intent-trace-intellij-0.8.0-SNAPSHOT.zip`을 설치하고, 시작 로그의 `IntentTrace (0.8.0-SNAPSHOT)` 로드를 확인했다. 기존 0.7.0 플러그인은 로컬 `build/installed-plugin-backup-0.7.0-20260830/`에 보관했다.
- 운영 서버 대신 loopback의 메모리 응답 서버를 연결했다. 테스트 세션은 서버와 IDE 프로세스에만 전달했으며 PasswordSafe의 기존 세션은 변경하지 않았다. 이 확인은 OAuth·서버 권한 검증이 아니다.
- `Tools` 메뉴에서 기록함 열기·GitHub 승인 시작·현재 줄 조회·세션 연결·저장 세션 삭제가 표시되는 것을 확인했다.
- 실제 기록함에서 팀 공개 기록 22건을 1페이지 20건·2페이지 2건으로 조회하고 이전 페이지로 돌아왔다. 첫 페이지의 이전 버튼과 마지막 페이지의 다음 버튼은 비활성화됐다.
- `현재 파일만`을 선택해 같은 파일의 공개·대체 기록 4건으로 줄어드는 것과, 적용된 파일 경로·페이지·건수 표시를 확인했다.
- 이후 화면 제어 도구가 IDE 창 내용을 빈 값으로 반환해 추가 조작을 중단했다. 이때 남은 동선의 후속 결과는 아래 `IntelliJ 기록함 추가 화면 검증`에 기록했다.
- 테스트용 IDE 프로세스에 종료 신호를 보내 메모리 응답 서버와 테스트 세션을 정리했다. 이후 테스트 환경 변수 없이 IntelliJ를 다시 열고 0.8.0-SNAPSHOT 로드를 확인했다.
- 자동 검증은 서버 `test`, IntelliJ `test`·프로젝트 구성·ZIP 구조 검사, `scripts/validate-plugin.sh`가 성공했다. 제품 코드 변경이 없어 두 `test` 작업은 기존 성공 결과를 재사용했다(`UP-TO-DATE`).

## IntelliJ 서버 설정 자동 검증 (2026-08-30)

- IntelliJ 테스트 29개가 모두 통과했다. 주소 우선순위·설정 저장과 복원, 실제 SDK 설정 패널의 적용·초기화·잘못된 주소 거부, 서버별 세션 분리와 인증 정보 없는 health 요청을 확인했다.
- `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`와 `scripts/validate-plugin.sh`가 성공했다.
- 서버 `test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`). 결과는 104개 중 100개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. 서버·DB 코드는 이번 작업에서 변경하지 않았다.
- 검증한 설치 ZIP은 `intellij-plugin/build/distributions/intent-trace-intellij-0.8.0-SNAPSHOT.zip`이다. 이후 실제 설치·화면 검증 결과는 아래에 기록했다.

## IntelliJ 서버 설정 화면 검증 (2026-08-30)

- `84f1dc5`의 서버 설정 기능이 포함된 0.8.0-SNAPSHOT ZIP을 IntelliJ IDEA 2025.3.2에 설치했다. ZIP의 JAR과 설치된 JAR의 SHA-256이 일치했다. 기존 플러그인은 `build/installed-plugin-backup-before-settings-20260830/`에 보관했다.
- `Settings > Tools > IntentTrace`와 `Tools > IntentTrace 서버 설정`에서 같은 설정 화면이 열리는 것을 확인했다.
- 정상 loopback 서버의 연결 확인은 `UP` 안내를 표시했다. 연결 확인만 실행하고 취소한 주소는 저장되지 않았으며, 설정을 다시 열면 원래 빈 값이 유지됐다.
- 주소의 앞뒤 공백과 끝 슬래시가 정리된 값으로 적용됐다. IDE를 정상 종료한 뒤 다시 실행해도 저장된 주소가 복원됐다. 설정 파일에는 서버 주소만 들어 있었다.
- 서로 다른 두 loopback 서버로 주소를 바꾼 뒤 승인 시작 액션이 각각 새 서버의 `/auth/github/start`를 호출했다. 두 번째 전환에는 IDE 재시작이 필요하지 않았다. 테스트 서버는 안내 문구만 반환하고 실제 GitHub 승인·token 교환은 하지 않았다.
- 장애 서버는 로그인 만료가 아닌 `HTTP 503` 안내를 표시했다. 외부 HTTP 주소는 적용 시 거부됐고, 변경 내용 되돌리기로 기존 저장값이 복원됐다. 이후 정상 주소를 적용하면 오류 안내가 화면에서 사라졌다.
- 세 번의 health 요청과 두 번의 승인 시작 요청에 인증 헤더가 없음을 로컬 서버에서 확인했다. 헤더의 유무만 기록했으며 값은 출력하지 않았다. PasswordSafe 세션과 환경 변수 세션의 서버별 분리는 기존 자동 테스트 범위이고, 이번 화면 검증에서는 저장 세션을 조회·변경하지 않았다.
- 검증 후 원래의 빈 서버 설정을 저장하고 설정 파일에서도 빈 값을 확인했다. 테스트 서버 3개를 종료하고 확인용 Chrome 탭 2개만 닫았다. 새 플러그인은 설치된 상태로 유지했다.
- IntelliJ 테스트 29개, 프로젝트 구성·ZIP 구조 검사와 `scripts/validate-plugin.sh`가 성공했다. 서버 `test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`, 100개 통과·PostgreSQL 조건부 테스트 4개 건너뜀). 제품 코드는 이번 작업에서 변경하지 않았다.

## IntelliJ 기록함 추가 화면 검증 (2026-08-30)

- 서버 설정 기능이 포함된 설치 ZIP을 그대로 사용했다. 제품 코드는 변경하지 않았고, 기록 응답은 loopback 메모리 서버가 제공했다. 임시 세션은 서버와 IDE 프로세스에만 전달했으며 기존 PasswordSafe 세션은 변경하지 않았다. OAuth·운영 서버 권한 검증은 이번 범위가 아니다.
- 대체된 기록의 상세에서 요청, 판단 출처, 코드 근거, 검증과 미확인 항목을 확인했다. `당시 스냅샷 기준`과 `현재 편집 중인 코드의 검증이 아닙니다` 안내가 표시됐다. 검증 예시도 실제 실행 증거가 아닌 화면 표시용 데이터라고 표시했다.
- `대체 기록 열기`가 후속 공개 기록을 별도 상세 요청으로 조회했다. 후속 기록에 대체 대상이 없으면 해당 버튼은 비활성화됐고, 창을 닫으면 원래 상세와 목록으로 돌아왔다.
- `원래 커밋 열기`와 `당시 코드 열기`가 실제 Chrome에서 GitHub 페이지를 열었다. 두 링크는 현재 HEAD가 아닌 테스트 기록의 `v0.7.0` 전체 커밋 `6c1c7a4240f9c3898537e88df272c02101f58540`을 사용했다. 코드 페이지는 기록의 상대 경로와 `#L12-L17`을 유지했고 실제 파일 본문이 표시됐다.
- 현재 Kotlin 파일의 26번째 줄 조회 결과가 없을 때 안내 창에서 `이 파일의 과거 기록 보기`를 선택했다. 같은 파일로 제한된 공개·대체 기록 4건이 표시됐으며, 현재 줄의 직접 근거로 표시하지 않았다.
- 팝업을 열지 않고 Tab·End로 `내 비공개 기록 · 작성자 확인`을 선택해 해당 파일의 기록 1건을 조회했다. README에서는 같은 상태·현재 파일 조건으로 0건이 반환됐고, 빈 결과 안내와 이전·다음·상세 버튼 비활성화를 실제 화면에서 확인했다.
- 필터 팝업을 닫은 뒤 화면 제어 도구가 접근성 내용과 스크린샷을 반환하지 않는 현상이 반복됐다. IDE 정상 재시작 두 번과 키보드 경로로 확인 범위를 넓혔지만, `팀 공개 기록 · 공개/대체됨`, `내 비공개 기록 · 전체/초안`의 조회 결과와 커밋 없는 초안의 이동 버튼은 아직 화면 검증을 마치지 못했다. 제품 오류로 단정하지 않는다.
- 검증용 Chrome 탭 2개를 닫고 테스트 IDE를 정상 종료해 메모리 서버와 임시 세션을 정리했다. 서버 설정이 원래 빈 값인 것을 확인했다. 이후 테스트 환경 변수 없이 원래 파일을 연 IntelliJ로 복원했다.
- 서버·IntelliJ `test`, IntelliJ 프로젝트 구성·ZIP 구조 검사와 `scripts/validate-plugin.sh`가 성공했다. 두 `test` 작업은 기존 결과를 재사용했다(`UP-TO-DATE`: 서버 100개 통과·PostgreSQL 조건부 4개 건너뜀, IntelliJ 29개 통과). 전체 수동 검증과 0.8.0 릴리스 준비가 끝난 상태는 아니다.

## MCP 기록 대체와 IntelliJ 조회 실패 복원 (2026-08-31)

- MCP `supersede_change_record`가 REST와 같은 대체 서비스를 호출한다. 기존 작성자·저장소·상태·버전 규칙과 공개 본문·증거 불변성을 유지하며, 새 검증 계층이나 DB 스키마를 추가하지 않았다.
- Codex 조회·기록 스킬에 사용자의 명시적 대체 요청, 후속 기록의 사전 공개, 기존 기록의 조회 버전 사용과 결과 불확실 시 재조회 절차를 추가했다. GitHub Check Run은 자동 갱신하지 않는다.
- 실제 `/mcp` 요청을 통한 대체 성공, 타인 요청 거부, 오래된 버전 거부와 기존 본문·후속 기록 보존을 통합 테스트했다. 인증은 기존 fake GitHub 사용자 응답과 호환 Bearer 경로를 사용했으며, 실제 OAuth·GitHub 권한 검증을 새로 수행한 것은 아니다.
- IntelliJ SDK 테스트에서 필터 변경·페이지 이동 실패 시 마지막 성공 조건과 목록·선택·페이지가 유지되고, 이후 조회 성공 시 새 조건과 결과가 적용되는 것을 확인했다.
- 서버 `./gradlew --no-daemon test`는 105개 중 101개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. DB 계약은 변경하지 않아 PostgreSQL 별도 검증은 재실행하지 않았다.
- IntelliJ `test` 30개가 모두 통과했고 `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, `scripts/validate-plugin.sh`와 스킬 `quick_validate.py`가 성공했다.
- `intellij-plugin/build/distributions/intent-trace-intellij-0.8.0-SNAPSHOT.zip`을 다시 빌드했다. 이번 ZIP은 설치하지 않았으며, 조회 실패 복원과 앞서 남은 화면 동선의 실제 IDE 검증은 아직 남아 있다. 푸시·릴리스는 하지 않았다.

## IntelliJ 조회 실패 복원 화면 검증 (2026-08-31)

- `4f1963a`의 조회 실패 복원이 포함된 0.8.0-SNAPSHOT ZIP을 IntelliJ IDEA 2025.3.2에 적용했다. 설치된 JAR과 빌드 JAR의 SHA-256이 일치했고 시작 로그에서 플러그인 로드를 확인했다. 기존 JAR은 `build/installed-plugin-backup-before-query-restore-20260831/`에 보관했다.
- 기존 loopback 메모리 응답 서버에 다음 목록 요청 한 번만 HTTP 503으로 반환하는 제어를 추가해 화면을 확인했다. 임시 세션은 서버와 IDE 프로세스에만 전달했고 PasswordSafe와 실제 GitHub 데이터는 변경하지 않았다. OAuth·운영 서버 권한 검증은 이번 범위가 아니다.
- 팀 공개 기록의 2페이지에서 기록 하나를 선택하고 `현재 파일만`과 `내 비공개 기록 · 작성자 확인`으로 조건을 변경했다. 조회 실패 안내를 닫자 팀 공개 전체·저장소 전체 조건, 2페이지의 2건과 선택한 기록이 복원됐다. 이전·다음·상세 버튼 상태도 기존 페이지와 일치했다.
- 같은 2페이지에서 이전 페이지 요청을 실패시킨 뒤에도 페이지와 선택이 유지됐다. 다시 이전 페이지를 요청하면 1페이지의 20건으로 이동하고 첫 페이지의 이전 버튼이 비활성화됐다.
- 키보드로 상태 필터 팝업을 열고 닫은 뒤 화면 제어 도구가 다시 접근성 내용과 스크린샷을 반환하지 않았다. 정확한 앱 식별자로 재조회해도 같아 추가 조작을 중단했다. 남은 네 가지 상태 필터의 결과와 커밋 없는 초안의 이동 버튼은 아직 수동 검증이 필요하며, 제품 오류로 단정하지 않는다.
- 검증용 IDE에 종료 신호를 보내 로컬 서버와 임시 세션을 정리했다. 서버 설정이 원래 빈 값인 것을 확인하고 테스트 환경 변수 없이 IntelliJ를 다시 열었다. 새 플러그인은 설치된 상태로 유지했다.
- 서버·IntelliJ `test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`: 서버 101개 통과·PostgreSQL 조건부 4개 건너뜀, IntelliJ 30개 통과). IntelliJ 프로젝트 구성·ZIP 구조 검사와 `scripts/validate-plugin.sh`도 성공했다. 제품 코드는 변경하지 않았고 푸시·릴리스는 하지 않았다.

## IntelliJ 필터 팝업 화면 조회 오류 진단 (2026-08-31)

- 같은 IntelliJ 2025.3.2와 설치된 `4f1963a` 빌드에서 재현했다. 기록함의 상태 필터 `JComboBox`를 열면 도구가 팝업의 접근성 항목만 반환하고 스크린샷은 이미 `null`이었다. 항목을 선택해 닫으면 창 제목만 남고 접근성 항목과 스크린샷이 모두 반환되지 않았다. 이는 화면 제어 도구의 응답이며 IDE 화면이 실제로 하얗게 변했다는 뜻은 아니다.
- 상태 필터 선택 자체에는 서버 요청을 실행하는 리스너가 없다. 서버 조회는 `조회`·페이지 이동 버튼에서 실행하므로, 이번 재현은 목록 API 실패와 별개다.
- 재현 전후 `jcmd Thread.print -l`에서 UI 스레드는 `EventQueue.getNextEvent`를 기다렸으며 네트워크 호출에 막혀 있지 않았다. `Esc` 입력 후 기록함의 모달 이벤트 루프가 사라졌고, 설정 열기 단축키 뒤에는 다시 모달 루프가 생겼다. IDE는 입력을 처리하지만 도구가 새 창 내용을 읽지 못하는 상태였다.
- 정상 상태에서 IntelliJ 설정의 키맵 선택 팝업은 열고 닫은 뒤에도 접근성 내용과 스크린샷을 반환했다. 재현 시점의 IDE 로그에는 새 IntentTrace 예외나 `CAccessibility` 오류가 확인되지 않았다. 기존 접근성 로그를 이번 증상의 원인으로 단정하지 않는다.
- 정확한 앱 식별자, 전체 접근성 재조회, 다른 앱으로 전환, Node 실행 세션 초기화로는 복구되지 않았다. 같은 상태에서 Finder의 화면 조회는 가능했다. 공용 화면 제어 서비스는 그대로 두고 IntelliJ만 다시 실행하자 접근성 내용과 스크린샷이 복구됐다.
- 현재 근거는 Swing 팝업 이후 해당 IDE 프로세스의 화면·접근성 조회가 실패하는 연동 문제를 가리킨다. IDE 전체 멈춤이나 서버 장애는 이번 재현과 맞지 않는다. 다만 화면 제어 서비스의 창 선택 상태와 JBR의 macOS 접근성 상태 중 어느 쪽에 결함이 있는지는 확정하지 못했다. 공용 서비스만 재시작하는 비교는 다른 화면 조작 연결에 영향을 줄 수 있어 승인 전에는 실행하지 않았다.
- 설치 SDK 확인 결과 `ComboBox.setSwingPopup(false)`는 JetBrains 팝업 구현을 선택하는 API다. 기본 `JComboBox`를 `ComboBox`로 바꾸기만 해서는 팝업 방식이 자동으로 바뀌지 않는다. [JetBrains 구현](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/openapi/ui/ComboBox.java)을 참고해 별도 비교할 수 있으나, 이번에는 제품 코드를 변경하지 않았고 해결 효과도 검증하지 않았다.
- 진단용 IDE에 종료 신호를 보내 loopback 서버와 임시 메모리 세션을 정리했다. 원래 빈 서버 설정과 PasswordSafe 세션을 유지했고 테스트 환경 변수 없이 IntelliJ를 복원했다. 이번 기록은 팝업 오류 진단 결과이며, 남은 상태 필터·초안 버튼의 수동 검증을 완료한 것은 아니다.
- 문서 변경 후 서버 `./gradlew --no-daemon test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`). `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다. 제품 코드·설치 파일을 변경하지 않았고 푸시·릴리스는 하지 않았다.

## 화면 제어 서비스 재시작 비교 (2026-08-31)

- 사용자 승인 후 같은 설치 빌드와 loopback 메모리 서버, 화면 제어 서비스 `26.828.1000919`로 필터 팝업 오류를 다시 재현했다. 팝업을 열 때 스크린샷이 `null`이 됐고, 항목 클릭 뒤에는 창 제목만 반환됐다. UI 스레드는 여전히 `EventQueue.getNextEvent`를 기다렸다.
- 오류 상태의 IntelliJ 프로세스는 그대로 두고 `SkyComputerUseService`만 종료한 뒤 같은 설치 경로에서 다시 실행했다. 서비스 PID는 `1201 → 29123`으로 바뀌었지만 IntelliJ PID `89150`은 유지됐다. 제어 도구의 JavaScript 세션도 초기화하지 않았다.
- 서비스 재시작 직후 같은 기록함의 접근성 항목과 스크린샷이 복구됐다. 이어 `조회` 버튼을 누르자 loopback 서버가 팀 공개 전체 1페이지 20건을 반환했고, 목록과 버튼 상태를 다시 읽을 수 있었다.
- 이번 비교는 화면 제어 서비스가 유지하던 앱별 창·접근성 조회 상태의 문제가 유력하다는 근거다. 복구에 제품 코드 변경이나 IntelliJ 재시작이 필요하지 않았다. 다만 서비스 내부의 어떤 상태가 잘못됐는지는 확인하지 못했으며, 재시작은 복구 방법이지 재발 방지 수정은 아니다. 이 결과만으로 제품의 `JComboBox`를 바꿀 필요가 있다고 판단하지 않는다.
- 복구 후 화면에서 종료 확인을 눌러 진단용 IDE를 정상 종료했다. 메모리 서버도 종료 코드 0으로 끝났고, 빈 서버 설정과 기존 PasswordSafe 세션을 유지한 채 테스트 환경 변수 없이 IntelliJ를 복원했다. 제품 코드와 설치 파일은 변경하지 않았다. 남은 네 가지 상태 필터·커밋 없는 초안 버튼의 수동 검증은 이번 비교에 포함하지 않았다.
- 문서 변경 후 서버 `./gradlew --no-daemon test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`). `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다. 푸시·릴리스는 하지 않았다.

## 큰 기록 조회와 저장소 경로 구분자 수정 (2026-08-31)

- IntelliJ 성공 응답 상한을 1,000,000바이트에서 4MiB(4,194,304바이트)로 조정했다. 서버 입력 한도와 저장된 기록은 변경하지 않았으며, 상한을 넘는 응답은 계속 거부한다. 현재 줄 조회의 여러 기록 합계가 상한을 넘으면 사용자가 기록함에서 개별 조회한다.
- JDK `Path.normalize()`의 경로 요소를 `/`로 연결한다. Windows에서 정규화한 경로가 역슬래시로 바뀌어 `CodeAnchor.copy()`의 검사에서 실패하던 경로를 수정했다. 새 경로 처리 클래스나 검증 계층은 추가하지 않았다.
- REST 통합 테스트에서 필드별 상한의 한글과 JSON 이스케이프 입력을 H2에 저장하고 상세를 조회했다. 두 응답 모두 기존 1MB를 넘고 새 4MiB 상한 안에 들어왔다. 실제 GitHub 인증·데이터는 사용하지 않았다.
- IntelliJ 로컬 HTTP 테스트에서 1MB를 넘는 기록의 단건·현재 줄 조회 성공, 정확히 4MiB인 응답 성공과 한 바이트 초과 응답 거부를 확인했다. 경로 정규화 후 코드 근거 재생성과 재정규화도 회귀 테스트에 추가했다.
- 서버 `./gradlew --no-daemon test`는 108개 중 104개 통과·PostgreSQL 조건부 4개 건너뜀이다. IntelliJ `test` 31개, `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, `scripts/validate-plugin.sh`와 `git diff --check`가 성공했다. 두 `test` 작업 모두 이번 수정으로 다시 실행됐다.
- DB 스키마·SQL은 변경하지 않아 PostgreSQL 별도 검증은 재실행하지 않았다. Windows 실행 환경과 실제 IntelliJ 화면에서는 이번 수정을 검증하지 않았다. 0.8.0-SNAPSHOT ZIP은 다시 빌드했지만 설치·푸시·릴리스는 하지 않았다.

## Git 증거 해시 일관성과 파일 객체 검사 (2026-08-31)

- `snapshot`의 Git 명령에 `core.quotePath=true`를 고정했다. 한글 등 비ASCII 파일명이 있어도 개인 설정에 따라 해시가 달라지지 않고, 기존 기본 설정의 해시는 유지한다. 줄바꿈 출력 형식과 저장된 기록의 해시는 변경하지 않았다.
- 예전 `core.quotePath=false`로 만든 기록은 README의 명시적 재현 명령 결과를 기존 `snapshotDigest`와 비교한다. 새 기록은 기본 helper를 사용하며, 서버에 두 해시를 자동 허용하는 로직이나 DB migration을 추가하지 않았다.
- `anchor`의 객체 존재 검사를 `blob` 타입 검사로 바꿨다. 디렉터리 목록이 파일 내용으로 해싱되지 않으며, 없는 파일·빈 파일의 줄 요청·파일 끝을 넘는 요청도 계속 거부한다.
- 임시 Git 저장소의 한글 파일명을 사용해 설정별 스냅샷 일치와 기존 기본 해시 유지, 실제 파일 범위와 디렉터리 거부를 검사했다. 수정 전 두 테스트가 실패하고 수정 후 모두 통과하는 것을 확인했다. 스크립트 변경도 테스트를 다시 실행하도록 Gradle 테스트 입력에 등록했다.
- `./gradlew --no-daemon test`는 109개 중 105개 통과·PostgreSQL 조건부 4개 건너뜀이다. `sh -n scripts/git-evidence.sh`, `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다.
- 서버 API·DB·IntelliJ 코드는 변경하지 않았다. PostgreSQL 별도 검증과 IntelliJ 테스트·ZIP 빌드는 재실행하지 않았고, 푸시·릴리스는 하지 않았다.

## 비밀값 이스케이프와 큰 줄 번호 오류 수정 (2026-08-31)

- 저장 전 비밀값 제거 정규식에서 이스케이프된 따옴표·역슬래시를 함께 처리한다. 비밀값의 뒷부분을 남기지 않고 뒤의 일반 필드는 유지한다. 별도 파서나 검증 계층은 추가하지 않았다.
- 코드 근거 helper가 셸 내장 숫자 비교로 `1 ≤ 시작 줄 ≤ 끝 줄 ≤ 10,000,000`을 확인한다. 숫자 비교 자체가 실패하는 큰 입력도 해시를 출력하지 않고 입력 오류로 종료한다. 정상 파일·줄의 해시 형식은 유지한다.
- 기존 테스트 클래스에 회귀 테스트를 각각 하나씩 추가했다. 따옴표·역슬래시가 섞인 값과 긴 비밀값, 셸 정수 범위를 넘는 시작·끝 줄을 확인했다. 수정 전 새 테스트 2개가 실패했고 수정 후 관련 테스트 8개가 모두 통과했다.
- `./gradlew --no-daemon test`는 111개 중 107개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. `sh -n scripts/git-evidence.sh`, `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다.
- REST·MCP 필드와 DB·IntelliJ 코드는 변경하지 않았다. PostgreSQL 별도 검증과 IntelliJ 테스트·ZIP 빌드는 재실행하지 않았다.
- 기존 저장 데이터와 GitHub 게시물은 조회하거나 자동 재처리하지 않았다. 이미 저장·공개된 기록에 대한 비밀값 잔여 여부 점검은 이번 범위에 포함하지 않았으며, 푸시·배포·릴리스도 하지 않았다.

## 동시 PostgreSQL backup 파일 보존 수정 (2026-09-01)

- `backup-postgres.sh`가 `pg_dump`를 최종 경로와 같은 디렉터리의 임시 파일에 만든다. `0600` 권한과 비어 있지 않은 결과를 확인한 뒤 하드 링크로 최종 경로를 배타적으로 확보한다.
- 같은 출력 경로의 다른 실행이 먼저 완료됐으면 후속 실행은 실패한다. 실패한 실행의 종료 처리는 자신이 만든 임시 파일만 삭제하므로 먼저 완료된 backup을 덮어쓰거나 삭제하지 않는다.
- 가짜 `docker`와 두 백업 프로세스를 사용한 회귀 테스트를 추가했다. 두 번째 덤프가 성공하는 경우와 실패하는 경우 모두 첫 backup 보존, 후속 실행 실패와 임시 파일 정리를 확인한다. 수정 전에는 각각 덮어쓰기와 파일 삭제로 실패했고 수정 후 통과했다.
- GitHub Actions 서버 검증에 동시 backup 파일 보존 테스트를 추가했다. 운영 절차와 ADR에는 배타적 최종 경로 확보와 실패 정리 범위를 기록했다.
- `./gradlew --no-daemon test`, `python3 scripts/test_backup_postgres.py`, `sh -n scripts/backup-postgres.sh`, `scripts/validate-plugin.sh`와 `git diff --check`가 성공했다.
- 별도 PostgreSQL 17 임시 Compose 프로젝트에서 migration·JDBC와 backup·restore 왕복을 실행해 기록 12건 복원을 확인했다. 검증 container·network·volume은 종료 시 정리됐다. 실제 팀 DB와 기존 backup은 사용하거나 변경하지 않았다.
- DB schema·REST·MCP·IntelliJ 코드는 변경하지 않았다. 푸시·배포·릴리스도 하지 않았다.

## Git 증거 생성 실패 전파 수정 (2026-09-01)

- `git-evidence.sh`가 스냅샷과 코드 근거의 Git 출력을 실행별 임시 작업 공간에 먼저 저장한다. Git 조회와 줄 추출이 각각 성공한 뒤 파일을 SHA-256으로 해싱하므로 앞 단계의 실패를 빈 내용의 성공 해시로 바꾸지 않는다.
- 정상 스냅샷과 코드 근거의 기존 해시가 유지되는 것을 직접 비교했다. Git 트리 객체를 잠시 이동한 재현에서도 종료 코드 `128`과 Git 오류만 반환하고 64자리 해시를 출력하지 않았다.
- Git 트리 조회 실패 회귀 테스트를 추가했다. 테스트 저장소의 loose tree 객체를 잠시 이동하고 `finally`에서 복구해 유효한 커밋의 트리 읽기 실패가 성공 처리되지 않는지 확인한다. 관련 테스트 4개가 모두 통과했다.
- `./gradlew --no-daemon test`는 112개 중 108개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. `sh -n scripts/git-evidence.sh`, `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다.
- DB schema·REST·MCP·IntelliJ 코드는 변경하지 않았다. PostgreSQL 별도 검증과 IntelliJ 테스트·ZIP 빌드는 재실행하지 않았으며 푸시·배포·릴리스도 하지 않았다.

## 셸 종료 신호와 코드 근거 규칙 정리 (2026-09-01)

- `git-evidence.sh`, `backup-postgres.sh`, `verify-postgres.sh`의 정리 동작을 `EXIT`에만 두고 `HUP`·`INT`·`TERM`은 각각 129·130·143으로 종료하게 분리했다. 종료 신호 뒤 성공 경로를 계속 실행하지 않으며 `EXIT`에서 실행별 임시 파일·디렉터리·Compose 자원을 정리한다.
- 백업 중 `TERM`을 보낸 회귀 테스트를 추가했다. 수정 전에는 정리 후 다음 명령을 계속 실행해 빈 백업 오류 코드 `1`로 끝났고, 수정 후에는 `143`으로 종료하며 임시 파일과 최종 backup을 남기지 않는다. 기존 동시 backup 보존 테스트도 함께 통과했다.
- 코드 근거의 10,000,000줄 상한을 `CodeAnchor` 도메인 상수로 옮기고 REST·MCP DTO가 같은 상수를 참조하게 했다. helper의 셸 비교도 이름을 붙인 같은 값으로 유지한다. 수정 전에는 직접 만든 `CodeAnchor`가 상한을 넘을 수 있었고 수정 후 거부한다.
- `anchor`는 `git cat-file -t`와 `git show`를 나눠 호출하지 않고 `git cat-file blob` 한 번으로 객체 타입과 내용을 확인한다. 정상 파일의 기존 해시가 유지되고 디렉터리·없는 파일 거부도 기존 테스트로 확인했다.
- `./gradlew --no-daemon test`는 113개 중 109개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. `python3 scripts/test_backup_postgres.py`, 전체 셸의 Linux `dash` 문법 검사와 기존 증거 해시 직접 비교도 성공했다.
- 별도 PostgreSQL 17 임시 Compose 프로젝트에서 migration·JDBC와 backup·restore 왕복을 실행해 기록 12건 복원을 확인했다. 검증 container·network·volume과 임시 파일은 종료 시 정리됐다. 실제 팀 DB와 기존 backup은 사용하거나 변경하지 않았다.
- DB schema·REST·MCP 응답·IntelliJ 코드는 변경하지 않았다. IntelliJ 테스트·ZIP 빌드는 재실행하지 않았고 푸시·배포·릴리스도 하지 않았다.

## 요청 식별자와 MCP 입력 오류의 비밀값 노출 방지 (2026-09-01)

- `requestId`에서 기존 비밀값 제거기가 token·자격 증명·private key 또는 개인 home 절대 경로를 감지하면 멱등 키를 치환하지 않고 저장 전에 거부한다. 서로 다른 요청을 `[REDACTED]` 하나로 합치지 않으며 정상 요청 ID의 재시도 계약은 유지한다.
- 같은 `requestId`의 작성자·저장소·저장 내용이 달라 발생하는 충돌 오류에서 실제 요청 ID를 제거했다. REST 오류 본문에도 고정된 충돌 설명만 반환한다.
- MCP 기록 조회·확인·공개·대체와 GitHub PR 게시의 문자열 기록 ID를 공용 파서에서 JDK `UUID.fromString`으로 변환한다. 잘못된 값은 입력 원문이나 원인 예외 없이 고정된 도구 오류로 반환한다.
- GitHub API 버전은 날짜 모양 정규식 대신 JDK `LocalDate.parse`로 실제 날짜를 확인한다. 10자 `YYYY-MM-DD` 형식은 유지하며 존재하지 않는 월·일을 시작 시 거부한다.
- REST에서 `its_` 세션 형태의 요청 ID가 원문 없이 거부되는지, 실제 MCP SSE 오류에서 민감한 입력이 빠지는지, 존재하지 않는 API 버전 날짜를 거부하는지 회귀 테스트를 추가했다. 요청 ID 충돌 예외도 실제 값을 포함하지 않는지 확인한다.
- 첫 전체 테스트는 `build/classes`에 남아 있던 이름 끝의 ` 2.class` 중복 생성물 때문에 JVM 클래스 이름 검사가 실패했다. 소스 파일이 아닌 Gradle 산출물임을 확인하고 `./gradlew --no-daemon clean test`로 다시 빌드해 116개 중 112개 통과·PostgreSQL 조건부 4개 건너뜀을 확인했다. `scripts/validate-plugin.sh`와 `git diff --check`도 성공했다.
- DB schema·SQL·IntelliJ 코드는 변경하지 않아 PostgreSQL 별도 검증과 IntelliJ 빌드는 재실행하지 않았다. 기존 저장 데이터는 변경하지 않았고 푸시·배포·릴리스도 하지 않았다.

## GitHub 저장소 권한 단건 조회 전환 (2026-09-02)

- 현재 사용자의 저장소 전체 목록을 최대 100페이지까지 순회하던 권한 확인을 `GET /repos/{owner}/{repo}/collaborators/{login}/permission` 단건 조회로 바꿨다. 저장소 수와 관계없이 권한 확인 요청은 한 번만 수행한다.
- GitHub의 기본 권한 `read`, `write`, `admin`을 기존 내부 역할에 연결하고, 기본 권한이 `write`로 축약되는 `maintain`은 `role_name`으로 구분한다. `none`과 404는 권한 없음으로 처리한다.
- 권한 응답의 숫자 사용자 ID를 `/user`로 확인한 현재 세션 subject와 비교한다. login은 API 경로에만 사용하며, 다른 사용자의 응답이나 알 수 없는 권한 값은 원문 없는 연동 오류로 중단한다.
- GitHub HTTP 계약 테스트에서 단건 요청 경로·역할 매핑·404·사용자 불일치·파싱 오류의 원문 제거를 확인한다. 실제 GitHub 사용자 token이나 저장소 데이터는 사용하지 않는다.
- `./gradlew --no-daemon clean test`는 125개 중 121개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. `scripts/validate-plugin.sh`, 스킬 `quick_validate.py`와 `git diff --check`도 성공했다. DB schema·REST·MCP·IntelliJ 계약은 변경하지 않아 PostgreSQL 별도 검증과 IntelliJ 빌드는 재실행하지 않았으며 푸시·배포·릴리스도 하지 않았다.

## 로컬 세션 폐기와 사용자별 상한 (2026-09-02)

- `DELETE /api/v1/session`이 인증 필터에서 확인한 현재 `its_` 세션의 digest를 메모리 store에서 제거한다. 호환용 `ghu_` 직접 인증 요청은 `400`으로 거부한다.
- 사용자별 활성 세션은 기본 5개이며 `INTENT_TRACE_GITHUB_MAX_SESSIONS_PER_USER`로 1~100 범위에서 설정한다. 새 세션 발급 시 만료 세션을 먼저 정리하고 같은 사용자의 가장 오래된 세션을 폐기한다.
- IntelliJ 저장 세션 삭제 액션은 PasswordSafe 세션을 서버에서 먼저 폐기한다. 성공 또는 이미 만료된 `401` 뒤 로컬 자격 증명을 삭제하고, 서버 장애 때는 token을 유지한다. 환경 변수 세션은 변경하지 않는다.
- 로컬 HTTP·Spring 통합 테스트로 세션 폐기 이후 `401`, `ghu_` 거부, 사용자별 상한, IntelliJ `DELETE` 요청과 `401` 정리를 확인했다. 서버 `./gradlew --no-daemon clean test`는 130개 중 126개 통과·PostgreSQL 조건부 4개 건너뜀이고, IntelliJ 테스트 32개와 플러그인 빌드·구성·구조 검사, `scripts/validate-plugin.sh`, Compose 검사, 스킬 `quick_validate.py`, `git diff --check`가 성공했다. 실제 GitHub App OAuth·권한은 재인증 시간 초과로 아직 포함하지 않았다.

## 실제 GitHub OAuth와 IntelliJ 조회 검증 (2026-09-02)

- `IntentTrace ljkhyeong` GitHub App의 기존 사용자 승인을 폐기하고 `@ljkhyeong` 계정으로 다시 승인했다. callback은 `http://127.0.0.1:18080/auth/github/callback`을 사용했고, 새 승인은 expiring user authorization token 설정 아래에서 수행했다.
- 검증용 client secret은 파일·로그·셸 인자에 저장하지 않고 로컬 서버 프로세스 환경에만 전달했다. OAuth callback에서 `its_` 세션을 발급받은 뒤 `GET /api/v1/change-records?repositoryKey=ljkhyeong%2Fintent-trace&scope=TEAM&page=0&size=1`을 호출해 HTTP 200을 확인했다. 이 요청은 GitHub `/user` 사용자 확인과 저장소 단건 권한 조회를 실제 GitHub 응답으로 통과했다.
- IntelliJ에서 서버 주소를 `http://127.0.0.1:18080`으로 설정하고 상태 `UP`을 확인한 뒤, 발급된 세션을 PasswordSafe에 저장했다. `IntentTrace 기록함 · ljkhyeong/intent-trace`가 열리고 팀 공개 기록 전체 조회가 1페이지 0건으로 완료돼 PasswordSafe 읽기, 세션 인증과 저장소 권한 조회가 함께 동작함을 확인했다.
- 설치돼 있던 `0.8.0-SNAPSHOT` 플러그인은 서버 세션 폐기 기능을 넣기 전 빌드였다. 따라서 실제 IntelliJ 검증은 서버 설정·PasswordSafe 저장·기록함 조회까지이며, 새 `DELETE /api/v1/session` 연결 해제 동작은 자동화 테스트로만 확인했다.
- 검증 후 임시 client secret 두 개를 삭제하고 기존 운영 client secret 하나만 유지했다. 로컬 서버를 정상 종료해 메모리의 GitHub token과 `its_` 세션을 제거했고, 검증 중 만든 `8080`·`18080` PasswordSafe 항목도 삭제한 뒤 IntelliJ 서버 주소를 빈 기본 설정으로 복원했다. GitHub App 사용자 승인은 유지했다.
- 실제 변경 기록 생성·수정, GitHub Check Run 게시와 PR 쓰기는 수행하지 않았다. client secret, GitHub token과 `its_` 원문은 저장소 문서나 변경 파일에 기록하지 않았다.

## 다음 작업 후보

[0.9.0 후속 개선 검토](docs/reviews/2026-09-05-v0.9-follow-up-review.md)의 7개 항목을 0.10.0에 반영했다. 구현 내용과 검증 범위는 [0.10.0 검증 기록](docs/reviews/2026-09-05-v0.10-verification.md)을 따른다.

[0.10.0 후속 개선 검토](docs/reviews/2026-09-05-v0.10-follow-up-review.md)의 5개 항목을 0.11.0에 반영했다. 범위와 검증은 [0.11.0 검증 기록](docs/reviews/2026-09-05-v0.11-verification.md), 변경 이력의 저장·노출 정책은 ADR-0011을 따른다.

[0.11.0 후속 개선 검토](docs/reviews/2026-09-05-v0.11-follow-up-review.md)의 4개 항목을 0.12.0에 반영했다. [0.12.0 검증 기록](docs/reviews/2026-09-05-v0.12-verification.md)을 따른다. Zed는 로컬 설치 패키지와 레지스트리 제출 자료 생성까지 완료했고 실제 외부 게시·등록은 수행하지 않았다.

[0.12.0 후속 개선 검토](docs/reviews/2026-09-05-v0.12-follow-up-review.md)의 세 항목을 0.12.1에 반영했다. 토큰 입력 보호에 실패하면 실행을 중단하고 잠금 파일로 배포 의존성을 준비한다. 조회는 최소 7회를 요구하고 근거를 처리하지 못한 재개를 따로 안내한다. [0.12.1 검증 기록](docs/reviews/2026-09-05-v0.12.1-verification.md)을 따른다.

[문구 검토](docs/reviews/2026-09-05-wording-review.md)의 수정안 40개를 0.12.2에 반영했다. 화면·Markdown·MCP·Zed·문서의 표현을 맞추고 오류 전달 조건과 모바일 줄바꿈을 확인했다. [0.12.2 검증 기록](docs/reviews/2026-09-05-v0.12.2-verification.md)을 따른다.

[추가 문구 검토](docs/reviews/2026-09-05-wording-follow-up-review.md)의 수정안 12개를 서버·플러그인 0.12.3에 반영했다. 요청 충돌·해시 불일치·전체 연결 종료·토큰 발급 안내를 실제 동작에 맞추고 검색·MCP·플러그인 소개의 용어를 통일했다. [0.12.3 검증 기록](docs/reviews/2026-09-05-v0.12.3-verification.md)을 따른다.

2026-09-05 [추가·개선 기능 검토](docs/reviews/2026-09-05-feature-review.md)의 9개 항목은 REST·MCP·로컬 실행 도구의 최소 기능을 구현했다. 계약은 PRD-0004, ADR-0007·0008과 기존 PRD의 확장 절을 따른다. 검색·브라우저 열람·후속 초안·코드 이동·PR 목록·진단과 Zed Agent 연결까지 확장했다. 외부 지표 대시보드와 편집기 인라인 UI는 후속 작업이다.

1. Zed 편집기 인라인 UI를 검토한다. IntelliJ 현재 줄 조회와 Zed Agent MCP 연결은 구현했다.
2. 실제 운영 결과를 바탕으로 encrypted session 저장 필요성을 다시 결정한다.
3. 코드 근거를 Check Run line annotation으로 선택 게시한다.
4. GitHub App webhook으로 사용자 승인·설치 제거와 권한 변경을 반영한다.

IntelliJ의 기록함 선택 팝업과 커밋 없는 초안의 이동 버튼 비활성화는 실제 IDE에서 추가 확인해야 한다. 메인의 자동 검증 결과만으로 이 수동 확인을 완료했다고 판단하지 않는다.

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
- IntelliJ 현재 줄 조회는 커밋되지 않은 파일을 지원하지 않는다. 별도 파일 이력은 조회할 수 있다.
- IntelliJ callback token 자동 가져오기, 기록 생성·수정과 Marketplace 배포는 아직 지원하지 않는다.
- 현재 두 개의 alignment HTML만 저장소에 유지하며 새 HTML은 GitHub Release나 별도 보관소에 둔다.
- Zed Agent MCP 연결을 구현했다. 인라인 IDE 메뉴와 자동 기록 수집은 없다. 실제 Zed 1.18.1에서 로컬 테스트 응답을 사용해 앱 연결·도구 승인·공개 기록 조회·세션 폐기 후 오류·새 세션 재연결을 확인했다. 실제 GitHub 사용자 승인은 확인하지 않았다.
- 일반 연결 진단은 게시 설정 존재만 확인한다. 관리자용 별도 사전 점검은 App 키·설치·실제 발급 범위·권한을 확인한다. 고정 token은 미확인으로 남기며 실제 Check Run 성공까지 보장하지 않는다.
- 기록 변경·게시 시도 이력을 저장한다. 인증·운영 전체 감사 로그와 자동 보존 정책은 없다. 수집 이전 이력과 과거 본문은 복원하지 않는다.
- Micrometer 지표는 수집하지만 외부 수집기와 대시보드는 별도 연결해야 한다.
- 과거 조회의 기한·호출 수와 전달된 interrupt를 처리한다. 모든 브라우저·MCP 연결 종료가 서버 스레드에 즉시 전달되는 것은 아니다. 원격 인증과 전체 HTTP 응답 시간을 보장하는 SLA는 별도다.
- 호출 수를 7회 이상 설정해도 시간 내 근거 처리를 보장하지 않는다. `resumeBlocked=true`이면 같은 커서를 자동 반복하지 않고 조회 기한·GitHub 응답 지연을 확인한 뒤 다시 요청한다. 근거 중간 응답은 요청 사이에 보관하지 않는다.
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

## 0.12.1 검증 결과

- 전체 서버 테스트 93개 중 92개 통과, PostgreSQL 전용 1개 제외. DB 관련 변경이 없어 별도 PostgreSQL 검증은 재실행하지 않았다.
- Zed Node 테스트 9개와 Python 입력 보호 테스트 3개 통과. 실제 Spring 서버와 stdio MCP 연결, 빈 캐시의 오프라인 패키지 설치를 확인했다.
- 설치 상태가 다른 임시 프로젝트에서도 잠금 파일의 직접·하위 의존성만 묶고, 선언 불일치는 거부했다. 최종 0.12.1 패키지와 생성 기준 해시를 만들었다.
- 최소 7회에서 근거별 재개, 같은 근거에서 반복 중단 시 안내, 원인 제거 뒤 재개를 확인했다. 실제 로컬 HTTP 기한·호출 수·취소 테스트도 통과했다.
- 플러그인·두 스킬 검증 통과. 이번 수정의 웹 안내는 서버 통합 테스트로 확인했고 실제 브라우저·Zed 앱·GitHub 사용자·외부 게시 검증은 추가로 수행하지 않았다.

## 0.12.2 검증 결과

- 문구 검토 40개가 각각 해당 파일에 반영된 것을 확인했다. REST·MCP 이름, JSON 필드, 상태·오류 코드와 권한·저장 정책은 유지했다.
- 전체 서버 테스트 93개 중 92개 통과, PostgreSQL 전용 1개 제외. DB 관련 변경이 없어 별도 PostgreSQL 검증은 재실행하지 않았다.
- Zed Node 테스트 9개·Python 테스트 3개와 플러그인·두 스킬 검증 통과. 잘못된 세션 토큰의 오류 안내·토큰 미출력, 5xx 오류 분류, 시간 초과의 상태 확인 안내를 확인했다.
- 실제 Chromium의 390px 화면에서 로그인·조회 중단·원본 비교 문구를 확인했다. 페이지는 서버 테스트가 생성한 HTML이며 실제 Zed 앱·GitHub 계정·외부 게시는 다시 확인하지 않았다.
- 최종 0.12.2 Zed 설치 파일을 생성하고 압축 파일·잠금 파일 해시와 포함된 실행 파일·사용 안내가 현재 소스와 일치함을 확인했다.

## 0.12.3 검증 결과

- 추가 문구 수정안 12개가 해당 파일에 반영된 것을 확인했다. 연결 종료 설명·관련 코드 입력 오류·플러그인 기본 프롬프트와 사용 스킬의 토큰 발급 설명도 맞췄다.
- 전체 서버 테스트 93개 중 92개 통과, PostgreSQL 전용 1개 제외. 같은 작성자·저장소에서 내용만 다른 요청의 충돌, 스냅샷 해시 불일치, 다른 브라우저를 포함한 본인 전체 연결 종료, 토큰 발급과 저장소 범위 확인을 검증했다.
- 플러그인과 변경한 사용 스킬 검증 통과. 새 테스트 파일은 추가하지 않고 기존 테스트 4개를 보완했다.
- 기존 Zed 연결 도구 0.12.2와 서버 0.12.3의 인증·MCP 도구 목록·연결 진단 통합 테스트 통과. Zed 실행 파일은 변경하지 않아 설치 파일을 새로 만들지 않았다.
- DB 변경이 없어 별도 PostgreSQL 검증은 재실행하지 않았다. 실제 브라우저·Zed 앱·GitHub 사용자·외부 게시는 이번 검증에 포함하지 않았다.


## 2026-09-05 메인 병합 검증

- 메인의 IntelliJ·권한 조회·세션 상한·게시 잠금과 작업 브랜치의 Zed·브라우저·기록 관리 기능을 통합했다.
- 서버·MCP·Codex·IntelliJ 버전은 `0.12.3-SNAPSHOT`으로 맞췄다. Zed 연결 도구는 `0.12.2`를 유지한다.
- REST·MCP는 기존 `MY_DRAFTS`·페이지 번호와 새 `MINE`·커서 조회를 함께 지원한다. 두 방식의 인자를 섞으면 거부한다.
- 메인의 Flyway V1~V6는 유지하고 개발 브랜치 변경을 V7~V11로 옮겼다. 새 DB와 메인 V6 DB가 대상이며 개발 브랜치 V5~V9 DB는 별도 이관이 필요하다. [DB 통합 절차](docs/operations/team-deployment.md#메인과-개발-브랜치의-db-변경-이력-통합)를 따른다.
- 전체 서버 테스트 169개 중 165개 통과, PostgreSQL 전용 4개는 기본 실행에서 제외했다. 별도 PostgreSQL 17에서 해당 4개와 백업·복구를 통과했다. 복구 전후 기록 13건·변경 이력 28건이 일치했다.
- IntelliJ 테스트 32개, 설치 ZIP·프로젝트 구성·플러그인 구조 검증 통과. Zed Node 9개·Python 3개, 실제 Spring 서버의 stdio MCP 연결 검증 통과.
- 릴리스 버전·JAR manifest, 릴리스 준비 테스트 2개, 백업 파일 보존 테스트 2개, Compose 경계, 공식 플러그인·두 스킬 검증 통과.
- 서버 컨테이너 이미지 빌드와 별도 Compose 프로젝트의 Caddy 설정 검증 통과.
- 실제 IDE UI·GitHub 사용자 승인·제품의 Check Run 게시는 이번 검증에 포함하지 않았다.

- PR CI의 전체 SHA 고정 정책에 맞춰 `actions/setup-node` v6 참조도 공식 커밋 SHA로 고정했다.
- Linux CI에서 확인한 DB 시각 정밀도 차이를 나노초 고정 시계로 재현했다. 생성·확인·공개·작업 시각을 DB의 마이크로초 정밀도로 맞추고 저장 전후 전체 기록이 같은지 기존 H2·PostgreSQL 계약 테스트에서 확인했다. 수정 후 전체 서버 테스트와 PostgreSQL 백업·복구가 다시 통과했다.
