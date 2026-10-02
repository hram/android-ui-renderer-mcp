#!/usr/bin/env python3
"""Render every Compose demo request through the locally built MCP server."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parent
SERVER = REPOSITORY / "build/install/android-ui-renderer-mcp/bin/android-ui-renderer-mcp"
REQUESTS = ROOT / "render-requests"
RENDERS = ROOT / "renders"


def call(server, tool, arguments):
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": arguments},
    }
    server.stdin.write(json.dumps(payload) + "\n")
    server.stdin.flush()
    while True:
        response = json.loads(server.stdout.readline())
        if response.get("id") == 1:
            return response


def main():
    if not SERVER.exists():
        raise SystemExit("Run ./gradlew installDist in the repository root first.")
    prefix = sys.argv[1] if len(sys.argv) > 1 else ""
    specs = sorted(path for path in REQUESTS.glob("*.json") if path.name.startswith(prefix))
    if not specs:
        raise SystemExit(f"No request starts with {prefix!r}")

    environment = os.environ | {"PROJECT_PATH": str(ROOT)}
    server = subprocess.Popen(
        [str(SERVER)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=environment
    )
    try:
        for spec_path in specs:
            spec = json.loads(spec_path.read_text())
            response = call(server, spec["tool"], spec["arguments"])
            if "error" in response:
                raise RuntimeError(response["error"])
            result = response["result"]
            if result.get("isError"):
                raise RuntimeError(result)
            data = json.loads(result["content"][0]["text"])
            destination = RENDERS / spec_path.stem
            destination.mkdir(parents=True, exist_ok=True)
            for name, source in {
                "render.png": data["screenshotPath"],
                "view-tree.json": data["viewTreePath"],
                "request.json": data["requestPath"],
                "replay.json": data["replayPath"],
            }.items():
                target = destination / name
                shutil.copy2(source, target)
                if name == "replay.json":
                    target.write_text(target.read_text().replace(str(ROOT), "sample-compose"))
            print(f"{spec_path.stem}: {destination / 'render.png'}")
    finally:
        server.terminate()
        server.wait(timeout=10)


if __name__ == "__main__":
    main()
