# Worker6 touch force-attack validation

## Scope

Continues worker5 (`3d6efe18e3a35c120f94e3db9854d1bb3b09d7ef`).
The native force-attack tap previously accepted every successful
`screenToTerrain` result without validating its numeric values or playable
map bounds. Worker6 rejects a missing engine service, failed terrain hit,
non-finite XYZ, invalid XY map extent, or a point outside the active playable
XY extent before object picking or command evaluation. The extent includes
both edges. Ground-only Z limits are intentionally not applied because a
bridge deck can be higher than the terrain's maximum elevation.

Rejected taps remain consumed by force-attack mode and keep it armed for the
next tap. Valid taps still use `evaluateForceAttack`, its existing object/ground
messages and one-order disarm behavior. There are no new network messages,
AI scheduling changes, or wall-clock-dependent decisions. Worker5's path queue
retry remains included. The full Army Load Governor is outside this change.

`[GX-TOUCH-ATTACK]` diagnostics record rejection reasons, the screen/world point,
a dispatch before evaluation, and its returned message type. They are per
force-attack tap, not per frame. The Android stderr log records these lines.
This defensive fix does not establish the cause of the reported crash; a
remaining weapon/rendering crash needs a device log and matching native symbols.

## Regression checks

`ASAN_OPTIONS=detect_leaks=0 python3 scripts/qa/touch-force-attack-regression.py`
passed with AddressSanitizer and UBSan. The fixture compiles the actual
production validation and dispatch functions, substitutes input services, and
verifies no picking/evaluation occurs for rejected input. It covers missing
services, no hit, NaN and positive/negative infinity in XYZ, malformed extents,
XY edges and overshoot, elevated bridge hits, object orders, evaluator rejection,
mode ownership and a valid retry after rejection. Leak detection is disabled
because this environment cannot inspect the process task directory.

The existing `pathfind-queue-regression.py` also passed for both games.
These fixtures are not full-engine or device tests.

## Device acceptance

Install as an update and confirm worker6, versionCode 10311. Repeat with each
simulation engine (30 Hz and 60 Hz):

1. Select armed units, press force attack and tap bare ground. Verify a normal
   ground order is issued and the button disarms.
2. Repeat on a friendly object and near each playable map edge.
3. Aim outside playable terrain. Verify no ordinary move/selection happens and
   the mode stays armed; tap valid terrain and verify a single order.
4. Test an elevated bridge and cancelling/changing selection before a tap.
5. Repeat the original crash-producing weapon/action and export the current
   crash and stderr logs immediately after any remaining crash.
6. Stress mass moves with 500/1000/2000 units, then test a match between two
   matching engines. Do not treat a fixture pass as multiplayer validation.

Native 60 Hz and 30 Hz builds both passed with NDK 27.2.12479018 from the
same source tree. Java compilation and clean Gradle 8.9 APK assembly passed.
Verified all 17 AArch64 libraries and their non-system DT_NEEDED dependencies,
both engine byte hashes against captured builds, embedded validation markers,
unstripped DXVK binaries, API 28 minimum, version metadata, build marker 3190,
and v2 signing against the committed keystore. Phone gameplay, crash reproduction,
replays and matching-engine multiplayer remain untested. No Actions workflow
was dispatched.

## Build identity

- Remote source commit: `ed5254b9dd7866f001e25aabe6641f4f0965c5f9`
- Source tree: `8895c1a295ff3d0cf76fb4568c501e148df00f53` (identical to local commit `62e0d50`)
- APK: `Abodeh-Play-1.3.0-abodeh-worker6-vc10311.apk`, 71821274 bytes
- APK SHA-256: `8036c74f62bf374609357f9bbfe625ceef754b5fc4a95ef2a9a6198330271bd7`
- 30 Hz SHA-256: `9ea4432210550742f8aeccbd6f90732df7a51c7eff248f59a7be90a585b7efcd`
- 60 Hz SHA-256: `913ae3bbd105625643e0e0cf431ce02e2cb3609bf4a3ad2627986a32d09bfa1a`
- Signing certificate SHA-256: `644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e`

Retained per-engine native symbol tables before stripping in the accompanying
worker6 symbol archive, together with native/Java/package build logs and the
verification record. Runtime fonts, Turnip and the optional validation layer
were recovered from the hash-verified worker5 APK without changing their bytes.
