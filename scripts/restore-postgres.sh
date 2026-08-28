#!/bin/sh
set -eu

usage() {
    printf '%s\n' '사용법: scripts/restore-postgres.sh <backup.dump> --confirm-replace' >&2
}

if [ "$#" -ne 2 ] || [ "$2" != "--confirm-replace" ]; then
    usage
    exit 1
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(dirname -- "$script_dir")
cd "$repository_root"

backup_file=$1
if [ ! -f "$backup_file" ] || [ -L "$backup_file" ]; then
    printf '%s\n' "일반 backup 파일만 복구할 수 있습니다: $backup_file" >&2
    exit 1
fi

environment_file=${INTENT_TRACE_ENV_FILE:-.env.team}
if [ ! -f "$environment_file" ]; then
    printf '%s\n' "팀 배포 환경 파일을 찾을 수 없습니다: $environment_file" >&2
    exit 1
fi

running_services=$(docker compose --env-file "$environment_file" ps --status running --services)
if printf '%s\n' "$running_services" | grep -qx 'app'; then
    printf '%s\n' '복구 전에 app을 중지하세요: docker compose --env-file .env.team stop caddy app' >&2
    exit 1
fi
if ! printf '%s\n' "$running_services" | grep -qx 'postgres'; then
    printf '%s\n' '복구할 PostgreSQL container가 실행 중이 아닙니다.' >&2
    exit 1
fi

docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'exec pg_restore --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --clean --if-exists --exit-on-error --single-transaction --no-owner --no-acl' \
    < "$backup_file"

printf '%s\n' "PostgreSQL backup을 복구했습니다: $backup_file"
printf '%s\n' 'app과 caddy를 다시 시작하세요: docker compose --env-file .env.team up -d app caddy'
