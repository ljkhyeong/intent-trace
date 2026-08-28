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
- `repositoryKey`는 `owner/repository` 형식이며 게시 대상과 대소문자 구분 없이 일치해야 한다.
- `targetRevision`과 GitHub PR `head.sha`는 정확히 일치해야 한다.
- Check Run 이름은 `IntentTrace / 변경 의도`, 결론은 정보성 `neutral`로 고정한다.
- 변경 기록 UUID를 Check Run `external_id`로 사용한다.
- GitHub 호출은 DB 트랜잭션 밖에서 실행한다.
- installation token과 GitHub 오류 응답 본문은 저장하거나 사용자 응답에 노출하지 않는다.
- GitHub App token은 대상 저장소와 `pull_requests: read`, `checks: write` 권한으로 축소해 발급한다.
- installation token은 만료 전에 메모리에서 갱신하고 인증 거부 시 한 번만 새 token으로 재시도한다.
- Check Run Markdown이 65,535자를 넘으면 GitHub 호출 전에 거부한다.

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
