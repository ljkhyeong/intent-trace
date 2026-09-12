"""작업 중 커밋·미추적 파일 누락과 실패 검사의 반복을 막는다."""

import importlib.util
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("feedback", Path(__file__).with_name("feedback.py"))
feedback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feedback)


class FeedbackTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.test")
        self.git("config", "user.name", "Test")
        (self.root / ".gitignore").write_text("build/\n")
        (self.root / "old.py").write_text("value = 1\n")
        self.git("add", ".")
        self.git("commit", "-qm", "initial")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.output = self.root / "build/feedback/test"
        self.output.mkdir(parents=True)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE).decode()

    def event(self, name, **extra):
        return feedback.hook(self.root, {"hook_event_name": name, **extra}, self.output)

    def test_final_diff_includes_committed_staged_working_and_new_files(self):
        (self.root / "old.py").write_text("value = 2\n")
        self.git("commit", "-qam", "intermediate")
        (self.root / "staged.py").write_text("value = 3\n")
        self.git("add", "staged.py")
        (self.root / "old.py").write_text("value = 4\n")
        (self.root / "new file.py").write_text("value = 5\n")
        feedback.finish(self.root, self.base, self.output)
        diff = (self.output / "review.diff").read_text()
        self.assertEqual(feedback.changed_files(self.root, self.base), ["new file.py", "old.py", "staged.py"])
        for change in ("-value = 1", "+value = 4", "+value = 3", "+value = 5"):
            self.assertIn(change, diff)

    def test_new_file_whitespace_and_syntax_errors_fail(self):
        path = self.root / "new file.py"
        path.write_text("value = 1  \n")
        with self.assertRaises(RuntimeError):
            feedback.check_files(self.root, [path.name], self.base, self.output / "checks.log")
        path.write_text("value = (\n")
        with self.assertRaises(SyntaxError):
            feedback.check_files(self.root, [path.name], self.base, self.output / "checks.log")

    def test_staged_change_is_visible_even_when_working_file_matches_head(self):
        path = self.root / "old.py"
        path.write_text("value = 2  \n")
        self.git("add", "old.py")
        path.write_text("value = 1\n")
        self.assertEqual(feedback.changed_files(self.root, self.base), ["old.py"])
        with self.assertRaises(RuntimeError):
            feedback.finish(self.root, self.base, self.output)
        self.assertIn("+value = 2", (self.output / "review.diff").read_text())

    def test_read_only_tools_do_not_repeat_failed_checks(self):
        self.event("UserPromptSubmit")
        (self.root / "old.py").write_text("value = (\n")
        with patch.object(feedback, "check_files", side_effect=SyntaxError("syntax")) as check:
            self.assertEqual(self.event("PostToolUse")["decision"], "block")
            self.assertEqual(self.event("PostToolUse"), {})
            check.assert_called_once()
        (self.root / "old.py").write_text("value = 3\n")
        self.assertIn("hookSpecificOutput", self.event("PostToolUse"))

    def test_stop_blocks_failure_once_and_preserves_start_commit(self):
        self.event("UserPromptSubmit")
        (self.root / "old.py").write_text("value = 2\n")
        self.git("commit", "-qam", "intermediate")
        self.event("UserPromptSubmit")
        state = json.loads((self.output / "state.json").read_text())
        self.assertEqual(state["base"], self.base)
        with patch.object(feedback, "finish", side_effect=RuntimeError("architecture failure")):
            self.assertEqual(self.event("Stop")["decision"], "block")
            self.assertIn("미해결 실패", self.event("Stop", stop_hook_active=True)["systemMessage"])
        self.assertFalse(json.loads((self.output / "state.json").read_text())["complete"])
        self.event("Stop")
        self.event("UserPromptSubmit")
        self.assertEqual(json.loads((self.output / "state.json").read_text())["base"], self.git("rev-parse", "HEAD").strip())

    def test_deleted_server_file_still_runs_architecture_check(self):
        path = self.root / "src/main/kotlin/Removed.kt"
        path.parent.mkdir(parents=True)
        path.write_text("class Removed\n")
        self.git("add", ".")
        self.git("commit", "-qm", "server")
        base = self.git("rev-parse", "HEAD").strip()
        path.unlink()
        with patch.object(feedback, "run") as run:
            feedback.finish(self.root, base, self.output)
        self.assertTrue(any("architectureTest" in call.args[1] for call in run.call_args_list))

    def test_renamed_file_and_repeated_finish_keep_diff_stable(self):
        self.git("mv", "old.py", "renamed.py")
        feedback.finish(self.root, self.base, self.output)
        before = (self.output / "review.diff").read_bytes()
        feedback.finish(self.root, self.base, self.output)
        self.assertEqual(before, (self.output / "review.diff").read_bytes())
        self.assertIn(b"rename to renamed.py", before)

    def test_hook_cli_returns_json_without_storing_prompt_or_transcript(self):
        def invoke(event):
            payload = {"session_id": "test-session", "hook_event_name": event,
                       "prompt": "do-not-store-prompt", "transcript_path": "do-not-read-transcript"}
            output = io.StringIO()
            with patch.object(feedback, "ROOT", self.root), patch("sys.stdin", io.StringIO(json.dumps(payload))), contextlib.redirect_stdout(output):
                self.assertEqual(feedback.main(["hook"]), 0)
            return json.loads(output.getvalue())
        self.assertEqual(invoke("UserPromptSubmit"), {})
        (self.root / "old.py").write_text("value = 2\n")
        self.assertIn("hookSpecificOutput", invoke("PostToolUse"))
        self.assertIn("systemMessage", invoke("Stop"))
        for path in (self.root / feedback.ARTIFACTS).rglob("state.json"):
            self.assertNotIn("do-not-", path.read_text())


if __name__ == "__main__":
    unittest.main()
