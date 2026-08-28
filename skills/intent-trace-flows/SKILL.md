---
name: intent-trace-flows
description: "`/Users/lim/devProject/personal/intent-trace`의 Kotlin/Spring 서비스와 Codex 플러그인 전용 개발 흐름. 변경 의도 수명주기, GitHub 사용자·저장소 권한, 커밋·스냅샷·코드 근거, 검증 증거, REST·MCP 계약, GitHub PR Check Run 게시, Flyway·JDBC, 외부 자격 증명, 문서나 플러그인 스킬을 변경할 때 사용한다."
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
- 공개 기록 본문은 수정하지 않고 새 공개 기록으로 대체한다.
- 코드 근거는 저장소 상대 경로와 필요한 최소 연속 줄 범위로 유지한다.
- 실행하지 않은 명령은 검증으로 만들지 않고 오래된 스냅샷의 검증은 현재 검증으로 표시하지 않는다.

## 팀 사용자와 저장소 접근 경계

- 모든 REST·MCP 요청은 `ghu_` GitHub App user access token으로 `/user`를 확인하고, 숫자 사용자 ID를 `github:<id>` subject로 사용한다. login은 표시값이며 소유권 키로 사용하지 않는다.
- 작성자·사용자 ID를 REST나 MCP 도구 입력으로 받지 않는다. 현재 요청의 인증 사용자만 초안 작성자가 된다.
- `DRAFT`와 `AUTHOR_CONFIRMED`는 만든 작성자만 조회·변경한다. `PUBLISHED`와 `SUPERSEDED`는 해당 GitHub 저장소의 읽기 권한이 있는 팀원만 조회한다.
- GitHub `/user/repos`의 `owner,collaborator,organization_member` 목록에 포함된 저장소만 팀 범위로 인정한다. 그 목록의 `pull`은 READER, `push`는 CONTRIBUTOR, `maintain`·`admin`은 MAINTAINER로 해석한다. public 저장소의 일반 읽기 가능 여부만으로 팀원이라고 판단하지 않는다.
- user access token은 요청 처리 중에만 보유하고 DB, 로그, 예외, 도구 입력에 넣지 않는다. 서버 게시용 installation token과 사용자 token의 책임을 섞지 않는다.
- V3 이전 작성자는 `legacy:<lowercase-login>`으로 보존하고 자동으로 GitHub 계정에 연결하지 않는다.

## 외부 게시 경계

- GitHub 게시 전 공개 기록인지 확인하고 PR `head.sha`가 기록의 `targetRevision`과 정확히 같은지 서버 응답으로 검증한다.
- Check Run의 `external_id`에는 변경 기록 UUID를 사용해 재시도 시 기존 실행을 찾아 갱신한다.
- GitHub 원격 호출을 DB 트랜잭션 안에서 실행하지 않는다. 외부 성공 뒤 로컬 저장이 실패해도 같은 요청을 안전하게 재시도할 수 있어야 한다.
- GitHub App client ID와 Base64 PEM private key는 환경 변수로만 주입하고 JWT·installation token을 DB, 로그, 오류 응답, Check Run 본문에 넣지 않는다.
- installation token은 대상 저장소와 필요한 권한으로 축소하고 만료 전에 메모리에서 갱신한다.
- 동적 token의 `401`은 캐시를 폐기하고 한 번만 반복하며 고정 token이나 다른 실패를 무조건 재시도하지 않는다.
- 외부 응답 본문과 자격 증명을 예외 메시지에 그대로 노출하지 않고 안정된 실패 분류로 변환한다.
- 실제 GitHub 쓰기는 사용자가 명시적으로 요청한 저장소와 PR에만 수행한다.

## 변경 절차

1. 요청을 기록 수명주기, 사용자·권한, 증거, 조회, GitHub 게시, 플러그인 또는 운영 중 하나로 분류한다.
2. `rg`로 같은 책임의 도메인 규칙, 포트, 어댑터, SQL, DTO, MCP 도구와 문서를 찾는다.
3. 가장 좁은 소유 계층에서 시작해 필요한 포트와 어댑터만 전파한다.
4. 스키마 변경은 다음 Flyway 버전으로 추가하고 적용된 migration을 수정하지 않는다.
5. HTTP·MCP나 외부 계약이 바뀌면 같은 변경에서 기준 PRD·ADR과 README·HANDOFF의 영향 부분만 갱신한다.
6. 도메인 정책, 애플리케이션 조율과 실제 실패 가능한 DB·HTTP 경계만 필요한 만큼 검증한다.

## 문서와 검증

- 사람용 문서, 코드 주석, 커밋 메시지와 리뷰는 구체적인 한국어로 작성한다. API 필드, enum, 경로, 설정 키와 표준명은 원문을 유지한다.
- 제품 동작은 PRD, 장기 구조·신뢰 경계는 ADR, 현재 제한과 다음 작업만 `HANDOFF.md`에 둔다.
- 테스트는 가장 작은 관련 대상을 먼저 실행하고 Flyway·Spring 조립·외부 어댑터를 바꾸면 통합 테스트로 넓힌다.
- 동일한 조건을 단위·웹·통합 테스트에 반복하지 않는다. 외부 쓰기는 fake 또는 로컬 stub으로 검증하며 실제 GitHub PR을 테스트에 사용하지 않는다.
- 최종 회귀 검증은 `./gradlew test`, 플러그인 변경은 `scripts/validate-plugin.sh`, 스킬 변경은 `quick_validate.py`를 실행한다.
