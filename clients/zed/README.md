# IntentTrace Zed 연결 도구

IntentTrace 서버의 변경 기록을 Zed Agent에서 사용하는 MCP 연결 도구다. Node.js 22 이상이 필요하다. `launch`에는 Python 3와 Zed CLI도 필요하다.

## 배포 파일 설치

받은 버전의 `.tgz` 파일을 전용 설치 폴더에 설치한다. 필요한 Node 의존성은 패키지에 포함한다.

```bash
npm install --prefix ~/.local/share/intent-trace --ignore-scripts --offline /배포파일/intent-trace-zed-0.12.2.tgz
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed configure
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed configure --apply
```

팀 서버는 `configure` 뒤에 HTTPS `/mcp` 주소를 넣는다. 설정에는 token을 저장하지 않는다. `INTENT_TRACE_MCP_URL` 환경 변수로 서버 주소를 정할 수도 있다. 명령에 주소를 쓰면 그 값이 우선한다.

IntentTrace 로그인 화면에서 `its_` 세션을 받은 뒤 Zed를 완전히 종료하고 실행한다.

```bash
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed launch .
```

세션 토큰은 화면에 표시하지 않고 Zed 실행 환경에만 전달한다. 입력을 숨길 수 없거나 입력을 취소·종료하면 Zed를 실행하지 않는다. 이 경우 터미널에서 실행하거나 `INTENT_TRACE_SESSION_TOKEN` 환경 변수로 세션을 미리 전달한다. 환경 변수로 세션을 전달했다면 `check [MCP 주소] [저장소]`로 연결을 확인할 수 있다. `serve`는 MCP 통신에 사용한다.

## 업데이트와 제거

새 버전의 배포 파일을 같은 설치 폴더에 설치하고 `configure` 미리보기·`--apply`를 다시 실행한다. Zed에서 연결을 다시 시작한다. 설치 폴더를 옮기거나 Node 경로가 바뀌었을 때도 다시 등록한다. 이전 버전 파일로 같은 절차를 수행하면 연결 도구만 되돌릴 수 있다.

제거할 때 Zed 설정의 `context_servers.intent-trace` 항목을 삭제하고 다음 명령을 실행한다.

```bash
npm uninstall --prefix ~/.local/share/intent-trace --ignore-scripts intent-trace-zed
```

공개 배포용으로 이름을 바꾼 패키지는 해당 이름으로 제거한다. 서버의 내 연결 화면에서 사용하지 않는 세션도 종료할 수 있다.

## 지원 범위

- macOS·Linux: 위 설치·실행 명령을 사용한다. Linux 검증은 저장소 CI에서 수행한다.
- Windows: Node로 연결 도구를 실행하고 `--settings`로 Zed 설정을 지정할 수 있다. `launch`는 PATH의 `python`을 사용한다. Windows 실제 Zed 앱 실행은 확인하지 않았다.
- Agent의 MCP 도구 연결을 지원한다. 편집기 인라인 메뉴와 자동 기록 수집은 제공하지 않는다.
- 서버 재시작·세션 만료 후에는 다시 로그인한다. 레지스트리의 비밀 입력 항목을 등록해도 IntentTrace의 OAuth가 자동 연동되지는 않는다.
