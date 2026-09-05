#!/usr/bin/env python3
"""저장된 Gradle XML 결과를 작업별로 집계한다. 테스트를 실행하지 않는다."""

import argparse
from datetime import datetime
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


RESULTS = {
    "server": "build/test-results/test",
    "focused": "build/test-results/focusedTest",
    "postgres": "build/test-results/postgresTest",
    "intellij": "intellij-plugin/build/test-results/test",
}


def summarize(directory: Path) -> dict:
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError("결과 파일 없음")
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for path in files:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.attrib[key])
    return {
        **counts,
        "passed": counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"],
        "updated": datetime.fromtimestamp(max(path.stat().st_mtime for path in files)).astimezone().isoformat(timespec="seconds"),
    }


def main(arguments=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("targets", nargs="+", choices=RESULTS, help="확인할 검증 작업")
    args = parser.parse_args(arguments)
    root = Path(__file__).resolve().parent.parent
    failed = False
    print("저장된 결과입니다. 현재 코드·환경의 검증 여부는 해당 Gradle 실행 결과와 함께 확인하세요.")
    for target in dict.fromkeys(args.targets):
        try:
            result = summarize(root / RESULTS[target])
        except (OSError, ET.ParseError, ValueError, KeyError):
            print(f"{target}: 결과 파일이 없거나 읽을 수 없습니다.")
            failed = True
            continue
        print(f"{target}: 통과 {result['passed']} / 실패 {result['failures']} / 오류 {result['errors']} / 건너뜀 {result['skipped']} / 전체 {result['tests']} | 결과 시각 {result['updated']}")
        failed |= result["failures"] + result["errors"] > 0
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
