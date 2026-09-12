# 홈서버 k3s 배포 준비

앱 코드와 배포 파일을 준비한다. 공유기·DNS·TLS 인증서·GitHub App 설정, 이미지 빌드와 클러스터 적용은 운영자가 수행한다. 실제 자격 증명은 저장소에 포함하지 않는다.

## 외부 API 활용 결과

| 기능 | 사용 중인 연동 | 이번 준비 |
| --- | --- | --- |
| 로그인·사용자·저장소 권한 | GitHub App OAuth와 REST API | 기존 구현 유지 |
| 이슈·PR 내용과 CI 결과 | GitHub Issues·Pull Requests·Actions API | 기존 읽기 API 유지. 별도 수집 서버나 CI 실행 추가 없음 |
| PR에 변경 기록 표시 | GitHub Checks API | 기존 게시·재시도 구현 유지 |
| 사용자가 GitHub 승인을 취소한 경우 | `github_app_authorization` 웹훅 | 해당 사용자의 브라우저·도구 세션 즉시 폐기 추가 |
| HTTPS와 요청 전달 | k3s의 Traefik | 표준 Ingress 제공. 인증서와 DNS는 운영자가 설정 |
| 프로세스·DB 상태 확인 | Spring Boot Actuator | DB 장애는 readiness에만 반영하고 liveness와 분리 |

추가 유료 서비스나 주기 조회를 도입하지 않는다. 기록의 스냅샷·작성자 확인·공개 규칙은 IntentTrace의 핵심 기능이므로 외부 서비스로 옮기지 않는다.

## 제공 파일

- [kustomization.yaml](../../deploy/k3s/kustomization.yaml): 이미지와 ConfigMap·Secret 조합
- [config.env](../../deploy/k3s/config.env), [.env.example](../../deploy/k3s/.env.example): 공개 설정과 비밀값 예시
- [app.yaml](../../deploy/k3s/app.yaml): 앱 1개, `Recreate` 교체 배포, 비root·읽기 전용 파일시스템, 상태 확인
- [postgres.yaml](../../deploy/k3s/postgres.yaml): PostgreSQL 17, 10Gi PVC, 앱에서만 DB 접근 허용
- [ingress.yaml](../../deploy/k3s/ingress.yaml): Traefik HTTPS와 내부 서비스 연결

세션은 프로세스 메모리에 있으므로 앱을 여러 개로 늘리지 않는다. 앱 교체 중에는 접속이 중단되고 교체 후 다시 로그인해야 한다. PostgreSQL의 변경 기록은 PVC에 남는다.

## 운영자가 바꿀 값

기본 전제는 k3s의 `traefik` IngressClass와 `local-path` StorageClass다. 다른 구성이면 해당 이름과 저장소 크기를 수정한다.

1. `.env.example`을 `.env.secrets`로 복사하고 권한을 제한한다. 이미 준비한 파일이 있으면 덮어쓰지 않고 편집한다.

   ```bash
   cp -n deploy/k3s/.env.example deploy/k3s/.env.secrets
   chmod 600 deploy/k3s/.env.secrets
   ```

2. `.env.secrets`의 DB 비밀번호, GitHub App client ID·client secret·Base64 private key·웹훅 secret을 실제 값으로 바꾼다. 예시 값으로는 로그인과 게시가 동작하지 않는다. 이 파일은 Git에서 제외한다. 기존 PostgreSQL PVC의 비밀번호를 바꿀 때는 환경 파일 변경과 별도로 DB 계정 비밀번호도 변경해야 한다.
3. `config.env`의 callback URL과 `ingress.yaml`의 두 호스트를 같은 실제 도메인으로 바꾼다. GitHub App callback도 `https://도메인/auth/github/callback`으로 맞춘다.
4. `intent-trace` 네임스페이스에 유효한 인증서를 담은 TLS Secret `intent-trace-tls`를 준비한다. DNS와 공유기의 HTTPS 연결은 Traefik으로 연결한다. 앱 8080과 DB 5432는 외부에 직접 공개하지 않는다.
5. 이미지는 홈서버 아키텍처에 맞게 직접 빌드하고 `kustomization.yaml`의 `newName`·`newTag`를 맞춘다. `newTag`에는 전체 Git 커밋을 사용한다. 로컬 이미지는 k3s 컨테이너 런타임에도 가져와야 하며 Docker에만 있으면 사용할 수 없다. 여러 노드라면 앱이 실행될 모든 노드에서 이미지를 사용할 수 있어야 한다.

이미지 빌드·반입 예시이며 이번 작업에서 실행하지 않는다.

```bash
docker build -t intent-trace:<전체커밋> .
docker save intent-trace:<전체커밋> -o intent-trace.tar
sudo k3s ctr images import intent-trace.tar
```

레지스트리를 사용하면 `newName`에 전체 이미지 주소를 넣고 해당 레지스트리의 인증을 k3s에서 준비한다. `IfNotPresent`를 사용하므로 기존 태그의 이미지를 덮어쓰지 않는다.

## GitHub 승인 취소 웹훅

GitHub App의 Webhook URL을 `https://도메인/webhooks/github`로 지정하고 활성화한다. Secret은 서버의 `INTENT_TRACE_GITHUB_WEBHOOK_SECRET`과 같아야 한다. GitHub App은 [승인 취소 이벤트](https://docs.github.com/en/webhooks/webhook-events-and-payloads#github_app_authorization)를 기본으로 받는다.

서버는 [GitHub의 서명 규칙](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)에 따라 원본 바이트와 `X-Hub-Signature-256`을 JDK HMAC-SHA256으로 검증한다. 서명 확인 후 `sender.id`에 해당하는 기존 세션을 폐기한다. 표시용 login으로 사용자를 찾지 않으며 원문 본문·서명·비밀값을 저장하지 않는다.

| 응답 | 의미 |
| --- | --- |
| `204` | 승인 취소 처리 완료 또는 `ping`·지원하지 않는 이벤트 수신 확인 |
| `400` | 승인 취소 본문이나 숫자 사용자 ID 오류 |
| `401` | 서명 누락·불일치 |
| `413` | 본문이 1MiB를 초과함 |
| `503` | 서버의 웹훅 secret 미설정 |

같은 취소 이벤트를 다시 받아도 없는 세션을 새로 만들거나 오류로 처리하지 않는다. 이 기능은 현재 프로세스의 해당 사용자 세션을 정리하며, 이미 인증을 통과한 작업까지 취소하지는 않는다. GitHub 전송이 실패하면 GitHub App의 최근 전송 기록에서 원인을 확인하고 재전송한다. 새 요청의 `/user`·저장소 권한 확인도 계속 수행한다.

## 로컬 검증과 적용

Kustomize가 포함된 `kubectl`과 `kubeconform` 0.8.0을 준비한 뒤 다음 검사를 실행한다. 예시 환경값만 사용하며 클러스터에 접속하지 않는다.

```bash
bash scripts/validate-k3s.sh
./gradlew test bootJar
```

검증기는 Kubernetes 1.35 스키마로 배포 리소스를 검사한다. 실제 클러스터의 저장소·Ingress·인증서·네트워크 연결까지 확인하는 명령은 아니다. `kubectl kustomize deploy/k3s` 출력에는 Secret이 Base64로 포함되므로 로그·이슈·채팅에 붙이지 않는다. Base64는 암호화가 아니다.

설정을 마친 운영자가 적용한다.

```bash
kubectl apply -k deploy/k3s
kubectl -n intent-trace rollout status statefulset/postgres --timeout=180s
kubectl -n intent-trace rollout status deployment/intent-trace --timeout=300s
kubectl -n intent-trace get pods,pvc,ingress
curl --fail https://도메인/actuator/health/readiness
```

GitHub App에서 `ping` 전송의 `204` 응답을 확인하고, 브라우저 로그인·기록 조회와 Codex 또는 Zed의 `/mcp` 연결을 확인한다. 실제 승인 취소 시험은 테스트 계정으로 수행한다.

ConfigMap·Secret 이름에는 내용 해시가 붙는다. 설정을 바꾸고 다시 적용하면 앱 Pod도 교체된다. 공용 Secret 변경은 PostgreSQL Pod도 교체하므로 점검 시간에 적용한다. 이전 Secret을 정리할 때는 현재 앱·PostgreSQL이 참조하는 이름을 먼저 확인한다. DB PVC는 앱 배포와 함께 삭제하지 않는다.

## 데이터 보관

`local-path`는 노드 디스크이므로 노드 장애를 대비한 별도 백업이 필요하다. 아래 명령은 운영자가 실행하며 DB 데이터를 덮어쓰지 않는다.

```bash
umask 077
kubectl -n intent-trace exec statefulset/postgres -- sh -ec 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > intent-trace.dump
```

백업에는 변경 기록이 포함되므로 접근을 제한하고 서버 밖에 보관한다. 복원·DB 교체와 k3s Secret 암호화·백업 정책은 운영자가 설정한다. 세션과 GitHub 사용자 토큰은 DB 백업에 포함되지 않는다.

기반 문서: [k3s 네트워크 서비스](https://docs.k3s.io/networking/networking-services), [Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/), [Spring Boot 상태 확인](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes).
