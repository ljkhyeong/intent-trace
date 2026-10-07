# IntentTrace 릴리스 절차

IntentTrace는 서버 실행 JAR과 IntelliJ 설치 ZIP을 같은 버전과 Git 태그로 발행한다. GitHub Marketplace 배포는 이 절차에 포함하지 않는다.

## 1. 릴리스 전 확인

1. `main`의 GitHub Actions `verify`가 성공했는지 확인한다.
2. 실제 IntelliJ IDEA 2025.3 이상에서 설치 ZIP으로 다음 동작을 확인한다.
   - `Tools` 메뉴에 서버 설정, GitHub 승인 시작, 세션 연결, 저장 세션 삭제, 현재 줄 조회, 기록함 열기, 저장소 연결 진단이 표시된다.
   - `Settings > Tools > IntentTrace`에서 주소 적용·취소가 동작한다. 주소를 비우면 환경 변수 또는 기본 주소를 사용한다. 적용한 주소는 재시작 없이 다음 요청에 반영된다.
   - 연결 확인은 설정을 저장하지 않고 인증 정보 없이 서버의 `UP` 상태를 확인한다. 로그인·저장소 권한 확인으로 표시하지 않는다.
   - 서버를 바꿔도 기존 서버의 PasswordSafe·환경 변수 세션이 새 서버로 전송되지 않는다. 기존 주소로 돌아가면 해당 주소의 저장 세션을 사용한다.
   - GitHub 로그인 후 받은 `its_` 세션 토큰만 PasswordSafe에 저장된다.
   - 커밋된 파일의 현재 줄에서 공개 기록을 조회한다.
   - 현재 파일에 커밋되지 않은 변경이 있으면 현재 줄 조회를 중단하고 이유를 안내한다.
   - 터미널에서 checkout·commit한 직후 조회하면 Git 상태를 다시 읽는다. 조회를 시작한 뒤 HEAD나 파일 상태가 바뀌었으면 조회하지 않고 이유를 안내한다.
   - 한 줄의 공개 기록이 20건을 넘으면 일부만 표시한다고 안내한다. 로컬 테스트 데이터로 확인해도 된다.
   - 현재 줄 결과의 `이전 커밋에서 이 줄 찾기`가 일치 방식·원본 커밋·조회한 커밋의 줄을 보여 주고, 중단되면 `중단 위치부터 계속 조회`로 결과를 합친다.
   - `IntentTrace 저장소 연결 진단`이 현재 파일 저장소의 로그인·읽기·쓰기 권한과 HEAD 코드 읽기 결과를 표시하며 게시나 테스트를 실행하지 않는다.
   - `IntentTrace 저장 세션 삭제`는 서버 폐기 후 PasswordSafe 세션을 지운다. 서버가 응답하지 않으면 사유와 함께 `이 PC에서만 삭제`를 묻고, 선택하면 서버 연결이 남는다고 안내한다.
   - 기록함에서 팀 공개 기록과 내 비공개 기록, 상태, 현재 파일 필터가 적용되고 필터 선택 팝업이 열린다. 커밋이 없는 초안은 원래 커밋·당시 코드 이동 버튼이 비활성화된다.
   - 다음·이전 페이지, 빈 결과, 기록 상세와 대체 기록 이동이 동작한다.
   - 목록 조회 실패 시 마지막 성공 필터와 기존 목록·선택·페이지를 유지하고, 재조회 성공 시 새 결과를 적용한다.
   - 원래 커밋·당시 코드 링크는 기록의 전체 커밋과 줄 범위를 가리키고, 과거 검증은 현재 코드 검증으로 표시하지 않는다.
   - 현재 줄 조회 결과가 없을 때 파일 이력을 열 수 있고, 수정 중인 파일에서도 별도 파일 이력은 조회할 수 있다.
   - 로컬 테스트 데이터로 화면만 확인했다면 GitHub 로그인·서버 권한 검증과 구분해 결과를 기록한다.
3. `CHANGELOG.md`의 미출시 항목을 릴리스 버전과 날짜로 옮긴다.
4. 다음 파일의 버전을 `0.7.0`처럼 동일한 정식 버전으로 바꾼다.
   - `build.gradle.kts`
   - `src/main/resources/application.properties`
   - `.codex-plugin/plugin.json`
   - `intellij-plugin/gradle.properties`

## 2. 로컬 검증

Java 21, Node.js 22 이상, Python 3.11 이상을 준비한다. 서버 테스트가 Zed 연결 도구를 사용하므로 새 체크아웃에서는 먼저 의존성을 설치한다. 이후에는 의존성 파일이 바뀌었거나 설치 폴더가 없을 때만 다시 설치한다.

```bash
npm ci --prefix clients/zed --ignore-scripts
```

```bash
./gradlew test bootJar
./gradlew -p intellij-plugin test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
python3 scripts/test_validate_release_version.py
python3 scripts/validate-release-version.py
scripts/validate-plugin.sh
```

정식 버전에서는 태그 발행 때와 같은 조건으로 배포 파일을 미리 확인할 수 있다.

```bash
python3 scripts/validate-release-version.py --release-tag v0.7.0
```

`build/release`에 준비되는 파일은 다음 네 개다.

- `intent-trace-0.7.0.jar`
- `intent-trace-0.7.0.jar.sha256`
- `intent-trace-intellij-0.7.0.zip`
- `intent-trace-intellij-0.7.0.zip.sha256`

## 3. 발행

정식 버전 변경 PR을 병합한 뒤 해당 병합 커밋에 주석 태그(annotated tag)를 만들고 푸시한다.

```bash
git switch main
git pull --ff-only
git tag -a v0.7.0 -m "릴리스: v0.7.0"
git push origin v0.7.0
```

`.github/workflows/release.yml`은 태그 커밋에서 일반 CI와 같은 `verify.yml` 검증을 먼저 실행한다. 검증이 통과하면 실행 JAR과 IntelliJ 설치 ZIP을 만들고, 태그와 프로젝트 버전이 정확히 일치할 때만 네 개의 파일을 GitHub Release에 첨부한다. 개발용 `-SNAPSHOT` 버전이나 다른 버전의 태그는 발행하지 않는다. 쓰기 권한은 발행 job에만 준다.

## 4. 발행 후 확인

1. GitHub Release가 사전 릴리스(prerelease)나 초안(draft)이 아닌 정식 릴리스인지 확인한다.
2. JAR과 IntelliJ ZIP을 새 디렉터리에 내려받는다.
3. 두 SHA-256 파일로 내려받은 파일을 검증한다.
4. 내려받은 ZIP을 IntelliJ에 다시 설치해 플러그인 버전을 확인한다.
5. 위의 버전 파일 네 개를 다음 개발 버전으로 함께 올린다.

이미 존재하는 태그나 릴리스를 같은 버전으로 덮어쓰지 않는다. 실패 원인을 수정한 새 커밋에는 새 패치 버전을 사용한다.
