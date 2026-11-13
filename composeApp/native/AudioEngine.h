#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include "AudioEngineCore.h"
#include <memory>
#include <atomic>
#include <mutex>
#include <android/performance_hint.h>
#include <time.h>

class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();
    void refreshInputStreamState();
    // Positive ID selects CAFA; 0 blocks output; -1 explicitly restores normal audio.
    bool setCafaOutputDevice(int32_t deviceId);
    int32_t getCafaOutputDevice();
    void setCafaAudioEnabled(bool enabled);
    bool isCafaAudioEnabled();
    void onErrorBeforeClose(oboe::AudioStream *audioStream, oboe::Result result) override;

    AudioEngineCore& getCore() { return *mCore; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *audioStream,
                           oboe::Result result) override;
    int getAudioThreadTid() const { return mAudioThreadTid; }

private:
    bool startLocked();
    void stopLocked();
    bool cafaRouteMatches(oboe::AudioStream *stream) const;
    std::mutex mStreamMutex; // Control calls only; never acquired by the data callback.
    bool mStartRequested = false;
    std::atomic<int32_t> mCafaDeviceId{-1};
    std::atomic<bool> mCafaAudioEnabled{false};
    std::atomic<oboe::AudioStream *> mCallbackOutput{nullptr};
    int mAudioThreadTid = -1;
    std::unique_ptr<AudioEngineCore> mCore;
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::shared_ptr<oboe::AudioStream> mInputStream;
    // ADPF Native Support & Function Pointers (v3.1.25)
    typedef APerformanceHintManager* (*APHint_getManagerFn)();
    typedef APerformanceHintSession* (*APHint_createSessionFn)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
    typedef int (*APHint_reportActualWorkDurationFn)(APerformanceHintSession*, int64_t);

    APerformanceHintManager* mAdpfManager = nullptr;
    APerformanceHintSession* mAdpfSession = nullptr;
    APHint_getManagerFn mGetManagerFn = nullptr;
    APHint_createSessionFn mCreateSessionFn = nullptr;
    APHint_reportActualWorkDurationFn mReportDurationFn = nullptr;
    
    int64_t mTargetDurationNs = 0;
    bool mAdpfInitialized = false;
};

#endif // AUDIO_ENGINE_H
