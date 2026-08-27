# IntentTrace

IntentTrace는 AI가 만든 코드에 **어떤 요청과 판단이 반영됐고, 어느 코드와 커밋에 연결되며, 무엇으로 검증했는지**를 남기는 Kotlin/Spring 프로젝트입니다. 개인이 코드를 다시 이해하는 일을 먼저 해결하고, 작성자가 확인한 기록을 팀 리뷰와 인수인계에 재사용하는 것이 목표입니다.

## 현재 MVP

- 변경 의도 초안 생성과 요청 ID 기반 멱등 처리
- `DRAFT → AUTHOR_CONFIRMED → PUBLISHED → SUPERSEDED` 수명주기
- 전체 Git 커밋 ID와 SHA-256 저장소 스냅샷 결박
- 파일·줄·콘텐츠 해시 기반 코드 근거
- 실제 검증 명령, 종료 코드, 실행 시간, 출력 해시, 결과 요약
- 작성자가 명시한 목적과 AI 추론·미확인 목적 구분
- REST API와 Spring AI Streamable HTTP MCP 도구
- PR 설명에 붙일 수 있는 Markdown 출력
- Codex 스킬과 세션 시작 안내 훅

원문 대화와 숨은 추론 과정은 저장하지 않습니다. 검증 원문 출력도 저장하지 않고 해시와 요약만 기록합니다.

## 실행

Java 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 서버는 `127.0.0.1:8080`에만 바인딩됩니다.

- REST API: `http://127.0.0.1:8080/api/v1/change-records`
- MCP: `http://127.0.0.1:8080/mcp`
- 상태 확인: `http://127.0.0.1:8080/actuator/health`
- H2 콘솔: `http://127.0.0.1:8080/h2-console`

기본 데이터는 `.intent-trace/data`에 저장됩니다. PostgreSQL을 사용할 때는 환경 변수를 설정하고 `postgres` 프로필을 켭니다.

```bash
export INTENT_TRACE_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/intent_trace'
export INTENT_TRACE_DATABASE_USERNAME='intent_trace'
export INTENT_TRACE_DATABASE_PASSWORD='로컬-비밀번호'
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

## 기록 흐름

```text
코드·검증 완료
    ↓
비공개 초안 생성
    ↓ 작성자가 내용과 전체 커밋 확인
작성자 확인
    ↓ 현재 스냅샷이 같을 때만 공개
팀 공개 기록
    ↓ 더 나은 공개 기록으로만 대체
대체됨
```

저장소와 코드 근거 해시는 다음 스크립트로 계산합니다.

```bash
scripts/git-evidence.sh snapshot "$(git rev-parse HEAD)"
scripts/git-evidence.sh anchor "$(git rev-parse HEAD)" src/main/kotlin/example/File.kt 10 25
```

## API

- `POST /api/v1/change-records`: 비공개 초안 생성
- `GET /api/v1/change-records/{id}`: 기록 조회
- `POST /api/v1/change-records/{id}/confirm`: 작성자 확인과 전체 커밋 결박
- `POST /api/v1/change-records/{id}/publish`: 스냅샷 재확인 후 공개
- `POST /api/v1/change-records/{id}/supersede`: 새 공개 기록으로 대체
- `GET /api/v1/change-records/lookup`: 커밋·파일·줄로 공개 기록 조회
- `GET /api/v1/change-records/{id}/markdown`: 팀 공유용 Markdown 출력

MCP는 같은 애플리케이션 서비스를 사용하며 `create_change_record`, `get_change_record`, `confirm_change_record`, `publish_change_record`, `find_change_intent`를 제공합니다.

## Codex 플러그인

저장소 루트가 플러그인 루트입니다.

- `.codex-plugin/plugin.json`: 플러그인 메타데이터
- `.mcp.json`: 로컬 IntentTrace 서버 연결
- `skills/intent-trace/SKILL.md`: 기록·조회 절차
- `hooks/hooks.json`: 세션 시작 시 개인정보·공개 규칙 안내

플러그인 훅은 원문 프롬프트나 도구 출력을 수집하지 않습니다. Codex에 기록 원칙만 전달합니다.

## 검증

```bash
./gradlew test
scripts/validate-plugin.sh
```

## 현재 제한

- GitHub PR 댓글이나 Checks 자동 게시 어댑터는 아직 없습니다.
- IntelliJ 라인 조회 플러그인은 다음 단계입니다.
- HTTP MCP에는 인증이 없으므로 현재 설정처럼 로컬호스트에서만 사용해야 합니다.
- 팀 서버로 배포하기 전에 인증·권한·감사 로그를 추가해야 합니다.

## 문서

- `docs/PRD-0001-intent-trace-mvp.md`
- `docs/ADR-0001-evidence-bound-change-record.md`
- `HANDOFF.md`
