# IntentTrace 추가 문구 검토

검토일: 2026-09-05

기준 커밋: `fc5c2eb2d1b1d44fd017a7c11fb2560a0308fbd2` · 0.12.2

상태: 추가 수정안 12개를 0.12.3에 반영했다. [적용 내용과 검증 결과](2026-09-05-v0.12.3-verification.md)를 참고한다. 아래 표의 현재 문구와 줄 번호는 검토 당시 기준을 유지한다.

[앞선 검토](2026-09-05-wording-review.md)의 40개 수정안은 반영된 상태다. 이번에는 서버 예외 메시지, 연결 관리·진단, 검색 입력 안내와 플러그인 소개를 확인했다. 실무에서 쓰는 기술 용어는 유지하고, 실제 동작과 다르게 읽히거나 같은 개념의 이름이 다른 항목만 추렸다. 줄 번호는 기준 커밋의 위치다. `{ID}`는 실행 시 표시하는 요청 ID다.

## 먼저 수정할 문구

| 번호 | 현재 문구 | 권장 문구 | 이유와 위치 |
|---|---|---|---|
| 1 | 요청 식별자 {ID} 가 다른 작성자 또는 저장소에서 이미 사용됐습니다. | 요청 ID {ID}가 기존 요청과 충돌합니다. 작성자·저장소·내용을 확인하세요. | 같은 작성자·저장소여도 요청 내용이 다르면 발생한다. [ChangeRecordExceptions.kt:12](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordExceptions.kt#L12) |
| 2 | 코드 스냅샷이 달라져 검증과 판단이 오래된 상태입니다. | 기록과 현재 스냅샷 해시가 달라 공개할 수 없습니다. | 해시가 다르다는 사실만으로 어느 쪽이 오래됐는지 알 수 없다. [ChangeRecord.kt:85](../../src/main/kotlin/io/intenttrace/record/domain/ChangeRecord.kt#L85) |
| 3 | 현재 브라우저와 연결된 모든 Agent·API가 로그아웃됩니다. 다시 사용하려면 로그인해야 합니다. | 내 모든 브라우저·Agent·API 연결이 종료됩니다. 다시 사용하려면 로그인하세요. | 현재 브라우저뿐 아니라 같은 사용자의 다른 브라우저 연결도 종료한다. [RecordBrowserManagement.kt:17](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserManagement.kt#L17) |
| 4 | 대상 저장소로 제한한 token을 메모리에서 발급했습니다. | GitHub App 토큰을 발급받았습니다. | GitHub가 발급하며, 이 단계 다음에 대상 저장소로 제한됐는지 확인한다. [GitHubAppInstallationClient.kt:101](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAppInstallationClient.kt#L101) |

1번의 조건은 [요청 재사용 검사](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordFacade.kt#L204), 3번의 범위는 [사용자별 전체 연결 종료](../../src/main/kotlin/io/intenttrace/identity/application/GitHubUserOAuth.kt#L256), 4번의 확인 순서는 [토큰 발급과 저장소 범위 검사](../../src/main/kotlin/io/intenttrace/publication/adapter/out/github/GitHubAppInstallationClient.kt#L94)에서 확인했다.

## 화면과 용어를 맞출 문구

| 번호 | 현재 문구 | 권장 문구 | 이유와 위치 |
|---|---|---|---|
| 5 | 실행한 검증 | 등록된 검증 결과 | 상세 내용과 빈 상태 안내에 맞춰 등록된 결과를 보여주는 영역임을 밝힌다. [RecordBrowserPage.kt:100](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L100) |
| 6 | 제목, 요청 또는 판단 내용 | 제목, 요청, 구현 결정·이유 | 상세 화면과 같은 용어를 쓰고 실제 검색 대상인 이유도 포함한다. [RecordBrowserPage.kt:49](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L49) |
| 7 | 저장소부터 선택해 주세요 | 저장소를 입력하세요 | 선택 목록이 아니라 직접 입력하는 화면이다. [RecordBrowserPage.kt:57](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L57) |
| 8 | 서버 게시 자격 증명이 설정되어 있습니다. 키 유효성·설치 권한·Checks 쓰기는 확인하지 않았습니다. | GitHub 게시 인증이 설정돼 있습니다. 키 유효성과 설치·Checks 쓰기 권한은 확인하지 않았습니다. | 화면 제목인 ‘GitHub 게시 인증 설정’과 용어를 맞춘다. 설정 존재와 유효성 확인은 계속 구분한다. [ConnectionDiagnostics.kt:68](../../src/main/kotlin/io/intenttrace/connection/application/ConnectionDiagnostics.kt#L68) |
| 9 | 저장소 읽기 권한과 전체 커밋 또는 PR 번호가 필요합니다. | 저장소 읽기 권한과 커밋 해시 또는 PR 번호가 필요합니다. | 입력란과 같은 이름을 사용한다. 전체 길이 요구는 입력 안내에 유지한다. [ConnectionDiagnostics.kt:64](../../src/main/kotlin/io/intenttrace/connection/application/ConnectionDiagnostics.kt#L64) |
| 10 | 내 로컬 연결의 ID·생성 시각·최근 사용·만료 정보를 조회합니다. token은 반환하지 않습니다. | 내 IntentTrace 연결의 ID·생성·최근 사용·만료 시각을 조회합니다. 토큰은 반환하지 않습니다. | 서버에 저장된 본인의 연결 목록이며 현재 컴퓨터의 연결만 조회하는 기능이 아니다. [MySessionTools.kt:13](../../src/main/kotlin/io/intenttrace/identity/adapter/in/mcp/MySessionTools.kt#L13) |
| 11 | 최소 한 개의 판단이 필요합니다. | 구현 결정을 1개 이상 입력하세요. | 입력할 대상과 개수를 화면의 용어로 안내한다. [ChangeRecordFacade.kt:177](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordFacade.kt#L177) |
| 12 | AI가 만든 코드의 요청, 판단, 코드 근거, 검증 결과를 작성자 확인 후 공유하는 플러그인 | AI 코드의 요청·구현 결정·관련 코드·검증 결과를 작성자 확인 후 공유하는 플러그인 | 플러그인 소개에도 바뀐 화면 용어를 적용한다. [plugin.json:4](../../.codex-plugin/plugin.json#L4) |

## 적용 시 확인할 내용

- 1~4번을 먼저 반영한다. 오류 유형·발생 조건과 연결 종료 범위는 그대로 두고 안내를 실제 동작에 맞춘다.
- 10~12번은 같은 파일의 연결 종료 설명·관련 코드 입력 오류·기본 프롬프트에도 동일한 용어를 적용한다. 토큰 미반환, 사용자 종료 요청과 작성자 확인 조건은 유지한다.
- ‘미확인’, ‘불일치’, ‘실패’와 커밋·해시·토큰·세션·Checks 같은 실무 용어는 계속 구분해서 쓴다.

검토 당시에는 현재 문구·파일 위치와 관련 실행 조건을 소스에서 확인했으며 제품 테스트는 재실행하지 않았다. 이후 12개 수정안과 연결된 표현을 반영하고, 관련 테스트와 전체 서버 테스트를 실행했다. 결과는 위의 0.12.3 검증 기록에 정리했다.
