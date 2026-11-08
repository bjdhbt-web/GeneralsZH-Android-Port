#!/usr/bin/env bash
# Print fingerprint of tracked C++/native build inputs at HEAD.
# Used to prevent accidental "quick APK exports" after native engine edits.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
NATIVE_PATHS=(
  Core Generals GeneralsMD GeneralsZH Dependencies Patches cmake
  CMakeLists.txt CMakePresets.json vcpkg.json vcpkg-lock.json
  references/fbraz3-dxvk android/patches
)
if ! git diff --quiet HEAD -- "${NATIVE_PATHS[@]}"; then
  echo "NATIVE_SOURCE_DIRTY: source changes require a full engine rebuild." >&2
  exit 2
fi
if [ -n "$(git ls-files --others --exclude-standard -- "${NATIVE_PATHS[@]}")" ]; then
  echo "NATIVE_SOURCE_UNTRACKED: new native files require a full engine rebuild." >&2
  exit 2
fi
git ls-tree -r HEAD -- "${NATIVE_PATHS[@]}" | sha256sum | awk '{print $1}'
