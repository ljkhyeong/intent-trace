# IntentTrace 작업 규칙

## 먼저 읽을 문서

1. `skills/intent-trace-flows/SKILL.md`
2. `README.md`
3. 요청과 관련된 PRD·ADR
4. `HANDOFF.md`

## 서비스 경계

- IntentTrace는 코드 생성 도구가 아니라 변경 의도와 검증 증거를 기록하는 서비스다.
- 원문 대화와 숨은 추론을 영구 저장하거나 팀에 공개하지 않는다.
- 사용자 요청, 커밋 메시지, 작성자가 확인한 AI 요약, 추론, 미확인을 구분한다.
- 공개 기록은 전체 커밋과 스냅샷 해시에 묶고 직접 수정하지 않는다.
- 실행하지 않은 명령이나 테스트를 검증으로 기록하지 않는다.
- 비밀값과 절대 개인 경로는 저장 전에 제거한다.
- GitHub 게시 전 저장소와 PR HEAD를 서버 응답으로 확인하고 private key, JWT와 installation token을 저장하거나 로그에 남기지 않는다.
- REST와 MCP 작성자는 GitHub `/user` 응답의 숫자 ID로 정하고 요청 입력의 작성자 값을 신뢰하지 않는다.
- 초안은 작성자에게만, 공개·대체 기록은 GitHub 저장소 읽기 권한이 있는 팀원에게만 노출한다.
- Codex와 기본 REST 클라이언트에는 `its_` session token만 전달하고, GitHub access·refresh token은 메모리 밖이나 DB, 로그, cookie, URL, 오류 응답, MCP 도구 인자에 넣지 않는다.
- OAuth callback은 같은 브라우저의 HttpOnly·SameSite cookie, TTL 안의 미사용 `state`, PKCE `S256` verifier와 정확한 callback 경로를 모두 확인한 뒤 code를 교환한다.
- refresh token은 세션별로 한 번만 사용하고 새 access·refresh token 쌍으로 함께 교체하며, 사용자 subject가 바뀌면 세션을 폐기한다.

## 구현 원칙

- 상태 전이 규칙은 도메인에 둔다.
- REST와 MCP는 같은 애플리케이션 서비스를 사용한다.
- 외부 연동은 어댑터로 분리한다.
- 필요한 검증만 추가하고, 같은 정책을 여러 계층에서 중복 테스트하지 않는다.
- 코드 주석, 문서, 커밋 메시지, 리뷰는 실무에서 바로 이해할 수 있는 한국어로 작성한다.

## 검증

```bash
./gradlew test
scripts/validate-plugin.sh
```
