# 컬렉션 순회와 중복 검사 추가 검토

기준: `e7f1a8a`, 검토 시작 시 미커밋 변경 없음. 앞선 JDBC 일괄 조회·이력 재개·세션 종료 개선을 제외하고 서버의 Git 코드 조회·기록 비교·게시·세션 처리를 확인했다. 아래 두 항목은 우선순위가 낮으며 아직 구현하지 않았다.

## 1. 이름 변경 판별에서 중복 발견 후에도 전체 트리를 순회한다

- 위치: [ChangeIntentHistoryService.kt:104](../../src/main/kotlin/io/intenttrace/record/application/ChangeIntentHistoryService.kt#L104)
- 현재: 이전·현재 트리에서 같은 blob의 개수를 `count { ... } == 1`로 확인한다. 같은 blob이 두 개 발견돼 이름 변경으로 인정할 수 없어도 해당 트리를 끝까지 센다.
- 제안: `singleOrNull { ... } != null`로 바꾼다. [공식 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/single-or-null.html)는 조건에 맞는 항목이 하나일 때만 값을 반환한다. 프로젝트가 사용하는 Kotlin 2.3.21의 소스에서도 두 번째 일치 항목에서 즉시 반환하는 것을 확인했다.
- 효과와 제한: 중복 blob이 있는 경우의 남은 순회를 줄인다. blob이 유일하면 전체 순회는 여전히 필요하며 GitHub 호출 수는 달라지지 않는다. 실제 지연 시간 개선율은 측정하지 않았다.
- 구현 후 확인: 기존 `RecordEvidenceIntegrationTest`의 이름 변경 사례에 이전·현재 트리의 중복 blob을 보완한다. 한쪽이라도 중복이면 이름 변경으로 표시하지 않는 조건과 기존 경로 소멸·조상·해시 조건을 유지한다.

## 2. 중복 검사용 목록을 따로 만들고 같은 키로 Map을 다시 만든다

- 위치: [GitHubGitEvidenceClient.kt:40](../../src/main/kotlin/io/intenttrace/record/adapter/out/github/GitHubGitEvidenceClient.kt#L40), [RecordComparisonDetails.kt:32](../../src/main/kotlin/io/intenttrace/record/application/RecordComparisonDetails.kt#L32)
- 현재: Git 트리는 경로 목록에 `distinct()`를 적용한 뒤 경로별 Map을 다시 만든다. 기록 비교도 이전·이후 키 목록에 `distinct()`를 적용한 뒤 인덱스 Map을 만든다.
- 제안: 필요한 Map을 먼저 만들고 원본 개수와 Map 크기를 비교한다. Git 트리는 `associateBy(keySelector, valueTransform)`으로 변환 목록도 생략할 수 있다. [공식 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/associate-by.html)의 중복 키 처리에 따라 Map 크기가 줄어든 경우를 중복으로 판정한다.
- 유지할 조건: 중복 Git 경로는 기존 오류로 거부하고, 중복 비교 키는 기존 `AMBIGUOUS`를 반환한다. 덮어쓴 Map으로 후속 처리를 계속하면 안 된다. Git 트리의 중복 검사와 객체 형식 검사의 기존 오류 우선순위도 유지한다.
- 구현 후 확인: `GitHubEvidenceClientTest`에 중복 경로 응답을 보완하고, 기존 `DraftManagementIntegrationTest`의 중복·이동·변경 비교 사례를 사용한다. 별도 컬렉션 유틸리티나 Map 동작을 재검증하는 테스트는 추가하지 않는다.

## 검토 결과와 검증 범위

대규모 구조 변경이나 추가로 삭제할 권한·도메인·동시성 검증은 이번 범위에서 찾지 못했다. 두 항목은 다음 코드 정리에 함께 반영할 수 있는 소규모 개선이다.

이번 작업은 코드와 표준 라이브러리 소스 검토만 수행했다. 제품 코드·테스트·설정·의존성을 변경하지 않아 테스트를 다시 실행하지 않았다. 문서의 로컬 링크와 `git diff --check`를 확인했다.
