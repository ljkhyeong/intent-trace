# IntentTrace 인수인계

## 현재 구현

- Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- H2 기본 저장소와 PostgreSQL 프로필
- Flyway 초기 스키마
- Flyway V2 GitHub 게시 이력, V3 GitHub 작성자 subject, V4 저장소 키와 V5 코드 경로 정규화 스키마
- 변경 의도 생성·확인·공개·대체·라인 조회
- 팀 공유용 Markdown 출력
- PR HEAD 커밋 검증과 neutral GitHub Check Run 게시·재시도 갱신
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신·401 복구
- GitHub user access token 인증과 저장소별 READER·CONTRIBUTOR·MAINTAINER 역할 판정
- GitHub Web Application Flow와 callback `state`·cookie·TTL·일회성 검증
- GitHub access·refresh token 메모리 보관과 만료 전 token 쌍 자동 갱신
- SHA-256 digest로 조회하는 `its_` 로컬 세션과 Codex MCP 인증
- PostgreSQL·Caddy HTTPS 기반 단일 인스턴스 Docker Compose
- 비root·읽기 전용 app container와 분리된 data·edge network
- PostgreSQL 17 migration·JDBC·backup·restore 왕복과 GitHub Actions 검증
- 초안 작성자 소유권과 저장소 권한 기반 팀 공개 조회
- Streamable HTTP MCP 도구 6개
- REST·MCP 공통 생성 입력 검증과 전체 Git commit 값 객체
- GitHub token·private key·client secret의 안전한 문자열 표현
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- IntentTrace 저장소 전용 개발 스킬

## 확인할 불변식

- 초안은 만든 작성자만 확인한다.
- 초안과 확인 기록은 만든 작성자만 조회하고, 공개·대체 기록은 저장소 읽기 권한이 있는 팀원만 조회한다.
- 작성자는 요청 본문이 아니라 `/user`에서 확인한 GitHub 숫자 ID로 결정한다.
- Codex에는 GitHub token 대신 `its_` session token만 전달하고 GitHub token 쌍은 메모리 밖으로 노출하지 않는다.
- callback은 같은 브라우저의 cookie와 미사용 `state`가 일치할 때만 code를 교환한다.
- 미완료 OAuth `state`는 TTL과 전역 개수 상한으로 제한하고 상한 도달 시 새 승인을 거부한다.
- refresh token은 한 번 사용한 뒤 새 access·refresh token 쌍으로 함께 교체하고, 사용자 subject가 바뀌면 세션을 폐기한다.
- 생성·확인·공개·대체·GitHub 게시는 저장소 쓰기 권한이 필요하다.
- 확인 시 전체 Git 커밋 ID가 필요하다.
- 확인과 공개 시 현재 스냅샷이 기록의 스냅샷과 같아야 한다.
- 공개된 본문과 근거는 수정하지 않고 새 공개 기록으로 대체한다.
- 팀 조회에는 공개 또는 대체된 기록만 노출한다.
- GitHub 게시 전 기록 저장소와 PR 저장소, 기록 커밋과 PR `head.sha`가 각각 일치해야 한다.
- Check Run은 `intent-trace:<변경 기록 UUID>` `external_id`로 재사용하고 GitHub 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- 같은 기록과 PR의 게시 요청은 단일 app에서 직렬화하고, Check Run 검색 한도를 다 채우면 중복 생성하지 않는다.
- GitHub 저장소 식별자는 소문자 `owner/repository`로 정규화해 권한·멱등성·조회·게시에서 같은 값으로 비교한다.
- 코드 근거 경로는 `./`, 중복 `/`, 끝 `/`을 제거해 저장과 라인 조회에서 같은 값으로 비교한다.
- 같은 `requestId`는 작성자·저장소와 정규화된 저장 내용이 모두 같은 재시도에만 기존 기록을 반환한다.
- 팀 배포는 Caddy만 host port를 열고 app·PostgreSQL은 Docker network 안에 둔다.
- PostgreSQL에는 제품 데이터만 저장하며 GitHub access·refresh token과 `its_` session은 app 메모리에만 둔다.
- restore는 app 중지와 명시적 `--confirm-replace` 없이는 실행하지 않는다.
- MCP 생성 도구는 Jakarta Validator를 명시적으로 실행하고 전체 Git commit 형식은 도메인 값 객체에서 검증한다.
- GitHub 자격 증명 보유 객체의 `toString()`에는 실제 비밀값을 넣지 않는다.

## 다음 작업 후보

1. IntelliJ에서 현재 줄의 공개 변경 의도를 조회한다.
2. 실제 운영 결과를 바탕으로 encrypted session 저장 필요성을 다시 결정한다.
3. 코드 근거를 Check Run line annotation으로 선택 게시한다.
4. GitHub App webhook으로 사용자 승인·설치 제거와 권한 변경을 반영한다.

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
- IDE 자동 연동은 아직 구현하지 않았다.
- 감사 로그와 기록 보존 정책은 아직 구현하지 않았다.
