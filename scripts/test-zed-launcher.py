"""Zed 세션 입력 실패와 실행 환경 전달만 확인한다."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("zed-with-intent-trace.py")
spec = importlib.util.spec_from_file_location("zed_launcher", SCRIPT)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)
TOKEN = "its_" + "a" * 43


class ZedLauncherTest(unittest.TestCase):
    def test_hidden_input_failure_stops_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "zed"
            executable.write_text("#!/bin/sh\necho ZED_STARTED\n")
            executable.chmod(0o700)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            env.pop("INTENT_TRACE_SESSION_TOKEN", None)
            result = subprocess.run([sys.executable, str(SCRIPT)], input=TOKEN + "\n",
                                    capture_output=True, text=True, env=env, start_new_session=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertIn("세션 입력을 숨길 수 없습니다", result.stderr)
            self.assertNotIn("ZED_STARTED", result.stdout)
            self.assertNotIn(TOKEN, result.stdout + result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_environment_and_hidden_input_deliver_session(self):
        for from_environment in (True, False):
            with self.subTest(from_environment=from_environment), \
                    patch.dict(os.environ, {"INTENT_TRACE_SESSION_TOKEN": TOKEN if from_environment else ""}), \
                    patch.object(launcher.shutil, "which", return_value="/test/zed"), \
                    patch.object(launcher.getpass, "getpass", return_value=TOKEN) as getpass, \
                    patch.object(launcher.os, "execv") as execute, \
                    patch.object(sys, "argv", [str(SCRIPT), "project with spaces"]):
                launcher.main()
                self.assertEqual(os.environ["INTENT_TRACE_SESSION_TOKEN"], TOKEN)
                execute.assert_called_once_with("/test/zed", ["/test/zed", "project with spaces"])
                self.assertEqual(getpass.call_count, 0 if from_environment else 1)

    def test_cancel_and_end_of_input_do_not_launch(self):
        for failure in (EOFError, KeyboardInterrupt):
            with self.subTest(failure=failure), \
                    patch.dict(os.environ, {"INTENT_TRACE_SESSION_TOKEN": ""}), \
                    patch.object(launcher.shutil, "which", return_value="/test/zed"), \
                    patch.object(launcher.getpass, "getpass", side_effect=failure), \
                    patch.object(launcher.os, "execv") as execute, \
                    contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(launcher.main(), 1)
                execute.assert_not_called()
                self.assertIn("입력을 취소하거나 종료", stderr.getvalue())
                self.assertNotIn("Traceback", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
