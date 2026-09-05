# Zed에서 IntentTrace 사용하기

확인일: 2026-09-05

## 지원 범위

Zed Agent에서 IntentTrace의 MCP 도구를 사용할 수 있다. 기록 생성·검색·확인·공개 등은 같은 서버의 `/mcp`에 연결한다. 이 저장소의 `.codex-plugin`, Codex 훅과 설정 파일을 Zed 확장으로 직접 설치하는 방식은 아니다.

[Zed 공식 MCP 문서](https://zed.dev/docs/ai/mcp)는 원격 서버와 로컬 stdio 서버를 모두 지원한다. [MCP 확장 문서](https://zed.dev/docs/extensions/mcp-extensions)는 MCP 서버 확장을 공식 MCP registry 방식으로 전환할 예정이라고 안내하므로, 전용 Rust 확장 패키지보다는 MCP 연결을 사용한다.

현재 코드 줄에 표시하는 메뉴·인라인 설명 같은 편집기 전용 UI는 제공하지 않는다. Agent에 저장소·전체 커밋·상대 경로·줄을 전달해 기존 조회 도구를 호출하는 범위다.

## 환경 변수로 연결

Zed의 원격 서버 예시는 `headers.Authorization`에 토큰을 직접 지정한다. 이 프로젝트에서는 세션 토큰을 설정 파일에 저장하지 않으므로 환경 변수 치환을 지원하는 `mcp-remote`를 로컬 stdio 연결 도구로 사용한다.

1. IntentTrace를 실행하고 브라우저에서 `/auth/github/start`를 열어 CLI용 `its_` 세션을 발급받는다. `/records` 로그인 cookie는 MCP용이 아니다.
2. `INTENT_TRACE_SESSION_TOKEN`을 터미널 환경에 넣고 그 환경을 상속하도록 Zed를 실행한다. 토큰을 shell history·설정 파일에 적지 않으려면 아래와 같이 숨긴 입력을 사용한다. Zed가 이미 실행 중이면 완전히 종료한 뒤 시작한다.

```bash
printf 'IntentTrace session token: '
stty -echo
IFS= read -r INTENT_TRACE_SESSION_TOKEN
stty echo
printf '\n'
export INTENT_TRACE_SESSION_TOKEN
zed .
unset INTENT_TRACE_SESSION_TOKEN
```

3. Zed의 사용자 설정에 아래 연결을 추가한다. Node.js와 `npx`가 필요하다. 실제 토큰을 `args`, `headers`, `env`에 쓰지 않는다. 아래 `${INTENT_TRACE_SESSION_TOKEN}`은 치환할 변수 이름 그대로 유지한다.

```json
{
  "context_servers": {
    "intent-trace": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote@0.8.3",
        "http://127.0.0.1:8080/mcp",
        "--allow-http",
        "--transport", "http-only",
        "--header", "Authorization:Bearer ${INTENT_TRACE_SESSION_TOKEN}",
        "--silent"
      ],
      "env": {}
    }
  }
}
```

팀 서버는 URL을 실제 HTTPS `/mcp`로 바꾸고 `--allow-http`를 제거한다. `--debug`를 켜거나 인증 헤더를 파일로 저장하지 않는다. `mcp-remote`는 별도 오픈소스 연결 도구이며 이 저장소에 포함된 서버 구성요소가 아니다.

4. Zed의 Settings → AI → MCP Servers에서 서버가 활성화되는지 확인한다. Agent에 다음처럼 요청한다.

> IntentTrace에서 acme/project 저장소의 팀 공개 기록 중 “캐시”가 포함된 기록을 찾아줘.

기록 공개·GitHub 게시·세션 폐기처럼 변경을 일으키는 도구는 사용자가 요청한 범위에서 실행한다. 도구 사용 규칙은 이 저장소의 [IntentTrace 사용 스킬](../../skills/intent-trace/SKILL.md)을 기준으로 필요한 내용을 Zed의 지침에 반영한다. Codex 세션 시작 훅은 Zed에서 자동 실행되지 않는다.

## 인증 오류

- 서버를 재시작했거나 세션을 폐기했다면 `/auth/github/start`에서 새 `its_`를 발급받고 Zed를 새 환경으로 다시 실행한다.
- GitHub access·refresh token과 브라우저용 `itb_`를 Zed에 전달하지 않는다.
- IntentTrace의 GitHub 웹 승인은 MCP 표준 OAuth discovery와 다르다. Zed 원격 서버에 URL만 입력해서 자동 로그인되는 계약은 아직 제공하지 않는다.

## 검증 범위와 출처

IntentTrace 서버의 인증된 MCP 초기화·도구 목록·호출은 통합 테스트로 확인한다. 이 설정을 실제 Zed 앱과 사용자 GitHub 계정으로 연결하는 검증은 별도로 필요하다. 사용자 Zed 설정이나 확장 설치 상태는 이 작업에서 변경하지 않았다.

- [Zed MCP 설정과 도구](https://zed.dev/docs/ai/mcp)
- [Zed 환경 변수 상속](https://zed.dev/docs/environment)
- [mcp-remote 환경 변수 헤더와 전송 방식](https://github.com/punkpeye/mcp-remote)

`mcp-remote@0.8.3`은 위 확인일에 npm registry에서 확인한 배포 버전이다.
