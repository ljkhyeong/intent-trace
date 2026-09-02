# ADR-0002: 변경 의도를 GitHub neutral Check Run으로 게시한다

## 상태

채택

## 배경

PR 댓글은 변경 의도 기록을 리뷰에 노출할 수 있지만 커밋 상태와 직접 결박되지 않고 재시도 때 댓글이 누적되기 쉽습니다. Check Run은 특정 `head_sha`에 연결되고 Markdown 상세 내용을 제공하며 PR 화면에서 확인할 수 있습니다.

## 결정

- GitHub PR의 `head.sha`를 서버에서 조회해 전체 커밋 ID 형식인지 확인한 뒤 공개 기록의 `targetRevision`과 비교한다.
- 기록 저장소 키와 `owner/repository`도 비교해 다른 저장소 게시를 막는다.
- Check Run 결론은 품질 합격을 뜻하지 않는 `neutral`로 사용한다.
- `intent-trace:<변경 기록 UUID>`를 `external_id`로 보내고, 로컬 Check Run ID가 없거나 오래됐으면 HEAD의 Check Run 목록에서 같은 값을 찾아 갱신한다.
- 목록 조회는 GitHub의 동일 이름 Check Run 제한에 맞춰 최대 100개씩 10페이지로 제한한다. 마지막 페이지도 가득 차면 기존 실행이 없다고 단정하지 않고 새 Check Run 생성 없이 실패한다.
- 단일 app 안에서는 변경 기록 UUID 단위로 게시 요청을 직렬화해 조회와 생성 사이의 동시 요청을 막는다. 한 기록의 저장소·커밋과 `external_id`는 고정되므로 PR 번호가 달라도 같은 잠금을 사용한다. 각 PR의 HEAD 확인과 게시 이력은 따로 유지한다.
- GitHub 원격 호출이 성공한 뒤 로컬 게시 이력을 저장한다. 원격 호출과 DB 저장을 하나의 트랜잭션으로 묶지 않는다.
- Check Run 생성·수정 응답은 양수 ID, 요청한 HEAD와 `external_id`, 사용자 정보가 없는 HTTPS `html_url`을 모두 확인한 뒤 게시 성공으로 인정한다.
- 기록의 제목·요약·판단·질문은 Markdown 문법이 아닌 일반 문장으로 렌더링하고, 명령·경로·심벌은 포함된 백틱보다 긴 구분자를 사용해 하나의 코드 표현으로 유지한다.
- GitHub App 인증과 installation token 수명주기는 `ADR-0003`을 따른다.
- 외부 호출은 연결 5초·응답 10초 제한과 redirect 금지를 적용하고 API 기본 주소는 HTTPS만 허용한다.
- GitHub HTTP·응답 파싱 오류는 작업명과 필요한 상태 코드만 가진 예외로 변환한다. 응답 원문을 포함할 수 있는 원인 예외는 전달하지 않는다.

## 영향

- GitHub `Pull requests: read`, `Checks: write` 권한이 필요하다.
- 외부 성공 뒤 로컬 저장이 실패해도 `external_id` 조회로 같은 Check Run을 복구할 수 있다.
- Check Run 생성 권한과 Fork PR 제약 때문에 모든 GitHub 인증·PR 형태를 지원하지 않는다.
- 실제 GitHub 쓰기 없이 로컬 HTTP 계약 테스트로 요청 형식과 재시도를 검증한다.

## 대안

- PR 댓글: 권한과 구현은 단순하지만 커밋 결박과 리뷰 신호가 약하고 댓글 누적 위험이 있어 선택하지 않았다.
- commit status: 상태 요약에는 적합하지만 변경 의도 Markdown 전체를 전달하기 어려워 선택하지 않았다.
