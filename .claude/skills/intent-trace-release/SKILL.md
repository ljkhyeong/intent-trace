---
name: intent-trace-release
description: IntentTrace 서버·IntelliJ 정식 버전 릴리스를 준비하거나 발행 후 다음 개발 버전으로 올릴 때 사용한다. /intent-trace-release <버전>으로만 실행한다.
disable-model-invocation: true
argument-hint: "<버전, 예: 0.13.0>"
---

# IntentTrace 릴리스

대상 버전: `$ARGUMENTS` (`v` 없이 `X.Y.Z`). 비어 있으면 사용자에게 묻는다.

[릴리스 절차](../../../docs/operations/release.md)가 기준이다. 요청받은 단계까지만 진행하고 단계마다 결과를 보고한다. 태그 생성·푸시, GitHub Release 변경, 기존 태그·릴리스 덮어쓰기는 사용자가 그 작업을 요청한 경우에만 한다.

## 1. 정식 버전 준비

1. `git tag -l 'v$ARGUMENTS'`가 비어 있는지 확인한다. 이미 있으면 새 패치 버전을 쓰도록 알리고 멈춘다.
2. `gh run list --workflow verify.yml --branch main --limit 1`로 main의 최신 `verify` 성공을 확인한다. 실패·진행 중이면 멈춘다.
3. 절차 1장의 실제 IntelliJ 확인 항목은 사용자에게 결과를 받는다. 자동 테스트나 로컬 응답 서버 확인으로 대신하지 않는다.
4. CHANGELOG의 `## 미출시` 내용을 `## <버전> - YYYY-MM-DD`로 옮기고, `## 미출시`에는 `### 추가`·`### 변경` 아래 `- 없음`을 남긴다.
5. 다음 네 값을 같은 정식 버전으로 바꾼다. `clients/zed/package.json`은 이 절차의 검사 대상이 아니며 [Zed 배포 안내](../../../docs/clients/zed-distribution.md)를 따른다.
   - `build.gradle.kts`의 `version`
   - `src/main/resources/application.properties`의 `spring.ai.mcp.server.version`
   - `.codex-plugin/plugin.json`의 `version`
   - `intellij-plugin/gradle.properties`의 `pluginVersion`
6. 절차 2장의 로컬 검증을 실행하고 `python3 scripts/validate-release-version.py --release-tag v<버전>`로 `build/release`에 준비되는 첨부 파일 네 개를 확인한다.
7. `릴리스: v<버전> 확정`으로 커밋한다. PR은 사용자가 요청하면 만든다.

## 2. 발행

정식 버전 PR이 병합된 뒤 사용자가 요청하면 `main`의 병합 커밋에 `git tag -a v<버전> -m "릴리스: v<버전>"`을 만들고 푸시한다. 이후 `release` 워크플로가 첨부한 파일 네 개를 확인한다.

## 3. 발행 후

1. Release가 초안·사전 릴리스가 아닌지, 내려받은 JAR·ZIP이 SHA-256 파일과 일치하는지 확인한다. IntelliJ 재설치 확인은 사용자 결과로 받는다.
2. 네 값을 다음 개발 버전 `<다음 버전>-SNAPSHOT`으로 올리고 `릴리스: 다음 개발 버전을 <다음 버전>-SNAPSHOT으로 전환`으로 커밋한다.
3. 실행한 검증은 `intent-trace-docs` 스킬의 구현·검증 기록과 `intent-trace-handoff` 스킬로 남긴다.
