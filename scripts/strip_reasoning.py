#!/usr/bin/env python3
"""Copy the results directory to DEST with every "reasoning" field removed.

JSON files containing a key named "reasoning" (at any depth) have it deleted
and are rewritten with two-space indentation. All other files, including JSON
files without the key, are copied byte for byte.

Usage: python3 scripts/strip_reasoning.py DEST [--src RESULTS_DIR]
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def strip(value):
    """Return `value` with every "reasoning" key removed, recursively."""
    if isinstance(value, dict):
        return {k: strip(v) for k, v in value.items() if k != "reasoning"}
    if isinstance(value, list):
        return [strip(v) for v in value]
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dest", type=Path, help="directory to create; must not exist")
    parser.add_argument("--src", type=Path, default=ROOT / "results",
                        help="results directory to copy (default: %(default)s)")
    args = parser.parse_args()

    src, dest = args.src.resolve(), args.dest.resolve()
    if not src.is_dir():
        sys.exit(f"source is not a directory: {src}")
    if dest.exists():
        sys.exit(f"destination already exists: {dest}")
    if dest.is_relative_to(src):
        sys.exit(f"destination is inside the source: {dest}")

    stripped = 0
    for path in sorted(src.rglob("*")):
        target = dest / path.relative_to(src)
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix == ".json":
            with path.open(encoding="utf-8") as f:
                data = json.load(f)
            cleaned = strip(data)
            if cleaned != data:
                stripped += 1
                with target.open("w", encoding="utf-8") as f:
                    json.dump(cleaned, f, indent=2, ensure_ascii=False)
                    f.write("\n")
                continue
        shutil.copy2(path, target)

    print(f"copied {src} -> {dest}; removed reasoning from {stripped} JSON file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
