# IntentTrace 추가·개선 기능 검토

검토일: 2026-09-05  
기준 커밋: `ec5c7af9a3c4bd36148e980a91271965ebcb4c66`  
상태: 코드와 PRD·ADR를 비교한 제안. 기능 구현이나 제품 요구사항 확정은 포함하지 않는다.

## 판단

기록 생성·확인·공개, GitHub 사용자 권한, PR HEAD 비교와 팀 배포는 구현되어 있다. 다음 개발은 작성자가 기록을 고치고 다시 찾는 흐름과 증거의 확인 범위를 보완하는 데 집중하는 편이 좋다. IntelliJ 연동은 이 흐름이 갖춰진 뒤 연결해야 사용자가 현재 코드에서 이전 의도를 찾을 수 있다.

아래 우선순위는 운영 사용량을 측정한 결과가 아니라 현재 구현과 사용자 흐름에 근거한 제안이다. **P1**은 다음 개발에서 우선 처리할 항목, **P2**는 그다음 확장 또는 팀 사용량에 따라 추진할 항목이다.

| 순서 | 우선순위 | 추가·개선 항목 | 사용자가 얻는 결과 |
|---|---|---|---|
| 1 | P1 | PR 저장소 확인과 Fork PR 명시적 거부 | 지원하지 않는 PR에 게시를 시도하기 전에 이유를 알 수 있다. |
| 2 | P1 | 초안 수정·폐기와 내 초안 목록 | 작성자 피드백을 반영하고 중단한 기록 작업을 다시 시작한다. |
| 3 | P1 | 저장소·파일별 기록 목록과 이전 커밋 탐색 | 기록 UUID나 과거 커밋을 몰라도 관련 의도를 찾는다. |
| 4 | P1 | 증거 수집 도구와 서버 확인 여부 표시 | 어떤 코드에서 검증했으며 무엇을 서버가 확인했는지 구분한다. |
| 5 | P1 | 삭제·이름 변경 코드의 이전 근거 | 삭제한 기능의 이유도 해당 변경 기록에 남긴다. |
| 6 | P1 | MCP 대체 기능과 GitHub 대체 안내 | 이전 설명에서 새 설명으로 따라갈 수 있다. |
| 7 | P2 | GitHub 게시 상태 조회와 재시도 관리 | 게시 결과가 불확실할 때 중복 요청 없이 복구한다. |
| 8 | P2 | 내 세션 조회·폐기 | 사용하지 않는 연결을 서버 재시작 없이 종료한다. |
| 9 | P2 | GitHub 호출 제한 안내와 운영 지표 | 연결 실패 원인과 다시 시도할 시점을 확인한다. |

## 1. PR 저장소 확인과 Fork PR 명시적 거부

**확인한 구현 누락:** 서버는 PR 응답의 `head.sha`만 읽는다. `head.repo`와 `base.repo`를 보존하거나 비교하지 않아, SHA가 맞으면 Fork 여부를 검사하지 않고 Check Run 쓰기 단계로 진행할 수 있다. 플러그인 스킬의 Fork 중단 지침만으로는 직접 REST·MCP 호출까지 보장할 수 없다. 실제 GitHub 게시 성공 여부는 이번 검토에서 확인하지 않았다.

- 근거: [GitHubRestClient.kt](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubRestClient.kt#L30)의 PR 조회와 `PullRequestHeadResponse`, [PublishChangeRecordToGitHub.kt](../../src/main/kotlin/io/intenttrace/publication/application/PublishChangeRecordToGitHub.kt#L29)의 게시 검사, [사용 스킬](../../skills/intent-trace/SKILL.md)의 중단 조건.
- 최소 변경: PR 조회 결과에 저장소 식별자를 포함하고 기록 저장소·PR base 저장소·head 저장소를 서버 응답으로 비교한다. Fork와 저장소 정보 누락은 외부 쓰기 전에 명확한 오류로 처리한다.
- 완료 기준: 같은 SHA를 가진 Fork 응답도 거부하고 Check Run 쓰기 호출이 발생하지 않는다. 정상 저장소 PR은 기존대로 게시한다.

GitHub도 Checks API가 생성된 저장소의 push만 조회하며 Fork branch에서는 `pull_requests`가 비어 있을 수 있다고 설명한다. 따라서 Check Run 생성과 대상 PR 연결을 같은 성공으로 간주하지 않는 계약이 필요하다. [GitHub Checks API](https://docs.github.com/en/rest/checks/runs)

## 2. 초안 수정·폐기와 내 초안 목록

**현재 제약:** 초안 생성 이후에는 확인·공개·대체만 가능하다. 작성자가 요약이나 판단 출처를 고치려면 새 요청 ID로 새 초안을 만들어야 한다. 같은 요청 ID에 다른 내용을 보내도 같은 작성자·저장소이면 이전 기록을 반환한다. UUID를 잃어버렸을 때 내 초안을 찾는 목록 API도 없다.

- 근거: [ChangeRecordController.kt](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ChangeRecordController.kt#L28), [ChangeRecord.kt](../../src/main/kotlin/io/intenttrace/record/domain/ChangeRecord.kt#L29), [ChangeRecordFacade.kt](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordFacade.kt#L138).
- 최소 기능: 작성자 전용 초안 목록, `DRAFT` 내용 수정, 비공개 초안 폐기. 수정에는 현재 버전을 요구한다. 확인 완료 후 수정이 필요하면 확인을 취소하는 절차를 먼저 거치고 다시 확인받는다.
- 보완: 생성 요청의 정규화된 내용 해시를 비교해 같은 요청 ID에 다른 내용을 보내면 충돌을 알린다. 같은 내용의 재시도는 기존 기록을 반환한다.
- 완료 기준: 초안을 수정하고 다시 확인할 수 있으며 다른 작성자의 초안은 목록·수정·폐기에 노출되지 않는다. 공개 본문은 계속 수정할 수 없다.

## 3. 저장소·파일별 기록 목록과 이전 커밋 탐색

**현재 제약:** 조회는 UUID 단건 또는 저장소·전체 커밋·파일·줄의 완전 일치뿐이다. A 커밋에서 기록한 파일이 B 커밋에서도 그대로여도 B를 넣으면 A의 기록은 조회되지 않는다. 조회 결과는 페이지 제한 없이 본문 전체를 불러온다.

- 근거: [JdbcChangeRecordRepository.kt](../../src/main/kotlin/io/intenttrace/record/adapter/out/persistence/JdbcChangeRecordRepository.kt#L45)의 `target_revision = ?`, 결과별 `hydrate`; [IntentTraceTools.kt](../../src/main/kotlin/io/intenttrace/record/adapter/in/mcp/IntentTraceTools.kt#L129)의 `find_change_intent`.
- 첫 범위: 저장소별 공개 기록 목록과 파일·작성자·상태 필터, 커서 페이지 처리, 요약 응답. 내 초안 목록과 팀 공개 목록의 접근 규칙을 구분한다.
- 다음 범위: 정확히 일치하는 기록이 없으면 이전 커밋의 관련 기록을 별도로 제시한다. 원본 커밋과 조회 커밋, 일치 근거를 응답에 담는다. 같은 코드인지 검증할 수 없는 후보는 현재 코드의 확정 설명으로 표시하지 않는다.
- 완료 기준: 관계없는 파일만 바뀐 새 커밋에서도 이전 기록으로 접근한다. 코드가 바뀐 경우 관련 기록과 정확히 일치하는 기록을 구분하고 과거 테스트를 현재 테스트로 표시하지 않는다.

IntelliJ 구현은 이 조회 계약 이후에 진행한다. 우선 REST·MCP 목록만 추가해도 기록을 다시 찾는 경로가 생긴다.

## 4. 증거 수집 도구와 서버 확인 여부 표시

**현재 제약:** `git-evidence.sh`는 커밋 트리와 코드 줄 해시를 계산하지만 검증 명령을 실행하고 결과를 수집하는 기능은 없다. 명령·종료 코드·시각·스냅샷·출력 해시는 클라이언트가 제출한다. 서버의 `current`도 제출한 스냅샷끼리의 비교이며 실제 코드 확인이나 테스트 실행 증명은 아니다. 이는 [ADR-0001](../ADR-0001-evidence-bound-change-record.md)에 명시된 제한이다.

- 근거: [git-evidence.sh](../../scripts/git-evidence.sh#L28), [VerificationRun](../../src/main/kotlin/io/intenttrace/record/domain/ChangeRecord.kt#L115), [작성자 확인](../../src/main/kotlin/io/intenttrace/record/domain/ChangeRecord.kt#L29), [GitHub 게시](../../src/main/kotlin/io/intenttrace/publication/application/PublishChangeRecordToGitHub.kt#L34).
- 첫 범위: 로컬 실행 도구가 실제 명령의 종료 코드·시각·출력 해시를 수집한다. 실행 전후 작업 파일 상태를 확인해 테스트 대상과 기록할 커밋이 다른 경우 알려준다. 원문 출력은 서버에 보내거나 영구 보관하지 않는다.
- 표시 개선: 작성자 확인 여부, 코드 근거의 서버 확인 여부, 검증 결과의 수집 출처를 별도 필드로 제공한다. 현재처럼 클라이언트가 제출한 과거 결과도 출처를 드러내어 조회할 수 있게 한다.
- 다음 범위: GitHub의 해당 커밋에서 트리·파일 범위를 읽어 스냅샷과 줄 해시를 비교한다. 기존 helper와 동일한 직렬화·경로·개행 규칙을 정의하고 필요한 저장소 읽기 권한을 PRD·ADR에 반영한다. GitHub 트리 응답의 일부 누락도 성공으로 처리하지 않는다. [GitHub Git Trees API](https://docs.github.com/en/rest/git/trees)
- 완료 기준: 잘못된 줄 해시를 서버 확인 완료로 표시하지 않는다. 다른 코드로 실행한 테스트를 현재 검증으로 표시하지 않는다. 코드 해시 확인만으로 테스트 실행 자체를 서버가 증명했다고 표시하지 않는다.

## 5. 삭제·이름 변경 코드의 이전 근거

**현재 제약:** 코드 근거에는 경로·줄·내용 해시만 있고 자체 커밋이나 변경 전후 구분이 없다. helper와 사용 스킬은 같은 대상 커밋에서 파일을 읽는다. 삭제된 파일은 대상 커밋에 없으므로 삭제 이전 줄을 정확히 표현할 수 없다. `baseRevision` 필드는 있지만 근거 조회에 쓰이지 않는다.

- 근거: [CodeAnchor](../../src/main/kotlin/io/intenttrace/record/domain/ChangeRecord.kt#L97), [git-evidence.sh](../../scripts/git-evidence.sh#L66), [ChangeRecordDtos.kt](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ChangeRecordDtos.kt#L31).
- 최소 기능: 근거가 `BASE` 또는 `TARGET` 중 어느 쪽인지 명시한다. 변경 전 근거는 검증한 전체 base 커밋을 참조하고 이전 경로를 보존한다. 이름 변경은 이전·이후 근거를 연결한다.
- 완료 기준: 파일 삭제만 있는 변경도 삭제 전 코드로 근거를 만들고 조회한다. 이전 줄을 현재 커밋의 줄로 표시하지 않는다.

## 6. MCP 대체 기능과 GitHub 대체 안내

**현재 제약:** REST에는 `supersede`가 있지만 MCP에는 대응 도구가 없다. 대체 동작은 DB 상태와 `supersededBy`만 갱신한다. Markdown에는 대체 기록 링크가 없으며, 이미 게시한 Check Run도 갱신되지 않는다. `SUPERSEDED` 기록은 현재 GitHub 게시 사용 사례를 다시 호출할 수도 없다.

- 근거: [TeamChangeRecordService.kt](../../src/main/kotlin/io/intenttrace/record/application/TeamChangeRecordService.kt#L39), [IntentTraceTools.kt](../../src/main/kotlin/io/intenttrace/record/adapter/in/mcp/IntentTraceTools.kt), [ChangeRecordMarkdownRenderer.kt](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordMarkdownRenderer.kt#L9), [게시 상태 제한](../../src/main/kotlin/io/intenttrace/publication/application/PublishChangeRecordToGitHub.kt#L25).
- 첫 범위: `supersede_change_record` 도구, Markdown의 대체 기록 ID·접근 링크, 대체 이력 조회.
- 다음 범위: 사용자가 GitHub 반영까지 요청하면 기존 Check Run에 대체 안내와 새 기록 링크를 반영한다. 외부 게시 내용의 해시와 이력을 함께 갱신하되 원래 공개 본문은 보존한다. DB 대체 성공과 GitHub 안내 실패를 구분하고 후자를 재시도할 수 있게 한다.
- 완료 기준: REST와 MCP 모두 대체할 수 있으며 이전 기록에서 후속 기록을 찾는다. GitHub 반영을 요청한 경우 이전 Check Run에도 대체 사실을 확인할 수 있다.

## 7. GitHub 게시 상태 조회와 재시도 관리

**현재 제약:** 성공한 게시 결과를 저장하지만 읽기 API가 없고 실패·진행 중 상태를 기록하지 않는다. 외부 성공 후 응답 또는 로컬 저장이 실패하면 사용자는 결과를 조회하는 대신 다시 게시해야 한다. `external_id`로 기존 Check Run을 찾아 복구하는 순차 재시도는 구현되어 있으나, 최초 동시 요청에서 두 호출이 모두 기존 실행을 못 찾고 생성하는 순서를 막는 조율은 없다. 실제 GitHub 중복 생성은 이번 검토에서 재현하지 않았다.

- 근거: [게시 사용 사례](../../src/main/kotlin/io/intenttrace/publication/application/PublishChangeRecordToGitHub.kt#L45), [upsertCheckRun](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubRestClient.kt#L48), [게시 컨트롤러](../../src/main/kotlin/io/intenttrace/publication/adapter/in/web/GitHubPublicationController.kt#L25).
- 최소 기능: 게시 대상별 최근 상태·Check Run URL·안전한 실패 분류 조회. 현재 단일 인스턴스에서 기록·저장소·커밋 단위로 외부 생성을 조율하고, 응답 유실은 원격 조회로 복구한다. 저장할 실패 정보에 token과 GitHub 오류 원문을 포함하지 않는다.
- 완료 기준: 동시에 같은 기록을 게시해도 생성 단계가 중복 실행되지 않는다. 외부 성공 후 로컬 저장 실패를 stub으로 재현하고 같은 Check Run으로 복구한다.

## 8. 내 세션 조회·폐기

**현재 제약:** 세션 저장소는 발급과 조회만 제공한다. 사용자가 선택한 연결을 끊는 경로가 없어 로컬 session 종료를 위해 서버 재시작이나 GitHub 승인 취소에 의존한다.

- 근거: [GitHubUserSessionStore](../../src/main/kotlin/io/intenttrace/identity/application/GitHubUserOAuth.kt#L65), [InMemoryGitHubUserSessionStore](../../src/main/kotlin/io/intenttrace/identity/application/GitHubUserOAuth.kt#L155).
- 최소 기능: 현재 사용자와 세션 만료 정보 조회, 현재 세션 폐기, 본인의 전체 세션 폐기. 목록은 별도 공개 식별자를 사용하고 token과 digest는 응답하지 않는다.
- 완료 기준: 폐기된 세션의 다음 요청은 거부되며 다른 사용자의 세션은 유지된다. 세션 폐기와 token 갱신이 겹쳐도 폐기한 세션을 다시 활성화하지 않는다.

이 기능은 메모리 저장 방식을 유지해도 구현할 수 있다. 재시작 복구를 위한 자격 증명 영구 저장은 실제 재로그인 불편과 운영 요구를 확인한 뒤 별도로 결정한다.

## 9. GitHub 호출 제한 안내와 운영 지표

**현재 제약:** 요청마다 `/user`와 저장소 목록을 조회하고, 대상 저장소를 찾을 때까지 페이지를 넘긴다. 호출 제한도 일반 의존 서비스 오류로 변환하므로 사용자가 언제 다시 시도할지 알 수 없다. 사용자 요청 지연이나 실제 GitHub 호출량은 이번에 측정하지 않았다.

- 근거: [GitHubUserRestClient.kt](../../src/main/kotlin/io/intenttrace/identity/adapter/out/github/GitHubUserRestClient.kt#L30)의 인증·권한 조회와 오류 변환, [GitHubRestClient.kt](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubRestClient.kt#L207)의 게시 오류 변환.
- 첫 범위: 호출 수·지연·실패 분류와 초안→확인→공개 건수, 게시 복구 건수 측정. 호출 제한은 `Retry-After` 등 안전한 정보를 해석해 재시도 가능 시점을 안내한다. 원문 요청과 token은 수집하지 않는다.
- 다음 범위: 실제 중복 호출이 확인되면 요청 안의 권한 조회부터 재사용한다. 요청 간 캐시는 권한 회수 반영 시간과 무효화 계약을 먼저 정한다. GitHub의 호출 제한 안내도 재시도 전에 대기하도록 권고한다. [GitHub REST API 권장 사용법](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)
- 완료 기준: 호출 제한과 권한 부족을 구분해 안내하고, 자동 재시도가 있으면 지정된 대기 시간을 지킨다. 이전 권한을 무기한 재사용하지 않는다.

## 권장 진행 순서

1. PR 저장소 검사 누락을 보완하고 초안 수정·폐기·목록과 MCP 대체 도구를 추가한다. 작게 나눠 배포할 수 있고 기록 작성 흐름을 바로 개선한다.
2. 증거 출처 표시·실행 결과 수집과 변경 전후 코드 근거를 정의한다. 공개 계약과 데이터 구조에 영향을 주므로 PRD·ADR부터 변경한다.
3. 파일별 이전 기록 탐색과 GitHub 대체 안내·게시 복구를 연결한다. 이 단계 이후 IntelliJ에서 현재 줄을 조회하는 기능을 진행한다.
4. 세션 관리와 호출 지표를 추가하고 실제 사용 결과로 권한 캐시·webhook 우선순위를 결정한다.

Check Run line annotation은 관련 코드로 이동하는 보조 기능으로 남긴다. 먼저 전체 커밋이 고정된 코드 링크를 제공하고, 기록 탐색과 변경 전후 근거가 갖춰진 뒤 annotation을 연결한다. annotation 자체는 GitHub가 제공하는 기능이다. [GitHub Checks 사용 안내](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-checks)

감사 이력과 보존 정책은 팀의 실제 운영 요구를 확인해 별도 범위로 정한다. 공개·대체·폐기 사건을 추적하더라도 대화 원문과 비밀값은 저장하지 않는다. AI 자동 공개, 대화 원문 수집, 다중 인스턴스와 자격 증명 영구 저장은 현재 서비스 경계를 바꿀 수 있어 이번 우선 작업에 포함하지 않는다.

## 검증과 한계

- `./gradlew test`: 성공. 테스트 54개 중 53개 통과, PostgreSQL 전용 1개는 환경 조건 미설정으로 건너뜀. 실패·오류 0개.
- `scripts/validate-plugin.sh`: 성공.
- 실행 코드와 테스트 코드는 변경하지 않았다. 검토 문서와 인계 링크만 추가한다.
- `scripts/verify-postgres.sh`, 실제 GitHub 쓰기, 운영 환경 성능 측정은 실행하지 않았다.
- 기존 테스트 통과는 현재 테스트가 다루는 계약을 확인한 결과다. 위에서 제안한 미구현 기능과 외부 동시 게시 동작까지 검증한 결과는 아니다.
