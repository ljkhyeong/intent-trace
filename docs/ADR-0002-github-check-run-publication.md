# ADR-0002: 변경 의도를 GitHub neutral Check Run으로 게시한다

## 상태

채택

## 배경

PR 댓글은 변경 의도 기록을 리뷰에 노출할 수 있지만 특정 커밋에 직접 연결되지 않고 재시도 때 댓글이 누적되기 쉽습니다. Check Run은 특정 `head_sha`에 연결되고 Markdown 상세 내용을 제공하며 PR 화면에서 확인할 수 있습니다.

## 결정

- GitHub PR의 `head.sha`를 서버에서 조회해 공개 기록의 `targetRevision`과 비교한다.
- 기록 저장소 키와 `owner/repository`도 비교해 다른 저장소 게시를 막는다.
- Check Run 결론은 품질 합격을 뜻하지 않는 `neutral`로 사용한다.
- `intent-trace:<변경 기록 UUID>`를 `external_id`로 보내고, 로컬 Check Run ID가 없거나 오래됐으면 HEAD의 Check Run 목록에서 같은 값을 찾아 갱신한다.
- 목록 조회는 GitHub의 동일 이름 Check Run 제한에 맞춰 최대 100개씩 10페이지로 제한한다.
- 게시 시도·결과 미확인 복구와 대체 안내는 `ADR-0008`을 따른다.
- GitHub 원격 호출이 성공한 뒤 로컬 게시 이력을 저장한다. 원격 호출과 DB 저장을 하나의 트랜잭션으로 묶지 않는다.
- GitHub App 인증과 installation token 수명주기는 `ADR-0003`을 따른다.
- 외부 호출은 연결 5초·응답 10초 제한과 redirect 금지를 적용하고 API 기본 주소는 HTTPS만 허용한다.

- PR 응답의 base 저장소 이름은 게시 대상과 같아야 하며 head·base 저장소 ID와 이름이 모두 같아야 한다. Fork는 `409`, 저장소 응답 누락은 `502`로 외부 쓰기 전에 거부한다.

## 영향

- GitHub `Pull requests: read`, `Checks: write` 권한이 필요하다.
- 외부 성공 뒤 로컬 저장이 실패해도 `external_id` 조회로 같은 Check Run을 복구할 수 있다.
- Check Run 생성 권한과 Fork PR 제약 때문에 모든 GitHub 인증·PR 형태를 지원하지 않는다.
- 실제 GitHub 쓰기 없이 로컬 HTTP 계약 테스트로 요청 형식과 재시도를 검증한다.

## 대안

- PR 댓글: 권한과 구현은 단순하지만 커밋 연결과 검사 상태 표시가 부족하고 댓글 누적 위험이 있어 선택하지 않았다.
- commit status: 상태 요약에는 적합하지만 변경 의도 Markdown 전체를 전달하기 어려워 선택하지 않았다.
