#!/usr/bin/env python3
import os
import pathlib
import subprocess
import tempfile
import unittest


BACKUP_SCRIPT = pathlib.Path(__file__).with_name("backup-postgres.sh").resolve()
ENVIRONMENT_FILE = BACKUP_SCRIPT.parent.parent / ".env.team.example"


class BackupPostgresTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary_directory.name)
        self.bin_directory = self.root / "bin"
        self.bin_directory.mkdir()
        self._write_executable(
            self.bin_directory / "mkdir",
            """#!/bin/sh
/bin/mkdir "$@"
touch "$CONTROL_DIRECTORY/ready-$BACKUP_SIDE"
if [ "$BACKUP_SIDE" = A ]; then
    while [ ! -e "$CONTROL_DIRECTORY/ready-B" ]; do sleep 0.01; done
else
    while [ ! -e "$CONTROL_DIRECTORY/release-B" ]; do sleep 0.01; done
fi
""",
        )
        self._write_executable(
            self.bin_directory / "docker",
            """#!/bin/sh
if [ "$BACKUP_SIDE" = A ]; then
    printf '%s\n' '테스트 백업 A'
elif [ "$SECOND_DUMP_SUCCEEDS" = true ]; then
    printf '%s\n' '테스트 백업 B'
else
    exit 1
fi
""",
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_동시_백업은_먼저_완료한_파일을_보존한다(self) -> None:
        for second_dump_succeeds in (False, True):
            with self.subTest(second_dump_succeeds=second_dump_succeeds):
                self._assert_first_backup_is_preserved(second_dump_succeeds)

    def _assert_first_backup_is_preserved(self, second_dump_succeeds: bool) -> None:
        case = self.root / str(second_dump_succeeds).lower()
        control = case / "control"
        control.mkdir(parents=True)
        output = case / "same-output.dump"
        processes = []
        try:
            for side in ("A", "B"):
                environment = dict(
                    os.environ,
                    PATH=str(self.bin_directory) + os.pathsep + os.environ["PATH"],
                    INTENT_TRACE_ENV_FILE=str(ENVIRONMENT_FILE),
                    CONTROL_DIRECTORY=str(control),
                    BACKUP_SIDE=side,
                    SECOND_DUMP_SUCCEEDS=str(second_dump_succeeds).lower(),
                )
                processes.append(
                    subprocess.Popen(
                        ["/bin/sh", str(BACKUP_SCRIPT), str(output)],
                        env=environment,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                    )
                )

            first, second = processes
            first_stdout, first_stderr = first.communicate(timeout=10)
            self.assertEqual(0, first.returncode, first_stderr)
            self.assertIn("backup을 만들었습니다", first_stdout)
            self.assertEqual("테스트 백업 A\n", output.read_text(encoding="utf-8"))

            (control / "release-B").touch()
            second_stdout, second_stderr = second.communicate(timeout=10)
            self.assertNotEqual(0, second.returncode, second_stdout + second_stderr)
            self.assertEqual("테스트 백업 A\n", output.read_text(encoding="utf-8"))
            self.assertEqual([], list(case.glob(".same-output.dump.*")))
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    process.communicate(timeout=5)

    @staticmethod
    def _write_executable(path: pathlib.Path, content: str) -> None:
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
