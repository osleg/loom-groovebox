#include "AudioEngineCore.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>
static int failures;
static void check(bool good, const char *why) { std::printf("%s %s\n",good?"PASS":"FAIL",why); failures += !good; }
static bool near(float a,float b) { return std::abs(a-b)<0.00001f; }
int main() {
    auto core=std::make_unique<AudioEngineCore>();
    core->updateSampleRate(44100);
    std::array<float,256> out{};
    for(int i=0;i<200;++i) core->render(out.data(),128); // Complete native startup silence before queue checks.
    // The same APIs/values invoked by the Kotlin adapter, against production DSP.
    for(int track=0;track<8;++track) for(int bus=0;bus<18;++bus)
        core->setParameter(track,2000+bus*10,(track+bus)%11/10.f);
    core->render(out.data(),128);
    bool sends=true,unity=true;
    for(int track=0;track<8;++track) for(int bus=0;bus<18;++bus) {
        sends &= near(core->getTracks()[track].fxSends[bus].load(),(track+bus)%11/10.f);
        unity &= near(core->getTracks()[track].fxMix[bus].load(),1.f);
    }
    check(sends,"all 144 track/effect sends reach their native destination");
    core->setParameter(0,2001,.2f); core->render(out.data(),128);
    check(unity && near(core->getTracks()[0].fxMix[0].load(),1.f),"FX mix is forced unity, so v4 displays read-only UNITY rather than fake MIX");
    std::vector<std::vector<bool>> rhythm(3,std::vector<bool>(16,true));
    std::vector<float> gates(16,.5f);
    core->setArpConfig(7,1,-3,-2,true,false,rhythm,{},gates,.25f,.5f);
    core->setArpRate(7,4.f,0); core->setArpStrum(7,.1f);
    core->setTrackHumanize(7,.2f); core->setSwing(-.23f);
    core->render(out.data(),128);
    const auto &t=core->getTracks()[7];
    check(t.arpeggiator.getMode()==ArpMode::UP && t.arpeggiator.isLatched(),"ARP mode/latch address track eight");
    check(near(t.arpeggiator.getProbability(),.25f) && near(t.arpeggiator.getWeird(),.5f),"ARP probability/weird preserved at native values");
    check(near(t.mArpRate,4.f),"ARP rate uses native step multiplier");
    check(near(t.humanize,.2f),"track humanize reaches native track eight");
    core->setStep(7,63,true,{62,66,69},.8f,3,true,.8f,2.5f,false,.12f,{.01f,.03f,.06f},{.5f,.7f,.9f});
    core->render(out.data(),128);
    const auto &s=core->getTracks()[7].sequencer.getSteps()[63];
    check(s.active && s.notes.size()==3 && s.notes[2].note==69 && near(s.notes[0].velocity,.5f) && near(s.notes[2].subStepOffset,.06f),"held-step chord edits preserve per-note velocity and microtiming in native engine");
    check(s.ratchet==3 && s.punch && near(s.gate,2.5f) && near(s.probability,.8f),"held-step gate/probability preserve ratchet and punch in native engine");
    bool finite=true;
    for(int i=0;i<200;++i) { core->render(out.data(),128); for(float x:out)finite &= std::isfinite(x); }
    check(finite,"mapped controls retain finite rendered output");
    return failures?1:0;
}
