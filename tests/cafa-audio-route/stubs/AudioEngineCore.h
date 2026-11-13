#pragma once
#include <functional>
inline std::function<void()> renderHook;
class AudioEngineCore { public: void updateSampleRate(float) {} bool isInputNeeded() {return false;} void pushInputSamples(const float*,int,int){} void render(float* p,int n){if(renderHook)renderHook();for(int i=0;i<n*2;i++)p[i]=0.5f;} };
