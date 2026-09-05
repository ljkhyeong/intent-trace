# Zed 연결 도구 배포 파일 만들기

기준: Zed 연결 도구 0.12.2 · 2026-09-05

## 로컬 설치 파일

Node.js 22 이상, npm과 macOS 또는 Linux에서 실행한다. 생성기가 임시 폴더에서 잠금 파일에 따라 의존성을 설치하므로 npm 레지스트리 또는 필요한 항목이 있는 캐시가 필요하다.

```bash
node scripts/package-zed.mjs
```

`build/zed-release/intent-trace-zed-0.12.2.tgz`와 같은 이름의 `.sha256`, `.build.json` 파일을 만든다. Node 의존성이 포함되어 빈 npm 캐시에서도 오프라인 설치할 수 있다. `--output 폴더`로 생성 위치를 바꿀 수 있다. 이 파일은 로컬 설치용이며 npm 공개 게시가 비활성화되어 있다.

설치·버전 변경·제거는 [패키지 사용 안내](../../clients/zed/README.md)를 따른다. 생성 파일에는 실제 사용자 설정·세션·서버 소스·테스트가 포함되지 않는다. 작업 폴더의 `node_modules`가 오래됐거나 없어도 `package-lock.json`으로 직접·하위 의존성을 준비한다. 선언과 잠금 파일이 다르거나 의존성 설치가 실패하면 생성을 중단한다. 의존성을 바꿀 때는 잠금 파일과 검증을 함께 갱신한다.

`.build.json`에는 패키지 이름·버전, 사용한 잠금 파일과 압축 파일의 SHA-256이 있다. 패키지 안에도 잠금 파일 기준을 담은 `build-info.json`이 들어간다. 이 정보를 보관하면 같은 버전 파일이 어떤 잠금 파일에서 만들어졌는지 비교할 수 있다.

## MCP 레지스트리 제출 자료

Zed는 MCP 확장 플러그인을 공식 레지스트리로 전환할 계획을 안내한다. 현재 사용자 지정 stdio 연결은 계속 사용할 수 있다. [Zed 공식 문서](https://zed.dev/docs/extensions/mcp-extensions)

공개 배포에 사용할 npm 패키지 이름과 GitHub 소유자·저장소를 정한 뒤 생성한다. 아래 `example` 값은 형식 예시다.

```bash
node scripts/package-zed.mjs --output build/zed-publication \
  --npm-name @example/intent-trace-zed \
  --mcp-name io.github.example/intent-trace \
  --repository https://github.com/example/intent-trace
```

출력에는 지정한 이름의 패키지, SHA-256 파일, `server.json`이 들어간다. 패키지의 `mcpName`과 레지스트리 이름이 일치하며 버전을 고정한다. 제출용 패키지는 공개 가능한 설정으로 생성되지만 명령 자체는 외부 게시를 하지 않는다. 생성한 패키지의 라이선스 필드 기본값은 `UNLICENSED`다. 배포 라이선스를 결정하면 생성 스크립트와 패키지 안내를 함께 갱신한다.

레지스트리는 실행 파일 대신 메타데이터를 보관하므로 npm 패키지가 먼저 게시되어 있어야 한다. 실제 소유권 인증·게시 절차는 [공식 등록 안내](https://modelcontextprotocol.io/registry/quickstart)를 따른다. 공식 `mcp-publisher`의 `validate` 기능으로 제출 자료를 확인하고 선택한 계정으로 등록한다. 생성만 완료한 상태를 레지스트리 설치 가능으로 안내하지 않는다.

`server.json`은 `INTENT_TRACE_SESSION_TOKEN`을 필수 비밀 입력으로 선언하고 실제 값은 포함하지 않는다. `INTENT_TRACE_MCP_URL`은 선택 주소이며 기본값은 로컬 `/mcp`다. 설치 도구가 비밀 입력을 파일에 보관한다면 직접 환경 변수를 전달하는 기존 Zed 연결 방식을 사용한다. IntentTrace의 GitHub 로그인은 MCP 표준 OAuth 자동 로그인과 별개다.

## 검증

`npm test --prefix clients/zed`에는 아래 패키지 검증이 포함된다. GitHub Actions의 기존 Node 22·Linux 작업도 같은 테스트를 실행한다.

- 잘못 설치된 직접·하위 의존성을 배제하고 잠금 파일의 버전으로 생성, 선언 불일치 거부
- 배포 파일·잠금 파일 SHA-256 일치와 개발 폴더 미변경
- 저장소 밖의 전용 폴더와 빈 캐시에서 오프라인 설치
- 설치된 실행 명령의 설정 생성·등록과 token 미저장
- 표준 MCP SDK를 통한 로컬 테스트 서버 연결
- 설치 패키지의 Python 실행 도구에 세션·공백이 있는 Zed 인자 전달
- 토큰을 화면에서 숨길 수 없으면 실행 중단, 환경 변수·정상 입력, EOF·사용자 취소 처리
- 예시 계정으로 제출 자료 생성과 비밀 입력 선언

실제 npm 게시, MCP 레지스트리 등록, Windows·Linux의 실제 Zed 앱 실행은 이 테스트의 범위에 포함하지 않는다.
