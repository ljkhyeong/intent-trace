# ADR-0003: 저장소별 GitHub App installation token을 자동 발급한다

## 상태

채택

## 배경

installation token을 환경 변수로 직접 교체하면 한 시간 만료와 저장소별 설치 범위를 운영자가 계속 관리해야 합니다. 장기 token을 저장하면 유출 영향도 커집니다. IntentTrace는 게시 대상 저장소가 요청마다 정해지므로 해당 저장소의 GitHub App 설치에서 짧은 수명의 token을 발급하는 편이 적합합니다.

## 결정

- 운영 기본 인증은 GitHub App client ID와 RSA private key로 한다. 기존 `INTENT_TRACE_GITHUB_TOKEN`은 로컬 검증과 호환을 위한 우선 fallback으로 유지한다.
- private key는 PEM 전체를 Base64로 인코딩해 환경 변수로 주입하며 PKCS#8 `PRIVATE KEY`와 PKCS#1 `RSA PRIVATE KEY`를 지원한다.
- App JWT는 `RS256`으로 서명하고 `iat`를 현재보다 60초 전, `exp`를 현재보다 9분 후, `iss`를 client ID로 설정한다.
- JWT로 `GET /repos/{owner}/{repo}/installation`을 호출해 installation ID를 찾는다.
- installation token은 대상 저장소 하나와 `pull_requests: read`, `checks: write` 권한으로 축소해 발급한다.
- token은 저장소별로 메모리에만 캐시하고 만료 5분 전부터 새 token으로 교체한다.
- GitHub가 동적 token을 `401`로 거부하면 해당 캐시를 폐기하고 한 번만 재발급해 요청을 반복한다. 고정 token은 자동 반복하지 않는다.
- JWT, private key, installation token과 GitHub 오류 본문은 DB·로그·오류 응답에 넣지 않는다.

## 영향

- GitHub App 등록, 저장소 설치, client ID와 private key 주입은 운영자가 수행해야 한다.
- installation token은 프로세스 재시작 시 사라지며 다음 게시 요청에서 다시 발급된다.
- 여러 인스턴스는 각자 token을 캐시한다. 현재 로컬·단일 인스턴스 범위에서는 공유 캐시를 두지 않는다.
- private key 회전은 GitHub에서 새 key를 만든 뒤 환경 변수를 교체하고 애플리케이션을 재시작하는 방식이다.

## 대안

- 고정 installation token만 사용: 구현은 단순하지만 한 시간마다 외부에서 교체해야 해 운영 기본값으로 선택하지 않았다.
- 사용자 OAuth token 사용: 사용자 행위 귀속에는 유리하지만 현재의 서버 주도 Check Run 게시에는 불필요한 사용자 인증 흐름이 생겨 선택하지 않았다.
- token DB 저장: 여러 인스턴스 공유는 쉽지만 단기 비밀값의 저장·암호화·폐기 책임이 늘어나 현재 범위에서 선택하지 않았다.
