---
name: intent-trace-flows
description: IntentTrace 저장소의 서버·IntelliJ·Zed 연동, 배포 설정, 문서, Codex·Claude Code 설정을 수정할 때 사용한다. Codex 개발 스킬에서 기능별 문서와 검증 명령을 고르고 Claude Code 훅과 함께 수정·검증 순서를 지킨다. 기록 생성·조회는 intent-trace 스킬을 따른다.
allowed-tools:
  - Bash(git rev-parse *)
  - Bash(git status *)
---

# IntentTrace 개발 (Claude Code)

기능별 문서, 구현 시 주의할 점, 변경별 검증은 Codex와 같은 [개발 스킬](../../../skills/intent-trace-flows/SKILL.md)을 기준으로 한다. 그 파일을 읽고 변경할 기능의 행만 적용한다. 이 문서는 Claude Code에서 달라지는 점과 함께 맞출 파일만 다룬다.

- HEAD: !`git rev-parse HEAD`
- 브랜치와 작업 파일:
!`git status --short --branch`

작업을 시작하기 전에 불러왔다면 위 HEAD를 시작 커밋으로 기록한다. 작업 전부터 있던 미추적 파일은 수정하거나 stage하지 않는다.

## 훅과 수동 검사

`.claude/settings.json`은 `.codex/config.toml`과 같은 `scripts/feedback.py hook`을 실행한다.

- `UserPromptSubmit`은 시작 커밋을 저장한다. 앞선 종료 검사가 통과하면 다음 요청의 기준을 그때의 HEAD로 바꾼다. 여러 요청에 걸친 작업은 처음 기록한 시작 커밋으로 `finish`를 실행한다.
- `PostToolUse`는 `Edit`·`Write`·`MultiEdit`·`NotebookEdit`·`Bash` 뒤 내용이 바뀐 파일만 지역 검사한다. 통과 문맥을 받은 파일에는 `feedback.py files`를 반복하지 않는다. 실패하면 사유를 고친다.
- `Stop`은 전체 diff와 구조를 검사하고 실패하면 한 번 더 이어서 수정하게 한다. 통과 메시지는 사용자에게만 보이므로 최종 응답 전에 `python3 scripts/feedback.py finish --base <시작 커밋>`을 직접 실행하고 `review.diff`를 끝까지 읽는다.
- 훅 결과가 보이지 않는 세션에서는 AGENTS의 수동 명령을 사용한다.
- 서브에이전트의 도구 호출에도 같은 훅이 실행된다. Gradle 명령은 병렬 호출이나 백그라운드로 겹쳐 실행하지 않는다.

## 함께 맞출 파일

| 변경 | 함께 확인할 파일 |
| --- | --- |
| MCP 도구·REST 계약 | 해당 PRD·ADR, README의 `API`·`MCP 도구`, [사용 스킬](../../../skills/intent-trace/SKILL.md), CHANGELOG `미출시` |
| 새 PRD·ADR, 검토 문서 | `intent-trace-docs` 스킬 |
| 화면·오류·MCP 설명 문구 | [문구 검토](../../../docs/reviews/2026-09-05-wording-review.md)의 `사실과 다르게 읽힐 수 있는 문구`·`그대로 유지할 표현` |
| 훅·MCP 설정 | `.codex/config.toml`과 `.claude/settings.json`, [로컬 검증 절차](../../../docs/development/verification.md), README `Claude Code` |
| 스킬 | 공통 절차는 Codex `skills/`에 두고 `.claude/skills/`에는 Claude Code 차이만 둔다 |

루트 `CLAUDE.md`는 `@AGENTS.md`만 가져온다. 공통 규칙은 AGENTS.md에 쓴다. `CLAUDE.md`가 있는데 가져오기를 지우면 Claude Code가 AGENTS.md를 읽지 않는다.

## Claude 설정 검증

| 변경 | 검증 |
| --- | --- |
| `.claude/skills/` | `name`이 디렉터리 이름과 같은지, `description`이 1,536자 이하인지, 링크 대상이 있는지 확인. Codex `quick_validate.py`는 `disable-model-invocation` 등 Claude 전용 키를 거부하므로 Codex 스킬에만 쓴다 |
| `.claude/settings.json` 훅 | `python3 scripts/test_feedback.py`, 설정의 명령에 `session_id`·`hook_event_name` 입력을 넣어 출력 JSON 확인 |
