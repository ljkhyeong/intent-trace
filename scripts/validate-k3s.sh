#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT

# 운영 비밀값 대신 저장소의 예시로 렌더링하며 클러스터에는 접속하지 않는다.
mkdir "$temporary/base" "$temporary/rendered"
cp "$root"/deploy/k3s/*.yaml "$root/deploy/k3s/config.env" "$root/deploy/k3s/database.env" "$temporary/base/"
cp "$root/deploy/k3s/.env.example" "$temporary/base/.env.secrets"
cp "$root/deploy/k3s/.env.database.example" "$temporary/base/.env.database.secrets"
kubectl kustomize "$temporary/base" -o "$temporary/rendered"
"${KUBECONFORM:-kubeconform}" -strict -summary -kubernetes-version 1.35.0 "$temporary/rendered"

# 설정만 바꿔 다시 렌더링하고 앱·DB의 교체 범위를 확인한다.
app=apps_v1_deployment_intent-trace.yaml
database=apps_v1_statefulset_postgres.yaml
test -f "$temporary/rendered/$app"
test -f "$temporary/rendered/$database"

check_rollout() {
  local scenario="$1" file="$2" key="$3" value="$4" expected_database="$5"
  local source="$temporary/$scenario/source" rendered="$temporary/$scenario/rendered"
  local actual_database=unchanged
  mkdir -p "$source" "$rendered"
  cp -R "$temporary/base/." "$source/"
  sed "s|^$key=.*|$key=$value|" "$source/$file" > "$source/updated.env"
  mv "$source/updated.env" "$source/$file"
  kubectl kustomize "$source" -o "$rendered"
  test -f "$rendered/$app"
  test -f "$rendered/$database"
  if cmp -s "$temporary/rendered/$app" "$rendered/$app"; then
    printf '%s: 앱에 설정 변경이 반영되지 않았습니다.\n' "$scenario" >&2
    exit 1
  fi
  if ! cmp -s "$temporary/rendered/$database" "$rendered/$database"; then
    actual_database=changed
  fi
  if [[ "$actual_database" != "$expected_database" ]]; then
    printf '%s: PostgreSQL의 설정 변경 범위가 예상과 다릅니다.\n' "$scenario" >&2
    exit 1
  fi
  printf '%s: 통과\n' "$scenario"
}

check_rollout app-config config.env INTENT_TRACE_GITHUB_CALLBACK_URL https://changed.example.com/auth/github/callback unchanged
check_rollout app-secret .env.secrets INTENT_TRACE_GITHUB_WEBHOOK_SECRET validation-only-webhook-secret unchanged
check_rollout database-config database.env INTENT_TRACE_DATABASE_URL jdbc:postgresql://postgres:5432/changed_database changed
check_rollout database-secret .env.database.secrets INTENT_TRACE_DATABASE_PASSWORD validation-only-database-password changed
