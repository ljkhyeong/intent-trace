#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT

# 운영 비밀값 대신 저장소의 예시로 렌더링하며 클러스터에는 접속하지 않는다.
cp "$root"/deploy/k3s/*.yaml "$root/deploy/k3s/config.env" "$temporary/"
cp "$root/deploy/k3s/.env.example" "$temporary/.env.secrets"
kubectl kustomize "$temporary" > "$temporary/rendered.yaml"
"${KUBECONFORM:-kubeconform}" -strict -summary -kubernetes-version 1.35.0 "$temporary/rendered.yaml"
