# Zed 인라인 UI 검토

검토일: 2026-10-05

기준 커밋: `cc05a4b4c1d833be5965be1f86553841cdcdf0e3`

상태: 제안. 구현하지 않았다. Zed 기능은 2026-10-05 공개 문서와 변경 이력으로 확인했고 실제 Zed에서 시제품을 실행하지 않았다.

## 우선순위

| 순서 | 우선순위 | 항목 | 확인 방법 |
| --- | --- | --- | --- |
| 1 | P2 | IntentTrace 언어 서버의 hover로 현재 줄 공개 기록 요약 표시 | Zed 개발용 확장으로 설치한 뒤 커밋된 파일의 줄에서 hover를 열어 기록 요약과 다른 언어 서버의 hover가 함께 보이는지 확인 |
| 2 | 보류 | 코드 렌즈로 기록 있는 줄 표시 | 파일 단위 조회 API가 생긴 뒤 다시 검토 |
| 3 | 보류 | 코드 줄 메뉴·거터 아이콘·전용 패널 | Zed가 확장 UI API를 공개한 뒤 다시 검토 |

## 현재 상태

- Zed에서는 Agent MCP로만 기록을 조회한다. 사용자가 Agent에 저장소·전체 커밋·상대 경로·줄을 전달하고, 코드 줄의 인라인 메뉴는 없다([Zed 사용 안내](../clients/zed.md#L9)).
- 같은 조회를 IntelliJ는 현재 줄 액션으로 제공한다. 조회 전 파일·Git 상태를 다시 읽고 커밋되지 않은 변경이 있으면 조회하지 않는다([CurrentLineContextResolver](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/CurrentLineContextResolver.kt#L52)).

## 확인한 Zed 확장 범위

공개 문서 기준이다. 시제품으로 확인하지 않은 항목은 `미확인`으로 적는다.

- 확장이 제공할 수 있는 것은 언어·언어 서버, 디버거, 테마·아이콘 테마, 스니펫, MCP 서버다. 확장 권한도 `process:exec`, `download_file`, `npm:install` 세 가지뿐이라 편집기 UI를 직접 그릴 수 없다. Zed는 UI 확장을 이후 계획으로 밝혔다([확장 개발](https://zed.dev/docs/extensions), [확장 권한](https://zed.dev/docs/extensions/capabilities)).
- 언어 서버는 `extension.toml`의 `[language_servers.<id>]`에서 `languages`에 언어 이름을 적어 등록한다. 내장 언어나 다른 확장의 언어에도 붙일 수 있다. 모든 언어를 한 번에 지정하는 방법은 문서에 없어 대상 언어를 나열해야 한다([언어 확장](https://zed.dev/docs/extensions/languages)).
- Zed는 hover와 code action을 주 언어 서버뿐 아니라 연결된 모든 언어 서버에 요청한다(변경 이력 `9aad30a`). 공식 설정 문서에는 이 동작이 없어, 기존 언어 서버의 hover와 함께 보이는지는 미확인이다.
- 코드 렌즈는 `"code_lens": "on"`으로 켜야 표시되며 기본값은 꺼짐이다.
- 언어 서버가 브라우저를 여는 `window/showDocument`(`external`)를 Zed가 지원하지 않는다는 안내가 있다([gopls의 Zed 안내](https://go.dev/gopls/editor/zed)). 그래서 code action으로 웹 기록 화면을 여는 방식은 쓰지 않는다.

## 1. 언어 서버 hover (P2)

### 최소 변경

- Zed 확장(Rust → WASM)을 추가한다. 이 확장은 `language_server_command`에서 기존 [Zed 연결 도구](../../clients/zed/intent-trace.mjs)의 새 `lsp` 모드를 실행한다. 서버 주소와 `its_` 세션은 MCP 연결과 같은 `INTENT_TRACE_MCP_URL`·`INTENT_TRACE_SESSION_TOKEN`에서 읽는다.
- 언어 서버는 `textDocument/hover`만 지원한다. 작업 폴더에서 `git rev-parse HEAD`, origin의 `owner/repository`, 저장소 상대 경로를 구한다. origin 해석은 IntelliJ의 [GitHubRemoteParser](../../intellij-plugin/src/main/kotlin/io/intenttrace/intellij/GitHubRemoteParser.kt#L8)와 같은 규칙을 쓴다.
- 파일이 편집 중(`didChange` 이후 저장 전)이거나 Git에 커밋되지 않은 변경이 있으면 서버를 호출하지 않는다. hover에는 커밋된 줄만 조회한다고 안내한다. IntelliJ와 같은 규칙이다.
- 조회는 REST `GET /api/v1/change-records/lookup`([ChangeRecordController](../../src/main/kotlin/io/intenttrace/record/adapter/in/web/ChangeRecordController.kt#L90))이다. 응답 `{items, truncated}`를 hover Markdown으로 바꾼다. 기록마다 제목·상태·`@login`·구현 결정 요약을 쓰고 `/records/{UUID}` 링크를 붙인다. 표시는 최대 3건이다. 더 있거나 `truncated=true`이면 웹 파일 이력 링크로 안내한다.
- 오류는 기존 [errors.mjs](../../clients/zed/errors.mjs)의 분류(인증·권한·호출 제한·서버 장애)를 쓴다. 토큰과 응답 원문은 hover·로그에 넣지 않는다.

### 위험과 확인할 점

- 서버는 REST 요청마다 GitHub `/user`와 저장소 권한을 조회한다([ADR-0004](../ADR-0004-github-user-repository-authorization.md#L14), [세션 확인](../../src/main/kotlin/io/intenttrace/identity/application/InMemoryGitHubUserSessionStore.kt#L56)). hover를 열 때마다 GitHub 호출이 늘어나는 셈이다. 언어 서버에서 같은 커밋·경로·줄의 결과를 짧게 캐시하고, 호출 제한(429)을 받으면 대기 시간 동안 다시 호출하지 않는다.
- JSON-RPC 처리를 직접 만들지 않고 `vscode-languageserver` 패키지를 쓰면 Zed 연결 도구에 의존성이 하나 늘어난다. 현재 의존성은 `@modelcontextprotocol/sdk`와 `jsonc-parser`뿐이다([package.json](../../clients/zed/package.json#L12)).
- Rust 확장을 공개하려면 Zed 확장 저장소에 등록해야 한다. 등록 전에는 개발용 확장으로 설치한다. 현재 연결 도구도 공개 레지스트리에 게시하지 않은 상태다([배포 안내](../clients/zed-distribution.md)).
- 대상 언어를 나열해야 하므로 처음에는 저장소에서 쓰는 언어(Kotlin·JavaScript·Python·Markdown 등)부터 지정한다.
- 미확인: hover Markdown의 링크를 Zed에서 클릭할 수 있는지, 여러 언어 서버의 hover가 실제로 함께 표시되는지.

### 완료 기준

- 개발용 확장을 설치하면 커밋된 파일에서 hover를 열 때 공개 기록 요약이 기존 hover와 함께 보인다.
- 편집 중인 파일에서는 서버를 호출하지 않고 안내만 표시한다.
- 401·403·429·5xx를 각각 안내하고, 토큰과 응답 원문이 hover·로그에 남지 않는다.
- Node 단위 테스트로 Git 문맥 계산, 미커밋 판정, Markdown 변환, 캐시와 호출 제한 대기를 확인한다.

## 2. 보류한 방식

- **코드 렌즈:** LSP의 `textDocument/codeLens`는 문서 전체의 렌즈를 한 번에 요청한다. 현재 서버는 줄 단위 조회만 제공하므로, 렌즈를 만들려면 줄마다 조회하고 그때마다 GitHub 권한 조회가 반복된다. 파일·커밋 단위로 기록과 줄 범위를 한 번에 돌려주는 조회가 생기면 다시 검토한다.
- **code action으로 웹 화면 열기:** `window/showDocument`의 `external`을 지원하지 않아 보류한다.
- **줄 메뉴·거터 아이콘·패널:** 확장 UI API가 없어 지금은 구현할 수 없다.

## 결정이 필요한 것

1. Rust 확장 추가와 Zed 확장 저장소 등록을 감수하고 hover를 구현할지, Agent MCP 조회만 유지할지 정한다.
2. 구현한다면 `vscode-languageserver` 의존성 추가를 허용할지 정한다.
