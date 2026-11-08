#!/usr/bin/env bash
# One-command, NO GitHub Actions, 30 Hz + 60 Hz Android APK builder.
# Run inside Ubuntu WSL2 (or native Ubuntu) on a machine with internet access.
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive

BRANCH="fix/abodeh-play-compat-20261107"
REPO_URL="https://github.com/bjdhbt-web/GeneralsZH-Android-Port.git"
WORKDIR="${GX_REPO_DIR:-$HOME/GeneralsZH-Android-Port}"
APK_NAME="GeneralsZH-AbodehPlay-1.3.0-compat1-vc10320.apk"
EXPECTED_CODE=10320
EXPECTED_NAME="1.3.0-abodeh-compat1"
LOG="${GX_BUILD_LOG:-$HOME/generals-android-10320-build.log}"

say() { printf '\n==> %s\n' "$*"; }
retry() {
  local n=0 max=10 delay=10
  until "$@"; do
    n=$((n+1))
    if [ "$n" -ge "$max" ]; then
      echo "ERROR: command failed after $max attempts: $*" >&2
      return 1
    fi
    echo "Connection interrupted. Retry $n/$max in $delay seconds (existing downloads/cache are kept)." >&2
    sleep "$delay"
  done
}
download_resume() {
  local url="$1" output="$2"
  # -C - requests remaining bytes; partial ZIP stays on disk between attempts.
  retry curl --fail --location --continue-at - --retry 10 \
    --retry-all-errors --retry-delay 8 --connect-timeout 45 \
    --output "$output" "$url"
}
# Slow networks: do not abort a transfer merely because the transfer rate is low.
export GIT_TERMINAL_PROMPT=0
git config --global http.lowSpeedLimit 0
git config --global http.lowSpeedTime 999999
git config --global http.version HTTP/1.1
fail() { echo "ERROR: $*" >&2; exit 1; }
trap 'echo "Build stopped near line $LINENO. Check $LOG (when created). No old Android app was removed." >&2' ERR

command -v sudo >/dev/null || fail "Ubuntu/Debian with sudo required"
[ -f /etc/debian_version ] || fail "Ubuntu/Debian Linux required (WSL2 Ubuntu is supported)"
say "Installing host build dependencies (no GitHub Actions)"
retry sudo apt-get -o Acquire::Retries=10 update
retry sudo apt-get -o Acquire::Retries=10 install -y --no-install-recommends \
  git ca-certificates curl unzip zip python3 python3-pip python3-venv \
  build-essential cmake ninja-build meson pkg-config \
  glslang-tools ccache openjdk-17-jdk-headless \
  autoconf automake libtool nasm yasm patchelf file wget

say "Checking free disk space"
free_kb=$(df -Pk "$HOME" | awk 'NR==2 {print $4}')
if [ "$free_kb" -lt 41943040 ]; then
  echo "WARNING: less than 40 GiB free. Native C++/NDK builds can exhaust disk space." >&2
fi

say "Checking out exact compatibility branch"
if [ ! -d "$WORKDIR/.git" ]; then
  [ ! -e "$WORKDIR" ] || fail "$WORKDIR exists and is not a Git checkout"
  retry git clone --filter=blob:none --branch "$BRANCH" --single-branch "$REPO_URL" "$WORKDIR"
else
  [ -z "$(git -C "$WORKDIR" status --porcelain)" ] || fail "Working tree has changes; refusing to overwrite local edits."
  retry git -C "$WORKDIR" fetch origin "$BRANCH"
  git -C "$WORKDIR" switch "$BRANCH"
  git -C "$WORKDIR" merge --ff-only FETCH_HEAD
fi
cd "$WORKDIR"
retry git submodule update --init references/fbraz3-dxvk
retry git -C references/fbraz3-dxvk submodule update --init --depth 1

say "Checking the source and the historical APK signing key"
python3 scripts/qa/abodeh-android-compat-preflight.py
git rev-parse --short HEAD

say "Installing pinned Gradle 8.9 if needed"
GRADLE_ROOT="$HOME/.local/share/abodeh-gradle"
if [ ! -x "$GRADLE_ROOT/gradle-8.9/bin/gradle" ]; then
  mkdir -p "$GRADLE_ROOT"
  download_resume "https://services.gradle.org/distributions/gradle-8.9-bin.zip" \
    "$GRADLE_ROOT/gradle-8.9-bin.zip"
  unzip -tq "$GRADLE_ROOT/gradle-8.9-bin.zip" >/dev/null \
    || fail "Gradle ZIP damaged. Remove only $GRADLE_ROOT/gradle-8.9-bin.zip and rerun."
  unzip -q -o "$GRADLE_ROOT/gradle-8.9-bin.zip" -d "$GRADLE_ROOT"
  rm -f "$GRADLE_ROOT/gradle-8.9-bin.zip"
fi
export PATH="$GRADLE_ROOT/gradle-8.9/bin:$PATH"

# Adapt native compilation to actual WSL resources instead of hard-coding -j3.
# Keep 2 GiB free and budget ~2 GiB per C++ compiler process to reduce OOM.
# nproc reflects WSL CPU quotas; MemAvailable reflects memory that can be used.
cpu_count="$(nproc)"
mem_available_kib="$(awk '/^MemAvailable:/ { print $2; exit }' /proc/meminfo)"
mem_available_kib="${mem_available_kib:-4194304}"
memory_jobs=$(( (mem_available_kib - 2097152) / 2097152 ))
(( memory_jobs > 0 )) || memory_jobs=1
cpu_jobs=$(( cpu_count > 1 ? cpu_count - 1 : 1 ))
auto_jobs=$(( memory_jobs < cpu_jobs ? memory_jobs : cpu_jobs ))
(( auto_jobs > 0 )) || auto_jobs=1
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-$auto_jobs}"
export VCPKG_MAX_CONCURRENCY="${VCPKG_MAX_CONCURRENCY:-$auto_jobs}"
# Reuse native compiler output across builds; default 8 GiB on sufficiently
# large storage. This is a CACHE, not additional RAM or a rebuilt dependency.
export GX_CCACHE_SIZE="${GX_CCACHE_SIZE:-8G}"
say "Build resources: WSL CPUs=$cpu_count; available RAM=$((mem_available_kib / 1024)) MiB"
say "Parallel native jobs=$CMAKE_BUILD_PARALLEL_LEVEL; vcpkg jobs=$VCPKG_MAX_CONCURRENCY; ccache=$GX_CCACHE_SIZE"
echo "Override if desired: CMAKE_BUILD_PARALLEL_LEVEL=8 VCPKG_MAX_CONCURRENCY=8 bash scripts/build/android/build-latest-10320-wsl.sh"
export GX_ANDROID_VERSION_CODE="$EXPECTED_CODE"
export GX_ANDROID_VERSION_NAME="$EXPECTED_NAME"

say "Ensuring Ninja >=1.13.2 is installed without relying on GitHub Releases"
# Ubuntu 24.04 ships Ninja 1.11.1, but the pinned vcpkg version requires
# Ninja 1.13.2 and GitHub Releases may return 504 on unstable connections.
# PyPI has a small Linux x86_64 Ninja 1.13.2 wheel (~183 KB).
NINJA_VENV="$HOME/.cache/generalszh-build-tools-venv"
if [ ! -x "$NINJA_VENV/bin/python" ]; then
  python3 -m venv "$NINJA_VENV"
fi
if [ ! -x "$NINJA_VENV/bin/ninja" ] ||
   ! "$NINJA_VENV/bin/ninja" --version | grep -q '^1\.13\.2'; then
  retry "$NINJA_VENV/bin/python" -m pip install --only-binary=:all: \
    --retries 10 --timeout 60 ninja==1.13.2
fi
export PATH="$NINJA_VENV/bin:$PATH"
# Keep this PATH when vcpkg runs its child tool detection, so it can
# use the available pinned Ninja instead of downloading it from GitHub.
export VCPKG_KEEP_ENV_VARS="${VCPKG_KEEP_ENV_VARS:+$VCPKG_KEEP_ENV_VARS;}PATH"
ninja --version

say "Building BOTH native game engines; saving the full log to $LOG"
./scripts/build/android/build-dual-hz.sh 2>&1 | tee "$LOG"

APK="$WORKDIR/GeneralsXZH-android-local.apk"
[ -s "$APK" ] || fail "Build returned without an APK at $APK"

say "Verifying package name, version, native libraries, and APK signature"
AAPT="/opt/android-sdk/build-tools/35.0.0/aapt"
APKSIGNER="/opt/android-sdk/build-tools/35.0.0/apksigner"
[ -x "$AAPT" ] || fail "Android aapt missing at $AAPT"
[ -x "$APKSIGNER" ] || fail "Android apksigner missing at $APKSIGNER"
BADGING="$("$AAPT" dump badging "$APK")"
grep -q "package: name='com.generalsx.zerohour' versionCode='${EXPECTED_CODE}' versionName='${EXPECTED_NAME}'" <<< "$BADGING" \
  || fail "Built APK has an unexpected application ID or version; not publishing"
# Capture ZIP listing before grep, avoiding pipefail/SIGPIPE on large APKs.
APK_ENTRIES="$(unzip -Z1 "$APK")"
grep -Fxq 'lib/arm64-v8a/libmain.so' <<< "$APK_ENTRIES" \
  || fail "30 Hz engine missing"
grep -Fxq 'lib/arm64-v8a/libmain60.so' <<< "$APK_ENTRIES" \
  || fail "60 Hz engine missing"
"$APKSIGNER" verify --print-certs "$APK" || fail "APK signing validation failed"

DEST="$WORKDIR/$APK_NAME"
cp "$APK" "$DEST"
# Copy to the signed-in Windows account's Downloads folder if WSL integration exists.
if command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  WIN_PROFILE=$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r' | tail -1 || true)
  if [[ "$WIN_PROFILE" =~ ^[A-Za-z]:\\ ]]; then
    WIN_HOME=$(wslpath -u "$WIN_PROFILE" 2>/dev/null || true)
    if [ -d "$WIN_HOME/Downloads" ]; then
      cp "$DEST" "$WIN_HOME/Downloads/$APK_NAME"
      DEST="$WIN_HOME/Downloads/$APK_NAME"
    fi
  fi
fi

say "SUCCESS: $DEST"
ls -lh "$DEST"
sha256sum "$DEST"
printf '\nIMPORTANT: in-place updates also require the same certificate as the APK currently installed on the phone. Do not uninstall the previous app.\n'
