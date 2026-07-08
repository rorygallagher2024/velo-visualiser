#include "AudioEngine.h"
#include <android/log.h>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine &AudioEngine::instance() {
    static AudioEngine sInstance;   // thread-safe since C++11
    return sInstance;
}

AudioEngine::AudioEngine()
        : mBuffer(std::make_unique<CircularBuffer>(kBufferCapacity)),
          mStereoBuffer(std::make_unique<CircularBuffer>(kBufferCapacity * 2)),
          mFft(std::make_unique<FftProcessor>()),
          mFftScratch(FftProcessor::kFftSize) {}

bool AudioEngine::startMicrophone() {
    std::lock_guard<std::mutex> lock(mLifecycleLock);
    if (mRunning.load(std::memory_order_acquire)) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)   // lowest latency path
            ->setInputPreset(oboe::InputPreset::Unprocessed) // raw, uncolored signal
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            // Prefer AAudio; Oboe falls back to OpenSL ES automatically on
            // devices / OS versions where AAudio is unavailable.
            ->setAudioApi(oboe::AudioApi::AAudio)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGW("Exclusive AAudio open failed (%s); retrying with Shared mode.",
             oboe::convertToText(result));
        // Some devices refuse EXCLUSIVE for input — relax and retry.
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    mSampleRate.store(mStream->getSampleRate(), std::memory_order_relaxed);

    // Request the smallest stable burst-aligned buffer for minimal latency.
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("Mic stream started. API=%s rate=%d burst=%d bufSize=%d sharing=%d",
         oboe::convertToText(mStream->getAudioApi()),
         mStream->getSampleRate(),
         mStream->getFramesPerBurst(),
         mStream->getBufferSizeInFrames(),
         static_cast<int>(mStream->getSharingMode()));

    mRunning.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::startPlayback(int sampleRate, int channelCount) {
    std::lock_guard<std::mutex> lock(mLifecycleLock);
    if (mRunning.load(std::memory_order_acquire)) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(channelCount)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setAudioApi(oboe::AudioApi::AAudio);
            // No DataCallback for playback! We use blocking write().

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGW("Exclusive output open failed; retrying with Shared.");
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    mSampleRate.store(mStream->getSampleRate(), std::memory_order_relaxed);
    
    // For playback pumped from a Java thread via JNI, we need a larger buffer 
    // to absorb GC pauses and thread scheduling jitter to prevent audio cracking.
    // The visualization is synchronized with the blocking write, so this larger
    // audio buffer does not negatively impact A/V sync.
    mStream->setBufferSizeInFrames(mStream->getBufferCapacityInFrames());

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed for playback: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("Playback stream started. rate=%d channels=%d", mStream->getSampleRate(), mStream->getChannelCount());
    mRunning.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLifecycleLock);
    mRunning.store(false, std::memory_order_release);
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // HOT PATH — runs on the real-time audio thread.
    auto now = std::chrono::steady_clock::now();
    if (mLastCallbackTime.time_since_epoch().count() != 0) {
        auto dt = std::chrono::duration<float, std::milli>(now - mLastCallbackTime);
        mCallbackPeriodMs.store(dt.count(), std::memory_order_relaxed);
    }
    mLastCallbackTime = now;

    const auto *samples = static_cast<const float *>(audioData);
    
    // Mic is always mono.
    mBuffer->write(samples, static_cast<size_t>(numFrames));
    static thread_local std::vector<float> stereo;
    if (stereo.size() < static_cast<size_t>(numFrames * 2)) stereo.resize(numFrames * 2);
    for (int i = 0; i < numFrames; ++i) {
        stereo[i*2] = samples[i];
        stereo[i*2+1] = samples[i];
    }
    mStereoBuffer->write(stereo.data(), static_cast<size_t>(numFrames * 2));
    
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::pushExternalPcm(const float *data, size_t numSamples) noexcept {
    // System-audio path: still goes through the same real-time-safe ring write.
    mBuffer->write(data, numSamples);
}

void AudioEngine::pushExternalPcmStereo(const float *interleaved, size_t numSamples) noexcept {
    mStereoBuffer->write(interleaved, numSamples * 2);
}

void AudioEngine::pushPlaybackAudio(const float *interleaved, size_t numFrames) noexcept {
    if (mStream && mStream->getDirection() == oboe::Direction::Output) {
        int channels = mStream->getChannelCount();
        int64_t timeoutNanos = 500000000; // 500ms
        
        size_t framesRemaining = numFrames;
        size_t offsetFrames = 0;
        size_t chunkSize = 256; // About 5.3ms at 48kHz to ensure smooth 60fps+ visualizer updates
        
        while (framesRemaining > 0) {
            size_t currentChunk = std::min(framesRemaining, chunkSize);
            auto result = mStream->write(interleaved + (offsetFrames * channels), currentChunk, timeoutNanos);
            
            if (result.value() > 0) {
                size_t framesWritten = result.value();
                
                // Local playback audio is often mastered to 0dBFS (very loud),
                // whereas microphone audio averages much lower. We apply an attenuation 
                // factor ONLY to the mono buffer (which drives the FFT and reactive visuals)
                // so they don't wash out, while leaving the stereo buffer untouched for the oscilloscope.
                const float localPlaybackAttenuation = 0.15f;

                if (channels == 2) {
                    mStereoBuffer->write(interleaved + (offsetFrames * channels), framesWritten * 2);
                    
                    // Downmix to mono for FFT analysis + attenuate
                    static thread_local std::vector<float> mono;
                    if (mono.size() < framesWritten) mono.resize(framesWritten);
                    for (size_t i = 0; i < framesWritten; ++i) {
                        mono[i] = (interleaved[(offsetFrames + i)*2] + interleaved[(offsetFrames + i)*2+1]) * 0.5f * localPlaybackAttenuation;
                    }
                    mBuffer->write(mono.data(), framesWritten);
                } else {
                    // Mono track: attenuate for FFT
                    static thread_local std::vector<float> monoAttenuated;
                    if (monoAttenuated.size() < framesWritten) monoAttenuated.resize(framesWritten);
                    for (size_t i = 0; i < framesWritten; ++i) {
                        monoAttenuated[i] = interleaved[offsetFrames + i] * localPlaybackAttenuation;
                    }
                    mBuffer->write(monoAttenuated.data(), framesWritten);
                    
                    // Upmix to stereo for stereo visualizers (unattenuated)
                    static thread_local std::vector<float> stereo;
                    if (stereo.size() < framesWritten * 2) stereo.resize(framesWritten * 2);
                    for (size_t i = 0; i < framesWritten; ++i) {
                        stereo[i*2] = interleaved[offsetFrames + i];
                        stereo[i*2+1] = interleaved[offsetFrames + i];
                    }
                    mStereoBuffer->write(stereo.data(), framesWritten * 2);
                }
                
                offsetFrames += framesWritten;
                framesRemaining -= framesWritten;
            } else {
                break; // Stream disconnected or timeout
            }
        }
    }
}

void AudioEngine::copyLatest(float *out, size_t numSamples) const noexcept {
    mBuffer->readLatest(out, numSamples);
}

void AudioEngine::copyLatestStereo(float *outInterleaved, size_t numSamples) const noexcept {
    mStereoBuffer->readLatest(outInterleaved, numSamples * 2);
}

void AudioEngine::computeBands(float *outBands) noexcept {
    mBuffer->readLatest(mFftScratch.data(), FftProcessor::kFftSize);
    mFft->process(mFftScratch.data(), sampleRate(), outBands);
}

void AudioEngine::computeFullSpectrum(float *outMagnitudes, float *outPeaks, float dt) noexcept {
    mBuffer->readLatest(mFftScratch.data(), FftProcessor::kFftSize);
    mFft->processFullSpectrum(mFftScratch.data(), sampleRate(), outMagnitudes, outPeaks, dt);
}

void AudioEngine::computeAll(float *outBands, float *outMagnitudes, float *outPeaks, float dt) noexcept {
    mBuffer->readLatest(mFftScratch.data(), FftProcessor::kFftSize);
    mFft->processAll(mFftScratch.data(), sampleRate(), outBands, outMagnitudes, outPeaks, dt);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typical cause: route change (e.g. headset unplug) or device disconnect.
    LOGW("Stream error after close: %s — attempting restart.",
         oboe::convertToText(error));
    mRunning.store(false, std::memory_order_release);
    mStream.reset();
    
    // Attempt a simple synchronous restart. If the OS audio HAL is stuck, 
    // we fail gracefully rather than hammering it in a loop.
    startMicrophone();
}
