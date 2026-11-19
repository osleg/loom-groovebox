#include "AudioEngineCore.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>

static double energy(AudioEngineCore &core, int blocks) {
    std::array<float, 256> output{};
    double sum = 0;
    for (int block = 0; block < blocks; ++block) {
        core.render(output.data(), 128);
        for (float sample : output) sum += sample * sample;
    }
    return sum;
}

int main() {
    auto core = std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    energy(*core, 200); // Pass native startup before listening.
    core->setTrackVolume(0, 1.f);
    core->setTrackVolume(1, 1.f);
    core->setTrackSolo(0, true);
    core->setTrackSolo(1, true);
    core->triggerNote(0, 60, 100);
    core->triggerNote(1, 67, 100);
    const double both = energy(*core, 30);
    core->setTrackMute(0, true);
    const double one = energy(*core, 30);
    core->setTrackMute(0, false);
    core->setTrackMute(1, true);
    const double other = energy(*core, 30);
    core->setTrackMute(0, true);
    const double none = energy(*core, 30);
    const auto &tracks = core->getTracks();
    const bool flags = tracks[0].isSoloed && tracks[1].isSoloed &&
        tracks[0].isMuted && tracks[1].isMuted;
    const bool audible = std::isfinite(both) && std::isfinite(one) && std::isfinite(other) &&
        both > 1e-8 && one > 1e-8 && other > 1e-8;
    const bool muted = none < 1e-10;
    std::printf("%s additive solo keeps both tracks and mute silences each\n",
        flags && audible && muted ? "PASS" : "FAIL");
    return flags && audible && muted ? 0 : 1;
}
