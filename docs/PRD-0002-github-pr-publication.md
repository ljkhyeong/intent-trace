# PRD-0002: GitHub PR 변경 의도 게시

## 문제

IntentTrace 공개 기록을 팀원이 별도 URL에서 찾아야 하면 PR 리뷰 흐름에서 놓치기 쉽습니다. 반대로 커밋이 다른 PR에 기록을 붙이면 설명과 검증이 실제 코드의 근거처럼 오해될 수 있습니다.

## 목표

작성자가 공개한 변경 의도 기록을 같은 Git 커밋의 GitHub Pull Request에 Check Run으로 게시합니다. 팀원은 PR에서 요청, 판단 출처, 코드 근거, 실제 검증과 미검증 항목을 함께 읽을 수 있습니다.

## 사용자 흐름

1. 기록 작성자이면서 저장소 쓰기 권한이 있는 사용자가 공개 기록과 GitHub 저장소·PR 번호를 지정해 게시를 요청한다.
2. IntentTrace가 저장소의 GitHub App 설치를 찾고 유효한 installation token을 준비한다.
3. GitHub API에서 PR `head.sha`를 읽는다.
4. 기록의 `repositoryKey`와 `targetRevision`이 게시 대상 저장소와 PR HEAD에 모두 일치하는지 확인한다.
5. 기존 `external_id` Check Run을 찾으면 갱신하고 없으면 새로 만든다.
6. 외부 성공 결과를 GitHub 게시 이력에 저장하고 Check Run URL을 반환한다.

## 불변식

- `PUBLISHED` 상태의 기록만 게시한다.
- 요청 사용자는 `PRD-0003`의 GitHub 인증을 통과한 기록 작성자이며 저장소 `CONTRIBUTOR` 이상이어야 한다.
- `repositoryKey`는 `owner/repository` 형식이며 입력 경계에서 소문자로 정규화해 게시 대상과 비교한다.
- `targetRevision`과 GitHub PR `head.sha`는 정확히 일치해야 한다.
- Check Run 이름은 `IntentTrace / 변경 의도`, 결론은 정보성 `neutral`로 고정한다.
- `intent-trace:<변경 기록 UUID>`를 Check Run `external_id`로 사용한다.
- GitHub 호출은 DB 트랜잭션 밖에서 실행한다.
- installation token과 GitHub 오류 응답 본문은 저장하거나 사용자 응답에 노출하지 않는다.
- GitHub App token은 대상 저장소와 `pull_requests: read`, `checks: write` 권한으로 축소해 발급한다.
- installation token은 만료 전에 메모리에서 갱신하고 인증 거부 시 한 번만 새 token으로 재시도한다.
- Check Run Markdown이 65,535자를 넘으면 GitHub 호출 전에 거부한다.

- PR 응답의 base 저장소 이름은 게시 대상과 같아야 하며 head·base 저장소 ID와 이름이 모두 같아야 한다. Fork는 `409`, 저장소 응답 누락은 `502`로 외부 쓰기 전에 거부한다.

## 계약

- REST: `POST /api/v1/change-records/{recordId}/github-pull-request`
- MCP: `publish_change_record_to_github_pr`
- 입력: 저장소 소유자, 저장소 이름, PR 번호
- 출력: 기록 ID, 저장소, PR 번호, HEAD 커밋, Check Run ID·URL, 게시 내용 해시와 시각

## 성공 기준

- PR HEAD가 다르면 Check Run 쓰기를 시작하지 않는다.
- 같은 기록을 다시 게시하면 같은 `external_id` Check Run을 찾아 갱신한다.
- 게시 내용은 기존 IntentTrace Markdown 렌더러와 동일하다.
- GitHub 자격 증명이 없거나 API가 실패하면 안전한 오류 분류만 반환한다.
- 같은 저장소의 유효한 installation token은 재사용하고 만료 5분 전부터 새로 발급한다.

## 제외

- GitHub App 등록·설치 화면과 private key 회전 자동화
- GitHub webhook 처리
- Fork PR Check Run
- line annotation 자동 생성
- 실제 외부 GitHub 저장소를 사용하는 자동 테스트

## 게시 결과 조회와 대체 안내

- `GET /api/v1/change-records/{id}/github-pull-request?owner=...&repository=...&pullNumber=...`는 마지막 게시 결과와 최근 20회의 시도를 반환한다. MCP는 `get_github_publication_status`다.
- 시도 상태는 `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `RESULT_UNKNOWN`이다. 결과 미확인은 기존 게시 요청을 재시도해 원격 실행을 찾아 복구한다. 재시작으로 중단된 시도도 결과 미확인으로 표시한다.
- 같은 기록의 동시 게시 요청은 단일 app에서 직렬 처리한다.
- `POST /api/v1/change-records/{id}/github-pull-request/supersession`과 `sync_superseded_record_to_github_pr`는 사용자가 GitHub 반영을 요청했을 때 `SUPERSEDED` 기록의 기존 Check Run에만 대체 안내를 반영한다.
- 대체 안내는 새로운 Check Run을 만들지 않는다. 기존 Check Run의 원래 커밋과 external ID를 확인하므로 PR HEAD가 진행된 뒤에도 원래 기록의 대체 사실을 표시할 수 있다. 새 기록 게시의 PR HEAD 일치 규칙과 구분한다.
- 오류 코드에는 HEAD 불일치, Fork, 저장소 불일치, 내용 크기, 자격 증명 설정, 호출 제한, 기록 상태와 원격 결과 미확인을 사용한다. GitHub 오류 원문과 token은 반환하지 않는다.

## PR별 기록 모아보기

- `GET /api/v1/github-pull-request/records?owner=...&repository=...&pullNumber=...`와 `list_pull_request_records`는 게시 결과 또는 게시 시도가 있는 팀 공개·대체 기록을 조회한다.
- 저장소 읽기 권한을 확인한 뒤 사용자 자격 증명으로 GitHub PR의 base 저장소와 전체 HEAD를 읽는다. 목록 조회에 서버 게시 키는 필요하지 않다. Fork 여부는 표시하되 Check Run을 쓰지 않는다.
- 기록 요약, `matchesCurrentHead`, 마지막으로 저장된 게시 결과와 최신 시도를 반환한다. 게시 결과가 없고 시도만 있으면 성공으로 추정하지 않는다. 비공개 기록과 다른 저장소·PR 기록은 제외한다.
- 선택 `cursor`, `limit`을 받으며 생성 시각·ID 내림차순, 기본 20개·최대 100개다. `checkedAt`은 응답을 만든 시각이며 이후 PR 커밋이 바뀔 수 있다.
- `matchesCurrentHead`는 기록 커밋 일치만 뜻한다. 대체 상태·실제 게시 결과·테스트 실행은 별도 필드를 읽어야 한다. 아직 한 번도 게시를 시도하지 않은 기록은 PR에 연결되지 않는다.

## 관리자용 게시 사전 점검

`POST /api/v1/publication-preflight`와 `check_publication_credentials`에 `repositoryKey`를 전달한다. 저장소 MAINTAINER 권한이 필요하다. App 키 사용·원격 인증·저장소 설치·대상 한 곳으로 축소한 token 발급·실제 부여 범위와 권한을 단계별로 반환한다. 모든 단계가 확인된 경우만 `ready=true`다. 고정 token은 `CONFIGURED_UNVERIFIED`로 반환한다.

이 점검은 Check Run을 생성·수정하지 않는다. token은 메모리에서만 사용하고 응답에는 단계·설치 ID·만료 및 확인 시각만 포함한다. 실패 시 외부 오류 원문을 숨기며 호출 제한은 기존 대기 계약을 따른다. 실제 게시의 PR HEAD·저장소 검사는 그대로 수행한다.
