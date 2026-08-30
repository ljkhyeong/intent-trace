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
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신·401 복구
- GitHub user access token 인증과 저장소별 READER·CONTRIBUTOR·MAINTAINER 역할 판정
- GitHub Web Application Flow와 callback `state`·cookie·TTL·일회성 검증
- GitHub access·refresh token 메모리 보관과 만료 전 token 쌍 자동 갱신
- SHA-256 digest로 조회하는 `its_` 로컬 세션과 Codex MCP 인증
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 Docker Compose
- 외부 container digest 고정과 전체 Git commit 기반 app image tag·rollback 절차
- 비root·읽기 전용 app container와 분리된 data·edge network
- PostgreSQL 17 migration·JDBC·backup·restore 왕복과 GitHub Actions 검증
- GitHub Actions commit SHA 고정, 중복 실행 취소와 Dependabot 정기 갱신
- 초안 작성자 소유권과 저장소 권한 기반 팀 공개 조회
- Streamable HTTP MCP 도구 7개 (`list_change_records` 포함)
- REST·MCP 공통 생성 입력 검증과 전체 Git commit 값 객체
- GitHub token·private key·client secret의 안전한 문자열 표현
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- Codex 조회 스킬의 정확한 줄·기록함·파일 이력 분기, 페이지·상세·대체 기록 조회 안내
- IntentTrace 저장소 전용 개발 스킬
- IntelliJ 2025.3+ 현재 줄 공개 변경 의도 조회와 PasswordSafe 세션 저장
- IntelliJ에서 기존 GitHub 승인 페이지 열기와 PasswordSafe 세션 삭제
- IntelliJ 공용 서버 주소 설정, 재시작 없는 주소 적용과 인증 정보 없는 연결 확인
- IntelliJ 기록함과 파일 이력, 전체 커밋·당시 코드·대체 기록 탐색
- tag와 프로젝트 version을 확인한 뒤 서버 JAR·IntelliJ ZIP·SHA-256 파일을 함께 발행하는 GitHub Actions
- Apache License 2.0과 Hope HTML의 MIT·OFL-1.1 제3자 라이선스 고지
- SECURITY 정책과 0.6.0 변경 이력

## 확인할 불변식

- 초안은 만든 작성자만 확인한다.
- 초안과 확인 기록은 만든 작성자만 조회하고, 공개·대체 기록은 저장소 읽기 권한이 있는 팀원만 조회한다.
- 작성자는 요청 본문이 아니라 `/user`에서 확인한 GitHub 숫자 ID로 결정한다.
- Codex에는 GitHub token 대신 `its_` session token만 전달하고 GitHub token 쌍은 메모리 밖으로 노출하지 않는다.
- callback은 같은 브라우저의 cookie와 미사용 `state`가 일치할 때만 code를 교환한다.
- 미완료 OAuth `state`는 TTL과 전역 개수 상한으로 제한하고 상한 도달 시 새 승인을 거부한다.
- refresh token은 한 번 사용한 뒤 새 access·refresh token 쌍으로 함께 교체하고, 사용자 subject가 바뀌면 세션을 폐기한다.
- 갱신 거부 또는 응답 수신·파싱·token 값 변환 실패 시 세션을 폐기하고 `401`로 재승인을 요구한다. 잠금을 기다리던 요청도 폐기된 세션을 사용하지 않는다. 단순 사용자 조회 장애는 `502`로 구분하고 세션을 유지한다.
- 생성·확인·공개·대체·GitHub 게시는 저장소 쓰기 권한이 필요하다.
- 확인 시 전체 Git 커밋 ID가 필요하다.
- 확인과 공개 시 현재 스냅샷이 기록의 스냅샷과 같아야 한다.
- 공개된 본문과 근거는 수정하지 않고 새 공개 기록으로 대체한다.
- 팀 조회에는 공개 또는 대체된 기록만 노출한다.
- 목록은 저장소 읽기 권한을 먼저 확인하고 SQL에서 공개 상태 또는 현재 작성자의 비공개 상태를 제한한 뒤 페이지로 나눈다.
- 파일 이력은 정확한 상대 경로로만 조회하며 과거 줄·검증을 현재 편집기 코드의 근거로 자동 해석하지 않는다.
- GitHub 게시 전 기록 저장소와 PR 저장소, 기록 커밋과 PR `head.sha`가 각각 일치해야 한다.
- Check Run은 `intent-trace:<변경 기록 UUID>` `external_id`로 재사용하고 GitHub 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- 같은 기록의 게시 요청은 PR 번호가 달라도 단일 app에서 직렬화한다. PR별 HEAD 확인과 게시 이력은 따로 유지하고, Check Run 검색 한도를 다 채우면 중복 생성하지 않는다.
- GitHub 저장소 식별자는 소문자 `owner/repository`로 정규화해 권한·멱등성·조회·게시에서 같은 값으로 비교한다.
- 코드 근거 경로는 `./`, 중복 `/`, 끝 `/`을 제거해 저장과 라인 조회에서 같은 값으로 비교한다.
- 코드 심벌 이름(`symbolName`)도 설명 필드와 같은 비밀값·개인 home 절대 경로 제거를 거쳐 저장한다.
- 같은 `requestId`는 작성자·저장소와 정규화된 저장 내용이 모두 같은 재시도에만 기존 기록을 반환한다.
- 검증 시작·종료 시각은 DB와 같은 마이크로초 반올림을 적용해 비교·저장한다. 이전 저장 방식의 재시도도 같은 정밀도로 비교한다.
- 비밀값 제거 후 문자열 길이가 저장 한도를 넘으면 원문을 포함하지 않은 입력 오류로 거부하고 내용을 자르지 않는다.
- 기록 대체는 두 기록을 ID 순서로 잠그는 DB 트랜잭션 안에서 최신 상태를 확인한다. GitHub 권한 조회는 트랜잭션 밖에서 수행한다.
- 팀 배포는 Caddy만 host port를 열고 app·PostgreSQL은 Docker network 안에 둔다.
- PostgreSQL에는 제품 데이터만 저장하며 GitHub access·refresh token과 `its_` session은 app 메모리에만 둔다.
- restore는 app 중지와 명시적 `--confirm-replace` 없이는 실행하지 않는다.
- MCP 생성 도구는 Jakarta Validator를 명시적으로 실행하고 전체 Git commit 형식은 도메인 값 객체에서 검증한다.
- GitHub 자격 증명 보유 객체의 `toString()`에는 실제 비밀값을 넣지 않는다.
- GitHub 연동과 IntelliJ 응답 파싱 오류는 응답 원문을 포함할 수 있는 원인 예외를 전달하지 않는다.
- IntelliJ 플러그인은 `its_` session만 PasswordSafe에 저장하고 GitHub token을 입력받지 않는다.
- IntelliJ 현재 줄 조회는 커밋된 파일과 전체 HEAD commit만 사용한다.
- IntelliJ HTTP 조회는 연결 5초·응답 읽기 10초 제한, redirect 금지와 1,000,000바이트 응답 상한을 유지한다.
- IntelliJ 승인 시작은 기존 서버 URL만 열고 callback·state·PKCE는 서버가 처리한다.
- IntelliJ 서버 주소는 로컬 IDE 설정, 환경 변수, 기본 loopback 주소 순서로 선택하며 설정 동기화에서 제외한다. 연결 확인은 인증 정보 없이 health만 조회하고 설정을 저장하지 않는다.
- PasswordSafe와 환경 변수 세션은 해당 서버에서만 사용한다. PasswordSafe 세션을 삭제해도 해당 서버에 사용할 환경 변수 세션이 있으면 연결은 유지된다고 안내한다.

## IntelliJ 설치와 화면 검증 (2026-08-30)

- IntelliJ IDEA 2025.3.2에 서버 설정 기능 추가 전의 `intent-trace-intellij-0.8.0-SNAPSHOT.zip`을 설치하고, 시작 로그의 `IntentTrace (0.8.0-SNAPSHOT)` 로드를 확인했다. 기존 0.7.0 플러그인은 로컬 `build/installed-plugin-backup-0.7.0-20260830/`에 보관했다.
- 운영 서버 대신 loopback의 메모리 응답 서버를 연결했다. 테스트 세션은 서버와 IDE 프로세스에만 전달했으며 PasswordSafe의 기존 세션은 변경하지 않았다. 이 확인은 OAuth·서버 권한 검증이 아니다.
- `Tools` 메뉴에서 기록함 열기·GitHub 승인 시작·현재 줄 조회·세션 연결·저장 세션 삭제가 표시되는 것을 확인했다.
- 실제 기록함에서 팀 공개 기록 22건을 1페이지 20건·2페이지 2건으로 조회하고 이전 페이지로 돌아왔다. 첫 페이지의 이전 버튼과 마지막 페이지의 다음 버튼은 비활성화됐다.
- `현재 파일만`을 선택해 같은 파일의 공개·대체 기록 4건으로 줄어드는 것과, 적용된 파일 경로·페이지·건수 표시를 확인했다.
- 이후 화면 제어 도구가 IDE 창 내용을 빈 값으로 반환해 추가 조작을 중단했다. 비공개·상태 필터, 빈 결과, 상세·대체 기록, 브라우저 링크, 현재 줄에서 파일 이력으로 이동하는 동선은 아직 실제 화면 검증을 마치지 않았다.
- 테스트용 IDE 프로세스에 종료 신호를 보내 메모리 응답 서버와 테스트 세션을 정리했다. 이후 테스트 환경 변수 없이 IntelliJ를 다시 열고 0.8.0-SNAPSHOT 로드를 확인했다.
- 자동 검증은 서버 `test`, IntelliJ `test`·프로젝트 구성·ZIP 구조 검사, `scripts/validate-plugin.sh`가 성공했다. 제품 코드 변경이 없어 두 `test` 작업은 기존 성공 결과를 재사용했다(`UP-TO-DATE`).

## IntelliJ 서버 설정 자동 검증 (2026-08-30)

- IntelliJ 테스트 29개가 모두 통과했다. 주소 우선순위·설정 저장과 복원, 실제 SDK 설정 패널의 적용·초기화·잘못된 주소 거부, 서버별 세션 분리와 인증 정보 없는 health 요청을 확인했다.
- `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`와 `scripts/validate-plugin.sh`가 성공했다.
- 서버 `test`는 기존 성공 결과를 재사용했다(`UP-TO-DATE`). 결과는 104개 중 100개 통과·PostgreSQL 조건부 테스트 4개 건너뜀이다. 서버·DB 코드는 이번 작업에서 변경하지 않았다.
- 새 설치 ZIP은 `intellij-plugin/build/distributions/intent-trace-intellij-0.8.0-SNAPSHOT.zip`이다. 실제 IDE에는 아직 새 ZIP을 설치하지 않았으며, 서버 설정·연결 확인 화면의 수동 검증은 남아 있다.

## 다음 작업 후보

1. 서버 설정 기능이 포함된 새 ZIP을 설치한 뒤 주소 적용·취소·연결 확인과 위에 남긴 미확인 화면 동선을 검증한다. 개발용 `runIde`는 화면 제어 도구가 Java 프로세스를 식별하지 못했고, 설치된 IDE도 일부 조작 후 창 내용을 읽지 못했다. 전체 수동 검증이 끝나기 전에는 0.8.0 릴리스 준비가 완료됐다고 판단하지 않는다.
2. 작성자 본인의 비공개 초안 수정·폐기 기능을 설계한다. 공개 기록 불변성과 낙관적 잠금은 유지한다.
3. 실제 사용자 피드백을 바탕으로 결과 창을 ToolWindow로 바꿀 필요가 있는지 결정한다.
4. 판단별 코드·검증 연결과 기록 생성 보조를 검토한다.
5. 운영 요구가 생기면 encrypted session, 승인 폐기 webhook, Check Run line annotation을 검토한다.

## 현재 제한

- 사용자 token 쌍과 `its_` 세션은 메모리 전용이라 재시작과 다중 인스턴스 간에 유지되지 않는다.
- 승인 폐기 webhook과 사용자가 세션을 직접 조회·폐기하는 UI는 없다.
- 팀 배포는 단일 app만 지원하며 무중단 rolling 배포와 여러 host의 session 공유가 없다.
- GitHub 사용자와 저장소 권한은 요청마다 조회하며 캐시와 webhook 무효화가 없다.
- V3 이전 기록은 `legacy:<login>` subject로 남아 현재 GitHub 계정이 수정할 수 없다.
- 코드 스냅샷과 줄 해시는 제공 스크립트로 계산하며 서버가 Git 객체를 직접 검증하지 않는다.
- GitHub App 등록·설치와 private key 회전은 운영자가 수행해야 한다.
- installation token 캐시는 프로세스 메모리에만 있어 여러 인스턴스가 공유하지 않는다.
- Fork PR Check Run과 GitHub webhook은 아직 지원하지 않는다.
- 실제 GitHub 저장소 쓰기는 자동 테스트하지 않고 로컬 HTTP 계약으로 검증한다.
- IntelliJ 현재 줄 조회는 커밋되지 않은 파일을 지원하지 않는다. 별도 파일 이력은 조회할 수 있다.
- IntelliJ callback token 자동 가져오기, 기록 생성·수정과 Marketplace 배포는 아직 지원하지 않는다.
- 파일 rename·줄 이동은 자동 추적하지 않으며 목록에 총 건수·전문 검색·고정 검색 스냅샷은 없다.
- 감사 로그와 기록 보존 정책은 아직 구현하지 않았다.
- 현재 두 개의 alignment HTML만 저장소에 유지하며 새 HTML은 GitHub Release나 별도 보관소에 둔다.
