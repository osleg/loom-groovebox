#pragma once
#include <cstdint>
#include <memory>
namespace oboe {
enum class Direction {Input,Output}; enum class PerformanceMode {LowLatency}; enum class SharingMode {Exclusive,Shared}; enum class AudioFormat {Float}; enum class AudioApi {AAudio,OpenSLES}; enum class InputPreset {Camcorder}; enum class StreamState {Open,Started,Closed}; enum class Result {OK,ErrorDisconnected}; enum class DataCallbackResult {Continue};
class AudioStream;
class AudioStreamCallback { public: virtual ~AudioStreamCallback()=default; virtual DataCallbackResult onAudioReady(AudioStream*,void*,int32_t)=0; virtual void onErrorBeforeClose(AudioStream*,Result){} virtual void onErrorAfterClose(AudioStream*,Result){} };
inline const char *convertToText(Result){return "result";}
inline bool wrongDevice=false, wrongRate=false, failOpen=false;
inline AudioStream* lastOutput=nullptr;
class AudioStream { public:
 Direction direction=Direction::Output; AudioApi api=AudioApi::OpenSLES; int device=1,rate=48000,channels=2; StreamState state=StreamState::Open;
 Direction getDirection(){return direction;} AudioApi getAudioApi(){return api;} int getDeviceId(){return device;} int getSampleRate(){return rate;} int getChannelCount(){return channels;} AudioFormat getFormat(){return AudioFormat::Float;} StreamState getState(){return state;} int getFramesPerBurst(){return 64;} void setBufferSizeInFrames(int){} Result requestStart(){state=StreamState::Started;return Result::OK;} void stop(){state=StreamState::Open;} void close(){state=StreamState::Closed;}
};
class AudioStreamBuilder { AudioStream value; public:
 void setDirection(Direction x){value.direction=x;} void setPerformanceMode(PerformanceMode){} void setSharingMode(SharingMode){} void setFormat(AudioFormat){} void setChannelCount(int x){value.channels=x;} void setSampleRate(int x){value.rate=x;} void setCallback(AudioStreamCallback*){} void setInputPreset(InputPreset){} void setAudioApi(AudioApi x){value.api=x;} void setDeviceId(int x){value.device=x;}
 Result openStream(std::shared_ptr<AudioStream>& s){if(failOpen)return Result::ErrorDisconnected; s=std::make_shared<AudioStream>(value); if(s->direction==Direction::Output){if(wrongDevice)s->device=1;if(wrongRate)s->rate=48000;lastOutput=s.get();}return Result::OK;}
};
}
