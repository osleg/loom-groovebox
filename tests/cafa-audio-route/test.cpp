#include "AudioEngine.h"
#include <cassert>
#include <iostream>
static void block(AudioEngine& e,float expected){float samples[16]; e.onAudioReady(oboe::lastOutput,samples,8); for(float x:samples)assert(x==expected);}
int main(){
 AudioEngine e;
 assert(e.start()); assert(!e.isCafaAudioEnabled()); block(e,0.5f); // normal audio preserved
 assert(e.setCafaOutputDevice(42)); assert(e.getCafaOutputDevice()==42); assert(!e.isCafaAudioEnabled()); block(e,0);
 e.setCafaAudioEnabled(true); assert(e.isCafaAudioEnabled()); block(e,0.5f);
 e.setCafaAudioEnabled(false); assert(!e.isCafaAudioEnabled()); block(e,0);
 e.setCafaAudioEnabled(true); oboe::lastOutput->device=1; block(e,0); assert(e.getCafaOutputDevice()==0); assert(!e.isCafaAudioEnabled()); oboe::lastOutput->device=42; block(e,0);
 assert(e.setCafaOutputDevice(42)); e.setCafaAudioEnabled(true); assert(e.isCafaAudioEnabled()); block(e,0.5f);
 auto stream=oboe::lastOutput; e.onErrorBeforeClose(stream,oboe::Result::ErrorDisconnected); block(e,0); e.onErrorAfterClose(stream,oboe::Result::ErrorDisconnected); assert(e.getCafaOutputDevice()==0); assert(!e.isCafaAudioEnabled()); e.setCafaAudioEnabled(true); block(e,0);
 assert(e.setCafaOutputDevice(42)); e.setCafaAudioEnabled(true); e.stop(); e.setCafaAudioEnabled(true); assert(e.getCafaOutputDevice()==0); assert(!e.isCafaAudioEnabled()); assert(e.start()); assert(!e.isCafaAudioEnabled()); block(e,0);
 assert(!e.setCafaOutputDevice(0)); assert(!e.start()); assert(e.getCafaOutputDevice()==0); assert(!e.isCafaAudioEnabled());
 oboe::wrongDevice=true; assert(!e.setCafaOutputDevice(42)); assert(e.getCafaOutputDevice()==0); assert(!e.isCafaAudioEnabled()); oboe::wrongDevice=false;
 oboe::wrongRate=true; assert(!e.setCafaOutputDevice(42)); oboe::wrongRate=false;
 oboe::failOpen=true; assert(!e.setCafaOutputDevice(42)); oboe::failOpen=false;
 assert(e.setCafaOutputDevice(42)); e.setCafaAudioEnabled(true); renderHook=[&]{e.setCafaAudioEnabled(false);}; block(e,0); renderHook={};
 assert(e.setCafaOutputDevice(-1)); block(e,0.5f);
 std::cout << "PASS normal output; select/mute/unmute; actual-route mismatch; error latch; stop/enable/restart; missing device; wrong device/rate; open failure; explicit normal restore\n";
}
