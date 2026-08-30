# PRD-0004: IntelliJ 현재 줄 변경 의도 조회

## 문제

개발자가 AI가 만든 코드를 읽다가 의도를 확인하려면 커밋, 파일 경로와 줄 번호를 직접 찾아 REST나 MCP 조회 요청을 만들어야 합니다. 이 과정이 길면 변경 기록이 있어도 실제 코드 이해 흐름에서 사용하지 않게 됩니다.

## 목표

IntelliJ 편집기에서 현재 줄의 공개 변경 의도를 한 번의 액션으로 조회합니다. 플러그인은 Git 저장소와 편집기 문맥만 계산하고, 기록 공개 범위와 저장소 권한은 기존 IntentTrace 서버가 결정합니다.

## 사용자 흐름

1. 사용자가 IntelliJ에서 `IntentTrace GitHub 승인 시작`을 실행해 기존 서버 승인 페이지를 시스템 브라우저로 연다.
2. 사용자가 IntentTrace OAuth callback에 한 번 표시된 `its_` session token을 IntelliJ PasswordSafe에 저장한다.
3. 공개 변경 의도를 확인할 파일에 커서를 둔다.
4. `현재 줄 변경 의도 조회` 액션을 실행한다.
5. 플러그인이 GitHub `origin`, 전체 HEAD commit, 저장소 상대 경로와 1부터 시작하는 줄 번호를 계산한다.
6. 플러그인이 기존 `GET /api/v1/change-records/lookup`을 Bearer session으로 호출한다.
7. 조회된 기록의 요청 요약, 판단 출처, 검증 결과, 코드 근거와 미확인 항목을 읽기 전용 창으로 보여준다.
   결과가 없으면 사용자가 선택해 PRD-0005의 파일 이력을 연다. 과거 기록을 현재 줄의 근거로 자동 연결하지 않는다.
8. 연결을 끝낼 때는 PasswordSafe에 저장한 session token을 삭제한다.

## 범위

### 포함

- IntelliJ IDEA 2025.3 이상에서 동작하는 독립 플러그인
- GitHub HTTPS·SSH `origin` 주소의 `owner/repository` 변환
- 현재 Git HEAD, 파일 상대 경로와 커서 줄 계산
- 수정되지 않은 현재 파일의 공개·대체 기록 조회
- PasswordSafe에 저장한 `its_` token과 `INTENT_TRACE_SESSION_TOKEN` 환경 변수 지원
- 기존 서버의 GitHub 승인 시작 페이지를 시스템 브라우저로 열기
- PasswordSafe에 저장한 session token 삭제와 환경 변수 fallback 안내
- 기본 loopback HTTP 서버와 HTTPS 팀 서버 지원
- 에디터 메뉴와 Tools 메뉴 액션

### 제외

- 변경 기록 생성·확인·공개·대체
- IntelliJ 내부 GitHub OAuth callback 처리와 token 자동 가져오기
- GitHub access·refresh token 저장
- 커밋되지 않은 파일의 줄 번호 추정
- 새 서버 API와 IntelliJ 전용 응답 형식
- 원문 대화, 프롬프트, 코드 본문 수집과 telemetry

## 성공 기준

- GitHub HTTPS와 SSH remote를 같은 소문자 저장소 키로 계산한다.
- 현재 파일이 HEAD와 다르면 잘못된 줄 근거를 조회하지 않고 사용자에게 먼저 커밋하라고 안내한다.
- `its_` token을 프로젝트 설정 파일이나 로그에 기록하지 않는다.
- GitHub 승인 시작 주소에는 token, query와 임의 경로를 넣지 않는다.
- PasswordSafe token을 삭제해도 환경 변수 token이 남아 있으면 이를 연결 해제로 표시하지 않는다.
- loopback이 아닌 HTTP 서버로 Bearer token을 보내지 않는다.
- API 호출과 PasswordSafe 접근은 IntelliJ UI thread 밖에서 실행한다.
- 응답 데이터가 10초 동안 도착하지 않으면 조회를 중단한다. redirect는 따라가지 않고, 1,000,000바이트를 넘는 성공 응답은 거부한다.
- `401`, `403`, 서버 장애와 조회 결과 없음이 서로 다른 안내로 표시된다.
- 조회 결과에서 판단 출처, 기록 snapshot과 검증 snapshot의 일치 여부, 미확인 항목을 구분한다.
- 플러그인 단위 테스트와 배포 ZIP 빌드가 성공한다.
