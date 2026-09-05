#!/usr/bin/env python3
"""세션을 파일이나 명령 인자에 넣지 않고 새 Zed 프로세스에 전달한다."""
import getpass
import os
import re
import shutil
import sys
import warnings


def main():
    executable = shutil.which("zed")
    if executable is None:
        print("Zed를 설치하고 명령 팔레트에서 cli: install을 실행하세요.", file=sys.stderr)
        return 1
    token = os.environ.get("INTENT_TRACE_SESSION_TOKEN")
    if not token:
        try:
            # 숨긴 입력을 제공할 수 없으면 표준 입력으로 대체하기 전에 중단한다.
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                token = getpass.getpass("IntentTrace its_ 세션: ")
        except getpass.GetPassWarning:
            print("세션 입력을 숨길 수 없습니다. 터미널에서 실행하거나 INTENT_TRACE_SESSION_TOKEN 환경 변수로 세션을 전달하세요.", file=sys.stderr)
            return 1
        except (EOFError, KeyboardInterrupt):
            print("세션 입력을 취소하거나 종료했습니다. Zed를 실행하지 않았습니다.", file=sys.stderr)
            return 1
    if not re.fullmatch(r"its_[A-Za-z0-9_-]{32,128}", token):
        print("로그인 화면의 its_ 세션을 확인하세요.", file=sys.stderr)
        return 1
    os.environ["INTENT_TRACE_SESSION_TOKEN"] = token
    os.execv(executable, [executable, *(sys.argv[1:] or ["."])])


if __name__ == "__main__":
    raise SystemExit(main())
