#!/usr/bin/env python3
"""Compile the production queue/retry code against a minimal engine fixture.

No Android SDK required. Covers ring wrap, overload, retries, sleep, cancellation,
destroyed objects, duplicate admission, and repeatable 500/1000/2000-unit bursts.
This is not a substitute for native compilation, replays, or device tests.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


pathfinder = (ROOT / 'Core/GameEngine/Source/GameLogic/AI/AIPathfind.cpp').read_text()
queue = function(pathfinder, 'Bool Pathfinder::queueForPath(ObjectID id)')
process = function(pathfinder, 'void Pathfinder::processPathfindQueue()')
start = process.index('\tm_cumulativeCellsAllocated = 0;')
end = process.index('\tif (pathsFound > 0)', start)
process = 'void Pathfinder::drain() {\n' + process[start:end] + '\n}'
fixture = r'''
#include <algorithm>
#include <cassert>
#include <iostream>
#include <vector>
using Bool = bool; using Int = int; using UnsignedInt = unsigned; using ObjectID = unsigned;
using UpdateSleepTime = unsigned;
#define TRUE true
#define FALSE false
#define SLEEPY_AI
#define UPDATE_SLEEP_NONE 1
#define UPDATE_SLEEP(n) (static_cast<unsigned>(n))
#define PROFILER_PLOT(name, value) ((void)(value))
constexpr unsigned INVALID_ID = 0;
constexpr int PATHFIND_QUEUE_LEN = 512;
constexpr unsigned PATHFIND_CELLS_PER_FRAME = 5000;
struct Pathfinder;
struct AIUpdateInterface;
struct Object {
  unsigned id; AIUpdateInterface* ai;
  unsigned getID() const { return id; }
  AIUpdateInterface* getAIUpdateInterface() { return ai; }
};
struct GameLogic {
  unsigned frame = 100;
  std::vector<Object*> objects;
  unsigned getFrame() const { return frame; }
  Object* findObjectByID(unsigned id) {
    return id && id <= objects.size() ? objects[id-1] : nullptr;
  }
} logic;
auto* TheGameLogic = &logic;
struct AIManager { Pathfinder* finder; Pathfinder* pathfinder() { return finder; } } aiManager;
auto* TheAI = &aiManager;
std::vector<unsigned> execution;
struct AIUpdateInterface {
  Object object; bool m_waitingForPath = true, m_isInUpdate = false;
  unsigned m_queueForPathFrame = 0, wakeFrame = 10000;
  explicit AIUpdateInterface(unsigned id) : object{id, this} {}
  Object* getObject() { return &object; }
  unsigned getWakeFrame() { return wakeFrame; }
  void setWakeFrame(Object*, unsigned sleep) { wakeFrame = sleep; }
  bool isWaitingForPath() const { return m_waitingForPath; }
  void doPathfind(Pathfinder*);
  void setQueueForPathTime(Int);
  unsigned retry(unsigned subMachineSleep);
  void request();
};
struct Pathfinder {
  ObjectID m_queuedPathfindRequests[PATHFIND_QUEUE_LEN] = {};
  Int m_queuePRHead = 0, m_queuePRTail = 0;
  UnsignedInt m_cumulativeCellsAllocated = 0;
  UnsignedInt m_pathQueueFullCount = 0, m_pathQueueAcceptedCount = 0, m_pathQueueProcessedCount = 0;
  Bool queueForPath(ObjectID);
  void drain();
  unsigned depth() const { return (m_queuePRTail-m_queuePRHead+PATHFIND_QUEUE_LEN)%PATHFIND_QUEUE_LEN; }
};
void AIUpdateInterface::doPathfind(Pathfinder* finder) {
  assert(m_waitingForPath);
  m_waitingForPath = false;
  execution.push_back(object.id);
  finder->m_cumulativeCellsAllocated += 500;
}
'''
tests = r'''
std::vector<unsigned> stress(unsigned n) {
  Pathfinder finder; aiManager.finder = &finder;
  logic.frame = 100; logic.objects.clear(); execution.clear();
  std::vector<AIUpdateInterface> units; units.reserve(n);
  for (unsigned i=1; i<=n; ++i) units.emplace_back(i);
  for (auto& unit: units) { logic.objects.push_back(&unit.object); unit.request(); }
  assert(finder.depth() == 511);
  assert(finder.m_pathQueueFullCount == n-511);
  for (unsigned elapsed=0; execution.size()<n && elapsed<1000; ++elapsed) {
    finder.drain(); ++logic.frame;
    for (auto& unit: units) {
      unit.m_isInUpdate = true;
      unsigned sleep = unit.retry(10000);
      if (unit.m_queueForPathFrame) assert(sleep <= 1);
      unit.m_isInUpdate = false;
    }
  }
  assert(execution.size() == n);
  assert(finder.depth() == 0);
  assert(finder.m_pathQueueAcceptedCount == n);
  assert(finder.m_pathQueueProcessedCount == n);
  auto sorted = execution; std::sort(sorted.begin(), sorted.end());
  for (unsigned i=0; i<n; ++i) assert(sorted[i] == i+1);
  logic.objects.clear();
  return execution;
}
int main() {
  Pathfinder finder; aiManager.finder = &finder;
  for (unsigned i=1; i<=511; ++i) assert(finder.queueForPath(i));
  const auto oldQueue = std::vector<unsigned>(finder.m_queuedPathfindRequests,
                                              finder.m_queuedPathfindRequests+512);
  assert(!finder.queueForPath(512)); assert(!finder.queueForPath(513));
  assert(finder.depth() == 511 && finder.m_queuePRHead == 0 && finder.m_queuePRTail == 511);
  assert(std::equal(oldQueue.begin(), oldQueue.end(), finder.m_queuedPathfindRequests));
  assert(finder.queueForPath(300)); assert(finder.m_pathQueueAcceptedCount == 511);
  AIUpdateInterface pending(512); pending.request();
  assert(pending.m_waitingForPath && pending.m_queueForPathFrame == 101 && pending.wakeFrame == 1);
  assert(pending.retry(10000) == 1); assert(pending.m_queueForPathFrame == 101);
  ++logic.frame; pending.m_isInUpdate = true; pending.wakeFrame = 10000;
  assert(pending.retry(10000) == 1);
  assert(pending.m_queueForPathFrame == 102 && pending.wakeFrame == 10000);
  ++logic.frame; assert(pending.retry(10000) == 1); assert(pending.m_queueForPathFrame == 103);
  // Free one slot; the retry wraps the tail without losing the next head.
  finder.m_queuedPathfindRequests[0] = INVALID_ID; finder.m_queuePRHead = 1;
  ++logic.frame; pending.retry(10000);
  assert(pending.m_queueForPathFrame == 0 && finder.m_queuePRTail == 0);
  assert(finder.m_queuedPathfindRequests[511] == 512 && finder.depth() == 511);
  pending.m_queueForPathFrame = logic.frame; pending.m_waitingForPath = false;
  const auto admitted = finder.m_pathQueueAcceptedCount;
  pending.retry(10000); assert(pending.m_queueForPathFrame == 0);
  assert(finder.m_pathQueueAcceptedCount == admitted);
  // The production drain must skip a destroyed object and a cancelled move.
  finder = Pathfinder{}; AIUpdateInterface cancelled(1); cancelled.m_waitingForPath = false;
  logic.objects = {&cancelled.object, nullptr};
  assert(finder.queueForPath(1)); assert(finder.queueForPath(2)); finder.drain();
  assert(finder.depth() == 0 && finder.m_pathQueueProcessedCount == 0);
  for (unsigned n: {500U, 1000U, 2000U}) {
    // 500 does not saturate; test it separately before the overload fixture.
    if (n==500) {
      finder = Pathfinder{}; logic.objects.clear(); execution.clear();
      std::vector<AIUpdateInterface> units; units.reserve(n);
      for (unsigned i=1;i<=n;++i) units.emplace_back(i);
      for (auto& u: units) { logic.objects.push_back(&u.object); u.request(); }
      assert(finder.m_pathQueueFullCount == 0);
      for (unsigned i=0;i<50;++i) finder.drain();
      assert(execution.size() == n && finder.depth() == 0);
      logic.objects.clear();
    } else {
      auto first = stress(n); auto second = stress(n); assert(first == second);
    }
  }
  std::cout << "PASS: overload, FIFO, duplicate, wrap, retry, sleep, cancel, deletion, 500/1000/2000 stress\n";
}
'''
for game in ('GeneralsMD', 'Generals'):
    source = (ROOT / game / 'Code/GameEngine/Source/GameLogic/Object/Update/AIUpdate.cpp').read_text()
    scheduling = function(source, 'void AIUpdateInterface::setQueueForPathTime(Int frames)')
    start = source.index('\tUnsignedInt now = TheGameLogic->getFrame();')
    end = source.index('\n\tObject *obj = getObject();', start)
    retry = 'unsigned AIUpdateInterface::retry(unsigned subMachineSleep) {\n' + source[start:end] + '\nreturn subMachineSleep;\n}'
    # Each request mode must use the same tested admission/retry code.
    tails = []
    for name in ('requestPath', 'requestAttackPath', 'requestApproachPath', 'requestSafePath'):
        body = function(source, 'void AIUpdateInterface::' + name + '(')
        tails.append(body[body.index('\t// GeneralsX @bugfix Codex 08/10/2026 Retry'):body.rfind('}')].strip())
    assert len(set(tails)) == 1, f'{game}: request modes have different retry handling'
    request = 'void AIUpdateInterface::request() {\n' + tails[0] + '\n}'
    with tempfile.TemporaryDirectory(prefix='pathfind-queue-') as temp:
        cpp = Path(temp) / 'test.cpp'
        binary = Path(temp) / 'test'
        cpp.write_text(fixture + queue + process + scheduling + retry + request + tests)
        subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                        '-fsanitize=address,undefined', str(cpp), '-o', str(binary)], check=True)
        print(game, flush=True)
        subprocess.run([str(binary)], check=True)
