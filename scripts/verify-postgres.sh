#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(dirname -- "$script_dir")
cd "$repository_root"

environment_file=${INTENT_TRACE_ENV_FILE:-.env.team.example}
smoke_port=${INTENT_TRACE_POSTGRES_SMOKE_PORT:-55432}
smoke_directory=$(mktemp -d "${TMPDIR:-/tmp}/intent-trace-postgres.XXXXXX")
smoke_database_name=intent_trace
smoke_database_username=intent_trace
smoke_database_password=intent-trace-smoke-password

export COMPOSE_FILE=${COMPOSE_FILE:-compose.yaml:compose.postgres-smoke.yaml}
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-intent-trace-postgres-smoke}
export INTENT_TRACE_DATABASE_NAME=$smoke_database_name
export INTENT_TRACE_DATABASE_USERNAME=$smoke_database_username
export INTENT_TRACE_DATABASE_PASSWORD=$smoke_database_password

cleanup() {
    docker compose --env-file "$environment_file" down --volumes >/dev/null 2>&1 || true
    rm -rf -- "$smoke_directory"
}
trap cleanup EXIT HUP INT TERM

docker compose --env-file "$environment_file" up -d postgres

attempt=0
until docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'pg_isready --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        printf '%s\n' 'PostgreSQL 상태 확인이 제한 시간 안에 성공하지 않았습니다.' >&2
        exit 1
    fi
    sleep 2
done

INTENT_TRACE_POSTGRES_SMOKE=true \
INTENT_TRACE_DATABASE_URL="jdbc:postgresql://127.0.0.1:$smoke_port/$smoke_database_name" \
INTENT_TRACE_DATABASE_USERNAME="$smoke_database_username" \
INTENT_TRACE_DATABASE_PASSWORD="$smoke_database_password" \
    ./gradlew --no-daemon test --tests 'io.intenttrace.record.application.PostgresRepositorySmokeTest'

before_count=$(docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="select count(*) from change_records"')
before_activities=$(docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="select count(*) from record_activities"')
backup_file="$smoke_directory/postgres-smoke.dump"
INTENT_TRACE_ENV_FILE="$environment_file" scripts/backup-postgres.sh "$backup_file"

docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="truncate table change_records cascade"' >/dev/null
INTENT_TRACE_ENV_FILE="$environment_file" scripts/restore-postgres.sh "$backup_file" --confirm-replace >/dev/null

after_count=$(docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="select count(*) from change_records"')
after_activities=$(docker compose --env-file "$environment_file" exec -T postgres sh -c \
    'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="select count(*) from record_activities"')
if [ "$before_count" != "$after_count" ] || [ "$after_count" -lt 1 ]; then
    printf '%s\n' "backup 복구 뒤 기록 수가 일치하지 않습니다: $before_count -> $after_count" >&2
    exit 1
fi
if [ "$before_activities" != "$after_activities" ] || [ "$after_activities" -lt 1 ]; then
    printf '%s\n' "backup 복구 뒤 변경 이력 수가 일치하지 않습니다: $before_activities -> $after_activities" >&2
    exit 1
fi

printf '%s\n' "PostgreSQL migration·JDBC·backup·restore를 확인했습니다: 기록 $after_count 건, 변경 이력 $after_activities 건"
