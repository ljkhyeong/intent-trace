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
- Streamable HTTP MCP 도구 8개 (`list_change_records`, `supersede_change_record` 포함)
- REST·MCP 공통 생성 입력 검증과 전체 Git commit 값 객체
- GitHub token·private key·client secret의 안전한 문자열 표현
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- Codex 조회 스킬의 정확한 줄·기록함·파일 이력 분기, 페이지·상세·대체 기록 조회 안내
- Codex에서 사용자 요청에 따른 공개 기록 대체와 결과 불확실 시 재조회 안내
- IntentTrace 저장소 전용 개발 스킬
- IntelliJ 2025.3+ 현재 줄 공개 변경 의도 조회와 PasswordSafe 세션 저장
- IntelliJ에서 기존 GitHub 승인 페이지 열기와 PasswordSafe 세션 삭제
- IntelliJ 공용 서버 주소 설정, 재시작 없는 주소 적용과 인증 정보 없는 연결 확인
- IntelliJ 기록함과 파일 이력, 전체 커밋·당시 코드·대체 기록 탐색
- IntelliJ 기록함 조회 실패 시 마지막 성공 필터 복원과 기존 목록·선택·페이지 유지
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
- MCP 생성 도구는 Jakarta Validator를 명시적으로 실행하고 전체 Git commit 형식은 도메인 값 객체에서 검증한다.
- GitHub 자격 증명 보유 객체의 `toString()`에는 실제 비밀값을 넣지 않는다.
- GitHub 연동과 IntelliJ 응답 파싱 오류는 응답 원문을 포함할 수 있는 원인 예외를 전달하지 않는다.
- IntelliJ 플러그인은 `its_` session만 PasswordSafe에 저장하고 GitHub token을 입력받지 않는다.
- IntelliJ 현재 줄 조회는 커밋된 파일과 전체 HEAD commit만 사용한다.
- IntelliJ HTTP 조회는 연결 5초·응답 읽기 10초 제한, redirect 금지와 4MiB(4,194,304바이트) 응답 상한을 유지한다. 서버 입력 상한의 단건 기록은 수용하고, 여러 기록의 합계가 상한을 넘으면 기록함에서 개별 조회한다.
- IntelliJ 승인 시작은 기존 서버 URL만 열고 callback·state·PKCE는 서버가 처리한다.
- IntelliJ 서버 주소는 로컬 IDE 설정, 환경 변수, 기본 loopback 주소 순서로 선택하며 설정 동기화에서 제외한다. 연결 확인은 인증 정보 없이 health만 조회하고 설정을 저장하지 않는다.
- PasswordSafe와 환경 변수 세션은 해당 서버에서만 사용한다. PasswordSafe 세션을 삭제해도 해당 서버에 사용할 환경 변수 세션이 있으면 연결은 유지된다고 안내한다.

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

## 다음 작업 후보

1. 화면 제어 서비스 재시작 비교는 마쳤으며, 제품 코드를 바꾸지 않고 화면 조회가 복구됐다. 재발 방지 수정이 확인된 것은 아니므로 팝업을 직접 열지 않는 키보드 선택이나 정상적으로 화면을 읽는 환경에서 `팀 공개 기록 · 공개/대체됨`, `내 비공개 기록 · 전체/초안`과 커밋 없는 초안의 이동 버튼 비활성화를 직접 확인한다. 공용 제어 서비스 재시작은 다른 연결에도 영향을 줄 수 있으므로 상시 우회 수단으로 반복하지 않는다. 남은 수동 검증 전에는 0.8.0 릴리스 준비가 완료됐다고 판단하지 않는다.
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
