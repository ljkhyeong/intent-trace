#!/bin/sh
set -eu

codex_root=${CODEX_HOME:-"$HOME/.codex"}
validator=${CODEX_PLUGIN_VALIDATOR:-"$codex_root/skills/.system/plugin-creator/scripts/validate_plugin.py"}

if [ ! -f "$validator" ]; then
    printf '%s\n' "Codex 플러그인 검증기를 찾을 수 없습니다: $validator" >&2
    exit 1
fi

if ! python3 -c 'import yaml' >/dev/null 2>&1; then
    printf '%s\n' '플러그인 검증에 PyYAML이 필요합니다: python3 -m pip install PyYAML' >&2
    exit 1
fi

python3 "$validator" .
