#include "AudioEngineCore.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

static int failures = 0;
static void check(bool ok, const char *message) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", message);
    if (!ok) ++failures;
}

static void patternLengths(int drumType) {
    auto core = std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    core->setTempo(240);
    constexpr std::array<int, 8> lengths{4, 17, 33, 64, 4, 17, 33, 64};
    for (int t = 0; t < 8; ++t) {
        core->setEngineType(t, t < 4 ? 0 : drumType);
        // Reproduce restoring a saved exact per-track length through the queued command.
        core->setPatternLength(t, lengths[t]);
    }
    std::vector<float> audio(128 * 2);
    core->render(audio.data(), 128); // Apply queued configuration before transport.
    core->setPlaying(true);
    std::array<std::array<bool, 64>, 8> seen{};
    std::array<int, 8> previous{}, wraps{};
    std::array<std::array<std::array<bool, 64>, 16>, 4> drumSeen{};
    std::array<std::array<int, 16>, 4> drumPrevious{}, drumWraps{};
    bool bounded = true, finite = true, ordered = true;
    bool drumPublication = true;
    // > 2 complete cycles of the longest 64-step pattern at 240 BPM/44.1 kHz.
    for (int block = 0; block < 3300; ++block) {
        core->render(audio.data(), 128);
        for (float value : audio) finite &= std::isfinite(value);
        const auto &tracks = core->getTracks(); // Single-threaded test, never concurrent with render.
        for (int t = 0; t < 8; ++t) {
            int step = core->getCurrentStep(t);
            bounded &= step >= 0 && step < lengths[t];
            if (step >= 0 && step < 64) seen[t][step] = true;
            if (step != previous[t]) {
                ordered &= step == (previous[t] + 1) % lengths[t];
                if (step == 0) ++wraps[t];
            }
            previous[t] = step;
            if (t < 4) continue;
            for (int lane = 0; lane < 16; ++lane) {
                int ds = tracks[t].drumSequencers[lane].getCurrentStepIndex();
                bounded &= ds >= 0 && ds < lengths[t];
                drumPublication &= core->getCurrentStep(t, lane) == ds;
                if (ds >= 0 && ds < 64) drumSeen[t-4][lane][ds] = true;
                if (ds != drumPrevious[t-4][lane]) {
                    ordered &= ds == (drumPrevious[t-4][lane] + 1) % lengths[t];
                    if (ds == 0) ++drumWraps[t-4][lane];
                }
                drumPrevious[t-4][lane] = ds;
            }
        }
    }
    bool complete = true;
    for (int t = 0; t < 8; ++t) {
        complete &= wraps[t] >= 2;
        for (int step = 0; step < lengths[t]; ++step) complete &= seen[t][step];
        if (t >= 4) for (int lane = 0; lane < 16; ++lane) {
            complete &= drumWraps[t-4][lane] >= 2;
            for (int step = 0; step < lengths[t]; ++step) complete &= drumSeen[t-4][lane][step];
        }
    }
    std::printf("\nEngine type %d (four melodic tracks + four drum tracks)\n", drumType);
    check(bounded, "4/17/33/64-step melodic and all 16 drum-lane indices stay in range");
    check(ordered, "all steps increment and wrap at exact lengths, including first cycle");
    check(drumPublication, "selected drum-lane getter matches current rendered lane, without lag");
    check(complete, "every expected step visited and every track/lane loops at least twice");
    check(finite, "rendered stereo samples remain finite at 44.1 kHz");
}

static void selectedLaneDivergence(int drumType) {
    auto core = std::make_unique<AudioEngineCore>();
    bool initialized = true;
    for (int t = 0; t < 8; ++t) {
        initialized &= core->getCurrentStep(t) == 0;
        for (int lane = 0; lane < 16; ++lane) initialized &= core->getCurrentStep(t, lane) == 0;
    }
    check(initialized, "all playheads initialize to zero before first render");
    core->updateSampleRate(44100);
    core->setTempo(240);
    core->setEngineType(0, drumType);
    core->setPatternLength(0, 4);
    // Only lane 1 skips step 1. Its rendered playhead must diverge from lane 0
    // and the track-level sequencer; a getter ignoring lane would fail.
    core->setStep(0, 1, false, {61}, 0.8f, 1, false, 1.0f, 1.0f, true);
    std::vector<float> audio(128 * 2);
    core->render(audio.data(), 128);
    core->setPlaying(true);
    bool matches = true, diverged = false, skipped = true;
    for (int block = 0; block < 400; ++block) {
        core->render(audio.data(), 128);
        const auto &track = core->getTracks()[0];
        for (int lane = 0; lane < 16; ++lane)
            matches &= core->getCurrentStep(0, lane) == track.drumSequencers[lane].getCurrentStepIndex();
        diverged |= core->getCurrentStep(0, 0) != core->getCurrentStep(0, 1);
        skipped &= core->getCurrentStep(0, 1) != 1;
        matches &= core->getCurrentStep(0, 16) == core->getCurrentStep(0);
    }
    std::printf("\nSelected-lane divergence: engine %d\n", drumType);
    check(matches, "all selected lane getters match actual current lane; invalid lane uses track");
    check(diverged && skipped, "lane 1 skips independently and publishes a divergent playhead");
    check(core->getCurrentStep(-1, 0) == 0 && core->getCurrentStep(8, 0) == 0,
          "out-of-range tracks return zero");
}

static void allTracksAudio() {
    auto core = std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    std::vector<float> audio(256 * 2);
    for (int t = 0; t < 8; ++t) {
        core->setEngineType(t, 0);
        core->setTrackVolume(t, 0.6f);
    }
    core->render(audio.data(), 256);
    for (int t = 0; t < 8; ++t) {
        core->panic();
        core->triggerNote(t, 48 + t * 3, 100);
        double energy = 0;
        bool finite = true;
        for (int b = 0; b < 40; ++b) {
            core->render(audio.data(), 256);
            for (float value : audio) { finite &= std::isfinite(value); energy += value * value; }
        }
        char message[100];
        std::snprintf(message, sizeof(message), "track %d MIDI pitch %d produces finite nonzero audio", t, 48+t*3);
        check(finite && energy > 1e-8, message);
    }
}
int main() {
    patternLengths(5); // FM drums
    patternLengths(6); // Analog drums
    selectedLaneDivergence(5);
    selectedLaneDivergence(6);
    allTracksAudio();
    std::printf("\n%s (%d failures)\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
