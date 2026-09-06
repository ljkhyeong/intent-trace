---
name: intent-trace-flows
description: IntentTrace 저장소의 서버, Codex·IntelliJ·Zed 연동, 배포 설정과 문서를 수정할 때 사용한다. 변경할 기능의 설계 문서와 검증 명령을 찾는다. 다른 저장소에서 IntentTrace 기록을 사용하는 작업은 intent-trace 스킬을 따른다.
---

# IntentTrace 개발

공통 작업 규칙은 루트 [AGENTS.md](../../AGENTS.md)에 있다. 아래에서 변경할 기능에 해당하는 문서만 읽는다. 제품 동작은 PRD, 설계와 제약은 ADR을 기준으로 하며 관련 계약이 바뀌면 해당 문서도 수정한다.

## 기능별 문서

| 변경 대상 | 읽을 문서 |
| --- | --- |
| 기록 생성·확인·공개·대체·후속 초안 | [MVP 요구사항](../../docs/PRD-0001-intent-trace-mvp.md), [기록·스냅샷 규칙](../../docs/ADR-0001-evidence-bound-change-record.md) |
| 초안 수정·검색·페이지 조회·원본 비교 | [기록 관리 계약](../../docs/PRD-0004-record-management-and-evidence.md), 기존 페이지 번호 방식은 [기록함 계약](../../docs/PRD-0005-record-browser.md) |
| 코드 해시 확인·이전 줄 조회·중단 후 재개 | [코드 확인과 이력 조회](../../docs/ADR-0007-evidence-check-and-history.md) |
| 기록 변경 이력 | [이력 저장과 공개 범위](../../docs/ADR-0011-record-activity-history.md) |
| GitHub 사용자·저장소 권한 | [팀 접근 요구사항](../../docs/PRD-0003-team-identity-and-repository-access.md), [권한 확인](../../docs/ADR-0004-github-user-repository-authorization.md) |
| OAuth·세션 갱신·폐기 | [메모리 세션](../../docs/ADR-0005-github-web-oauth-memory-session.md) |
| 브라우저 로그인·기록 화면 | [브라우저 전용 세션](../../docs/ADR-0009-browser-record-access.md) |
| GitHub Check Run 게시·대체 안내·재시도 | [게시 요구사항](../../docs/PRD-0002-github-pr-publication.md), [Check Run](../../docs/ADR-0002-github-check-run-publication.md), [App 인증](../../docs/ADR-0003-github-app-installation-auth.md), [게시 복구](../../docs/ADR-0008-publication-recovery.md) |
| 이슈·PR 초안 재료·Actions 결과 조회 | [GitHub 자료 조회](../../docs/ADR-0012-github-context-read.md) |
| IntelliJ 플러그인 | [현재 줄 조회](../../docs/PRD-0004-intellij-line-intent.md), [클라이언트 보안·통신](../../docs/ADR-0007-intellij-plugin-client-boundary.md) |
| Zed 연결·설정·패키지 | [MCP 중계와 진단](../../docs/ADR-0010-zed-mcp-and-connection-diagnostics.md), [사용 안내](../../docs/clients/zed.md), [배포 안내](../../docs/clients/zed-distribution.md) |
| Compose·DB·백업·복구·릴리스 | [배포 구조](../../docs/ADR-0006-single-instance-team-deployment.md), [운영 절차](../../docs/operations/team-deployment.md), [릴리스 절차](../../docs/operations/release.md) |

## 구현 시 주의할 점

- 상태 전이는 `domain`, 사용 사례와 포트는 `application`, REST·MCP는 `adapter/in`, JDBC·외부 HTTP는 `adapter/out`에 둔다. REST와 MCP는 같은 서비스를 호출한다.
- Spring AI MCP는 Jakarta Validation을 자동 실행하지 않는다. 생성·수정 DTO는 `Validator`로 검증하고 선택 입력은 `McpToolParam(required = false)`로 등록한다. 전체 커밋 형식은 도메인 `GitRevision`에서도 검사한다.
- MCP 응답의 최상위 값은 객체로 유지하고 목록은 `items`에 넣는다. 도구 계약을 바꾸면 표준 SDK로 실제 서버 연결을 확인한다.
- DB 변경은 새 Flyway 버전으로 추가한다. 적용된 migration을 수정하지 않는다. GitHub 호출은 DB 트랜잭션 밖에서 실행한다.
- 테스트는 관련 기능부터 실행한다. 외부 게시는 fake·로컬 stub으로 검증하고 실제 GitHub PR을 테스트용으로 변경하지 않는다.

## 변경별 검증

아래에서 영향받는 항목만 실행한다. 수정 중에는 관련 테스트, 코드 수정을 마친 뒤에는 필요한 전체 검증을 한 번 실행한다. 결과 정리·작업 재개는 [로컬 검증 절차](../../docs/development/verification.md)를 따른다. 동작을 바꾸지 않은 문서 수정에는 서버·IDE 테스트가 필요하지 않다.

| 변경 대상 | 검증 |
| --- | --- |
| 서버 Kotlin·Spring 설정 | 수정 중 `./gradlew focusedTest --tests '*대상테스트명'`, 수정 완료 후 `./gradlew test` |
| Flyway·JDBC·백업·복구 | `scripts/verify-postgres.sh`, 백업 스크립트 수정 시 `python3 scripts/test_backup_postgres.py` |
| Codex 플러그인·스킬 | `scripts/validate-plugin.sh`, 스킬 frontmatter·문서 링크·사용 조건 확인. `quick_validate.py`가 설치되어 있으면 변경한 스킬에 실행 |
| IntelliJ | `./gradlew -p intellij-plugin test`, 패키지·설정 변경 시 `buildPlugin verifyPluginProjectConfiguration verifyPluginStructure`도 실행 |
| Zed·MCP 연결 | 의존성이 없거나 명세가 바뀌면 `npm ci --prefix clients/zed --ignore-scripts`. `npm test --prefix clients/zed`와 `./gradlew focusedTest --tests '*ZedBridgeIntegrationTest'`. 서버 전체 검증에서 연결 테스트가 통과했다면 별도 실행 생략 |
| Compose | `python3 scripts/validate-compose.py .env.team.example`, Caddy 수정 시 운영 문서의 설정 검증 |
| 릴리스 버전·패키지 | [릴리스 절차](../../docs/operations/release.md)의 해당 검사 |

결과 집계는 `python3 scripts/test-summary.py server focused postgres intellij`에서 실행한 대상만 지정한다. SDK·stub 검증을 실제 IDE 화면 확인이나 GitHub 게시 성공으로 설명하지 않는다. 전체 릴리스는 [README 검증 목록](../../README.md#검증)을 따른다.
