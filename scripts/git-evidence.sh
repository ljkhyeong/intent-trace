#!/bin/sh
set -eu

usage() {
    printf '%s\n' '사용법:'
    printf '%s\n' '  git-evidence.sh snapshot <전체-커밋-ID>'
    printf '%s\n' '  git-evidence.sh anchor <전체-커밋-ID> <상대-경로> <시작-줄> <끝-줄>'
}

require_full_revision() {
    revision=$1
    resolved=$(git rev-parse --verify "${revision}^{commit}")
    if [ "$revision" != "$resolved" ]; then
        printf '%s\n' '축약 커밋 ID 대신 전체 커밋 ID를 사용하세요.' >&2
        exit 1
    fi
}

prepare_workspace() {
    evidence_directory=$(mktemp -d "${TMPDIR:-/tmp}/intent-trace-git-evidence.XXXXXX")
    trap 'rm -rf -- "$evidence_directory"' EXIT HUP INT TERM
}

sha256_file() {
    digest_line=$(shasum -a 256 < "$1")
    printf '%s\n' "${digest_line%% *}"
}

if [ "$#" -lt 2 ]; then
    usage >&2
    exit 1
fi

operation=$1
revision=$2
require_full_revision "$revision"

case "$operation" in
    snapshot)
        if [ "$#" -ne 2 ]; then
            usage >&2
            exit 1
        fi
        # 기존 기본 출력의 해시를 유지하고 개인 파일명 표시 설정은 적용하지 않는다.
        prepare_workspace
        git -c core.quotePath=true ls-tree -r --full-tree "$revision" > "$evidence_directory/snapshot"
        sha256_file "$evidence_directory/snapshot"
        ;;
    anchor)
        if [ "$#" -ne 5 ]; then
            usage >&2
            exit 1
        fi
        path=$3
        start_line=$4
        end_line=$5
        case "$path" in
            ''|/*|\\*|[A-Za-z]:/*|[A-Za-z]:\\*|*\\*|../*|*/../*|*/..)
                printf '%s\n' '저장소 기준 상대 경로만 사용할 수 있습니다.' >&2
                exit 1
                ;;
        esac
        case "$start_line" in
            ''|*[!0-9]*)
                printf '%s\n' '줄 번호는 양의 정수여야 합니다.' >&2
                exit 1
                ;;
        esac
        case "$end_line" in
            ''|*[!0-9]*)
                printf '%s\n' '줄 번호는 양의 정수여야 합니다.' >&2
                exit 1
                ;;
        esac
        if ! { [ "$start_line" -ge 1 ] &&
            [ "$end_line" -ge "$start_line" ] &&
            [ "$end_line" -le 10000000 ]; } 2>/dev/null; then
            printf '%s\n' '줄 범위는 1~10000000 사이여야 하며 시작 줄은 끝 줄 이하여야 합니다.' >&2
            exit 1
        fi
        if [ "$(git cat-file -t "$revision:$path" 2>/dev/null)" != blob ]; then
            printf '%s\n' "해당 커밋에서 파일을 찾을 수 없습니다: $path" >&2
            exit 1
        fi
        prepare_workspace
        git show "$revision:$path" > "$evidence_directory/source"
        line_count=$(awk 'END { print NR }' "$evidence_directory/source")
        if [ "$end_line" -gt "$line_count" ]; then
            printf '%s\n' "요청한 끝 줄이 파일 범위를 벗어났습니다: $end_line > $line_count" >&2
            exit 1
        fi
        sed -n "${start_line},${end_line}p" "$evidence_directory/source" > "$evidence_directory/range"
        sha256_file "$evidence_directory/range"
        ;;
    *)
        usage >&2
        exit 1
        ;;
esac
