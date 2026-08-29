# ADR-0006: 단일 인스턴스 Compose에서 PostgreSQL과 Caddy를 운영한다

## 상태

채택

## 배경

IntentTrace는 로컬 H2 실행과 PostgreSQL 연결 profile을 제공하지만 팀이 같은 방식으로 실행할 container, HTTPS 종료, 상태 확인, backup·restore와 CI 계약이 없었습니다. `ADR-0005`의 GitHub 사용자 token과 `its_` session은 프로세스 메모리 전용이므로 여러 app 인스턴스를 바로 실행하면 요청마다 session이 달라질 수 있습니다.

## 결정

- 한 host의 Docker Compose에서 PostgreSQL, IntentTrace app 하나와 Caddy 하나를 실행한다.
- Caddy만 host의 80·443에 연결한다. app과 PostgreSQL에는 host port를 열지 않는다.
- PostgreSQL과 app은 외부 통신이 차단된 `data` network를 공유한다. app과 Caddy는 GitHub API와 ACME에 나갈 수 있는 `edge` network를 공유한다.
- Caddy가 TLS 인증서 발급·갱신과 reverse proxy를 담당한다. app은 내부 HTTP를 받고 forwarded header로 외부 HTTPS origin을 해석한다.
- app image는 Java 21 다단계 build, 비root 사용자, 읽기 전용 root filesystem, `/tmp` tmpfs와 제거된 Linux capability로 실행한다.
- PostgreSQL·Caddy·Java build/runtime image와 Dockerfile frontend는 tag와 digest를 함께 기록한다. app image는 배포한 전체 Git commit ID를 tag로 사용해 같은 host에서 이전 image를 식별한다.
- PostgreSQL volume에는 변경 의도와 GitHub 게시 이력만 저장한다. GitHub access·refresh token과 `its_` session은 계속 app 메모리에만 둔다.
- backup은 기존 파일을 덮어쓰지 않는 custom-format `pg_dump`로 만들고 권한을 `0600`으로 제한한다.
- restore는 app이 중지된 상태와 `--confirm-replace`가 모두 확인될 때만 `pg_restore --clean --single-transaction`으로 실행한다.
- pull request와 `main` push에서 Gradle Wrapper·테스트, PostgreSQL 17 migration·JDBC·backup·restore, 플러그인 구조, Compose network·port·image 경계, Caddy 설정과 app image build를 검증한다.

## 영향

- app 재시작과 image 교체 때 모든 `its_` session이 사라져 사용자가 다시 승인해야 한다. PostgreSQL의 변경 기록은 유지된다.
- 단일 app이므로 session routing이나 분산 잠금은 필요 없지만 app 장애 중에는 서비스를 사용할 수 없다.
- 실제 운영자는 DNS가 host를 가리키게 하고 80·443 inbound를 허용해야 한다. 이 저장소는 DNS와 방화벽을 직접 변경하지 않는다.
- Caddy 인증서 상태와 PostgreSQL 데이터는 각각 named volume에 남는다. host 자체 장애에 대비하려면 backup 파일을 별도 저장소로 옮겨야 한다.
- 이전 commit image로 되돌릴 때 해당 버전이 현재 DB schema를 읽을 수 있어야 한다. 열 삭제처럼 하위 호환되지 않는 migration 뒤에는 업그레이드 전 backup과 이전 commit을 함께 복구한다.
- `.env.team`은 secret manager가 아니라 단일 host MVP용 주입 수단이다. 파일 권한을 제한하고 Git에 넣지 않는다.

## 대안

- GitHub token과 session을 PostgreSQL에 암호화 저장: 재시작 복구는 가능하지만 encryption key의 보관·회전·backup 폐기 책임이 추가돼 실제 필요를 확인하기 전에는 선택하지 않았다.
- app 여러 인스턴스: 가용성은 높아지지만 공유 session store, refresh 동시성, migration 조율과 rolling 배포 계약이 먼저 필요해 제외했다.
- Spring에서 TLS 직접 종료: 인증서 발급과 갱신이 애플리케이션 운영 책임에 섞이므로 선택하지 않았다.
- Kubernetes: replica와 secret 배포에는 적합하지만 현재 단일 host 검증보다 운영 구성이 커져 후속 단계로 남겼다.
