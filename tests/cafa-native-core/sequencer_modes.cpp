#include <cstdlib>
#include "Sequencer.h"
#include <cstdio>
#include <initializer_list>

static int failures = 0;
static void sequence(Sequencer& seq, std::initializer_list<int> expected, const char* label) {
    bool ok = true;
    for (int step : expected) { seq.advance(); ok &= seq.getCurrentStepIndex() == step; }
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", label);
    if (!ok) ++failures;
}
int main() {
    Sequencer forward; forward.setConfiguration(1, 4);
    sequence(forward, {0,1,2,3,0,1}, "forward visits step 1 on first cycle and wraps");
    forward.reset(); sequence(forward, {0,1,2,3,0}, "reset restores the full forward first cycle");
    Sequencer reverse; reverse.setConfiguration(1, 4); reverse.setPlaybackDirection(1);
    sequence(reverse, {0,3,2,1,0,3}, "reverse visits final step on first cycle and wraps");
    reverse.reset(); sequence(reverse, {0,3,2,1}, "reset preserves reverse direction");
    Sequencer ping; ping.setConfiguration(1, 4); ping.setPlaybackDirection(2);
    sequence(ping, {0,1,2,3,2,1,0,1}, "ping-pong reaches both boundaries without duplicate endpoints");
    ping.reset(); sequence(ping, {0,1,2,3}, "reset restores ping-pong forward phase");
    Sequencer jump; jump.setConfiguration(1,4); jump.setIsJumpMode(true);
    sequence(jump, {0,0,0}, "jump mode holds initial step");
    jump.jumpToStep(2); sequence(jump, {2,2,2}, "jump mode selects and holds requested step");
    jump.reset(); sequence(jump, {0,0}, "reset clears jump position");
    jump.setIsJumpMode(false); sequence(jump, {1,2,3,0}, "leaving jump mode resumes ordered traversal");
    Sequencer skipped; skipped.setConfiguration(1,4); Step step; step.isSkipped=true; skipped.setStep(1,step);
    sequence(skipped, {0,2,3,0,2}, "skipped step is bypassed from the first cycle");
    std::printf("%s (%d failures)\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
