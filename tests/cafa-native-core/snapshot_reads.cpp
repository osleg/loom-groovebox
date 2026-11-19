#include "AudioEngineCore.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>

static int failures = 0;
static void check(bool ok, const char *message) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", message);
    failures += !ok;
}

static void render(AudioEngineCore &core, int blocks) {
    std::array<float, 256> audio{};
    for (int i = 0; i < blocks; ++i) core.render(audio.data(), 128);
}

static void snapshotReads() {
    auto core = std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    core->setFocusedTrack(0);
    check(core->getAllTrackParameters(7).empty(),
          "unpublished native parameters cannot replace saved track seven");
    bool steps[64];
    std::fill(steps, steps + 64, true);
    check(!core->getStepActiveStates(7, steps, 64) && steps[5],
          "unpublished active steps leave saved state untouched");

    // Preserve saved values when native has no published data, then push them
    // through the same queued parameter path used by session restoration.
    float savedVolume = 0.7f;
    float savedCutoff = 1.0f;
    const auto early = core->getAllTrackParameters(7);
    if (!early.empty()) {
        savedVolume = early[0];
        savedCutoff = early[1];
    }
    check(savedVolume == 0.7f && savedCutoff == 1.0f,
          "saved volume and cutoff survive unavailable native startup");
    core->setParameter(7, 0, savedVolume);
    core->setParameter(7, 1, savedCutoff);
    render(*core, 80); // Includes the native startup silent period and first publication.
    core->triggerNote(7, 60, 100);
    std::array<float, 256> audio{};
    double energy = 0.0;
    for (int i = 0; i < 40; ++i) {
        core->render(audio.data(), 128);
        for (float sample : audio) energy += sample * sample;
    }
    check(energy > 1e-8, "preserved startup settings produce an audible saved-track note");

    // A nonfocused track must have a complete published snapshot.
    core->setParameter(7, 1, 0.73f);
    core->setStep(7, 5, true, {60}, 0.8f, 1, false, 1.0f, 1.0f, false);
    check(core->getAllTrackParameters(7).empty(),
          "queued saved edit cannot expose a stale parameter snapshot during render gap");
    std::fill(steps, steps + 64, true);
    check(!core->getStepActiveStates(7, steps, 64) && steps[5],
          "queued saved edit cannot expose stale active steps");
    render(*core, 1); // Commands applied, but snapshot cadence has not published them.
    check(core->getAllTrackParameters(7).empty(),
          "applied edit stays unavailable until its snapshot is published");
    std::fill(steps, steps + 64, true);
    check(!core->getStepActiveStates(7, steps, 64) && steps[5],
          "applied step edit stays unavailable until publication");
    render(*core, 3); // Next snapshot publication.
    const auto published = core->getAllTrackParameters(7);
    const bool publishedSteps = core->getStepActiveStates(7, steps, 64);
    check(published.size() == 1024 && std::abs(published[1] - 0.73f) < 1e-6f &&
              publishedSteps && steps[5],
          "nonfocused saved parameter and active step publish together");

    core->setParameter(7, 1, 0.0f);
    core->setStep(7, 5, false, {60}, 0.8f, 1, false, 1.0f, 1.0f, false);
    render(*core, 4);
    const auto zero = core->getAllTrackParameters(7);
    const bool zeroSteps = core->getStepActiveStates(7, steps, 64);
    check(zero.size() == 1024 && zero[1] == 0.0f && zeroSteps && !steps[5],
          "published zero parameter and inactive step remain valid values");

    for (int parameter = 0; parameter < 1024; ++parameter)
        core->setParameter(7, parameter, 0.0f);
    render(*core, 4);
    const auto allZero = core->getAllTrackParameters(7);
    check(allZero.size() == 1024 &&
              std::all_of(allZero.begin(), allZero.end(), [](float value) { return value == 0.0f; }),
          "a fully zero published parameter snapshot is valid");
}

int main() {
    snapshotReads();
    return failures ? 1 : 0;
}
