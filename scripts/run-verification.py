#!/usr/bin/env python3
"""검증 결과를 수집하고 원문 출력은 저장하지 않는다."""

import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys


def git(*arguments: str) -> bytes:
    result = subprocess.run(["git", *arguments], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise ValueError("Git 저장소와 대상 커밋을 확인할 수 없습니다.")
    return result.stdout


def require_clean(revision: str) -> None:
    if git("rev-parse", "HEAD").decode().strip() != revision:
        raise ValueError("현재 HEAD와 검증할 커밋이 다릅니다.")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("커밋하지 않은 변경 또는 추적하지 않는 파일이 있어 검증을 현재 커밋에 연결할 수 없습니다.")


def redact(text: str) -> str:
    text = re.sub(r"(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|secret|private[_-]?key|token)[\"']?\s*[:=]\s*)(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|[^\s,;]+)", r"\1[REDACTED]", text)
    text = re.sub(r"(?i)\bBearer\s+[\"']?[A-Za-z0-9._~+/=-]+[\"']?", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)\b(?:ghs_[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+){2}|(?:ghp|gho|ghu|ghs|ghr|github_pat|its|itb)_[A-Za-z0-9_=-]+)", "[REDACTED]", text)
    text = re.sub(r"(?i)/(?:Users|home)/[^\s\"'`,;)\]}]+|[A-Z]:\\Users\\[^\s\"'`,;)\]}]+", "[REDACTED]", text)
    text = re.sub(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----", "[REDACTED]", text, flags=re.S)
    return text


def now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision", help="현재 HEAD의 전체 커밋 ID")
    parser.add_argument("--summary", required=True, help="결과를 과장하지 않는 짧은 검증 설명")
    try:
        separator = sys.argv.index("--")
    except ValueError:
        parser.error("실행할 명령 앞에 --가 필요합니다.")
    arguments = parser.parse_args(sys.argv[1:separator])
    command = sys.argv[separator + 1:]
    if not command:
        parser.error("실행할 명령이 없습니다.")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", arguments.revision):
        parser.error("전체 Git 커밋 ID가 필요합니다.")
    summary = redact(arguments.summary)
    command_text = redact(shlex.join(command))
    if not summary.strip() or len(summary) > 2000 or len(command_text) > 2000:
        parser.error("명령과 요약은 비어 있지 않은 2,000자 이하 텍스트여야 합니다.")
    try:
        require_clean(arguments.revision)
        snapshot = hashlib.sha256(git("-c", "core.quotePath=true", "ls-tree", "-r", "--full-tree", arguments.revision)).hexdigest()
        started = now()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=os.environ.copy())
        digest = hashlib.sha256()
        with process.stdout:
            while chunk := process.stdout.read(65536):
                digest.update(chunk)
        exit_code = process.wait()
        finished = now()
        require_clean(arguments.revision)
        result = {
            "command": command_text,
            "exitCode": exit_code,
            "startedAt": started,
            "finishedAt": finished,
            "snapshotDigest": snapshot,
            "outputDigest": digest.hexdigest(),
            "summary": summary,
            "source": "LOCAL_RUNNER_REPORTED",
        }
        print(json.dumps(result, ensure_ascii=False))
        return exit_code if exit_code >= 0 else 128 - exit_code
    except ValueError as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2
    except OSError:
        print(json.dumps({"error": "검증 명령을 실행하지 못했습니다."}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
