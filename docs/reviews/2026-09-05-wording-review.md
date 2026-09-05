# IntentTrace 문구 검토

검토일: 2026-09-05

기준 커밋: `8401a11691e2790b3a384aaf10b24d2abd05dc75`

상태: 수정안 40개를 0.12.2에 반영했다. [적용 내용과 검증 결과](2026-09-05-v0.12.2-verification.md)를 참고한다. 아래 표의 현재 문구와 줄 번호는 검토 당시 기준을 유지한다.

화면·오류 메시지·MCP 도구 설명·README·HANDOFF·PRD/ADR·Zed 안내·개발 스킬의 한국어 문구를 확인했다. 추상적인 말, 불필요한 설명, 확인한 사실보다 강하게 말하는 표현을 우선 정리했다. 반복되는 표현은 대표 위치만 적었다.

표의 N·M은 화면에 표시하는 건수다. 줄 번호는 기준 커밋의 위치이며, 코드의 HTML 태그는 읽기 쉬운 형태로 표시했다.

## 사실과 다르게 읽힐 수 있는 문구

| 번호 | 현재 문구 | 권장 문구 | 위치 |
|---|---|---|---|
| 1 | 실행한 검증이 없습니다. | 등록된 검증 결과가 없습니다. | [ChangeRecordMarkdownRenderer.kt:62](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordMarkdownRenderer.kt#L62) |
| 2 | 오래된 스냅샷 | 다른 스냅샷의 결과 | [ChangeRecordMarkdownRenderer.kt:66](../../src/main/kotlin/io/intenttrace/record/application/ChangeRecordMarkdownRenderer.kt#L66) |
| 3 | 이전 커밋의 기록 | PR 최신 커밋과 다름 | [RecordBrowserSections.kt:36](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserSections.kt#L36) |
| 4 | IntentTrace 서버의 외부 연동에 장애가 있습니다. | IntentTrace 서버 오류가 발생했습니다. | [errors.mjs:6](../../clients/zed/errors.mjs#L6) |

- 1번: 결과가 없다는 사실만 확인했으므로 미실행으로 단정하지 않는다.
- 2번: 해시 불일치만으로 어느 쪽이 오래됐는지 알 수 없다.
- 3번: 커밋 불일치를 확인한 것이며 선후 관계를 확인한 것은 아니다.
- 4번: 5xx 응답만으로 외부 연동 장애라고 단정하지 않는다.

## 화면 제목과 용어

| 번호 | 현재 문구 | 권장 문구 | 위치 |
|---|---|---|---|
| 5 | 코드에 남은 선택을 다시 읽는 곳. | 코드 변경 이유와 검증 결과를 확인하세요. | [RecordBrowserPage.kt:27](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L27) |
| 6 | 판단과 근거 | 구현 결정과 이유 | [RecordBrowserPage.kt:79](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L79) |
| 7 | 코드 근거 | 관련 코드 | [RecordBrowserPage.kt:85](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L85) |
| 8 | 전체 커밋 | 커밋 해시(전체 길이) | [RecordBrowserEvidence.kt:17](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L17) |
| 9 | 스냅샷 | 스냅샷 해시 | [RecordBrowserPage.kt:115](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L115) |
| 10 | 원본과 후속 기록 비교 | 원본과 새 기록 비교 | [RecordBrowserSections.kt:69](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserSections.kt#L69) |
| 11 | 후보 {N}건 살펴봄 · 일치 또는 관련 결과 {M}건 | 조회한 기록 {N}건 · 관련 결과 {M}건 | [RecordBrowserEvidence.kt:23](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L23) |
| 12 | 관련 후보 · 현재 코드 일치 미확인 | 관련 기록 · 코드 일치 미확인 | [RecordBrowserEvidence.kt:40](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L40) |
| 13 | 중복 항목 · 대응 불명확 | 중복 항목 · 비교 대상 불명확 | [RecordBrowserSections.kt:85](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserSections.kt#L85) |
| 14 | 게시 자격 증명 설정 | GitHub 게시 인증 설정 | [RecordBrowserSections.kt:56](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserSections.kt#L56) |

- 6번: 무엇에 관한 판단인지 밝힌다. 사용자 요청·AI 추론 등의 출처 표시는 유지한다.
- 8번: 짧은 해시가 아닌 40자 또는 64자 입력이라는 의미를 유지한다.
- 11번: N은 이번 요청에서 살펴본 기록 수다. 모든 코드 확인을 완료했다는 뜻으로 쓰지 않는다.

## 화면 안내

| 번호 | 현재 문구 | 권장 문구 | 위치 |
|---|---|---|---|
| 15 | 이번 요청은 근거 하나도 끝까지 확인하지 못했습니다. 같은 조건으로 반복하면 같은 위치에서 멈출 수 있습니다. 서버 관리자에게 조회 제한과 GitHub 응답 지연을 확인해 달라고 요청한 뒤 다시 확인해 주세요. | 코드 확인을 완료하지 못했습니다. 반복 조회 전에 관리자에게 조회 제한과 GitHub 지연을 확인해 달라고 요청하세요. | [RecordBrowserEvidence.kt:33](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L33) |
| 16 | 중단한 근거부터 계속 | 중단 위치부터 계속 조회 | [RecordBrowserEvidence.kt:60](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L60) |
| 17 | 조회 취소가 전달되어 추가 확인을 중단했습니다. | 취소 요청으로 조회를 중단했습니다. | [RecordBrowserEvidence.kt:30](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L30) |
| 18 | 지원하지 않는 객체 | 지원하지 않는 Git 객체 | [RecordBrowserEvidence.kt:77](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L77) |
| 19 | 후속 기록에 제출된 검증이 없습니다. 원본의 검증은 후속 기록의 검증으로 이어지지 않습니다. | 새 기록에 등록된 검증 결과가 없습니다. 원본의 검증 결과는 복사하지 않습니다. | [RecordBrowserSections.kt:71](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserSections.kt#L71) |
| 20 | 서버가 테스트 실행 자체를 확인한 결과는 아닙니다. | 서버는 테스트 실행 여부를 확인하지 않습니다. | [RecordBrowserPage.kt:108](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserPage.kt#L108) |
| 21 | 이력 수집 시작 전 작업은 확인할 수 없습니다. 과거 작업을 추정해서 채우지 않습니다. | 이력 수집 전 작업은 저장되어 있지 않습니다. | [RecordBrowserManagement.kt:23](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserManagement.kt#L23) |
| 22 | 확인하지 못한 후보가 있습니다. 아래 사유와 재조회를 확인해 주세요. | 확인하지 못한 기록이 있습니다. 사유를 확인한 뒤 다시 조회하세요. | [RecordBrowserEvidence.kt:25](../../src/main/kotlin/io/intenttrace/record/adapter/in/browser/RecordBrowserEvidence.kt#L25) |

- 18번: 대상을 명시한다. 모든 객체를 파일이라고 바꾸면 의미가 달라진다.
- 21번: 화면 사용자가 알아야 할 제한만 남긴다. 추정 금지 원칙은 개발 문서에 유지한다.

## Zed·로그인·오류 안내

| 번호 | 현재 문구 | 권장 문구 | 위치 |
|---|---|---|---|
| 23 | INTENT_TRACE_SESSION_TOKEN에 로그인 화면에서 받은 its_ 세션을 환경 변수로 전달하세요. | INTENT_TRACE_SESSION_TOKEN 환경 변수에 로그인 화면의 its_ 세션 토큰을 설정하세요. | [intent-trace.mjs:25](../../clients/zed/intent-trace.mjs#L25) |
| 24 | IntentTrace its_ 세션:  | IntentTrace 세션 토큰(its_):  | [zed-with-intent-trace.py:22](../../scripts/zed-with-intent-trace.py#L22) |
| 25 | 숨긴 입력으로 받은 세션은 Zed 실행 환경에만 전달한다. | 세션 토큰은 화면에 표시하지 않고 Zed 실행 환경에만 전달한다. | [README.md:23](../../clients/zed/README.md#L23) |
| 26 | IntentTrace 인증이 만료되었거나 거부됐습니다. 다시 로그인한 세션으로 연결하세요. | IntentTrace 인증에 실패했습니다. 다시 로그인해 받은 세션 토큰으로 연결하세요. | [errors.mjs:3](../../clients/zed/errors.mjs#L3) |
| 27 | 변경 요청은 기록 또는 게시 상태를 먼저 조회하세요. | 변경 요청을 다시 보내기 전에 기록·게시 상태를 확인하세요. | [errors.mjs:1](../../clients/zed/errors.mjs#L1) |
| 28 | 설치한 연결 도구를 다시 설치하거나 저장소에서 고정 의존성을 설치해 주세요. | 연결 도구를 다시 설치하세요. 소스 실행 시 clients/zed에서 npm ci를 실행하세요. | [intent-trace.mjs:85](../../clients/zed/intent-trace.mjs#L85) |
| 29 | IntentTrace를 재시작하면 이 session은 사라지며 다시 연결해야 합니다. | IntentTrace 서버를 재시작하면 다시 로그인해야 합니다. | [GitHubOAuthController.kt:144](../../src/main/kotlin/io/intenttrace/identity/adapter/in/web/GitHubOAuthController.kt#L144) |
| 30 | 고정 token을 사용 중입니다. 이 점검은 App 키로 발급하는 자격 증명만 원격 확인합니다. | 고정 토큰은 이 점검에서 확인하지 않습니다. GitHub App 키로 발급한 토큰만 검증합니다. | [PublicationPreflight.kt:31](../../src/main/kotlin/io/intenttrace/publication/application/PublicationPreflight.kt#L31) |

## README·개발 문서·작업 보고

| 번호 | 현재 문구 | 권장 문구 | 위치 |
|---|---|---|---|
| 31 | IntentTrace는 AI가 만든 코드에 **어떤 요청과 판단이 반영됐고, 어느 코드와 커밋에 연결되며, 무엇으로 검증했는지**를 남기는 Kotlin/Spring 프로젝트입니다. | IntentTrace는 AI 코드의 변경 이유, 관련 커밋·코드, 검증 결과를 기록합니다. | [README.md:3](../../README.md#L3) |
| 32 | 전체 Git 커밋 ID와 SHA-256 저장소 스냅샷 결박 | 기록을 커밋 해시와 스냅샷 해시에 연결 | [README.md:14](../../README.md#L14) |
| 33 | 변경 의도 초안 생성과 최초 내용 해시 기반 멱등 처리 | 초안 생성과 중복 요청 처리 | [README.md:7](../../README.md#L7) |
| 34 | GitHub 웹 승인과 메모리 전용 `its_` 세션·user token 자동 갱신 | GitHub 로그인·세션 발급·사용자 토큰 자동 갱신(메모리 보관) | [README.md:29](../../README.md#L29) |
| 35 | `DRAFT → AUTHOR_CONFIRMED → PUBLISHED → SUPERSEDED` 수명주기 | 기록 상태: 초안 → 작성자 확인 → 팀 공개 → 새 기록으로 대체 | [README.md:13](../../README.md#L13) |
| 36 | 과거 기록 후보별 지원 불가 사유·완전 여부·실패 ID 재조회 | 이전 기록별 확인 불가 사유·처리 완료 여부·실패한 기록 재조회 | [HANDOFF.md:30](../../HANDOFF.md#L30) |
| 37 | 확인할 불변식 | 반드시 지킬 규칙 | [HANDOFF.md:58](../../HANDOFF.md#L58) |
| 38 | 0.10.0 조회 실패 경계 | 0.10.0 조회 오류 처리 | [ADR-0007-evidence-check-and-history.md:26](../ADR-0007-evidence-check-and-history.md#L26) |
| 39 | 검증 결과를 현재 검증으로 승격하지 않는다. | 이전 검증 결과를 현재 커밋의 검증으로 사용하지 않는다. | [ADR-0007-evidence-check-and-history.md:15](../ADR-0007-evidence-check-and-history.md#L15) |
| 40 | 진행하지 못하는 재개 | 조회 중단 시 재시도 안내 | [2026-09-05-v0.12.1-verification.md:15](2026-09-05-v0.12.1-verification.md#L15) |

- 32번: 결박을 실제 연결 관계로 바꾼다. 전체 길이·SHA-256 요구는 입력 명세에 유지한다.
- 33번: 기능 목록의 제목을 줄인다. 같은 ID·다른 내용의 충돌 규칙은 API 명세에 유지한다.
- 35번: 소개에서는 한국어 상태를 보여준다. enum 값은 API 문서에 유지한다.

## 그대로 유지할 표현

- `커밋`, `해시`, `토큰`, `세션`, `트랜잭션`, `멱등성`, `원자성`, `Git 객체`는 실무 용어다. 기술 문서에서 무조건 풀어 쓰지 않는다. 화면에서는 필요한 대상과 동작을 함께 적는다.
- `미확인`, `불일치`, `실패`는 구분한다. `미확인`을 실패로 바꾸거나, 해시 일치를 테스트 통과로 바꾸지 않는다.
- `원본 기록`과 `새 기록`은 별도 기록이다. 새 기록을 원본의 수정본이라고 단정하지 않는다.
- `폐기`는 물리 삭제가 아니다. `폐기됨`을 `삭제됨`으로 바꾸지 않는다.
- REST·MCP 이름, JSON 필드, enum, 오류 코드, 설정 키와 명령은 이번 문구 수정 대상에 포함하지 않는다.

## 적용 순서

1. 사실을 다르게 전달하는 1~4번을 먼저 바꾼다.
2. 화면·오류·Zed 안내를 정리하고 같은 표현이 있는 Markdown 출력과 MCP 설명도 맞춘다.
3. README·사용 안내·인수인계와 개발 문서를 정리한다. 과거 검증 기록의 사실·수치와 기존 Git 커밋 메시지는 바꾸지 않는다.

문구가 오류 전달 조건이나 테스트 기대값에 쓰이는 곳은 함께 확인해야 한다. 제품 동작·권한·검증 상태를 바꾸는 작업과 섞지 않는다.

## 검토 당시 확인한 내용

현재 문구 40개가 실제 파일에 있는지, 표시한 줄 번호가 맞는지, 문서의 로컬 링크가 연결되는지 확인했다. 이번 변경은 검토 문서와 인수인계 링크뿐이므로 제품 테스트는 실행하지 않았다.

검토 이후 40개 수정안과 반복되는 표현을 적용했다. 제품 테스트와 모바일 화면 확인 결과는 위의 0.12.2 검증 기록에 정리했다.
