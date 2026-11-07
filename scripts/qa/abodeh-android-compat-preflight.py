#!/usr/bin/env python3
"""Offline, source-only Android APK compatibility preflight (no SDK or CI needed).

Checks obvious install/login regressions before an expensive native rebuild.
This does not verify an APK, signature, native dependency or real phone launch.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
manifest_path = ROOT / "android/app/src/main/AndroidManifest.xml"
manager = (ROOT / "android/app/src/main/java/com/generalsx/zerohour/SubscriptionManager.java").read_text(encoding="utf-8")

errors = []


def check(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


version = re.search(r"(?m)^\s*versionCode\s*\(.*:\s*(\d+)\)", gradle)
check(bool(version), "Missing default versionCode")
if version:
    check(int(version.group(1)) > 10311,
          "APK versionCode must be newer than the installed Worker6 (10311)")
check("1.3.0-abodeh-compat1" in gradle,
      "Unexpected versionName (check this preflight when shipping an update)")
check(bool(re.search(r"\bminSdk\s+28\b", gradle)), "Native build requires API 28+")
check(bool(re.search(r"\btargetSdk\s+35\b", gradle)), "Unexpected targetSdk")
check("abiFilters 'arm64-v8a'" in gradle, "Only arm64-v8a native libraries are supported")
check(bool(re.search(r"buildFeatures\s*\{\s*buildConfig\s+true", gradle)),
      "BuildConfig must be generated for AGP 8+ Java packaging")
check('https://abodeh-play-api.bjdhbt.workers.dev/v1' in manager,
      "Abodeh Play must use its independent Worker v2 endpoint")
check('syscomx.net' not in manager, "Retired Syscom backend must not be used")

android = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(manifest_path)
root = tree.getroot()
features = root.findall("uses-feature")
gles = [f for f in features if f.get(android+"glEsVersion") == "0x00030000"]
vulkan = [f for f in features if f.get(android+"name") == "android.hardware.vulkan.version"]
check(len(gles) == 1 and gles[0].get(android+"required") == "true",
      "GLES 3.0 is the expected required renderer feature")
check(len(vulkan) == 1 and vulkan[0].get(android+"required") == "false",
      "Vulkan must remain optional, especially on Mali phones")

permissions = [p.get(android+"name") for p in root.findall("uses-permission")]
check(len(permissions) == len(set(permissions)), "Duplicate permissions in AndroidManifest")
check("android.permission.INTERNET" in permissions, "Worker login needs INTERNET")

application = root.find("application")
launchers = []
if application is not None:
    for activity in application.findall("activity"):
        for intent in activity.findall("intent-filter"):
            actions = {x.get(android+"name") for x in intent.findall("action")}
            cats = {x.get(android+"name") for x in intent.findall("category")}
            if ("android.intent.action.MAIN" in actions
                    and "android.intent.category.LAUNCHER" in cats):
                launchers.append(activity.get(android+"name"))
check(len(launchers) == 1, "Expected one launcher activity, got: "+str(launchers))

if errors:
    for message in errors:
        print("FAIL:", message, file=sys.stderr)
    raise SystemExit(1)
print("PASS: source preflight (ARM64/API 28+/version/Worker v2/GLES/manifest).")
print("NOTE: an APK build and on-device install/launch tests are still required.")
