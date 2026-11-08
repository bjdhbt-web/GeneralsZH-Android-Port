#!/usr/bin/env bash
# Quick APK-only export after Android Java/UI/resource-only changes.
# Does NOT rebuild the native 30Hz/60Hz game engines. NO GitHub Actions.
set -Eeuo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
STAMP="$REPO/build/android-vulkan/native-source-fingerprint.sha256"
if [ ! -s "$STAMP" ]; then
  echo "ERROR: No validated native build marker. Run build-latest-10320-wsl.sh once first." >&2
  exit 1
fi
PREV="$(cat "$STAMP")"
CURRENT="$(bash "$REPO/scripts/build/android/native-source-fingerprint.sh")" || {
  echo "ERROR: Native sources changed. Use full dual-Hz build, not fast APK export." >&2
  exit 1
}
if [ "$PREV" != "$CURRENT" ]; then
  echo "ERROR: C++/engine sources changed since the last full build." >&2
  echo "Use: bash scripts/build/android/build-latest-10320-wsl.sh" >&2
  exit 1
fi
NATIVE_30="$REPO/build/android-vulkan/GeneralsMD/Code/Main/libmain.so"
NATIVE_60="$REPO/build/libmain60.so"
[ -s "$NATIVE_30" ] || { echo "ERROR: 30Hz engine missing." >&2; exit 1; }
[ -s "$NATIVE_60" ] || { echo "ERROR: 60Hz engine missing." >&2; exit 1; }

export GX_ANDROID_STAGING="$REPO/android-staging"
export GX_SECOND_GAME_LIB="$NATIVE_60"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}"
GRADLE_ROOT="$HOME/.local/share/abodeh-gradle"
[ -x "$GRADLE_ROOT/gradle-8.9/bin/gradle" ] || {
  echo "ERROR: Gradle 8.9 missing; run full build once." >&2
  exit 1
}
export PATH="$GRADLE_ROOT/gradle-8.9/bin:$PATH"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
[ -x "$AAPT" ] && [ -x "$APKSIGNER" ] || {
  echo "ERROR: Android build tools missing." >&2
  exit 1
}

# Default to Gradle file version, or allow intentional override.
read -r DEFAULT_CODE DEFAULT_NAME < <(python3 - "$REPO/android/app/build.gradle" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
code = re.search(r"versionCode[^\\n]*:\\s*(\\d+)\\)", text)
name = re.search(r"versionName[^\\n]*:\\s*'([^']+)'\\)", text)
if not code or not name:
    raise SystemExit("Cannot determine default APK version")
print(code.group(1), name.group(1))
PY
)
export GX_ANDROID_VERSION_CODE="${GX_ANDROID_VERSION_CODE:-$DEFAULT_CODE}"
export GX_ANDROID_VERSION_NAME="${GX_ANDROID_VERSION_NAME:-$DEFAULT_NAME}"

echo "Fast APK-only export; using existing 30/60 Hz native engines."
./scripts/build/android/package-android-zh.sh

APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
[ -s "$APK" ] || { echo "ERROR: APK not generated." >&2; exit 1; }

# If incremental ZIP packaging creates stale embedded bytes, clean ONLY
# Android Gradle build outputs and redo the fast export, not the C++ engines.
if ! python3 - "$APK" <<'PY'
import os, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
live = sum(entry.compress_size for entry in z.infolist())
raise SystemExit(1 if os.path.getsize(sys.argv[1]) - live > 2 * 1024 * 1024 else 0)
PY
then
  echo "Stale APK entries found; rebuilding the Android package from clean output."
  rm -rf "$REPO/android/app/build"
  ./scripts/build/android/package-android-zh.sh
fi

BADGING="$("$AAPT" dump badging "$APK")"
grep -Fq "package: name='com.generalsx.zerohour' versionCode='${GX_ANDROID_VERSION_CODE}' versionName='${GX_ANDROID_VERSION_NAME}'" <<< "$BADGING" || {
  echo "ERROR: APK package ID or version unexpected." >&2
  exit 1
}
APK_FILES="$(unzip -Z1 "$APK")"
grep -Fxq 'lib/arm64-v8a/libmain.so' <<< "$APK_FILES" || exit 1
grep -Fxq 'lib/arm64-v8a/libmain60.so' <<< "$APK_FILES" || exit 1
"$APKSIGNER" verify --print-certs "$APK" || exit 1

NAME="GeneralsZH-AbodehPlay-${GX_ANDROID_VERSION_NAME}-vc${GX_ANDROID_VERSION_CODE}.apk"
DEST="$REPO/$NAME"
cp "$APK" "$DEST"
if command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  WIN_PROFILE="$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r' | tail -1 || true)"
  if [[ "$WIN_PROFILE" =~ ^[A-Za-z]:\\ ]]; then
    WIN_HOME="$(wslpath -u "$WIN_PROFILE" 2>/dev/null || true)"
    if [ -d "$WIN_HOME/Downloads" ]; then
      cp "$DEST" "$WIN_HOME/Downloads/$NAME"
      DEST="$WIN_HOME/Downloads/$NAME"
    fi
  fi
fi
echo "FAST APK EXPORT SUCCEEDED: $DEST"
ls -lh "$DEST"
sha256sum "$DEST"
echo "Note: for C++ changes run full dual-Hz build. To update a phone, the installed app signing certificate must match."
