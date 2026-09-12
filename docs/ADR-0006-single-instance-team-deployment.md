# ADR-0006: Compose와 k3s에서 앱 1개와 PostgreSQL을 운영한다

## 상태

채택

## 배경

IntentTrace는 로컬 H2 실행과 PostgreSQL 연결 profile을 제공하지만 팀이 같은 방식으로 실행할 container, HTTPS 종료, 상태 확인, backup·restore와 CI 계약이 없었습니다. `ADR-0005`의 GitHub 사용자 token과 `its_` session은 프로세스 메모리 전용이므로 여러 app 인스턴스를 바로 실행하면 요청마다 session이 달라질 수 있습니다.

## 결정

- 한 host의 Docker Compose에서 PostgreSQL, IntentTrace app 하나와 Caddy 하나를 실행한다.
- 홈서버 k3s에는 앱 Deployment 1개와 `Recreate` 전략, PostgreSQL StatefulSet·PVC, Traefik Ingress를 제공한다. Kustomize의 ConfigMap·Secret 생성기로 환경값을 주입한다. 실제 이미지 빌드·DNS·공유기·인증서·클러스터 적용은 운영자가 수행한다. [k3s 준비 안내](operations/k3s-deployment.md)를 따른다.
- readiness에는 DB 상태를 포함하고 liveness는 프로세스 상태만 확인한다. DB 장애로 요청을 받지 못해도 앱 재시작을 반복하지 않는다.
- Caddy만 host의 80·443에 연결한다. app과 PostgreSQL에는 host port를 열지 않는다.
- PostgreSQL과 app은 외부 통신이 차단된 `data` network를 공유한다. app과 Caddy는 GitHub API와 ACME에 나갈 수 있는 `edge` network를 공유한다.
- Caddy가 TLS 인증서 발급·갱신과 reverse proxy를 담당한다. app은 내부 HTTP를 받고 forwarded header로 외부 HTTPS origin을 해석한다.
- app image는 Java 21 다단계 build, 비root 사용자, 읽기 전용 root filesystem, `/tmp` tmpfs와 제거된 Linux capability로 실행한다.
- PostgreSQL·Caddy·Java build/runtime image와 Dockerfile frontend는 tag와 digest를 함께 기록한다. app image는 배포한 전체 Git commit ID를 tag로 사용해 같은 host에서 이전 image를 식별한다.
- PostgreSQL volume에는 변경 의도와 GitHub 게시 이력만 저장한다. GitHub access·refresh token과 `its_` session은 계속 app 메모리에만 둔다.
- 백업은 최종 경로와 같은 디렉터리의 임시 파일에 custom-format `pg_dump`를 만들고 권한을 `0600`으로 제한한다. 덤프가 완성되면 하드 링크로 최종 파일을 만든다. 같은 경로의 다른 백업이 먼저 완료됐으면 기존 백업을 유지하고 자신의 임시 파일만 삭제한다. `HUP`·`INT`·`TERM`을 받으면 완료 처리 없이 신호별 종료 코드로 끝내고 자신의 임시 파일을 정리한다.
- restore는 app이 중지된 상태와 `--confirm-replace`가 모두 확인될 때만 `pg_restore --clean --single-transaction`으로 실행한다.
- PR과 `main` push에서 Gradle Wrapper·테스트, PostgreSQL 17 마이그레이션·JDBC·백업·복구, 플러그인 구조, Compose 네트워크·포트·이미지 해시, Caddy 설정과 app 이미지 빌드를 검증한다.

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
- Kubernetes 다중 replica: 현재 세션 저장 방식과 맞지 않아 제외한다. k3s에서도 앱은 1개로 유지한다.
