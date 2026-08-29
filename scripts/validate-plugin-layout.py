#!/usr/bin/env python3
import json
import pathlib
import sys


def fail(message: str) -> None:
    raise SystemExit(message)


def read_json(path: pathlib.Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"JSON 파일을 읽을 수 없습니다: {path}: {exception}")
    if not isinstance(value, dict):
        fail(f"JSON 최상위 값은 object여야 합니다: {path}")
    return value


def resolve_file(root: pathlib.Path, value: object, field: str) -> pathlib.Path:
    if not isinstance(value, str) or not value.strip():
        fail(f"plugin.json의 {field} 경로가 비어 있습니다.")
    path = (root / value).resolve()
    if root not in path.parents or not path.is_file():
        fail(f"plugin.json의 {field} 파일을 찾을 수 없습니다: {value}")
    return path


def validate_skill(path: pathlib.Path) -> None:
    text = path.read_text(encoding="utf-8")
    parts = text.split("---", 2)
    if len(parts) != 3 or parts[0].strip():
        fail(f"skill frontmatter가 올바르지 않습니다: {path}")
    metadata = {}
    for line in parts[1].splitlines():
        key, separator, value = line.partition(":")
        if separator and key in {"name", "description"}:
            metadata[key] = value.strip().strip('"').strip("'")
    if metadata.get("name") != path.parent.name:
        fail(f"skill 이름과 directory가 다릅니다: {path}")
    if not metadata.get("description"):
        fail(f"skill description이 비어 있습니다: {path}")


def main() -> None:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    manifest = read_json(root / ".codex-plugin" / "plugin.json")
    for field in ("name", "version", "description"):
        if not isinstance(manifest.get(field), str) or not manifest[field].strip():
            fail(f"plugin.json의 {field} 값이 비어 있습니다.")

    interface = manifest.get("interface")
    if not isinstance(interface, dict):
        fail("plugin.json의 interface 값은 object여야 합니다.")
    default_prompts = interface.get("defaultPrompt")
    if (
        not isinstance(default_prompts, list)
        or not 1 <= len(default_prompts) <= 3
        or any(not isinstance(prompt, str) or not prompt.strip() for prompt in default_prompts)
    ):
        fail("plugin.json의 interface.defaultPrompt는 비어 있지 않은 문자열 1~3개여야 합니다.")

    mcp_path = resolve_file(root, manifest.get("mcpServers"), "mcpServers")
    mcp = read_json(mcp_path)
    if not isinstance(mcp.get("mcpServers"), dict) or not mcp["mcpServers"]:
        fail("MCP server 설정이 비어 있습니다.")

    skills_root = (root / str(manifest.get("skills", "./skills"))).resolve()
    skill_files = sorted(skills_root.glob("*/SKILL.md"))
    if not skill_files:
        fail("검증할 skill이 없습니다.")
    for skill_file in skill_files:
        validate_skill(skill_file)

    hooks_path = root / "hooks" / "hooks.json"
    if hooks_path.exists():
        hooks = read_json(hooks_path)
        if not isinstance(hooks.get("hooks"), dict):
            fail("hooks.json의 hooks 값은 object여야 합니다.")
        session_start = hooks["hooks"].get("SessionStart")
        if not isinstance(session_start, list) or not session_start:
            fail("hooks.json의 SessionStart 설정이 비어 있습니다.")
        if not any("clear" in str(entry.get("matcher", "")).split("|") for entry in session_start if isinstance(entry, dict)):
            fail("SessionStart matcher에는 clear source가 필요합니다.")

    print(f"Plugin layout validation passed: {root}")


if __name__ == "__main__":
    main()
