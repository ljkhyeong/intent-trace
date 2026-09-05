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

## 초안 관리와 목록

- UUID를 모르면 `list_change_records`에 저장소와 `scope=MINE`을 지정해 내 초안을 찾는다. 팀 공개 기록은 `scope=TEAM`으로 조회한다. `nextCursor`로 다음 페이지를 요청한다.
- 작성자 피드백은 `revise_change_record`에 현재 버전과 전체 수정 내용을 전달한다. 최초 요청 ID와 저장소는 유지한다. 생성 도구에 같은 ID와 다른 내용을 보내는 것은 수정이 아니다.
- 확인한 비공개 기록을 고치려면 `reopen_change_record`로 확인을 취소하고 수정한 뒤 작성자의 확인을 다시 받는다.
- 사용자가 비공개 기록 폐기를 요청하면 `discard_change_record`를 사용한다.
- 새 공개 기록으로 교체할 때는 `supersede_change_record`를 사용한다. 사용자가 GitHub 반영까지 요청하면 `sync_superseded_record_to_github_pr`로 기존 Check Run에 대체 안내를 반영한다.

## 실행 증거와 이전 코드

- 검증 전에 `scripts/run-verification.py <전체-HEAD-커밋> --summary '검증 설명' -- <명령>`을 실행하면 실제 실행 결과 JSON을 얻는다. 실행 전후 코드가 달라지면 현재 커밋 검증으로 등록하지 않는다. 반환된 `source`와 실패 종료 코드를 그대로 사용한다.
- 삭제·이름 변경 이전의 근거는 `side=BASE`로 만들고 `baseRevision`에 전체 커밋을 지정한다. 변경 후는 `TARGET`이다. 이름 변경은 반대쪽 근거의 `relatedPath`로 연결한다.
- `check_change_record_evidence`는 GitHub 코드와 해시만 확인한다. `codeVerified=true`를 테스트 실행 증명으로 설명하지 않는다.
- 정확한 줄 조회는 `items`에서 읽는다. 결과가 없으면 `find_related_change_intent`로 이름 변경·줄 이동을 포함한 이전 기록을 찾는다. 결과가 비어 있어도 `nextCursor`가 있으면 아래의 중단 조건을 확인한 뒤 다음 후보를 살핀다. 원본·현재 줄 범위를 함께 설명한다. `RELATED_UNVERIFIED`를 현재 코드의 확정 의도로 설명하지 않으며 다른 커밋의 테스트를 현재 테스트로 재사용하지 않는다.

## 게시 복구와 연결 관리

- GitHub 응답을 받지 못하면 `get_github_publication_status`로 먼저 확인한다. `RESULT_UNKNOWN`은 실패 확정이 아니다. 같은 게시 요청 또는 대체 안내 요청을 다시 실행해 기존 Check Run으로 복구한다.
- GitHub 호출 제한 오류가 안내하는 시간이 지나기 전에는 자동 재시도하지 않는다.
- `list_my_sessions`로 본인의 연결 정보를 확인한다. 사용자가 종료를 요청한 경우에만 `revoke_my_session` 또는 `revoke_all_my_sessions`를 호출한다. 연결 ID 대신 token을 도구 인자로 보내지 않는다.

## 브라우저와 검색

- 저장소 목록의 `q`로 제목·요청·판단·판단 근거를 검색한다. 공개 범위와 작성자 소유권은 기존 규칙을 유지한다.
- 사람이 읽을 기록 링크는 `/records/{UUID}`를 사용한다. 로그인 후 원래 기록을 열며, 비공개 기록을 익명 공유하지 않는다.
- 브라우저 `itb_` cookie는 도구 입력이나 설정에 넣지 않는다. MCP에는 기존 환경 변수의 `its_` 세션을 사용한다.

## 후속 초안·PR 목록·진단

- 본인의 공개 기록을 재사용하려면 `create_successor_draft`에 새 요청 ID·스냅샷·코드 근거를 전달한다. 복사한 판단을 다시 검토하고 새로 실행한 검증을 추가한다. 원본의 확인·검증을 새 기록에 승계하지 않는다.
- PR 전체의 기록과 오래된 커밋을 보려면 `list_pull_request_records`를 사용한다. `matchesCurrentHead`만으로 게시·검증 성공을 판단하지 않고 최신 시도와 공개·대체 상태를 함께 읽는다.
- 연결 문제는 `diagnose_connection`으로 대상 저장소를 확인한다. 선택 PR 또는 전체 커밋으로 읽기 접근을 점검할 수 있다. `CONFIGURED_UNVERIFIED`를 게시 성공으로 설명하지 않는다.
- Zed에서도 같은 도구와 기록 원칙을 따른다. 연결 도구 설치·설정·세션 입력은 플러그인 루트의 `docs/clients/zed.md`를 참고한다.

## 원본 비교와 일부 실패 처리

- 후속 기록 확인 전에 `compare_change_record`로 원본·후속의 버전과 `changedFields`를 읽고 판단 출처·삭제 근거·새 검증 누락을 함께 확인한다.
- 이전 기록 조회의 `complete=false`는 실패 후보 또는 조회 중단이 있다는 뜻이다. `stopReason`이 있으면 같은 조건의 `cursor`에 `nextCursor`를 넣어 중단 위치부터 계속 조회하고 결과를 추가한다. `failures`의 ID를 `retryRecordId`로 다시 조회할 때는 해당 후보의 이전 결과를 교체하며 `cursor`를 함께 보내지 않는다. 기한·호출 수·전달된 취소에 따른 중단을 전체 조회 완료로 설명하지 않는다.
- `resumeBlocked=true`이거나 재개해도 같은 커서에서 다시 중단되면 자동 반복하지 않는다. 근거 하나를 처리하지 못한 상태로 설명하고 서버 조회 제한·GitHub 응답 지연 확인을 안내한다. 조치한 뒤 같은 커서로 다시 요청할 수 있다. `CANCELLED`도 사용자 재개 요청 없이 반복하지 않는다.
- `check_publication_credentials`는 저장소 관리자가 요청할 때만 실행한다. GitHub에서 설치 토큰을 발급받고 메모리에만 보관하므로 일반 읽기 진단과 구분한다. `ready=true`도 Check Run 게시 완료나 이후 HEAD 일치를 뜻하지 않는다.
- 브라우저 PR 기록은 `/records/pull-requests`, 연결 진단은 `/records/connection`, 후속 비교는 `/records/{UUID}/comparison`으로 안내한다.
- 비교의 `details`로 추가·삭제·출처·순서 변경을 확인한다. `AMBIGUOUS`는 중복 항목의 대응을 확정할 수 없다는 뜻이며 전체 원본·후속을 읽는다.
- 웹 파일·줄 조회는 `/records/history`, 코드 근거 확인은 `/records/{UUID}/evidence`, 본인 연결 관리는 `/records/sessions`를 사용한다.
- 기록의 처리 이력은 `list_record_activities` 또는 `/records/{UUID}/activities`에서 읽는다. `nextBeforeVersion`으로 이전 작업을 조회하며 작성자는 전체, 팀원은 공개·대체 작업만 볼 수 있다. 수집 이전 작업을 추정하거나 이력을 과거 본문 복원으로 설명하지 않는다.
