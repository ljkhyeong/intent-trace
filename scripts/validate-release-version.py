#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import pathlib
import re
import shutil
import xml.etree.ElementTree as ElementTree
import zipfile


def fail(message: str) -> None:
    raise SystemExit(message)


def require_match(path: pathlib.Path, pattern: str, label: str) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        fail(f"{label}을 찾을 수 없습니다: {path}")
    return match.group(1)


def project_version(root: pathlib.Path) -> str:
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
    intellij_plugin_version = require_match(
        root / "intellij-plugin/gradle.properties",
        r"^pluginVersion=(.+)$",
        "IntelliJ plugin version",
    )
    plugin = json.loads((root / ".codex-plugin/plugin.json").read_text(encoding="utf-8"))
    plugin_version = plugin.get("version")

    versions = {
        "Gradle": build_version,
        "MCP server": mcp_version,
        "Codex plugin": plugin_version,
        "IntelliJ plugin": intellij_plugin_version,
    }
    if any(version != build_version for version in versions.values()):
        details = ", ".join(f"{name}={version}" for name, version in versions.items())
        fail(f"릴리스 version이 일치하지 않습니다: {details}")
    return build_version


def validate_server_jar(root: pathlib.Path, version: str) -> pathlib.Path:
    jar = root / "build/libs/intent-trace.jar"
    if not jar.is_file():
        fail(f"릴리스 JAR을 찾을 수 없습니다: {jar}")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    implementation_version = require_manifest_value(manifest, "Implementation-Version")
    if implementation_version != version:
        fail(
            "JAR Implementation-Version이 Gradle version과 다릅니다: "
            f"{implementation_version} != {version}",
        )
    return jar


def validate_intellij_plugin(root: pathlib.Path, version: str) -> pathlib.Path:
    plugin_zip = (
        root
        / "intellij-plugin/build/distributions"
        / f"intent-trace-intellij-{version}.zip"
    )
    if not plugin_zip.is_file():
        fail(f"IntelliJ 플러그인 ZIP을 찾을 수 없습니다: {plugin_zip}")

    plugin_jar = (
        "intent-trace-intellij/lib/"
        f"intent-trace-intellij-{version}.jar"
    )
    with zipfile.ZipFile(plugin_zip) as archive:
        try:
            plugin_jar_bytes = archive.read(plugin_jar)
        except KeyError:
            fail(f"플러그인 ZIP에서 설치 JAR을 찾을 수 없습니다: {plugin_jar}")

    with zipfile.ZipFile(io.BytesIO(plugin_jar_bytes)) as archive:
        try:
            plugin_xml = archive.read("META-INF/plugin.xml")
        except KeyError:
            fail("IntelliJ 플러그인 JAR에서 META-INF/plugin.xml을 찾을 수 없습니다.")

    descriptor = ElementTree.fromstring(plugin_xml)
    descriptor_id = descriptor.findtext("id")
    descriptor_version = descriptor.findtext("version")
    if descriptor_id != "io.intenttrace.lineintent":
        fail(f"IntelliJ 플러그인 ID가 다릅니다: {descriptor_id}")
    if descriptor_version != version:
        fail(
            "IntelliJ plugin.xml version이 프로젝트 version과 다릅니다: "
            f"{descriptor_version} != {version}",
        )
    return plugin_zip


def write_checksum(path: pathlib.Path) -> pathlib.Path:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    checksum = path.with_name(f"{path.name}.sha256")
    checksum.write_text(f"{digest.hexdigest()}  {path.name}\n", encoding="utf-8")
    return checksum


def prepare_release_artifacts(
    output_directory: pathlib.Path,
    version: str,
    server_jar: pathlib.Path,
    plugin_zip: pathlib.Path,
) -> list[pathlib.Path]:
    output_directory.mkdir(parents=True, exist_ok=True)
    outputs = [
        output_directory / f"intent-trace-{version}.jar",
        output_directory / f"intent-trace-intellij-{version}.zip",
    ]
    for source, destination in zip((server_jar, plugin_zip), outputs, strict=True):
        shutil.copy2(source, destination)
    return [*outputs, *(write_checksum(path) for path in outputs)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-tag")
    parser.add_argument("--prepare-directory", type=pathlib.Path)
    args = parser.parse_args()

    root = pathlib.Path(__file__).resolve().parent.parent
    version = project_version(root)
    server_jar = validate_server_jar(root, version)

    if args.prepare_directory is not None and args.release_tag is None:
        fail("배포 파일 준비에는 --release-tag가 필요합니다.")

    if args.release_tag is None:
        print(f"릴리스 version과 JAR manifest를 확인했습니다: {version}")
        return

    if version.endswith("-SNAPSHOT"):
        fail(f"개발 version으로 릴리스할 수 없습니다: {version}")
    expected_tag = f"v{version}"
    if args.release_tag != expected_tag:
        fail(f"Git tag와 프로젝트 version이 다릅니다: {args.release_tag} != {expected_tag}")

    plugin_zip = validate_intellij_plugin(root, version)
    if args.prepare_directory is None:
        print(f"릴리스 tag와 서버·IntelliJ 산출물을 확인했습니다: {args.release_tag}")
        return

    artifacts = prepare_release_artifacts(
        args.prepare_directory,
        version,
        server_jar,
        plugin_zip,
    )
    names = ", ".join(path.name for path in artifacts)
    print(f"릴리스 파일을 준비했습니다: {names}")


def require_manifest_value(manifest: str, name: str) -> str:
    prefix = f"{name}: "
    for line in manifest.splitlines():
        if line.startswith(prefix):
            return line.removeprefix(prefix).strip()
    fail(f"JAR manifest에서 {name}을 찾을 수 없습니다.")


if __name__ == "__main__":
    main()
