#!/usr/bin/env python3
"""Apply/revert the Android large-army pathfinder backpressure fix.

This intentionally patches the two large legacy translation units at build time.
The ChatGPT GitHub connector used to prepare this branch cannot safely replace
300+ KiB source files as partial edits, so this helper makes exact, fail-closed
text replacements.  It refuses to write if upstream context has changed.

The gameplay change is deliberately restricted to campaign/skirmish.  LAN,
Internet and replay keep the retail queue-overflow behaviour so an Android-only
build does not introduce a lockstep/cross-play divergence.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PATHFIND = ROOT / "Core/GameEngine/Source/GameLogic/AI/AIPathfind.cpp"
AIUPDATE = ROOT / "GeneralsMD/Code/GameEngine/Source/GameLogic/Object/Update/AIUpdate.cpp"

PATHFIND_OLD = '''\tif (nextSlot==m_queuePRHead) {
\t\tDEBUG_CRASH(("Ran out of pathfind queue slots."));
\t\treturn false;
\t}
'''

PATHFIND_NEW = '''\tif (nextSlot==m_queuePRHead) {
\t\t// GeneralsX @bugfix Abodeh Play 07/10/2026 Queue saturation is recoverable.
\t\t// Release builds already return false here; do not turn the same condition into
\t\t// a fatal debug-only crash. AIUpdate decides whether retrying is safe for the
\t\t// current game mode.
\t\tDEBUG_LOG(("Pathfind queue saturated; request deferred by caller when safe."));
\t\treturn false;
\t}
'''

AIUPDATE_ANCHOR_OLD = '''#define SLEEPY_AI


//-------------------------------------------------------------------------------------------------
'''

AIUPDATE_ANCHOR_NEW = '''#define SLEEPY_AI

namespace
{
// GeneralsX @bugfix Abodeh Play 07/10/2026 Keep path-queue overflow recovery out
// of replay and multiplayer. Those modes must preserve the retail request/drop
// sequence unless every lockstep peer runs the exact same logic revision.
Bool shouldRetrySaturatedPathQueue()
{
\tconst GameMode mode = TheGameLogic->getGameMode();
\treturn mode == GAME_SINGLE_PLAYER || mode == GAME_SKIRMISH;
}

// Spread a burst over eight deterministic logic frames. ObjectID is part of the
// synchronized simulation, so this uses no RNG and gives repeatable single-player
// behaviour while keeping the 512-entry queue and 5000-cells/frame CPU budget.
Int saturatedPathQueueRetryFrames(ObjectID id)
{
\treturn 1 + static_cast<Int>(static_cast<UnsignedInt>(id) & 7U);
}

// TRUE means a caller with an existing delayed retry may clear it. FALSE means
// this helper re-armed the retry after a full queue and it must be left intact.
Bool queuePathWithBackpressure(AIUpdateInterface *ai)
{
\tconst ObjectID id = ai->getObject()->getID();
\tif (TheAI->pathfinder()->queueForPath(id))
\t\treturn TRUE;

\tif (shouldRetrySaturatedPathQueue())
\t{
\t\tai->setQueueForPathTime(saturatedPathQueueRetryFrames(id));
\t\treturn FALSE;
\t}

\t// Preserve retail/cross-play behaviour outside campaign/skirmish: when the
\t// queue is full the request is dropped, exactly as before this fix.
\treturn TRUE;
}
}


//-------------------------------------------------------------------------------------------------
'''

SCHEDULED_OLD = '''\t\tif (now >= m_queueForPathFrame)
\t\t{
\t\t\tTheAI->pathfinder()->queueForPath(getObject()->getID());
\t\t\tsetQueueForPathTime(0);
\t\t}
'''

SCHEDULED_NEW = '''\t\tif (now >= m_queueForPathFrame)
\t\t{
\t\t\tif (queuePathWithBackpressure(this))
\t\t\t\tsetQueueForPathTime(0);
\t\t}
'''

DIRECT_OLD = '\tTheAI->pathfinder()->queueForPath(getObject()->getID());\n'
DIRECT_NEW = '\tqueuePathWithBackpressure(this);\n'
DIRECT_EXPECTED = 4


class PatchError(RuntimeError):
    pass


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise PatchError(f"cannot read {path}: {exc}") from exc


def classify(pathfind: str, aiupdate: str) -> str:
    old_pf = PATHFIND_OLD in pathfind
    new_pf = PATHFIND_NEW in pathfind
    old_anchor = AIUPDATE_ANCHOR_OLD in aiupdate
    new_anchor = AIUPDATE_ANCHOR_NEW in aiupdate
    old_sched = SCHEDULED_OLD in aiupdate
    new_sched = SCHEDULED_NEW in aiupdate
    old_direct = aiupdate.count(DIRECT_OLD)
    new_direct = aiupdate.count(DIRECT_NEW)

    original = (
        old_pf and not new_pf
        and old_anchor and not new_anchor
        and old_sched and not new_sched
        and old_direct == DIRECT_EXPECTED
        and new_direct == 0
    )
    patched = (
        new_pf and not old_pf
        and new_anchor and not old_anchor
        and new_sched and not old_sched
        and old_direct == 0
        and new_direct == DIRECT_EXPECTED
    )

    if original:
        return "original"
    if patched:
        return "patched"

    details = (
        f"pathfind(old={old_pf}, new={new_pf}); "
        f"helper(old={old_anchor}, new={new_anchor}); "
        f"scheduled(old={old_sched}, new={new_sched}); "
        f"direct(old={old_direct}, new={new_direct}, expected={DIRECT_EXPECTED})"
    )
    raise PatchError("source context is mixed or changed; refusing to patch: " + details)


def transformed(pathfind: str, aiupdate: str, apply: bool) -> tuple[str, str]:
    state = classify(pathfind, aiupdate)
    if apply and state == "patched":
        return pathfind, aiupdate
    if not apply and state == "original":
        return pathfind, aiupdate

    if apply:
        pf_from, pf_to = PATHFIND_OLD, PATHFIND_NEW
        anchor_from, anchor_to = AIUPDATE_ANCHOR_OLD, AIUPDATE_ANCHOR_NEW
        sched_from, sched_to = SCHEDULED_OLD, SCHEDULED_NEW
        direct_from, direct_to = DIRECT_OLD, DIRECT_NEW
    else:
        pf_from, pf_to = PATHFIND_NEW, PATHFIND_OLD
        anchor_from, anchor_to = AIUPDATE_ANCHOR_NEW, AIUPDATE_ANCHOR_OLD
        sched_from, sched_to = SCHEDULED_NEW, SCHEDULED_OLD
        direct_from, direct_to = DIRECT_NEW, DIRECT_OLD

    new_pf = pathfind.replace(pf_from, pf_to, 1)
    new_ai = aiupdate.replace(anchor_from, anchor_to, 1)
    new_ai = new_ai.replace(sched_from, sched_to, 1)
    if new_ai.count(direct_from) != DIRECT_EXPECTED:
        raise PatchError(
            f"expected {DIRECT_EXPECTED} direct queue calls, found {new_ai.count(direct_from)}"
        )
    new_ai = new_ai.replace(direct_from, direct_to)

    expected_state = "patched" if apply else "original"
    if classify(new_pf, new_ai) != expected_state:
        raise PatchError("post-transform validation failed")
    return new_pf, new_ai


def write_pair(pathfind: str, aiupdate: str) -> None:
    # All transformations are validated in memory before either file is touched.
    try:
        PATHFIND.write_text(pathfind, encoding="utf-8", newline="")
        AIUPDATE.write_text(aiupdate, encoding="utf-8", newline="")
    except OSError as exc:
        raise PatchError(f"write failed: {exc}") from exc


def main() -> int:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--state", action="store_true", help="print original/patched")
    group.add_argument("--check", action="store_true", help="validate source context")
    group.add_argument("--apply", action="store_true", help="apply the fix")
    group.add_argument("--revert", action="store_true", help="revert the fix")
    args = parser.parse_args()

    try:
        pf = read_text(PATHFIND)
        ai = read_text(AIUPDATE)
        state = classify(pf, ai)

        if args.state:
            print(state)
            return 0
        if args.check:
            print(f"PATHFINDER_BACKPRESSURE_STATE={state}")
            return 0

        want_apply = bool(args.apply)
        new_pf, new_ai = transformed(pf, ai, want_apply)
        if (new_pf, new_ai) != (pf, ai):
            write_pair(new_pf, new_ai)
        print("PATHFINDER_BACKPRESSURE_STATE=" + ("patched" if want_apply else "original"))
        return 0
    except PatchError as exc:
        print(f"PATHFINDER_BACKPRESSURE_ERROR={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
