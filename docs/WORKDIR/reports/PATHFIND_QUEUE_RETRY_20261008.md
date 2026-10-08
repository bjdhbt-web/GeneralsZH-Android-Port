# Pathfinding queue retry implementation

Base: `bjdhbt-web/GeneralsZH-Android-Port`, `abodeh-play-worker-v2`,
`99c148e47c48554c5ca6b1c7c321c678f5cce4aa`.

## Behavior

When the 511 usable slots of the 512-entry ring are occupied, admission returns
false without changing the ring or evicting a request. Move, attack, approach,
and safe-path requests keep their destination/type and waiting flag, and schedule
another admission attempt on the next logic frame. The existing AI scheduler
provides the order; no wall-clock/FPS decisions or threads are introduced.

A timed retry only clears its deadline after admission, or when the AI is no
longer waiting. Repeated rejections keep scheduling one frame ahead and limit
returned AI sleep to that deadline. The drain skips cancelled/deleted objects.
Accepted requests retain the existing FIFO processing and 5000-cell soft budget.
An individual path search can exceed that budget, as before; this patch does not
make the path search interruptible or implement a complete Army Load Governor.

Profiler totals: `PathfindQueueFull` counts rejected admission attempts (including
retries), `PathfindQueueAccepted` counts unique successful admissions, and
`PathfindQueueProcessed` counts waiting AI requests dispatched to pathfinding.
`PathfindQueueDepth` measures remaining ring entries. Totals reset with the
pathfinder and are deliberately excluded from snapshots/CRC. They are observable
in builds with the profiler enabled, not guaranteed in a release device log.

The shared queue changed, so the admission checks are backported to base Generals
too. Otherwise its callers would silently lose requests. No unrelated AI bucketing,
visual throttling, or Attack Ground touch changes are included.

## Validation

Run `python3 scripts/qa/pathfind-queue-regression.py` with g++ installed.
The script extracts and compiles production queue, drain-loop, timer setter,
and retry code against a small fixture, checking all four request-mode tails.
It exercises 500/1000/2000 requests, ring wrap, FIFO preservation, duplicate
admission, repeated rejection, pending deadlines, sleep while inside update,
cancelled moves, deleted objects, and repeatable processing order.

Executed with `ASAN_OPTIONS=detect_leaks=0` in this environment because
LeakSanitizer cannot inspect `/proc/<pid>/task`. AddressSanitizer and UBSan remain
enabled. No full-engine build, device run, retail replay, or multiplayer run has
been performed. The fixture does not establish full-engine determinism or
cross-version cross-play compatibility. Saturated games can evolve differently
from unpatched clients; multiplayer testing must include matching patched engines
and separate PC cross-play/replay checks.

## APK validation still needed

Build both 30 Hz and 60 Hz engines from the same tree using
`scripts/build/android/build-dual-hz.sh`, then test mass moves with 500, 1000,
and 2000 units. Repeat while attacking, cancelling/replacing commands, and
removing units; verify no pending units remain after pressure subsides. Test
Skirmish and two matching APKs, then retail replays and PC cross-play separately.

Previous Android Build #65 succeeded through native verification and failed in
Package APK. Its full job log exceeded the retrieval limit; the exact packaging
error has not yet been established. No APK is claimed for this patch and no
Actions build was started.
