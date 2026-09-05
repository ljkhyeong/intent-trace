---
name: intent-trace-flows
description: "IntentTrace Kotlin/Spring 서비스와 Codex 플러그인을 변경할 때 사용하는 전용 개발 흐름. 변경 의도 수명주기, GitHub 사용자·저장소 권한, REST·MCP, Check Run, Flyway·JDBC, Docker Compose 팀 배포, TLS·backup·restore, 외부 자격 증명, 문서나 플러그인 스킬 변경에 적용한다."
---

# IntentTrace 개발 흐름

## 시작

- 저장소 루트의 `AGENTS.md`, `HANDOFF.md`, `README.md`를 먼저 읽는다.
- 제품 동작은 관련 PRD, 장기 구조와 실패 경계는 관련 ADR을 기준으로 삼는다.
- `HANDOFF.md`는 현재 인계 상태로만 보고 장기 명세로 사용하지 않는다.
- 구현되지 않은 API, MCP 도구, GitHub 동작과 검증을 현재 기능처럼 문서화하지 않는다.

## 책임과 의존 방향

- IntentTrace는 요청·판단 출처·코드 근거·검증 결과와 작성자 확인 상태를 소유한다.
- GitHub는 PR, 커밋과 Check Run의 원본을 소유한다. IntentTrace가 PR 상태나 권한을 재해석하지 않는다.
- 도메인 불변식은 `domain`, 사용 사례와 포트는 `application`, HTTP·MCP는 `adapter/in`, JDBC와 외부 HTTP는 `adapter/out`이 소유한다.
- REST와 MCP는 같은 애플리케이션 사용 사례를 호출하고 컨트롤러나 외부 어댑터에 공개 정책을 복제하지 않는다.
- 다른 저장소, GitHub App 설정이나 실제 PR을 사용자가 범위에 넣지 않았다면 변경하지 않는다.

## 변경 기록 불변식

- 원문 대화, 숨은 추론, 검증 원문 출력은 영구 저장하거나 팀에 게시하지 않는다.
- 판단 출처의 `STATED_*`, `CONFIRMED_AI_SUMMARY`, `INFERRED`, `UNKNOWN` 의미를 섞지 않는다.
- 작성자 확인과 공개는 전체 Git 커밋 ID 및 같은 SHA-256 스냅샷을 요구한다.
- 공개 기록 본문은 수정하지 않고 새 공개 기록으로 대체한다. 후속 초안에는 새 근거를 받고 원본 ID를 보존하며 검증·확인·공개 상태를 복사하지 않는다.
- 본문 수정은 `DRAFT`에서만 허용한다. 확인 취소는 연결한 target revision을 비우고, `DISCARDED`는 작성자에게만 보이는 종료 상태로 유지한다.
- GitHub 저장소 식별자는 입력 경계에서 소문자 `owner/repository`로 정규화하고 DB에도 같은 값만 저장한다.
- 코드 근거는 저장소 상대 경로와 필요한 최소 연속 줄 범위로 유지한다.
- 실행하지 않은 명령은 검증으로 만들지 않고 오래된 스냅샷의 검증은 현재 검증으로 표시하지 않는다.
- Spring AI MCP callback은 Jakarta Validation을 자동 실행하지 않으므로 생성·수정 도구는 기존 요청 DTO를 `Validator`로 명시적으로 검증한다. 선택 입력은 `McpToolParam(required = false)`로 명시하고 생략한 실제 요청도 확인한다. 전체 Git commit 형식은 REST 어노테이션에만 의존하지 않고 도메인 `GitRevision`에서도 확인한다.
- MCP 출력 스키마의 최상위 값은 객체로 유지한다. 목록은 `items`로 감싸고 Zed 중계기와 실제 서버를 연결하는 표준 SDK 통합 테스트로 확인한다.
- `BASE` 코드 근거는 `baseRevision`, `TARGET` 근거는 `targetRevision`을 사용한다. 변경 전 코드 조회에 변경 후 테스트를 현재 검증으로 표시하지 않는다.
- GitHub 코드 확인은 제출된 해시와 원격 객체를 비교할 뿐 로컬 명령 실행을 증명하지 않는다. 실행 도구로 수집한 결과도 `LOCAL_RUNNER_REPORTED`로 구분한다.

## 팀 사용자와 저장소 접근 경계

- 모든 REST·MCP 요청은 기본적으로 `its_` session token을 받고, 메모리의 GitHub App user access token으로 `/user`를 확인해 숫자 사용자 ID를 `github:<id>` subject로 사용한다. login은 표시값이며 소유권 키로 사용하지 않는다. 기존 `ghu_` 직접 Bearer 인증은 호환 경로로만 유지한다.
- OAuth 시작은 256비트 무작위 `state`의 digest·TTL과 PKCE verifier를 서버에 두고 `state` 원문은 callback 경로의 HttpOnly·SameSite cookie로 전달한다. callback은 query·cookie 일치, TTL·미사용 여부와 PKCE `S256`을 검증하기 전 code를 교환하지 않는다.
- 기록 작성자는 입력값으로 정하지 않는다. 현재 요청의 인증 사용자만 초안 작성자가 된다. 팀 목록의 작성자 ID 필터는 조회 조건으로만 사용한다.
- `DRAFT`와 `AUTHOR_CONFIRMED`는 만든 작성자만 조회·변경하고 `DISCARDED`는 만든 작성자만 조회한다. `PUBLISHED`와 `SUPERSEDED`는 해당 GitHub 저장소의 읽기 권한이 있는 팀원만 조회한다.
- GitHub `/user/repos`의 `owner,collaborator,organization_member` 목록에 포함된 저장소만 팀 범위로 인정한다. 그 목록의 `pull`은 READER, `push`는 CONTRIBUTOR, `maintain`·`admin`은 MAINTAINER로 해석한다. public 저장소의 일반 읽기 가능 여부만으로 팀원이라고 판단하지 않는다.
- GitHub access·refresh token은 메모리에만 보유하고 DB, URL, cookie, 로그, 예외, 도구 입력에 넣지 않는다. `its_` 원문도 callback 성공 본문 외에는 응답하지 않고 store에는 digest만 인덱스로 둔다. 서버 게시용 installation token과 사용자 token의 책임을 섞지 않는다.
- access token 만료 전 refresh는 세션별 잠금 안에서 한 번만 수행하고 새 access·refresh token 쌍으로 함께 교체한다. 갱신 거부, token 거부 또는 사용자 subject 변경은 session을 폐기해 재로그인을 요구한다.
- 세션 목록에는 본인의 비밀값 없는 메타데이터만 반환한다. 갱신 중 폐기한 세션을 다시 활성화하지 않는다.
- 브라우저 기록 화면은 `itb_` 전용 세션으로 읽기 사용 사례만 호출한다. 복귀 주소는 기록 경로만 허용하고 cookie를 REST·MCP 인증으로 확장하지 않는다. GitHub token을 cookie나 HTML에 넣지 않는다.
- V3 이전 작성자는 `legacy:<lowercase-login>`으로 보존하고 자동으로 GitHub 계정에 연결하지 않는다.

## 외부 게시 경계

- GitHub 게시 전 공개 기록인지 확인하고 PR `head.sha`가 기록의 `targetRevision`과 정확히 같은지 서버 응답으로 검증한다.
- PR 응답의 base·head 저장소가 게시 대상과 일치하는지도 확인하고 Fork 게시를 거부한다.
- 대체 안내는 기존 Check Run의 `external_id`와 원래 `head_sha`를 검증한 뒤 PATCH만 수행한다. 이 안내에는 진행된 PR HEAD를 허용하되 새 기록 게시에는 전체 커밋 일치 규칙을 유지한다.
- Check Run의 `external_id`에는 `intent-trace:<변경 기록 UUID>`를 사용해 재시도 시 기존 실행을 찾아 갱신한다.
- 게시 시도와 원격 결과를 구분한다. 응답 유실은 `RESULT_UNKNOWN`으로 기록하고 기존 게시 요청을 재실행해 확인한다. 호출 제한은 `Retry-After`를 전달하며 즉시 반복 호출하지 않는다.
- GitHub 원격 호출을 DB 트랜잭션 안에서 실행하지 않는다. 외부 성공 뒤 로컬 저장이 실패해도 같은 요청을 안전하게 재시도할 수 있어야 한다.
- GitHub App client ID와 Base64 PEM private key는 환경 변수로만 주입하고 JWT·installation token을 DB, 로그, 오류 응답, Check Run 본문에 넣지 않는다.
- installation token은 대상 저장소와 필요한 권한으로 축소하고 만료 전에 메모리에서 갱신한다.
- 동적 token의 `401`은 캐시를 폐기하고 한 번만 반복하며 고정 token이나 다른 실패를 무조건 재시도하지 않는다.
- 외부 응답 본문과 자격 증명을 예외 메시지에 그대로 노출하지 않고 안정된 실패 분류로 변환한다.
- token, private key와 client secret을 보유한 타입의 `toString()`에는 실제 비밀값을 넣지 않는다.
- 실제 GitHub 쓰기는 사용자가 명시적으로 요청한 저장소와 PR에만 수행한다.

## 단일 인스턴스 팀 배포 경계

- `compose.yaml`에서는 Caddy만 host의 80·443에 연결한다. app과 PostgreSQL에 host port를 추가하지 않는다.
- PostgreSQL은 외부 통신이 차단된 `data` network만 사용한다. app은 PostgreSQL용 `data`와 GitHub API outbound·Caddy proxy용 `edge`를 함께 사용한다.
- app은 비root, 읽기 전용 filesystem, `/tmp` tmpfs, 제거된 Linux capability를 유지한다. 편의를 위해 container socket, source directory나 host 비밀 경로를 mount하지 않는다.
- 팀 profile은 PostgreSQL, `0.0.0.0`, forwarded header, 비활성 H2 console과 readiness health를 유지한다. 외부 TLS와 인증서 갱신은 Caddy 책임이다.
- PostgreSQL backup에는 제품 데이터만 포함한다. GitHub access·refresh token과 `its_` session을 schema나 backup에 추가하려면 암호화 key·회전·폐기를 새 ADR로 먼저 결정한다.
- backup은 기존 파일을 덮어쓰지 않고 제한 권한으로 만든다. restore는 app 중지, 일반 파일과 명시적 `--confirm-replace`를 모두 확인한 뒤에만 현재 DB를 교체한다.

## 변경 절차

1. 요청을 기록 수명주기, 사용자·권한·OAuth session, 증거, 조회, GitHub 게시, 플러그인 또는 운영 중 하나로 분류한다.
2. `rg`로 같은 책임의 도메인 규칙, 포트, 어댑터, SQL, DTO, MCP 도구와 문서를 찾는다.
3. 가장 좁은 소유 계층에서 시작해 필요한 포트와 어댑터만 전파한다.
4. 스키마 변경은 다음 Flyway 버전으로 추가하고 적용된 migration을 수정하지 않는다.
5. HTTP·MCP나 외부 계약이 바뀌면 같은 변경에서 기준 PRD·ADR과 README·HANDOFF의 영향 부분만 갱신한다.
6. 도메인 정책, 애플리케이션 조율과 실제 실패 가능한 DB·HTTP 경계만 필요한 만큼 검증한다.

## 문서와 검증

- 사람용 문서, 코드 주석, 커밋 메시지와 리뷰는 구체적인 한국어로 작성한다. API 필드, enum, 경로, 설정 키와 표준명은 원문을 유지한다.
- 제품 동작은 PRD, 장기 구조·신뢰 경계는 ADR, 현재 제한과 다음 작업만 `HANDOFF.md`에 둔다.
- 테스트는 가장 작은 관련 대상을 먼저 실행하고 Flyway·Spring 조립·OAuth callback·외부 어댑터를 바꾸면 통합 테스트로 넓힌다.
- 동일한 조건을 단위·웹·통합 테스트에 반복하지 않는다. 외부 쓰기는 fake 또는 로컬 stub으로 검증하며 실제 GitHub PR을 테스트에 사용하지 않는다.
- 최종 회귀 검증은 `./gradlew test`, 플러그인 변경은 `scripts/validate-plugin.sh`, 스킬 변경은 `quick_validate.py`를 실행한다. Flyway·JDBC·backup·restore 계약을 바꾸면 `scripts/verify-postgres.sh`로 PostgreSQL 17에서도 확인한다.

## Zed 연결 도구 검증

`clients/zed` 변경은 `npm ci --prefix clients/zed --ignore-scripts`, `npm test --prefix clients/zed`와 `ZedBridgeIntegrationTest`를 실행한다. `its_`는 환경 변수와 메모리에만 유지하며 설정·명령 인자·로그에 넣지 않는다. MCP 주소의 redirect를 따르지 않고 외부 오류 본문을 출력하지 않는다. 실제 Zed 앱 확인과 표준 연결 테스트의 범위를 구분한다.

## 조회 개선 검증

- 권한 캐시는 인증 요청 객체의 수명 안에서만 공유한다. `its_` 장기 세션에 저장하거나 새 요청에 이전 권한을 넘기지 않는다.
- history의 지원 불가 예외만 후보 실패로 반환한다. 인증·권한·호출 제한을 부분 결과로 숨기지 않으며 실패 ID 재조회에도 공개 상태와 저장소 범위를 확인한다.
- 게시 사전 점검은 MAINTAINER와 실제 발급 응답의 저장소·권한을 확인한다. token·JWT·외부 오류 본문을 진단 결과로 반환하지 않는다.
- 원본 비교와 새 브라우저 화면은 기존 읽기 서비스의 소유권 검사를 거친다. 비교 때문에 공개 본문이나 검증 상태를 갱신하지 않는다.
- Zed `configure`는 미리보기와 명시적 적용을 구분하고 JSONC 주석·다른 서버·비밀값 미노출·반복 실행을 확인한다.
