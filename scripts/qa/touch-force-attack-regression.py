#!/usr/bin/env python3
"""Compile production touch force-attack code with a minimal input fixture.

Run with Python 3 and g++. Checks rejection before picking/evaluation, mode
ownership, terrain failures, map edges, NaN/infinity, bridges and object orders.
This fixture is not a native-engine or phone gameplay test.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / 'GeneralsMD/Code/GameEngineDevice/Source/SDL3Device/GameClient/TouchInput.cpp').read_text()


def function(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


fixture = r'''
#include <cassert>
#include <cmath>
#include <cstdio>
#include <limits>
using Bool = bool; using Int = int;
#define TRUE true
#define FALSE false
struct Coord3D { float x, y, z; };
struct Region3D { Coord3D lo, hi; };
struct ICoord2D { int x, y; };
struct Drawable {} target;
struct UI {
 bool armed = true, controllable = true;
 bool isInForceAttackMode() { return armed; }
 bool areSelectedObjectsControllable() { return controllable; }
 void setForceAttackMode(bool value) { armed = value; }
} ui;
struct View {
 bool hit = true; int calls = 0;
 Coord3D point = {50, 50, 5};
 bool screenToTerrain(const ICoord2D*, Coord3D* out) {
  ++calls; *out = point; return hit;
 }
} view;
struct Terrain {
 int calls = 0;
 Region3D extent = {{0, 0, -5}, {100, 100, 10}};
 void getExtent(Region3D* out) { ++calls; *out = extent; }
} terrain;
struct CommandTranslator { enum { DO_COMMAND = 1 }; };
struct GameMessage { enum Type { MSG_INVALID, MSG_DO_FORCE_ATTACK_GROUND, MSG_DO_FORCE_ATTACK_OBJECT }; };
struct Client {
 int calls = 0; Drawable* seen = nullptr; Coord3D point = {};
 GameMessage::Type result = GameMessage::MSG_DO_FORCE_ATTACK_GROUND;
 GameMessage::Type evaluateForceAttack(Drawable* draw, const Coord3D* pos, int mode) {
  assert(mode == CommandTranslator::DO_COMMAND); ++calls; seen = draw; point = *pos; return result;
 }
} client;
UI* TheInGameUI = &ui;
View* TheTacticalView = &view;
Terrain* TheTerrainLogic = &terrain;
Client* TheGameClient = &client;
int picks = 0; Drawable* picked = nullptr;
Drawable* pickForOrder(const ICoord2D&) { ++picks; return picked; }
'''
tests = r'''
const ICoord2D pixel = {40, 50};
void reset() {
 ui = {}; view = {}; terrain = {}; client = {}; picks = 0; picked = nullptr;
 TheInGameUI = &ui; TheTacticalView = &view; TheTerrainLogic = &terrain; TheGameClient = &client;
}
void reject() {
 assert(forceAttackTap(pixel)); assert(ui.armed); assert(!client.calls && !picks);
}
int main() {
 reset(); TheInGameUI = nullptr; assert(!forceAttackTap(pixel));
 reset(); ui.armed = false; assert(!forceAttackTap(pixel)); assert(!view.calls && !picks);
 reset(); ui.controllable = false; assert(!forceAttackTap(pixel)); assert(!ui.armed && !view.calls);
 reset(); TheTacticalView = nullptr; reject();
 reset(); TheTerrainLogic = nullptr; reject(); assert(!view.calls);
 reset(); TheGameClient = nullptr; reject(); assert(!view.calls);
 reset(); view.hit = false; reject(); assert(!terrain.calls);
 float nan = std::numeric_limits<float>::quiet_NaN();
 float inf = std::numeric_limits<float>::infinity();
 for (float bad : {nan, inf, -inf}) {
  for (int component = 0; component < 3; ++component) {
   reset(); if (component == 0) view.point.x = bad;
   if (component == 1) view.point.y = bad; if (component == 2) view.point.z = bad;
   reject();
  }
  reset(); terrain.extent.hi.x = bad; reject();
  reset(); terrain.extent.lo.y = bad; reject();
 }
 reset(); terrain.extent.hi.x = 0; reject();
 reset(); terrain.extent.lo.y = 101; reject();
 for (int axis = 0; axis < 2; ++axis) for (float bad : {-0.1f, 100.1f}) {
  reset(); if (axis == 0) view.point.x = bad; else view.point.y = bad; reject();
 }
 for (float edge : {0.0f, 100.0f}) {
  reset(); view.point = {edge, edge, 1000};
  assert(forceAttackTap(pixel)); assert(!ui.armed && client.calls == 1 && picks == 1);
  assert(client.point.z == 1000); // Bridge elevation is valid above ground max Z.
 }
 reset(); picked = &target; client.result = GameMessage::MSG_DO_FORCE_ATTACK_OBJECT;
 assert(forceAttackTap(pixel)); assert(client.seen == &target && !ui.armed);
 reset(); client.result = GameMessage::MSG_INVALID;
 assert(forceAttackTap(pixel)); assert(!ui.armed && client.calls == 1);
 // Rejected tap stays owned by force attack; a corrected tap dispatches once.
 reset(); view.point.x = nan; reject(); view.point.x = 50;
 assert(forceAttackTap(pixel)); assert(client.calls == 1 && picks == 1 && !ui.armed);
 puts("Touch force-attack production-code regression passed");
}
'''
fixture = fixture.replace('#include <limits>', '#include <limits>\n#include <initializer_list>')
with tempfile.TemporaryDirectory(prefix='gx-touch-attack-') as tmp:
    cpp = Path(tmp) / 'fixture.cpp'
    binary = Path(tmp) / 'fixture'
    cpp.write_text(fixture + '\n' + function('const char *forceAttackTargetError(') + '\n' + function('Bool forceAttackTap(') + '\n' + tests)
    subprocess.run(['g++', '-std=c++17', '-g', '-O1', '-fno-omit-frame-pointer', '-fsanitize=address,undefined', str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], env=os.environ.copy(), check=True)
