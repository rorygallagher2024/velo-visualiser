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
    // Float, mono => numFrames == numSamples. Single memcpy-class write,
    // zero allocation, zero blocking.
    const auto *samples = static_cast<const float *>(audioData);
    mBuffer->write(samples, static_cast<size_t>(numFrames));
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::pushExternalPcm(const float *data, size_t numSamples) noexcept {
    // System-audio path: still goes through the same real-time-safe ring write.
    mBuffer->write(data, numSamples);
}

void AudioEngine::copyLatest(float *out, size_t numSamples) const noexcept {
    mBuffer->readLatest(out, numSamples);
}

void AudioEngine::computeBands(float *outBands) noexcept {
    mBuffer->readLatest(mFftScratch.data(), FftProcessor::kFftSize);
    mFft->process(mFftScratch.data(), sampleRate(), outBands);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typical cause: route change (e.g. headset unplug) or device disconnect.
    // The stream is already closed by Oboe here; transparently reopen the mic.
    LOGW("Stream error after close: %s — attempting restart.",
         oboe::convertToText(error));
    mRunning.store(false, std::memory_order_release);
    mStream.reset();
    startMicrophone();
}
