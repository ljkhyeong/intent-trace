#!/usr/bin/env python3
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
import zipfile


SCRIPT = pathlib.Path(__file__).with_name("validate-release-version.py")
SPEC = importlib.util.spec_from_file_location("validate_release_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ReleaseArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary_directory.name)
        self.version = "0.7.0"
        self._write_versions()
        self.server_jar = self._write_server_jar()
        self.plugin_zip = self._write_plugin_zip()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_서버와_플러그인_산출물을_체크섬과_함께_준비한다(self) -> None:
        version = MODULE.project_version(self.root)
        server_jar = MODULE.validate_server_jar(self.root, version)
        plugin_zip = MODULE.validate_intellij_plugin(self.root, version)

        output = self.root / "release"
        artifacts = MODULE.prepare_release_artifacts(
            output,
            version,
            server_jar,
            plugin_zip,
        )

        self.assertEqual(
            [
                "intent-trace-0.7.0.jar",
                "intent-trace-intellij-0.7.0.zip",
                "intent-trace-0.7.0.jar.sha256",
                "intent-trace-intellij-0.7.0.zip.sha256",
            ],
            [path.name for path in artifacts],
        )
        checksum = artifacts[2].read_text(encoding="utf-8")
        self.assertTrue(checksum.endswith("  intent-trace-0.7.0.jar\n"))

    def test_plugin_xml_version이_다르면_거부한다(self) -> None:
        self.plugin_zip.unlink()
        self._write_plugin_zip(descriptor_version="0.7.1")

        with self.assertRaisesRegex(SystemExit, "plugin.xml version"):
            MODULE.validate_intellij_plugin(self.root, self.version)

    def _write_versions(self) -> None:
        (self.root / "intellij-plugin").mkdir(parents=True)
        (self.root / "src/main/resources").mkdir(parents=True)
        (self.root / ".codex-plugin").mkdir()
        (self.root / "build.gradle.kts").write_text(
            f'version = "{self.version}"\n',
            encoding="utf-8",
        )
        (self.root / "src/main/resources/application.properties").write_text(
            f"spring.ai.mcp.server.version={self.version}\n",
            encoding="utf-8",
        )
        (self.root / "intellij-plugin/gradle.properties").write_text(
            f"pluginVersion={self.version}\n",
            encoding="utf-8",
        )
        (self.root / ".codex-plugin/plugin.json").write_text(
            json.dumps({"version": self.version}),
            encoding="utf-8",
        )

    def _write_server_jar(self) -> pathlib.Path:
        jar = self.root / "build/libs/intent-trace.jar"
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF",
                f"Manifest-Version: 1.0\nImplementation-Version: {self.version}\n",
            )
        return jar

    def _write_plugin_zip(self, descriptor_version: str | None = None) -> pathlib.Path:
        version = descriptor_version or self.version
        plugin_jar = io.BytesIO()
        with zipfile.ZipFile(plugin_jar, "w") as archive:
            archive.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin>"
                "<id>io.intenttrace.lineintent</id>"
                f"<version>{version}</version>"
                "</idea-plugin>",
            )

        distribution = self.root / "intellij-plugin/build/distributions"
        distribution.mkdir(parents=True, exist_ok=True)
        plugin_zip = distribution / f"intent-trace-intellij-{self.version}.zip"
        with zipfile.ZipFile(plugin_zip, "w") as archive:
            archive.writestr(
                "intent-trace-intellij/lib/"
                f"intent-trace-intellij-{self.version}.jar",
                plugin_jar.getvalue(),
            )
        return plugin_zip


if __name__ == "__main__":
    unittest.main()
