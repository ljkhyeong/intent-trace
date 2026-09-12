#!/usr/bin/env python3
"""파일 수정 직후 검사와 작업 시작 커밋 기준의 최종 diff 검사를 실행한다."""

import argparse
import ast
import fcntl
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tomllib


ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = Path("build/feedback")


def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root, stderr=subprocess.PIPE)


def changed_files(root, base):
    tracked = git(root, "diff", "--name-only", "-z", base, "--").split(b"\0")
    staged = git(root, "diff", "--cached", "--name-only", "-z", "--").split(b"\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    return sorted({item.decode() for item in tracked + staged + untracked if item})


def snapshot(root, base):
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() if (root / name).is_file() else None
            for name in changed_files(root, base)}


def run(root, command, log, accepted=(0,)):
    result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=150)
    with log.open("ab") as output:
        output.write(("$ " + shlex.join(command) + "\n").encode() + result.stdout + b"\n")
    if result.returncode not in accepted:
        detail = result.stdout.decode(errors="replace")[-3500:]
        raise RuntimeError(f"검사 실패: {shlex.join(command)}\n{detail}\n로그: {log.relative_to(root)}")


def check_files(root, names, base, log, structural=False):
    if not names:
        return
    run(root, ["git", "diff", "--check", base, "--", *names], log)
    if structural:
        run(root, ["git", "diff", "--cached", "--check", "--", *names], log)
    untracked = set(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    tasks = {".": set(), "intellij-plugin": set()}
    for name in names:
        path = root / name
        if not path.exists():
            continue
        path.resolve().relative_to(root.resolve())
        if name in untracked:
            run(root, ["git", "diff", "--no-index", "--check", "--", "/dev/null", name], log, accepted=(0, 1))
        suffix = path.suffix
        if suffix == ".py":
            ast.parse(path.read_bytes(), filename=name)
        elif suffix == ".json":
            json.loads(path.read_text())
        elif suffix == ".toml":
            tomllib.loads(path.read_text())
        elif suffix in (".js", ".mjs"):
            run(root, ["node", "--check", name], log)
        elif suffix == ".sh":
            shell = "bash" if "bash" in path.read_text().partition("\n")[0] else "sh"
            run(root, [shell, "-n", name], log)
        project = "intellij-plugin" if name.startswith("intellij-plugin/") else "."
        if suffix == ".kt":
            tasks[project].add("compileTestKotlin" if "/src/test/" in f"/{name}" else "compileKotlin")
        elif suffix == ".kts":
            tasks[project].add("help")
    if structural and any(name.startswith(("src/", "gradle/")) or name in
                          ("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat") for name in names):
        tasks["."] = {"architectureTest"}
    for project, selected in tasks.items():
        if selected:
            run(root, ["./gradlew", "--console=plain", "-p", project, *sorted(selected)], log)


def write_diff(root, base, directory):
    patch = b""
    for label, revisions in (("작업 중 커밋", [base, "HEAD"]), ("stage", ["--cached"]), ("작업 파일", [])):
        part = git(root, "diff", "--no-ext-diff", "--no-color", *revisions, "--")
        if part:
            patch += f"\n# {label}\n".encode() + part
    for name in git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"):
        if name:
            result = subprocess.run(["git", "diff", "--no-ext-diff", "--no-color", "--no-index", "--", "/dev/null", name],
                                    cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode not in (0, 1):
                raise RuntimeError(f"새 파일 diff를 만들지 못했습니다: {name}")
            patch += result.stdout
    target = directory / "review.diff"
    target.write_bytes(patch)
    return target


def finish(root, base, directory):
    # 시작 커밋 이후의 커밋·stage·작업 파일과 미추적 파일을 한 번에 검토한다.
    base = git(root, "rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}").decode().strip()
    names = changed_files(root, base)
    patch = write_diff(root, base, directory)
    check_files(root, names, base, directory / "checks.log", structural=True)
    return f"전체 변경 {len(names)}개 검사 통과. {patch.relative_to(root)} 전체를 읽고 구조·누락·불필요한 변경을 검토하세요."


def hook(root, payload, directory):
    state_file = directory / "state.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else None
    event = payload["hook_event_name"]
    if event == "UserPromptSubmit":
        if state is None or state.get("complete"):
            base = git(root, "rev-parse", "HEAD").decode().strip()
            state = {"base": base, "files": snapshot(root, base), "complete": False}
            state_file.write_text(json.dumps(state))
        return {}
    if state is None:
        return {"systemMessage": "검사 시작 커밋이 없습니다. feedback.py finish --base <작업 시작 커밋>으로 검사하세요."}
    try:
        if event == "PostToolUse":
            current = snapshot(root, state["base"])
            names = sorted(name for name in current.keys() | state["files"].keys()
                           if current.get(name, "missing") != state["files"].get(name, "missing"))
            if not names:
                return {}
            state["files"] = current
            state_file.write_text(json.dumps(state))
            check_files(root, names, state["base"], directory / "checks.log")
            return {"hookSpecificOutput": {"hookEventName": event,
                    "additionalContext": f"변경 파일 {len(names)}개 지역 검사 통과. 동작 테스트는 변경 범위에 맞게 별도로 실행하세요."}}
        if event == "Stop":
            message = finish(root, state["base"], directory)
            state["complete"] = True
            state_file.write_text(json.dumps(state))
            return {"systemMessage": message}
    except (RuntimeError, ValueError, SyntaxError, OSError, subprocess.SubprocessError) as error:
        message = f"검증 루프: {error}"
        if event == "Stop" and payload.get("stop_hook_active"):
            return {"systemMessage": message + "\n같은 종료 검사를 무한 반복하지 않습니다. 미해결 실패를 최종 응답에 명시하세요."}
        return {"decision": "block", "reason": message}
    return {}


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    files = modes.add_parser("files", help="작성한 파일의 문법·컴파일·diff 공백 검사")
    files.add_argument("paths", nargs="+")
    final = modes.add_parser("finish", help="작업 전체 diff와 구조 규칙 검사")
    final.add_argument("--base", required=True, help="작업 시작 커밋")
    modes.add_parser("hook", help="Codex 훅의 JSON 입력 처리")
    args = parser.parse_args(arguments)
    payload = json.load(sys.stdin) if args.mode == "hook" else None
    key = hashlib.sha256(payload["session_id"].encode()).hexdigest()[:20] if payload else "manual"
    directory = ROOT / ARTIFACTS / key
    directory.mkdir(parents=True, exist_ok=True)
    with (ROOT / ARTIFACTS / "checks.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            if payload:
                print(json.dumps(hook(ROOT, payload, directory), ensure_ascii=False))
            elif args.mode == "files":
                names = [str(Path(path).resolve().relative_to(ROOT)) for path in args.paths]
                check_files(ROOT, names, "HEAD", directory / "checks.log")
                print(f"파일 {len(names)}개 지역 검사 통과")
            else:
                print(finish(ROOT, args.base, directory))
        except (RuntimeError, ValueError, SyntaxError, OSError, subprocess.SubprocessError) as error:
            print(f"검사 실패: {error}", file=sys.stderr)
            return 2 if payload else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
