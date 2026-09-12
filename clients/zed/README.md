# IntentTrace Zed 연결 도구

IntentTrace 서버의 변경 기록을 Zed Agent에서 사용하는 MCP 연결 도구다. Node.js 22 이상이 필요하다. `launch`에는 Python 3와 Zed CLI도 필요하다.

## 배포 파일 설치

받은 버전의 `.tgz` 파일을 전용 설치 폴더에 설치한다. 필요한 Node 의존성은 패키지에 포함한다.

```bash
npm install --prefix ~/.local/share/intent-trace --ignore-scripts --offline /배포파일/intent-trace-zed-0.12.2.tgz
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed configure
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed configure --apply
```

팀 서버는 미리보기와 `--apply` 명령 모두 `configure` 뒤에 같은 HTTPS `/mcp` 주소를 넣는다. 미리보기는 주소를 저장하지 않는다. 두 명령을 실행하기 전에 `INTENT_TRACE_MCP_URL` 환경 변수로 주소를 지정해도 된다. 명령 인자의 주소가 환경 변수보다 우선하며, 둘 다 없으면 로컬 서버를 사용한다. 설정에는 token을 저장하지 않는다.

IntentTrace 로그인 화면에서 `its_` 세션을 받은 뒤 Zed를 완전히 종료하고 실행한다.

```bash
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed launch .
```

세션 토큰은 화면에 표시하지 않고 Zed 실행 환경에만 전달한다. 입력을 숨길 수 없거나 입력을 취소·종료하면 Zed를 실행하지 않는다. 이 경우 터미널에서 실행하거나 `INTENT_TRACE_SESSION_TOKEN` 환경 변수로 세션을 미리 전달한다. 환경 변수로 세션을 전달했다면 `check [MCP 주소] [저장소]`로 연결을 확인할 수 있다. `serve`는 MCP 통신에 사용한다.

`check owner/repo --pr 12`는 PR과 현재 커밋 읽기까지 확인한다. 서버 주소는 `INTENT_TRACE_MCP_URL`, 없으면 로컬 서버를 사용한다. 다른 서버는 `check <MCP 주소> <owner/repo> --pr 12`로 지정한다. `--revision <전체 커밋 해시>`로 다른 커밋을 지정할 수 있다. 두 옵션을 함께 쓰면 지정한 커밋을 확인한다.

실행 명령 뒤에 `--help` 또는 `-h`를 붙이면 전체 사용법을, `check --help`처럼 명령 뒤에 붙이면 해당 명령의 옵션을 보여준다. 도움말에는 세션이나 서버 연결이 필요 없다. 알 수 없는 명령은 종료 코드 1로 실패한다. `launch` 뒤의 인자는 도움말 옵션을 포함해 Zed로 전달한다.

## 업데이트와 제거

새 버전의 배포 파일을 같은 설치 폴더에 설치하고 `configure` 미리보기·`--apply`를 다시 실행한다. Zed에서 연결을 다시 시작한다. 설치 폴더를 옮기거나 Node 경로가 바뀌었을 때도 다시 등록한다. 이전 버전 파일로 같은 절차를 수행하면 연결 도구만 되돌릴 수 있다.

제거할 때 먼저 설정 변경을 미리 확인하고 `--apply`로 저장한 뒤 패키지를 삭제한다.

```bash
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed unconfigure
~/.local/share/intent-trace/node_modules/.bin/intent-trace-zed unconfigure --apply
npm uninstall --prefix ~/.local/share/intent-trace --ignore-scripts intent-trace-zed
```

`unconfigure`는 `context_servers.intent-trace`만 제거하고 다른 연결·주석·파일 권한을 유지한다. 별도 설정 파일은 `--settings`로 지정한다. 연결이 없으면 파일을 바꾸지 않으며, 서버 주소와 세션 없이 실행할 수 있다. 패키지와 서버 세션은 별도로 관리한다. 공개 배포용으로 이름을 바꾼 패키지는 해당 이름으로 제거하고, 사용하지 않는 세션은 서버의 내 연결 화면에서 종료한다.

## 지원 범위

- macOS·Linux: 위 설치·실행 명령을 사용한다. Linux 검증은 저장소 CI에서 수행한다.
- Windows: Node로 연결 도구를 실행하고 `--settings`로 Zed 설정을 지정할 수 있다. `launch`는 PATH의 `python`을 사용한다. Windows 실제 Zed 앱 실행은 확인하지 않았다.
- Agent의 MCP 도구 연결을 지원한다. 편집기 인라인 메뉴와 자동 기록 수집은 제공하지 않는다.
- 서버 재시작·세션 만료 후에는 다시 로그인한다. 레지스트리의 비밀 입력 항목을 등록해도 IntentTrace의 OAuth가 자동 연동되지는 않는다.
