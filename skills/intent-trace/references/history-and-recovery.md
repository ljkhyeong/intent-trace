# 이력 조회와 복구

이전 코드 탐색, 처리 이력 조회 또는 변경 요청의 응답 유실이 있을 때만 읽는다.

## 이전 코드와 처리 이력

- 정확한 줄 조회에 결과가 없으면 `find_related_change_intent`로 이전 기록을 찾는다. 원본·현재 줄 범위를 함께 설명하고 `RELATED_UNVERIFIED`나 다른 커밋의 테스트를 현재 코드의 확정 근거로 쓰지 않는다.
- 결과가 비어 있어도 `nextCursor`가 있으면 다음 후보를 조회할 수 있다. 같은 검색 조건의 `cursor`에 그대로 전달한다. `complete`는 현재 후보 처리 상태이며 전체 저장소 탐색 완료를 뜻하지 않는다.
- `complete=false`이면 `failures`와 `stopReason`을 확인한다. 중단 커서로 이어 읽은 결과는 추가한다. `failures`의 ID를 `retryRecordId`로 재조회할 때는 해당 후보 결과를 교체하고 `cursor`는 함께 보내지 않는다.
- `resumeBlocked=true`이거나 같은 커서에서 다시 중단되면 자동 반복하지 않는다. 서버 조회 제한·GitHub 지연을 확인하고 조치한 뒤 재개한다. `CANCELLED`는 사용자 재개 요청을 기다린다.
- 처리 이력은 `list_record_activities`, 이전 작업은 `nextBeforeVersion`을 다음 요청의 `beforeVersion`으로 보내 조회한다. 작성자는 전체, 팀원은 공개·대체 이력만 본다. 수집 이전 작업을 추정하거나 이력을 과거 본문 복원으로 설명하지 않는다.
- 웹의 파일·줄 조회는 `/records/history`, 코드 확인은 `/records/{UUID}/evidence`, 비교는 `/records/{UUID}/comparison`, 처리 이력은 `/records/{UUID}/activities`다.

## 변경 요청 복구

- 대체 요청의 응답이 없거나 버전이 충돌하면 기존 기록을 다시 조회한다. 원하는 `supersededBy`가 이미 연결됐으면 완료다. 다른 대체 대상이나 버전 변경이 있으면 무조건 재전송하지 말고 변경 내용을 알린다.
- GitHub 게시 응답을 받지 못하면 `get_github_publication_status`로 먼저 확인한다. `RESULT_UNKNOWN`은 실패 확정이 아니다. 이미 요청받은 같은 게시·대체 안내를 재실행해 기존 Check Run으로 복구한다.
- 호출 제한은 응답의 재시도 대기 시간을 따른다. 기한 전에 반복 호출하지 않는다.
