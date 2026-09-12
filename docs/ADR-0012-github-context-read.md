# ADR-0012: GitHub 이슈·PR 내용과 CI 결과를 조회한다

## 목적

이슈·PR의 요청 내용을 초안에 활용하고 이미 실행된 CI 결과를 조회한다. 기존 GitHub 연결을 사용하며 유료 서비스, 새 워크플로 실행, 로그·아티팩트 저장, 자동 폴링과 CI 결과 수신용 웹훅을 추가하지 않는다.

## 이슈·PR 내용

- `GET /api/v1/github/request-context?repositoryKey=owner/repository&number=7`과 MCP `get_github_request_context`는 같은 서비스를 호출한다. GitHub의 이슈 조회 API가 PR도 반환하므로 요청 경로는 하나다.
- 매 요청 저장소 읽기 권한을 확인하고 현재 사용자의 메모리 토큰을 사용한다. GitHub 응답의 번호·원문 URL이 요청한 저장소·번호와 맞는지 확인한다.
- 제목과 본문은 먼저 비밀값·개인 경로를 제거한다. 제목은 200자, 출처 링크를 포함한 `requestSummary`는 2,000자로 제한하고 발췌 여부를 `truncated`로 알린다. 본문이 없으면 제목을 사용한다.
- 응답에는 `kind=ISSUE|PULL_REQUEST`, `sourceUrl`, 원문 `updatedAt`, 조회 `fetchedAt`, `authorConfirmed=false`를 포함한다. 본문 발췌를 서버가 요약하거나 작성자가 확인한 판단으로 표시하지 않는다.
- 이 응답을 기존 생성 요청의 `title`·`requestSummary`에 활용한다. 코드 근거·판단 출처와 실제 검증은 기존 생성 절차로 준비한다. 조회 자체는 DB를 변경하지 않는다.

## Actions 결과

- `GET /api/v1/github/actions?repositoryKey=owner/repository&revision=<전체-커밋>&page=1`과 MCP `list_github_actions_runs`는 GitHub의 `head_sha` 필터로 실행 결과를 20개씩 조회한다.
- GitHub가 반환한 각 실행의 저장소와 전체 HEAD가 요청과 다르면 실패한다. 실행 ID·재실행 차수·워크플로 이름·이벤트·상태·결론·시작 및 최근 갱신 시각을 반환한다. 링크는 설정된 GitHub 웹 주소와 확인한 ID로 만든다.
- `nextPage`는 다음 요청에 전달한다. GitHub 필터 검색 한도에 맞춰 50페이지까지 허용하고 `total_count > 1000`이면 `searchLimited=true`다. 조회 중 실행 상태와 목록이 바뀔 수 있으므로 페이지를 합칠 때 실행 ID로 구분한다.
- `source=GITHUB_ACTIONS`, `snapshotVerified=false`로 표시한다. `updatedAt`은 종료 시각이 아니다. 성공·실패·취소·건너뜀·진행 중을 유지하고 전체 통과 여부를 임의로 계산하지 않는다.
- API의 HEAD는 워크플로가 연결된 커밋이다. 워크플로 안에서 실제 checkout한 커밋이나 실행 중 코드 변경까지 증명하지 않는다. 명령·종료 코드·출력 해시가 필요한 기존 `VerificationRun`으로 변환하거나 현재 스냅샷 검증으로 저장하지 않는다.
- 공개 기록과 저장된 검증 결과는 수정하지 않는다. 실행·재실행·취소 API와 로그·아티팩트 API를 호출하지 않는다.

## 사용자 화면과 운영

- `/records/github`에서 이슈·PR 번호 또는 전체 커밋을 입력한다. 기록 상세에서도 해당 커밋의 CI 결과로 이동할 수 있다. 외부 텍스트는 HTML로 해석하지 않으며 기존 브라우저 로그인과 `no-store` 정책을 사용한다.
- PR 내용과 PR 변경 기록 화면은 조회한 저장소·번호로 서로 연결한다. 일반 이슈에는 PR 기록 링크를 표시하지 않는다.
- CI 결과에 현재 페이지·건수와 이전·다음·새로고침 링크를 표시한다. 저장소와 전체 커밋은 유지하며 새로고침은 현재 페이지를 다시 읽는다. 첫 페이지에는 이전 링크가 없고, `nextPage`가 없으면 다음 링크를 표시하지 않는다. 빈 페이지에서도 이전 페이지 이동과 새로고침은 가능하다. 페이지 번호는 브라우저 요청에서 사용하며 REST·MCP 응답 계약은 유지한다.
- GitHub App에 이슈 조회용 `Issues: read`, PR 조회용 `Pull requests: read`, CI 조회용 `Actions: read`가 필요하다. 저장소 읽기 권한과 App에 부여한 권한이 함께 적용된다. 기존 게시용 installation token의 축소 권한은 유지한다.
- 조회 응답은 2 MiB로 제한하고 기존 HTTP 제한·호출 제한 안내·지표를 재사용한다. 권한 부족·인증·원격 장애·호출 제한을 빈 목록이나 성공으로 숨기지 않는다. 오류 원문과 토큰은 반환하지 않는다.
- 이 연동은 새 Actions 실행 시간이나 아티팩트 사용량을 만들지 않는다. 기존 서버·네트워크와 사용자가 따로 실행하는 워크플로의 비용은 기존 환경에 따른다.
- 관리형 DB 전환은 추가 비용 없는 범위에서 제외한다. Blob 원문 응답 전환은 기존 객체·크기 확인을 대체할 코드가 필요해 이번 기능에 포함하지 않는다.

## 근거

- [GitHub 이슈 조회 API](https://docs.github.com/en/rest/issues/issues#get-an-issue): 이슈·PR 공통 조회와 읽기 권한.
- [GitHub Actions 실행 조회 API](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-repository): 커밋 필터, 실행 메타데이터와 검색 한도.
- [GitHub API 호출 제한](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api), [Actions 과금 기준](https://docs.github.com/en/billing/concepts/product-billing/github-actions): 조회와 실행·저장 비용을 구분한다. 2026-09-07 확인.
