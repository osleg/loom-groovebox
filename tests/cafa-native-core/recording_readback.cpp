#include "AudioEngineCore.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>

static bool hasRecordedNote(const std::vector<float> &batch, int track,
                            int lane, int note, bool finalGate) {
  for (size_t i = 0; i + 6 <= batch.size();) {
    int t = (int)batch[i++];
    int d = (int)batch[i++];
    i++; // step
    bool active = batch[i++] != 0;
    float gate = batch[i++];
    int count = (int)batch[i++];
    if (count < 0 || i + count * 4 > batch.size()) return false;
    for (int n = 0; n < count; ++n) {
      int pitch = (int)batch[i++];
      float velocity = batch[i++];
      float offset = batch[i++];
      float noteGate = batch[i++];
      if (t == track && d == lane && active && pitch == note &&
          std::abs(velocity - 100.f / 127.f) < .001f &&
          offset >= 0 && offset < 1 &&
          (!finalGate || (gate >= .1f && noteGate >= .1f)))
        return true;
    }
  }
  return false;
}

static float recordedGate(const std::vector<float> &batch, int track, int lane,
                          int note) {
  for (size_t i = 0; i + 6 <= batch.size();) {
    int t = (int)batch[i++], d = (int)batch[i++];
    i += 3; // step, active, shared gate
    int count = (int)batch[i++];
    if (count < 0 || i + count * 4 > batch.size()) return -1.f;
    for (int n = 0; n < count; ++n) {
      int pitch = (int)batch[i++];
      i += 2; // velocity and offset
      float gate = batch[i++];
      if (t == track && d == lane && pitch == note) return gate;
    }
  }
  return -1.f;
}

int main() {
  auto core = std::make_unique<AudioEngineCore>();
  std::array<float, 256> audio{};
  for (int i = 0; i < 200; ++i) core->render(audio.data(), 128);
  core->setEngineType(1, 5);
  core->render(audio.data(), 128);
  core->setPlaying(true);
  core->setIsRecording(true);
  core->triggerNote(0, 64, 100);
  core->triggerNote(1, 69, 100); // Drum lane 9, beyond the old gate cutoff.
  core->render(audio.data(), 128);
  auto started = core->drainRecordedSteps();
  bool on = hasRecordedNote(started, 0, -1, 64, false) &&
            hasRecordedNote(started, 1, 9, 69, false);
  for (int i = 0; i < 30; ++i) core->render(audio.data(), 128);
  core->triggerNote(0, 67, 100);
  for (int i = 0; i < 20; ++i) core->render(audio.data(), 128);
  auto ongoing = core->drainRecordedSteps();
  bool separateHeldGates = recordedGate(ongoing, 0, -1, 64) >
      recordedGate(ongoing, 0, -1, 67) + .25f;
  core->setPlaying(false); // STOP must finalize before resetting the clock.
  auto ended = core->drainRecordedSteps();
  bool emptyAfterDrain = core->drainRecordedSteps().empty();
  bool off = hasRecordedNote(ended, 0, -1, 64, true) &&
             hasRecordedNote(ended, 1, 9, 69, true);
  bool separateFinalGates = recordedGate(ended, 0, -1, 64) >
      recordedGate(ended, 0, -1, 67) + .25f;
  bool stoppedRecording = !core->getIsRecording() && !core->getIsPlaying();
  std::printf("%s live recording readback across tracks and lanes\n",
              on ? "PASS" : "FAIL");
  std::printf("%s changes drain exactly once\n",
              emptyAfterDrain ? "PASS" : "FAIL");
  std::printf("%s disarm retains final gates\n", off ? "PASS" : "FAIL");
  std::printf("%s transport stop disarms before resetting the gate clock\n",
              stoppedRecording ? "PASS" : "FAIL");
  std::printf("%s held chord notes retain separate gates on save and disarm\n",
              separateHeldGates && separateFinalGates ? "PASS" : "FAIL");
  return on && emptyAfterDrain && off && stoppedRecording &&
      separateHeldGates && separateFinalGates ? 0 : 1;
}
