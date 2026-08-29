---
name: intent-trace
description: AI가 작성하거나 수정한 코드에 대해 어떤 사용자 요청과 확인 가능한 판단으로 생겼는지, 어느 커밋·파일·줄에 연결되는지, 무엇으로 검증했는지를 IntentTrace 초안으로 만들거나 조회할 때 사용한다. 변경 의도 기록, AI 코드 설명, 팀 인수인계, PR 근거, 라인 단위 의도 조회 요청에 적용한다.
---

# IntentTrace 변경 의도 기록

## 원칙

- 원문 대화와 숨은 추론 과정은 저장하거나 공개하지 않는다.
- 사용자가 직접 말한 목적과 AI가 추론한 목적을 구분한다.
- 실행하지 않은 테스트나 명령을 검증으로 기록하지 않는다.
- 코드 근거는 저장소 기준 상대 경로와 필요한 최소 연속 줄 범위로 제한한다.
- 초안은 작성자가 내용을 확인하기 전까지 공개하지 않는다.
- 공개 기록은 수정하지 않고, 새 기록으로 대체한다.
- 작성자는 입력하지 않는다. IntentTrace가 `its_` MCP Bearer session으로 확인한 GitHub 사용자를 작성자로 사용한다.
- `its_` session token과 GitHub access·refresh token을 도구 인자, 기록 내용, 검증 명령이나 응답 요약에 넣지 않는다.

## 기록 절차

1. `git status`, `git diff`, 전체 커밋 ID를 확인한다.
2. 현재 `SKILL.md` 위치를 기준으로 플러그인 루트의 `scripts/git-evidence.sh` 절대 경로를 확인한다. 대상 저장소 안의 같은 상대 경로를 찾지 않는다.
3. 대상 저장소 루트를 작업 디렉터리로 두고 확인한 helper의 `snapshot <전체-커밋-ID>`로 저장소 스냅샷 해시를 만든다.
4. 각 근거에 같은 helper의 `anchor <전체-커밋-ID> <경로> <시작-줄> <끝-줄>`을 사용한다. 파일에 실제로 존재하는 줄 범위만 기록한다.
5. 판단 출처를 다음 중 하나로 명시한다.
   - `STATED_BY_USER`: 사용자가 요청이나 대화에서 명시했다.
   - `STATED_IN_COMMIT`: 커밋 제목이나 본문에 명시됐다.
   - `CONFIRMED_AI_SUMMARY`: AI 요약을 작성자가 확인했다.
   - `INFERRED`: 코드나 정황으로 추론했으며 사실로 단정할 수 없다.
   - `UNKNOWN`: 목적 근거를 찾지 못했다.
6. 실제 실행한 검증만 명령, 종료 코드, 시간, 스냅샷 해시, 출력 SHA-256, 결과 요약으로 구성한다. 원문 출력은 보내지 않는다.
7. `create_change_record`로 비공개 초안을 만들고, 작성자에게 요청·판단·검증·미검증 항목을 보여준다.
8. 작성자가 명시적으로 확인하면 `confirm_change_record`를 호출한다.
9. 팀 공개를 명시적으로 요청했고 스냅샷이 그대로일 때만 `publish_change_record`를 호출한다. 팀 공개는 해당 GitHub 저장소의 읽기 권한이 있는 사용자에게만 보인다.
10. 사용자가 GitHub PR 게시까지 명시적으로 요청하면 `repositoryKey`가 `owner/repository`와 같고 기록이 공개 상태인지 확인한 뒤 `publish_change_record_to_github_pr`를 호출한다.

## 조회 절차

- 커밋, 상대 경로, 줄 번호를 먼저 확정한다.
- `find_change_intent`로 공개 기록만 조회한다.
- `INFERRED`와 `UNKNOWN`, 오래된 검증, 남은 질문을 숨기지 않는다.

## 중단 조건

- 현재 코드 스냅샷이 기록의 해시와 다르면 확인이나 공개를 중단하고 새 초안을 만든다.
- `INTENT_TRACE_SESSION_TOKEN`이 없거나 GitHub 사용자 인증·저장소 쓰기 권한·전체 커밋 ID를 확정할 수 없으면 생성·확인·공개를 중단한다. 세션이 만료됐으면 `/auth/github/start`에서 다시 승인한다.
- GitHub PR HEAD가 기록 커밋과 다르거나 Fork PR이면 게시를 중단한다.
- 비밀값이 의심되면 기록을 만들기 전에 제거하고 사용자에게 알린다.
