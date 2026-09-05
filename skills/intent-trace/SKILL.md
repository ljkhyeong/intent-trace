---
name: intent-trace
description: IntentTrace로 변경 의도 기록을 생성·조회·수정·공개하거나, 코드 줄의 근거와 PR 기록을 찾고 연결을 관리할 때 사용한다. 일반 코드 설명이나 리뷰만 요청한 경우에는 기록 생성을 제안하지 않는다.
---

# IntentTrace 기록 사용

## 기록과 승인

- 원문 대화·숨은 추론·검증 원문 출력은 저장하지 않는다. 비밀값과 개인 절대 경로는 저장 전에 제거한다.
- 작성자는 인증된 GitHub 사용자로 서버가 정한다. 작성자 값을 임의로 넣거나 세션·GitHub 토큰·브라우저 cookie를 도구 인자나 기록에 넣지 않는다.
- 사용자가 요청한 생성·공개·PR 게시·대체·연결 종료 범위까지 진행한다. 앞선 대화에서 받은 승인은 다시 묻지 않는다. 작성자 확인은 사용자가 현재 기록 내용을 보고 승인한 경우에만 적용한다.
- 공개 전 확인이 아직 필요하면 요청·판단·검증·미검증 항목을 먼저 보여준다. 스킬 때문에 멈추는 경우 적용한 지시와 파일을 밝힌다.
- 팀 공개 기록은 해당 저장소 읽기 권한이 있는 사용자에게만 보이며 본문을 직접 수정하지 않는다. 기록의 `current`는 해당 스냅샷과의 일치 여부이며, 현재 편집 중인 코드의 검증 성공을 뜻하지 않는다.

## 초안 만들기

1. 대상 저장소의 변경과 전체 커밋 ID를 확인한다. 아직 커밋하지 않은 변경은 기존 커밋의 근거로 기록하지 않는다.
2. 이 파일의 디렉터리에서 `../..`인 플러그인 루트의 `scripts/git-evidence.sh`와 `scripts/run-verification.py` 절대 경로를 구한다. 대상 저장소 루트를 작업 디렉터리로 사용한다.
3. `git-evidence.sh snapshot <전체-커밋-ID>`로 스냅샷을, `anchor <전체-커밋-ID> <상대-경로> <시작-줄> <끝-줄>`로 필요한 최소 줄 범위의 해시를 구한다. 변경 전은 `BASE`·`baseRevision`, 변경 후는 `TARGET`·`targetRevision`으로 연결한다. 이름 변경은 `relatedPath`로 연결한다.
4. 판단마다 출처를 붙인다: 사용자 명시는 `STATED_BY_USER`, 커밋 명시는 `STATED_IN_COMMIT`, 작성자가 확인한 AI 요약은 `CONFIRMED_AI_SUMMARY`, 추론은 `INFERRED`, 근거 없음은 `UNKNOWN`.
5. 실제 실행한 검증의 명령·종료 코드·시각·스냅샷·출력 해시·짧은 요약만 제출한다. 필요한 검증을 새로 실행할 때는 `run-verification.py <전체-HEAD-커밋> --summary '검증 설명' -- <명령>`을 사용할 수 있다. 실패 코드와 `source`를 보존한다. 실행 전후 코드가 바뀌면 현재 커밋 검증으로 등록하지 않는다.
6. `create_change_record`로 비공개 초안을 만든다. 작성자가 승인한 내용은 `confirm_change_record`, 요청받은 팀 공개는 `publish_change_record`, 요청받은 PR 게시는 `publish_change_record_to_github_pr`로 진행한다.

확인·공개 시 스냅샷이 달라졌다면 비공개 초안을 수정하고 변경된 내용에 대해 다시 확인받는다. 새 PR 게시에는 기록 저장소와 PR 저장소, 기록 커밋과 PR HEAD가 일치해야 하며 Fork PR은 지원하지 않는다.

## 초안 수정과 공개 기록 대체

- `revise_change_record`에는 현재 `expectedVersion`과 수정된 전체 내용을 보낸다. 최초 `requestId`와 저장소는 유지한다. 같은 생성 ID에 다른 내용을 보내 수정하지 않는다.
- 확인된 비공개 기록은 `reopen_change_record` 후 수정한다. 요청받은 비공개 기록 폐기는 `discard_change_record`로 처리한다.
- 본인의 공개 기록에서 이어 쓰려면 `create_successor_draft`에 새 요청 ID·스냅샷·코드 근거를 보낸다. 원본 확인·검증을 승계하지 않는다. `compare_change_record`로 변경 내용을 검토하며 `AMBIGUOUS` 항목은 양쪽 원문을 읽는다.
- 기존 기록 대체가 요청되면 두 기록을 조회한 뒤 `supersede_change_record`에 기존 기록 ID·현재 버전·후속 공개 기록 ID를 보낸다. 같은 작성자·저장소의 공개 기록끼리 대체한다. GitHub 반영도 요청받았다면 `sync_superseded_record_to_github_pr`를 호출한다.

## 기록 찾기

| 목적 | 도구와 입력 |
| --- | --- |
| 현재 줄의 기록 | `find_change_intent`: 저장소·전체 커밋·상대 경로·줄 번호, 결과는 `items` |
| 기록함·파일 이력·검색 | `list_change_records`: 저장소 필수, 팀 기록은 `TEAM`, 본인 비공개 기록은 `MINE`. 파일은 정확한 상대 `path`, 검색은 `q`. `nextCursor`를 다음 요청의 `cursor`로 전달 |
| 상세·대체 기록 | `get_change_record`: 목록은 요약이므로 설명할 기록은 상세를 읽고 `supersededBy`를 따라감 |
| PR의 기록·이전 커밋 | `list_pull_request_records`: `matchesCurrentHead`와 게시·공개 상태를 함께 확인 |
| 코드 해시 확인 | `check_change_record_evidence`: `codeVerified=true`는 GitHub 코드와 해시 일치이며 테스트 실행 증명이 아님 |
| 이전 줄·이름 변경·처리 이력 | [이력 조회와 복구](references/history-and-recovery.md) |

목록은 커서 방식을 기본으로 쓴다. 기존 `MY_DRAFTS`·`page`·`size` 방식은 `cursor`·`limit`·`authorId`·`q`와 섞지 않는다. `authorId`는 팀 조회 필터로만 사용한다. 본인 폐기 기록은 `MINE`·`status=DISCARDED`로 찾는다. 팀 조회에 본인 비공개 기록을 섞거나, 빈 페이지를 전체 기록 없음으로 설명하지 않는다. 추론·미확인·오래된 검증을 구분해 전달한다. 기록 링크는 `/records/{UUID}`다.

## 연결과 게시 오류

- 연결 문제는 `diagnose_connection`으로 확인한다. 현재 MCP 연결이 인증됐는지로 판단하며, 셸에 `INTENT_TRACE_SESSION_TOKEN`이 없다는 이유만으로 중단하지 않는다. 인증이 없거나 만료됐다면 서버의 `/auth/github/start` 로그인 절차를 안내한다.
- `check_publication_credentials`는 저장소 관리자가 게시 자격 증명 점검을 요청했을 때 사용한다. `CONFIGURED_UNVERIFIED`나 `ready=true`를 게시 완료로 설명하지 않는다.
- 본인 연결 조회는 `list_my_sessions`, 요청받은 연결 종료는 `revoke_my_session` 또는 `revoke_all_my_sessions`를 사용한다. 토큰 대신 연결 ID를 전달한다.
- 게시 응답 유실·버전 충돌·호출 제한은 [복구 절차](references/history-and-recovery.md)를 따른다. Zed 설치·설정은 [Zed 사용 안내](../../docs/clients/zed.md)를 읽는다.
