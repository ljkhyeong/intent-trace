---
name: intent-trace
description: IntentTrace MCP로 변경 의도 기록을 생성·조회·수정·공개하거나, 코드 줄의 근거와 PR 기록을 찾고 연결·세션을 관리할 때 사용한다. 일반 코드 설명이나 리뷰만 요청한 경우에는 기록 생성을 제안하지 않는다.
allowed-tools:
  - Bash(git rev-parse *)
  - Bash(git status *)
---

# IntentTrace 기록 사용 (Claude Code)

기록 규칙, 초안 절차, 도구 입력은 Codex 플러그인과 같은 [사용 스킬](../../../skills/intent-trace/SKILL.md)을 따른다. 그 파일을 먼저 읽고, 이전 줄 조회·처리 이력·응답 유실이 있을 때만 [이력 조회와 복구](../../../skills/intent-trace/references/history-and-recovery.md)를 읽는다. 이 문서는 Claude Code에서 달라지는 점만 다룬다.

- HEAD: !`git rev-parse HEAD`
- 브랜치와 작업 파일:
!`git status --short --branch`

## 도구와 스크립트

- MCP 도구는 `mcp__intent-trace__<도구 이름>`으로 보인다. 목록에 없으면 ToolSearch에서 `intent-trace`로 찾는다.
- IntentTrace 저장소 루트는 `${CLAUDE_SKILL_DIR}/../../..`다. `scripts/git-evidence.sh`와 `scripts/run-verification.py`는 이 루트 기준 절대 경로로 실행한다.

## 연결

- 프로젝트 `.mcp.json`은 Codex 플러그인 형식이다. Claude Code는 `bearer_token_env_var`를 읽지 않아 인증 헤더 없이 연결한다.
- 같은 이름의 로컬 범위 서버가 프로젝트 설정보다 우선한다. 작은따옴표로 감싸 토큰 대신 환경 변수 참조를 저장한다. 팀 서버는 `intent-trace-team`처럼 다른 이름으로 추가한다.

  ```bash
  claude mcp add --transport http --scope local intent-trace http://127.0.0.1:8080/mcp --header 'Authorization: Bearer ${INTENT_TRACE_SESSION_TOKEN}'
  ```

- `INTENT_TRACE_SESSION_TOKEN`은 `/auth/github/start` 승인 후 받은 `its_` 세션이다. 사용자가 셸에 설정한 뒤 Claude Code를 시작하거나 `/mcp`에서 다시 연결한다. 토큰 값을 대화·명령 인자·설정 파일에 쓰지 않는다.
- 연결 상태는 `claude mcp list`나 `/mcp`로 확인한다. `claude mcp get`은 확장된 `Authorization` 값을 출력하므로 실행하지 않는다.
- 연결된 뒤 인증·권한 문제는 `diagnose_connection`으로 확인한다.

## 승인

도구 실행 허용이나 자동 승인은 작성자 확인이 아니다. `confirm_change_record`는 사용자가 대화에서 현재 기록 내용을 보고 승인한 경우에만 호출한다. 공개·PR 게시·대체·연결 종료도 사용자가 요청한 범위에서만 실행한다.
