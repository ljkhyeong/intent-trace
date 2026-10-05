# IntentTrace 추가·개선 기능 검토

검토일: 2026-10-05

기준 커밋: `9bc5a8ddd44a9fce76f5554f2b728dbc36f76c4a`

상태: P1 8건은 같은 날 반영했다. 검증 결과는 [HANDOFF](../../HANDOFF.md#2026-10-05-추가개선-기능-검토-반영)를 따른다. P2는 제안이며 구현하지 않았다.

## 우선순위

서버·클라이언트 코드와 PRD·ADR·README 계약을 비교했다. 운영 사용량은 측정하지 않았다. 재현 여부는 항목마다 적는다.

| 순서 | 우선순위 | 항목 | 확인 방법 |
| --- | --- | --- | --- |
| 1 | P1 | GitHub에 없는 커밋을 일시 장애(502)로 안내 | 코드 확인, 반영 후 어댑터 테스트 |
| 2 | P1 | 쓰기 전 PR 조회 실패를 게시 결과 미확인으로 저장 | 코드 확인, 반영 후 게시 테스트 |
| 3 | P1 | 기록 ID 조회의 403이 저장소 이름·기록 존재를 노출 | 코드 확인, 반영 후 서비스 테스트 |
| 4 | P1 | 브라우저 로그인이 오래 쓰는 도구 세션을 밀어냄 | 코드 확인, 반영 후 세션 저장소 테스트 |
| 5 | P1 | MCP에 Markdown 내보내기 도구가 없음 | README 계약과 도구 목록 비교 |
| 6 | P1 | 연결 진단이 PR 저장소 불일치에서 전체 중단 | 코드 확인, 반영 후 진단 테스트 |
| 7 | P1 | Zed `check`가 서버 입력 오류를 연결 실패로 표시 | 코드 확인, 반영 후 실제 서버 연결 테스트 |
| 8 | P1 | IntelliJ의 400·403·503 안내와 검색어 길이 | 코드 확인, 반영 후 IntelliJ 테스트 |
| 9 | P2 | 기록별 게시 PR 목록 | 코드 확인 |
| 10 | P2 | GitHub App 설치 제거·권한 변경 웹훅 | 코드 확인 |
| 11 | P2 | IntelliJ 저장소 진단과 IDE 안 이전 기록 조회 | 코드·ADR 비교 |
| 12 | P2 | 그 밖의 정리 후보 | 코드 확인 |

## 1. GitHub에 없는 커밋을 일시 장애로 안내

**확인한 문제:** 커밋 조회가 404·422여도 [GitHubGitEvidenceClient.kt](../../src/main/kotlin/io/intenttrace/record/adapter/out/github/GitHubGitEvidenceClient.kt#L90)는 `GitHubApiException`을 던지고 [ApiExceptionHandler.kt](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ApiExceptionHandler.kt#L80)가 502로 응답한다. 이전 기록 조회는 [지원 불가 사유만 후보 실패로 처리](../../src/main/kotlin/io/intenttrace/record/application/ChangeIntentHistoryService.kt#L77)하므로 푸시하지 않은 커밋으로 조회하거나 후보 기록의 커밋이 GitHub에 없으면 페이지 전체가 실패한다. 다시 시도해도 결과가 같다. 코드로만 확인했다.

- 최소 변경: 저장소 권한 확인 뒤의 [커밋 조회](../../src/main/kotlin/io/intenttrace/record/adapter/out/github/GitHubGitEvidenceClient.kt#L35)에서 404·422를 `REVISION_NOT_FOUND` 확인 불가 사유로 바꾼다. 기존 422·`failures`·진단 표시를 그대로 사용한다. 트리·blob·비교 조회는 바꾸지 않는다.
- 완료 기준: 코드 확인은 422와 푸시 안내, 이전 기록 조회는 해당 후보만 실패, 연결 진단은 `git_tree_read=FAILED`와 같은 안내를 반환한다.

## 2. 쓰기 전 PR 조회 실패를 결과 미확인으로 저장

**확인한 문제:** [TeamGitHubPublicationService.kt](../../src/main/kotlin/io/intenttrace/publication/application/TeamGitHubPublicationService.kt#L56)는 분류하지 않은 예외를 모두 `RESULT_UNKNOWN`으로 저장한다. PR 번호 오류, App 미설치처럼 [PR 조회 단계](../../src/main/kotlin/io/intenttrace/publication/application/PublishChangeRecordToGitHub.kt#L52)에서 끝난 요청도 PR 기록 화면에 "결과 미확인 · 다시 실행"으로 표시된다. [ADR-0008](../ADR-0008-publication-recovery.md)은 명확한 거부를 `FAILED`로 정했다. 코드로만 확인했다.

- 최소 변경: PR 조회(설치 토큰 발급 포함)의 `GitHubApiException`을 `PullRequestUnavailableException`으로 감싸 `FAILED`·`PULL_REQUEST_UNAVAILABLE`로 저장한다. HTTP 502와 원래 안내는 유지하고 쓰기 전에 중단했다는 문장을 붙인다.
- 완료 기준: PR 조회 실패 시 Check Run 쓰기 호출이 없고 시도 상태가 `FAILED`다. 쓰기 뒤 응답 유실은 계속 `RESULT_UNKNOWN`이다.
- 제외: Check Run 검색 한도 초과도 쓰기 전 실패지만 발생 빈도가 낮아 이번에 분류하지 않았다.

## 3. 기록 ID 조회의 403이 저장소 이름과 기록 존재를 노출

**확인한 문제:** [TeamChangeRecordService.get](../../src/main/kotlin/io/intenttrace/record/application/TeamChangeRecordService.kt#L33)은 기록을 찾은 뒤 권한을 확인한다. [RepositoryAccessDeniedException](../../src/main/kotlin/io/intenttrace/identity/application/GitHubUserAccess.kt#L72) 문구에 저장소 키가 있고 [REST 403](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ApiExceptionHandler.kt#L60)이 그대로 반환한다. 웹은 같은 경우를 [404로 숨긴다](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserController.kt#L249). 단건·Markdown·비교·코드 확인·변경 이력·게시 상태가 같은 경로를 쓴다. 코드로만 확인했다.

- 최소 변경: ID로 읽는 `get`에서 저장소 권한 없음과 다른 작성자의 비공개 기록을 `ChangeRecordNotFoundException`으로 바꾼다. 목록·변경 작업의 403은 유지한다.
- 완료 기준: REST·MCP의 ID 조회 오류에 저장소 이름이 없고 기록 없음과 구분되지 않는다.

## 4. 브라우저 로그인이 도구 세션을 밀어냄

**확인한 문제:** [removeOldestSessionsAtLimit](../../src/main/kotlin/io/intenttrace/identity/application/InMemoryGitHubUserSessionStore.kt#L95)는 채널과 관계없이 가장 오래된 세션을 폐기한다. 브라우저 세션은 8시간 뒤 만료되지만 `its_` 도구 세션은 refresh token 수명까지 쓰므로, 여러 브라우저로 로그인하면 보통 Codex·Zed·IntelliJ 연결이 예고 없이 끊긴다. 코드로만 확인했다.

- 최소 변경: 상한에서 새 세션과 같은 채널의 오래된 세션부터 폐기하고, 같은 채널이 없으면 기존처럼 가장 오래된 세션을 폐기한다. 상한 수와 설정 키는 유지한다.
- 완료 기준: 브라우저 로그인을 반복해도 도구 세션이 유지된다.

## 5. MCP Markdown 내보내기 누락

**확인한 문제:** Markdown 출력은 [REST](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ChangeRecordController.kt#L101)와 웹에만 있다. README는 [MCP가 REST와 같은 기능을 사용한다](../../README.md#mcp-도구)고 안내하지만 [MCP 도구](../../src/main/kotlin/io/intenttrace/record/adapter/in/mcp/IntentTraceTools.kt#L83)에는 대응이 없다.

- 최소 변경: 읽기 전용 `get_change_record_markdown(recordId)`가 `{recordId, version, status, markdown}`을 반환한다. 기존 권한 검사와 렌더러를 사용한다.
- 완료 기준: Agent가 PR 설명에 붙일 기록 본문을 REST 없이 읽는다.

## 6. 연결 진단의 PR 저장소 불일치

**확인한 문제:** [GitHubUserPullRequestClient.kt](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubUserPullRequestClient.kt#L41)의 `GitHubRepositoryMismatchException`을 [진단 check](../../src/main/kotlin/io/intenttrace/connection/application/ConnectionDiagnostics.kt#L36)가 잡지 않아 진단 전체가 409로 끝난다. ADR-0010은 PR 조회 실패에도 나머지 진단을 계속하도록 정했다. 저장소 이름 변경 직후처럼 드문 경우다.

- 최소 변경: 해당 예외를 `pull_request_read=FAILED`로 기록하고 나머지 진단을 계속한다.

## 7. Zed `check`의 서버 입력 오류

**확인한 문제:** [bridge.mjs](../../clients/zed/bridge.mjs#L81)는 진단 도구의 `isError`를 일반 오류로 던지고, 이 오류가 `CONNECTION_FAILED`로 바뀐다. `--revision` 오타만으로 "서버 주소와 연결 상태를 확인하세요"가 표시된다. ADR-0010은 업무 오류 내용을 전달하도록 정했다. 코드로만 확인했다.

- 최소 변경: 서버 안내를 제어 문자 제거·500자 제한 후 출력하고 오류 코드 줄 없이 종료 코드 1을 반환한다.

## 8. IntelliJ 오류 안내와 검색어 길이

**확인한 문제:** [IntentTraceApiClient.kt](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/IntentTraceApiClient.kt#L79)는 상태 확인의 503(Spring의 DOWN 응답)을 "요청 거부"로, 400을 일반 거부로 표시한다. [403 문구](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/IntentTraceApiClient.kt#L81)는 기록 단위로 안내하지만 목록·현재 줄 조회의 403은 저장소 단위다. 검색창은 200자 제한을 안내하지만 [search](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/IntentTraceRecordBrowser.kt#L149)는 길이를 확인하지 않아 서버 [400](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordCatalog.kt#L88)을 그대로 받는다.

- 최소 변경: 400은 조회 조건 확인, 403은 저장소 권한, 상태 확인 503은 서버가 UP이 아님으로 안내한다. 200자를 넘는 검색어는 요청 없이 안내하고 입력을 유지한다. 설정 액션 설명의 "로그인 정보 없이"도 현재 기능에 맞춘다.

## 9. P2: 기록별 게시 PR 목록

게시 저장소는 [PR을 지정한 조회](../../src/main/kotlin/io/intenttrace/publication/application/GitHubPublicationPorts.kt#L36)만 지원한다. 기록을 대체한 뒤 어느 PR의 Check Run에 대체 안내를 반영할지 작성자가 기억해야 한다. 기록 ID로 게시 대상과 최신 시도를 읽는 REST·MCP 조회와 상세 화면의 PR 링크를 제안한다. 새 조회 계약과 화면 변경이 필요해 별도 작업으로 둔다.

## 10. P2: GitHub App 설치 제거·권한 변경 웹훅

[웹훅](../../src/main/kotlin/io/intenttrace/identity/adapter/in/web/GitHubWebhookController.kt#L30)은 사용자 승인 취소만 처리한다. 설치 토큰은 [저장소별 메모리 캐시](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAccessTokenProvider.kt#L17)에 최대 55분 남는다. 401에는 이미 한 번 재발급하므로 설치 제거·정지는 첫 실패 뒤 정리될 것으로 추정한다(GitHub 응답은 확인하지 않음). 저장소를 설치 범위에서 뺐다가 다시 추가하는 경우에만 캐시 만료까지 실패할 수 있다. `installation`·`installation_repositories` 이벤트에서 설치 ID로 캐시를 비우는 방안을 제안한다. 실제 발생 빈도를 확인한 뒤 진행한다.

## 11. P2: IntelliJ 저장소 진단과 IDE 안 이전 기록 조회

- 설정의 확인 기능은 [로그인까지만](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/IntentTraceSettingsConfigurable.kt#L34) 본다. 서버의 `connection-diagnostics`를 쓰는 저장소 진단 메뉴를 추가하면 403 원인을 IDE에서 확인할 수 있다.
- 현재 줄 조회는 정확히 같은 커밋만 찾고 이전 커밋은 웹으로 보낸다. `/history`를 IDE에서 호출하려면 ADR-0007의 웹 이동 결정과 10초 응답 제한을 함께 바꿔야 한다.
- [현재 줄 문맥](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/CurrentLineContextResolver.kt#L27)은 비동기로 갱신되는 Git 상태를 읽는다. 터미널 checkout 직후 다른 HEAD로 조회할 수 있다는 지적은 재현하지 않았다.
- 서버에 닿지 않으면 [세션 삭제](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/DisconnectSessionAction.kt#L17)가 로컬 토큰도 지우지 않는다. ADR-0007의 의도된 동작이므로 폐기된 서버용 "이 PC에서만 삭제" 확인 절차를 별도로 정한다.

## 12. P2: 그 밖의 정리 후보

- [현재 줄 조회](../../src/main/kotlin/io/intenttrace/record/adapter/out/persistence/JdbcChangeRecordRepository.kt#L125)는 결과 수 제한이 없고 같은 공개 시각의 순서가 고정되지 않는다. 한 줄에 많은 기록이 쌓이는 사례를 확인한 뒤 상한과 `id` 정렬을 추가한다.
- [변경 이력 화면](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserManagement.kt#L32)은 처리자를 `github:<id>`로 표시한다. 작성자 login 표시를 검토한다.
- Zed의 세션 토큰 형식은 [32~128자](../../clients/zed/intent-trace.mjs#L42)를 받고 서버·IntelliJ는 43자를 쓴다. 잘못 붙여넣은 토큰이 연결 뒤에야 드러난다.
- [feedback.py](../../scripts/feedback.py#L37)는 명령마다 150초 제한이 있고 Gradle을 두 번까지 실행해 훅 제한 180초를 넘을 수 있다. 종료 검사는 `finish`로 다시 실행하므로 영향은 작다.

## 검증과 한계

- 실제 GitHub 저장소·PR 게시, 실제 IDE 화면과 Zed 앱은 확인하지 않았다. GitHub 응답은 로컬 stub과 fake로 검증했다.
- 1·2·3·4·6번은 반영 전 코드에서 재현 테스트를 따로 실행하지 않았다. 반영 후 테스트로 새 동작을 확인했다.
