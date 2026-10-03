#!/usr/bin/env python3
"""
Prepare a user's own Command & Conquer: Generals Zero Hour installation for an
Abodeh Play Full Edition APK.

Nothing produced by this script should be committed to the public repository.
The staged files are intentionally gitignored.

Usage:
    python3 scripts/build/android/prepare-full-bundle.py "/path/to/Zero Hour"

The source folder must contain INIZH.big and INI.big.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
DEST = ASSETS / "fullgame"
MANIFEST = ASSETS / "fullgame-manifest.txt"
HEADER = "ABODEH_PLAY_FULL_BUNDLE_V1"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: prepare-full-bundle.py <Zero Hour install folder>", file=sys.stderr)
        return 2

    src = Path(sys.argv[1]).expanduser().resolve()
    for required in ("INIZH.big", "INI.big"):
        if not (src / required).is_file():
            print(f"ERROR: missing {required} in {src}", file=sys.stderr)
            return 3

    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[int, str, str]] = []
    total = 0

    files = sorted(p for p in src.rglob("*") if p.is_file())
    for i, path in enumerate(files, 1):
        rel = path.relative_to(src)
        rel_posix = rel.as_posix()
        if "\t" in rel_posix or "\n" in rel_posix or "\r" in rel_posix:
            raise SystemExit(f"Unsupported filename: {rel_posix!r}")

        dest = DEST / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)

        size = dest.stat().st_size
        digest = sha256_file(dest)
        rows.append((size, digest, rel_posix))
        total += size
        print(f"[{i}/{len(files)}] {rel_posix}  {size / 1024 / 1024:.1f} MiB")

    with MANIFEST.open("w", encoding="utf-8", newline="\n") as f:
        f.write(HEADER + "\n")
        for size, digest, rel in rows:
            f.write(f"{size}\t{digest}\t{rel}\n")

    print()
    print(f"Prepared {len(rows)} files, {total / 1024 / 1024 / 1024:.2f} GiB")
    print(f"Assets:   {DEST}")
    print(f"Manifest: {MANIFEST}")
    print("Next: build the APK normally. Do NOT commit the staged fullgame files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
