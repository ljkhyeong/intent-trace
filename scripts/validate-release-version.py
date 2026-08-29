#!/usr/bin/env python3
import json
import pathlib
import re
import zipfile


def fail(message: str) -> None:
    raise SystemExit(message)


def require_match(path: pathlib.Path, pattern: str, label: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        fail(f"{label}을 찾을 수 없습니다: {path}")
    return match.group(1)


def main() -> None:
    root = pathlib.Path(__file__).resolve().parent.parent
    build_version = require_match(
        root / "build.gradle.kts",
        r'^version\s*=\s*"([^"]+)"$',
        "Gradle version",
    )
    mcp_version = require_match(
        root / "src/main/resources/application.properties",
        r"^spring\.ai\.mcp\.server\.version=(.+)$",
        "MCP server version",
    )
    plugin = json.loads((root / ".codex-plugin/plugin.json").read_text(encoding="utf-8"))
    plugin_version = plugin.get("version")

    versions = {
        "Gradle": build_version,
        "MCP server": mcp_version,
        "Codex plugin": plugin_version,
    }
    if any(version != build_version for version in versions.values()):
        details = ", ".join(f"{name}={version}" for name, version in versions.items())
        fail(f"릴리스 version이 일치하지 않습니다: {details}")

    jar = root / "build/libs/intent-trace.jar"
    if not jar.is_file():
        fail(f"릴리스 JAR을 찾을 수 없습니다: {jar}")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    implementation_version = require_manifest_value(manifest, "Implementation-Version")
    if implementation_version != build_version:
        fail(
            "JAR Implementation-Version이 Gradle version과 다릅니다: "
            f"{implementation_version} != {build_version}",
        )

    print(f"릴리스 version과 JAR manifest를 확인했습니다: {build_version}")


def require_manifest_value(manifest: str, name: str) -> str:
    prefix = f"{name}: "
    for line in manifest.splitlines():
        if line.startswith(prefix):
            return line.removeprefix(prefix).strip()
    fail(f"JAR manifest에서 {name}을 찾을 수 없습니다.")


if __name__ == "__main__":
    main()
