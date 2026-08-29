#!/bin/sh
set -eu

codex_root=${CODEX_HOME:-"$HOME/.codex"}
official_validator="$codex_root/skills/.system/plugin-creator/scripts/validate_plugin.py"
local_validator="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/validate-plugin-layout.py"
validator=${CODEX_PLUGIN_VALIDATOR:-$official_validator}

if [ ! -f "$validator" ]; then
    validator=$local_validator
fi

if [ "$validator" = "$official_validator" ] && ! python3 -c 'import yaml' >/dev/null 2>&1; then
    validator=$local_validator
fi

python3 "$validator" .
