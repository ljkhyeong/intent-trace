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
        git ls-tree -r --full-tree "$revision" | shasum -a 256 | awk '{print $1}'
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
            /*|*../*|../*|*/..)
                printf '%s\n' '저장소 기준 상대 경로만 사용할 수 있습니다.' >&2
                exit 1
                ;;
        esac
        case "$start_line:$end_line" in
            *[!0-9:]*|:*)
                printf '%s\n' '줄 번호는 양의 정수여야 합니다.' >&2
                exit 1
                ;;
        esac
        if [ "$start_line" -lt 1 ] || [ "$end_line" -lt "$start_line" ]; then
            printf '%s\n' '줄 범위가 올바르지 않습니다.' >&2
            exit 1
        fi
        git show "$revision:$path" | sed -n "${start_line},${end_line}p" | shasum -a 256 | awk '{print $1}'
        ;;
    *)
        usage >&2
        exit 1
        ;;
esac
