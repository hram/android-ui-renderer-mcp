#!/usr/bin/env python3
"""Render every request in render-requests/ through the MCP server and copy results to renders/.

Usage (from the repository root, after ./gradlew installDist):
    python3 sample/render.py [name-prefix ...]
"""
import json
import pathlib
import shutil
import subprocess
import sys

SAMPLE = pathlib.Path(__file__).resolve().parent
ROOT = SAMPLE.parent
SERVER = ROOT / "build/install/android-ui-renderer-mcp/bin/android-ui-renderer-mcp"
REQUESTS = SAMPLE / "render-requests"
RENDERS = SAMPLE / "renders"


def main() -> int:
    if not SERVER.exists():
        print(f"MCP server not found at {SERVER}; run ./gradlew installDist first", file=sys.stderr)
        return 1
    prefixes = sys.argv[1:]
    files = [p for p in sorted(REQUESTS.glob("*.json")) if not prefixes or p.stem.startswith(tuple(prefixes))]
    RENDERS.mkdir(exist_ok=True)
    proc = subprocess.Popen(
        [str(SERVER), "--stdio"], cwd=SAMPLE, env={**_env(), "PROJECT_PATH": str(SAMPLE)},
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
    )

    def call(msg_id: int, method: str, params: dict) -> dict:
        proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params}) + "\n")
        proc.stdin.flush()
        return json.loads(proc.stdout.readline())

    call(0, "initialize", {})
    failures = 0
    for index, path in enumerate(files, start=1):
        spec = json.loads(path.read_text(encoding="utf-8"))
        reply = call(index, "tools/call", {"name": spec["tool"], "arguments": spec["arguments"]})
        result = reply.get("result", {})
        text = result.get("content", [{}])[0].get("text", "")
        if result.get("isError"):
            failures += 1
            print(f"FAIL {path.stem}: {text[:2000]}")
            continue
        data = json.loads(text)
        shutil.copyfile(data["screenshotPath"], RENDERS / f"{path.stem}.png")
        shutil.copyfile(data["viewTreePath"], RENDERS / f"{path.stem}.view-tree.json")
        t = data["timings"]
        print(f"ok   {path.stem}: renderId={data['renderId']} gradleMs={t['gradleMs']} renderMs={t['renderMs']}")
    proc.stdin.close()
    proc.wait(timeout=60)
    return 1 if failures else 0


def _env() -> dict:
    import os
    return dict(os.environ)


if __name__ == "__main__":
    sys.exit(main())
