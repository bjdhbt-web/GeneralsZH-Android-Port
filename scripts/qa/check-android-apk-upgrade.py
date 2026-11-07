#!/usr/bin/env python3
"""Compare a prospective Android APK with the version installed on a USB-connected phone.

This tool does NOT uninstall/clear the old app. `--install` is opt-in and
permitted only after checking application ID, version, signing key and device
Android/ABI compatibility. Android SDK platform-tools and build-tools required.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

PACKAGE = "com.generalsx.zerohour"


def tool(name: str) -> str | None:
    suffixes = [name, name + ".exe", name + ".bat"]
    for item in suffixes:
        result = shutil.which(item)
        if result:
            return result
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.name == "nt":
        roots.append(str(Path.home() / "AppData/Local/Android/Sdk"))
    else:
        roots.append(str(Path.home() / "Android/Sdk"))
    for value in filter(None, roots):
        sdk = Path(value)
        if name == "adb":
            candidates = [sdk / "platform-tools" / file for file in ("adb.exe", "adb")]
        else:
            buildtools = sorted((sdk / "build-tools").glob("*"), reverse=True)
            candidates = [folder / file for folder in buildtools
                          for file in (name + ".bat", name + ".exe", name)]
        for path in candidates:
            if path.is_file():
                return str(path)
    return None


def command(args: list[str], *, required: bool = True) -> str:
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            encoding="utf-8", errors="replace", check=False)
    output = ((result.stdout or "") + "\n" + (result.stderr or "")).strip()
    if required and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}\n{output}")
    return output


def certificate(apksigner: str, file: Path) -> str:
    output = command([apksigner, "verify", "--print-certs", str(file)])
    value = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)",
                      output, re.I)
    if not value:
        raise RuntimeError("Could not read signer certificate from " + str(file))
    return value.group(1).lower().replace(":", "")


def apk_info(aapt: str, file: Path) -> tuple[str, int, int | None]:
    output = command([aapt, "dump", "badging", str(file)])
    package = re.search(r"package:\s+name='([^']+)'\s+versionCode='(\d+)'", output)
    if not package:
        raise RuntimeError("Cannot read package ID or versionCode from the APK.")
    sdk_match = re.search(r"sdkVersion:'(\d+)'", output)
    return (package.group(1), int(package.group(2)),
            int(sdk_match.group(1)) if sdk_match else None)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="New APK to test (existing local file)")
    parser.add_argument("--serial", help="ADB serial if more than one device is connected")
    parser.add_argument("--install", action="store_true",
                        help="Run adb install -r ONLY if all checks pass")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("APK file does not exist: " + str(args.apk))
    adb, aapt, apksigner = (tool("adb"), tool("aapt"), tool("apksigner"))
    missing = [n for n, v in (("adb", adb), ("aapt", aapt),
                               ("apksigner", apksigner)) if not v]
    if missing:
        print("Missing Android SDK tool(s): " + ", ".join(missing), file=sys.stderr)
        print("Install Android SDK platform-tools and build-tools, or set ANDROID_HOME.")
        return 2
    serial = ["-s", args.serial] if args.serial else []
    run = lambda *parts, required=True: command([adb, *serial, *parts], required=required)

    devices = command([adb, "devices"]).splitlines()[1:]
    connected = [row.split()[0] for row in devices if re.search(r"\sdevice(?:\s|$)", row)]
    if args.serial:
        if args.serial not in connected:
            print("The selected ADB device is not connected/authorized.")
            return 2
    elif len(connected) != 1:
        print("Connect and authorize exactly one phone (or pass --serial).")
        print("ADB devices:", connected)
        return 2

    package, version, minimum_sdk = apk_info(aapt, args.apk.resolve())
    new_cert = certificate(apksigner, args.apk.resolve())
    print("New APK: package =", package, "| versionCode =", version)
    print("New APK: cert SHA-256 =", new_cert)

    problems = []
    if package != PACKAGE:
        problems.append("WRONG_PACKAGE: use the existing application ID " + PACKAGE)
    sdk_text = run("shell", "getprop", "ro.build.version.sdk").strip()
    abis = run("shell", "getprop", "ro.product.cpu.abilist").strip()
    print("Phone: SDK =", sdk_text, "| ABIs =", abis)
    if minimum_sdk is not None and sdk_text.isdigit() and int(sdk_text) < minimum_sdk:
        problems.append(f"OLDER_ANDROID: device SDK {sdk_text} < APK minSdk {minimum_sdk}")
    if "arm64-v8a" not in abis.split(","):
        problems.append("ABI_INCOMPATIBLE: this build only supports arm64-v8a")

    installed = run("shell", "pm", "path", PACKAGE, required=False)
    paths = re.findall(r"(?m)^package:(\S+\.apk)\s*$", installed)
    if paths:
        print("Installed package detected.")
        dumpsys = run("shell", "dumpsys", "package", PACKAGE)
        old_version = re.search(r"\bversionCode=(\d+)", dumpsys)
        if old_version:
            old_code = int(old_version.group(1))
            print("Installed versionCode =", old_code)
            if version < old_code:
                problems.append(f"VERSION_DOWNGRADE: {version} < {old_code}. Rebuild with a higher versionCode.")
        else:
            problems.append("UNKNOWN_OLD_VERSION: cannot read installed versionCode")

        with tempfile.TemporaryDirectory(prefix="abodeh-android-") as directory:
            old_apk = Path(directory) / "installed-base.apk"
            pull = run("pull", paths[0], str(old_apk), required=False)
            if old_apk.is_file():
                try:
                    old_cert = certificate(apksigner, old_apk)
                    print("Installed cert SHA-256 =", old_cert)
                    if new_cert != old_cert:
                        problems.append("SIGNATURE_MISMATCH: the APK must be signed with the OLD installed app key.")
                except RuntimeError as error:
                    problems.append("SIGNATURE_UNKNOWN: " + str(error))
            else:
                problems.append("SIGNATURE_UNKNOWN: cannot pull installed APK to compare certificates: " + pull)
    else:
        print("No existing", PACKAGE, "app found for the current Android user.")
        print("A clean install may be possible, but no in-place update can be verified.")

    if problems:
        print("\nUPDATE BLOCKED. The current app/data was NOT touched.")
        for issue in problems:
            print(" -", issue)
        print("Do NOT uninstall the old app. Obtain its original signing key or fix the build/version.")
        return 1
    print("\nPASS: package/signature/version/SDK/ABI pre-install checks.")
    if args.install:
        print("Trying non-destructive install with adb install -r ...")
        output = run("install", "-r", str(args.apk.resolve()), required=False)
        print(output)
        if "Success" not in output.splitlines():
            print("Installation failed; keep this exact ADB output for diagnosis.")
            return 1
        print("Install succeeded; launch the app and test game files/runtime separately.")
    else:
        print("Nothing installed. Add --install to attempt an in-place update.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(2)
