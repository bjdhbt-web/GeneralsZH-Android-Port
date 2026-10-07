#!/usr/bin/env bash
# Build both Android engines with the large-army pathfinder backpressure fix.
#
# The source edit is exact/fail-closed and temporary: this wrapper restores the
# original files on success, failure, Ctrl-C or termination. If the source was
# already patched before this script started, it leaves it patched.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
PATCHER="${SCRIPT_DIR}/apply-pathfinder-backpressure.py"
APPLIED_BY_WRAPPER=0

cleanup_pathfinder_patch() {
    local status=$?
    trap - EXIT INT TERM
    if [ "${APPLIED_BY_WRAPPER}" -eq 1 ]; then
        echo "=== restoring source after pathfinder-safe build ==="
        python3 "${PATCHER}" --revert || {
            echo "ERROR: automatic pathfinder source restore failed" >&2
            exit 3
        }
    fi
    exit "${status}"
}
trap cleanup_pathfinder_patch EXIT INT TERM

STATE="$(python3 "${PATCHER}" --state)"
case "${STATE}" in
    original)
        echo "=== applying campaign/skirmish pathfinder backpressure fix ==="
        python3 "${PATCHER}" --apply
        APPLIED_BY_WRAPPER=1
        ;;
    patched)
        echo "=== pathfinder backpressure fix already present; leaving it in place ==="
        ;;
    *)
        echo "ERROR: unexpected patch state: ${STATE}" >&2
        exit 2
        ;;
esac

# Guard the compatibility decisions before spending time on a native build.
grep -Fq 'mode == GAME_SINGLE_PLAYER || mode == GAME_SKIRMISH' \
    "${REPO}/GeneralsMD/Code/GameEngine/Source/GameLogic/Object/Update/AIUpdate.cpp"
grep -Fq 'PATHFIND_CELLS_PER_FRAME = 5000' \
    "${REPO}/Core/GameEngine/Source/GameLogic/AI/AIPathfind.cpp"
grep -Fq 'PATHFIND_QUEUE_LEN=512' \
    "${REPO}/Core/GameEngine/Include/GameLogic/AIPathfind.h"

echo "=== compatibility guards passed: queue=512, cells/frame=5000, retry=single-player only ==="
"${SCRIPT_DIR}/build-dual-hz.sh"

echo "=== pathfinder-safe dual-Hz build completed ==="
