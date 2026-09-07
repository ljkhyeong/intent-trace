"""결과 누락과 실패를 통과로 집계하지 않는지 확인한다."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("test_summary", Path(__file__).with_name("test-summary.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class TestSummaryTest(unittest.TestCase):
    def test_counts_keep_skips_and_failures_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "TEST-a.xml").write_text('<testsuite tests="5" failures="1" errors="0" skipped="1"/>')
            (directory / "TEST-b.xml").write_text('<testsuite tests="2" failures="0" errors="1" skipped="0"/>')
            result = summary.summarize(directory)
            self.assertEqual((result["tests"], result["passed"], result["failures"], result["errors"], result["skipped"]), (7, 4, 1, 1, 1))

    def test_missing_results_are_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                summary.summarize(Path(temporary))

    def test_exit_status_reports_failure_or_unreadable_results(self):
        with patch("builtins.print"), patch.object(summary, "summarize") as read:
            read.return_value = {"passed": 1, "failures": 1, "errors": 0, "skipped": 0, "tests": 2, "updated": ""}
            self.assertEqual(summary.main(["server"]), 1)
            read.side_effect = ValueError("결과 없음")
            self.assertEqual(summary.main(["server"]), 1)


if __name__ == "__main__":
    unittest.main()
