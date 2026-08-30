# IntentTrace 릴리스 절차

IntentTrace는 서버 실행 JAR과 IntelliJ 설치 ZIP을 같은 version과 Git tag로 발행한다. GitHub Marketplace 배포는 이 절차에 포함하지 않는다.

## 1. 릴리스 전 확인

1. `main`의 GitHub Actions `verify`가 성공했는지 확인한다.
2. 실제 IntelliJ IDEA 2025.3 이상에서 설치 ZIP으로 다음 동작을 확인한다.
   - `Tools` 메뉴에 서버 설정, GitHub 승인 시작, 세션 연결, 저장 세션 삭제, 현재 줄 조회, 기록함 열기가 표시된다.
   - `Settings > Tools > IntentTrace`에서 주소 적용·취소와 빈 값의 환경 변수·기본 주소 복원이 동작한다. 적용한 주소는 재시작 없이 다음 요청에 반영된다.
   - 연결 확인은 설정을 저장하지 않고 인증 정보 없이 서버의 `UP` 상태를 확인한다. 로그인·저장소 권한 확인으로 표시하지 않는다.
   - 서버를 바꿔도 기존 서버의 PasswordSafe·환경 변수 세션이 새 서버로 전송되지 않는다. 기존 주소로 돌아가면 해당 주소의 저장 세션을 사용한다.
   - GitHub 승인 후 받은 `its_` session만 PasswordSafe에 저장된다.
   - 커밋된 파일의 현재 줄에서 공개 변경 의도를 조회한다.
   - 커밋되지 않은 파일은 조회하지 않고 이유를 안내한다.
   - 기록함에서 팀 공개 기록과 내 비공개 기록, 상태, 현재 파일 필터가 적용된다.
   - 다음·이전 페이지, 빈 결과, 기록 상세와 대체 기록 이동이 동작한다.
   - 원래 커밋·당시 코드 링크는 기록의 전체 커밋과 줄 범위를 가리키고, 과거 검증은 현재 코드 검증으로 표시하지 않는다.
   - 현재 줄 조회 결과가 없을 때 파일 이력을 열 수 있고, 수정 중인 파일에서도 별도 파일 이력은 조회할 수 있다.
   - 로컬 fixture로 화면만 확인했다면 OAuth·서버 권한 검증과 구분해 결과를 기록한다.
3. `CHANGELOG.md`의 미출시 항목을 릴리스 version과 날짜로 옮긴다.
4. 다음 파일의 version을 `0.7.0`처럼 동일한 정식 version으로 바꾼다.
   - `build.gradle.kts`
   - `src/main/resources/application.properties`
   - `.codex-plugin/plugin.json`
   - `intellij-plugin/gradle.properties`

## 2. 로컬 검증

```bash
./gradlew --no-daemon test bootJar
./gradlew --no-daemon -p intellij-plugin test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
python3 scripts/test_validate_release_version.py
python3 scripts/validate-release-version.py
scripts/validate-plugin.sh
```

정식 version에서는 tag와 같은 조건으로 배포 파일도 미리 확인할 수 있다.

```bash
python3 scripts/validate-release-version.py \
  --release-tag v0.7.0 \
  --prepare-directory build/release
```

준비되는 파일은 다음 네 개다.

- `intent-trace-0.7.0.jar`
- `intent-trace-0.7.0.jar.sha256`
- `intent-trace-intellij-0.7.0.zip`
- `intent-trace-intellij-0.7.0.zip.sha256`

## 3. 발행

정식 version 변경 PR을 병합한 뒤 해당 병합 commit에 annotated tag를 만들고 푸시한다.

```bash
git switch main
git pull --ff-only
git tag -a v0.7.0 -m "릴리스: v0.7.0"
git push origin v0.7.0
```

`.github/workflows/release.yml`은 tag가 프로젝트 version과 정확히 같은지 확인하고 서버·플러그인을 다시 검증한다. 검증에 성공하면 네 개의 파일을 GitHub Release에 첨부한다. 개발용 `-SNAPSHOT` version이나 다른 version의 tag는 발행하지 않는다.

## 4. 발행 후 확인

1. GitHub Release가 prerelease나 draft가 아닌 공개 release인지 확인한다.
2. JAR과 IntelliJ ZIP을 새 디렉터리에 내려받는다.
3. 두 SHA-256 파일로 내려받은 파일을 검증한다.
4. 내려받은 ZIP을 IntelliJ에 다시 설치해 플러그인 version을 확인한다.
5. 다음 개발 version으로 네 version 파일을 함께 올린다.

이미 존재하는 tag나 release를 같은 version으로 덮어쓰지 않는다. 실패 원인을 수정한 새 commit에는 새 patch version을 사용한다.
