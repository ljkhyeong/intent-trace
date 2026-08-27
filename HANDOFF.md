# IntentTrace 인수인계

## 현재 구현

- Kotlin 2.3.21, Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- H2 기본 저장소와 PostgreSQL 프로필
- Flyway 초기 스키마
- Flyway V2 GitHub 게시 이력 스키마
- 변경 의도 생성·확인·공개·대체·라인 조회
- 팀 공유용 Markdown 출력
- PR HEAD 커밋 검증과 neutral GitHub Check Run 게시·재시도 갱신
- 저장소별 GitHub App installation token 자동 발급·만료 전 갱신·401 복구
- Streamable HTTP MCP 도구 6개
- Codex 스킬과 개인정보를 수집하지 않는 세션 시작 훅
- IntentTrace 저장소 전용 개발 스킬

## 확인할 불변식

- 초안은 만든 작성자만 확인한다.
- 확인 시 전체 Git 커밋 ID가 필요하다.
- 확인과 공개 시 현재 스냅샷이 기록의 스냅샷과 같아야 한다.
- 공개된 본문과 근거는 수정하지 않고 새 공개 기록으로 대체한다.
- 팀 조회에는 공개 또는 대체된 기록만 노출한다.
- GitHub 게시 전 기록 저장소와 PR 저장소, 기록 커밋과 PR `head.sha`가 각각 일치해야 한다.
- Check Run은 변경 기록 UUID `external_id`로 재사용하고 GitHub 호출을 DB 트랜잭션 안에서 실행하지 않는다.

## 다음 작업 후보

1. 조직 계정, 저장소 권한, 작성자 신원을 연결한다.
2. PostgreSQL 기반 팀 배포와 MCP 인증을 추가한다.
3. IntelliJ에서 현재 줄의 공개 변경 의도를 조회한다.
4. 코드 근거를 Check Run line annotation으로 선택 게시한다.
5. GitHub App webhook으로 설치 제거와 권한 변경을 반영한다.

## 현재 제한

- 로컬 작성자 문자열을 신뢰하므로 팀 인증 용도로 사용할 수 없다.
- 코드 스냅샷과 줄 해시는 제공 스크립트로 계산하며 서버가 Git 객체를 직접 검증하지 않는다.
- GitHub App 등록·설치와 private key 회전은 운영자가 수행해야 한다.
- installation token 캐시는 프로세스 메모리에만 있어 여러 인스턴스가 공유하지 않는다.
- Fork PR Check Run과 GitHub webhook은 아직 지원하지 않는다.
- 실제 GitHub 저장소 쓰기는 자동 테스트하지 않고 로컬 HTTP 계약으로 검증한다.
- IDE 자동 연동은 아직 구현하지 않았다.
