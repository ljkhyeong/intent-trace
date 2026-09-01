#!/bin/sh
set -eu

umask 077

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(dirname -- "$script_dir")
cd "$repository_root"

environment_file=${INTENT_TRACE_ENV_FILE:-.env.team}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
output_file=${1:-"backups/intent-trace-$timestamp.dump"}

if [ ! -f "$environment_file" ]; then
    printf '%s\n' "팀 배포 환경 파일을 찾을 수 없습니다: $environment_file" >&2
    exit 1
fi

if [ -e "$output_file" ] || [ -L "$output_file" ]; then
    printf '%s\n' "기존 backup 파일은 덮어쓰지 않습니다: $output_file" >&2
    exit 1
fi

output_directory=$(dirname -- "$output_file")
mkdir -p -- "$output_directory"
output_name=$(basename -- "$output_file")
temporary_file=$(mktemp "$output_directory/.${output_name}.XXXXXX")
trap 'rm -f -- "$temporary_file"' EXIT HUP INT TERM

docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'exec pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl' \
    > "$temporary_file"

if [ ! -s "$temporary_file" ]; then
    printf '%s\n' "backup 파일이 비어 있습니다: $output_file" >&2
    exit 1
fi

chmod 600 "$temporary_file"
if ! ln -- "$temporary_file" "$output_file" 2>/dev/null; then
    if [ -e "$output_file" ] || [ -L "$output_file" ]; then
        printf '%s\n' "기존 backup 파일은 덮어쓰지 않습니다: $output_file" >&2
    else
        printf '%s\n' "backup 파일을 최종 경로에 만들 수 없습니다: $output_file" >&2
    fi
    exit 1
fi
rm -f -- "$temporary_file"
trap - EXIT HUP INT TERM
printf '%s\n' "PostgreSQL backup을 만들었습니다: $output_file"
