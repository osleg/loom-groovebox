#include "AudioEngineCore.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <map>
#include <memory>

static int failures = 0;
static void check(bool ok, const char *name) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", name);
    failures += !ok;
}

int main() {
    auto core = std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    std::array<float, 256> audio{};
    for (int block = 0; block < 200; ++block) core->render(audio.data(), 128);
    core->setTempo(120);
    core->setEngineType(0, 0);
    core->setEngineType(1, 5);
    core->setPatternLength(0, 16);
    core->setPatternLength(1, 16);
    const std::map<int, float> locks{{112, .3f}, {151, .7f}};
    core->setStep(0, 0, true, {60, 64}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {.1f, .8f}, &locks);
    core->setStep(1, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {.4f}, &locks);
    core->render(audio.data(), 128);
    const auto &mainStep = core->getTracks()[0].sequencer.getSteps()[0];
    const auto &drumStep = core->getTracks()[1].drumSequencers[0].getSteps()[0];
    check(mainStep.parameterLocks == locks && drumStep.parameterLocks == locks,
          "complete locks reach main and routed drum lanes");
    check(mainStep.notes.size() == 2 && mainStep.notes[0].gate == .1f &&
          mainStep.notes[1].gate == .8f && drumStep.notes[0].gate == .4f,
          "each chord note retains its own gate");

    core->setPlaying(true);
    bool bothStarted = false;
    for (int block = 0; block < 100 && !bothStarted; ++block) {
        core->render(audio.data(), 128);
        bool first = false, second = false;
        for (const auto &note : core->getTracks()[0].mActiveNotes) {
            first |= note.active && note.note == 60;
            second |= note.active && note.note == 64;
        }
        bothStarted = first && second;
    }
    check(bothStarted, "chord starts through the production renderer");
    for (int block = 0; block < 10; ++block) core->render(audio.data(), 128);
    bool shortAlive = false, longAlive = false;
    for (const auto &note : core->getTracks()[0].mActiveNotes) {
        shortAlive |= note.active && note.note == 60;
        longAlive |= note.active && note.note == 64;
    }
    check(!shortAlive && longAlive, "short chord note releases while long note sustains");

    const std::map<int, float> replacement{{120, .5f}};
    core->setStep(0, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {}, &replacement);
    core->setStep(1, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {}, &replacement);
    core->render(audio.data(), 128);
    check(core->getTracks()[0].sequencer.getSteps()[0].parameterLocks == replacement &&
          core->getTracks()[1].drumSequencers[0].getSteps()[0].parameterLocks == replacement,
          "complete lock edits replace old locks on both lanes");
    const std::map<int, float> cleared;
    core->setStep(0, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {}, &cleared);
    core->setStep(1, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {}, &cleared);
    core->render(audio.data(), 128);
    check(core->getTracks()[0].sequencer.getSteps()[0].parameterLocks.empty() &&
          core->getTracks()[1].drumSequencers[0].getSteps()[0].parameterLocks.empty(),
          "empty complete lock maps clear both lanes");
    core->setStep(0, 0, true, {60}, .8f, 1, false, 1.f, 1.f, false,
                  0.f, {}, {}, {NAN}, &locks);
    core->render(audio.data(), 128);
    check(core->getTracks()[0].sequencer.getSteps()[0].parameterLocks.empty(),
          "invalid note gate rejects the complete update");
    core->setPlaying(false);
    const std::map<int, float> validLocks{{112, .25f}};
    core->setStep(0, 0, false, {61}, .6f, 2, false, 1.f, .6f, false,
                  0.f, {}, {}, {.3f}, &validLocks);
    core->render(audio.data(), 128);
    const Step beforeInvalidLock = core->getTracks()[0].sequencer.getSteps()[0];
    const std::map<int, float> invalidLocks{{2500, .9f}};
    core->setStep(0, 0, false, {62}, .9f, 3, true, .5f, .8f, false,
                  0.f, {}, {}, {.4f}, &invalidLocks);
    core->render(audio.data(), 128);
    const Step &afterInvalidLock = core->getTracks()[0].sequencer.getSteps()[0];
    check(afterInvalidLock.parameterLocks == beforeInvalidLock.parameterLocks &&
          afterInvalidLock.notes.size() == beforeInvalidLock.notes.size() &&
          !afterInvalidLock.notes.empty() &&
          afterInvalidLock.notes[0].note == beforeInvalidLock.notes[0].note &&
          afterInvalidLock.notes[0].gate == beforeInvalidLock.notes[0].gate &&
          afterInvalidLock.gate == beforeInvalidLock.gate &&
          afterInvalidLock.ratchet == beforeInvalidLock.ratchet,
          "out-of-range lock ID rejects the entire queued step update");
    return failures ? 1 : 0;
}
