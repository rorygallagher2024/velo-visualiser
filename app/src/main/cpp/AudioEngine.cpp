#include "AudioEngine.h"
#include <algorithm>
#include <cmath>
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

bool AudioEngine::startMicrophone(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(mLifecycleLock);
    if (mRunning.load(std::memory_order_acquire)) {
        // Already live. Switching devices requires a stop() first — the
        // Kotlin controller does exactly that.
        return true;
    }
    mInputDeviceId.store(deviceId, std::memory_order_relaxed);

    mInputMeanSq = 0.0f;   // new session/device: re-adapt the input AGC

    // An explicitly chosen device (USB interface, line-in) may carry true
    // stereo — gold for the phase-accurate scope scenes — so try stereo
    // first and fall back to mono. The default route stays mono: that's the
    // built-in-mic path every visual was tuned against.
    if (deviceId != 0 && openInputStream(deviceId, oboe::ChannelCount::Stereo)) {
        return true;
    }
    if (openInputStream(deviceId, oboe::ChannelCount::Mono)) {
        return true;
    }
    // The explicit device wouldn't open at all (typically unplugged between
    // the picker snapshot and the tap): recover on the default route instead
    // of leaving no input stream running.
    if (deviceId != 0) {
        LOGW("Device %d failed to open — recovering on the default input.", deviceId);
        mInputDeviceId.store(0, std::memory_order_relaxed);
        return openInputStream(0, oboe::ChannelCount::Mono);
    }
    return false;
}

// Must be called with mLifecycleLock held (see startMicrophone).
bool AudioEngine::openInputStream(int32_t deviceId, oboe::ChannelCount channelCount) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)   // lowest latency path
            ->setInputPreset(oboe::InputPreset::Unprocessed) // raw, uncolored signal
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(channelCount)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            // Prefer AAudio; Oboe falls back to OpenSL ES automatically on
            // devices / OS versions where AAudio is unavailable.
            ->setAudioApi(oboe::AudioApi::AAudio)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (deviceId != 0) {
        builder.setDeviceId(deviceId);
    }

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
    mInputChannels.store(mStream->getChannelCount(), std::memory_order_relaxed);

    // Request the smallest stable burst-aligned buffer for minimal latency.
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("Input stream started. API=%s rate=%d ch=%d burst=%d bufSize=%d sharing=%d device=%d",
         oboe::convertToText(mStream->getAudioApi()),
         mStream->getSampleRate(),
         mStream->getChannelCount(),
         mStream->getFramesPerBurst(),
         mStream->getBufferSizeInFrames(),
         static_cast<int>(mStream->getSharingMode()),
         deviceId);

    mRunning.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::startPlayback(int sampleRate, int channelCount) {
    // Decode thread only — mPlaybackStream is single-thread owned (see header).
    stopPlayback();   // defensive: mid-stream format change reopens the stream

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            // Shared, not Exclusive: playback doesn't need the exclusive MMAP
            // path (we control both ends of the A/V clock), and a shared
            // stream is a better citizen when paused in the background.
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(channelCount)
            ->setSampleRate(sampleRate)
            // Conversion quality must stay None: with SRC enabled Oboe wraps
            // the stream in FilterAudioStream, whose blocking write() path
            // broke playback outright on-device (and reports device-rate frame
            // counts). AAudio accepts the file's rate directly and the
            // framework resamples downstream when the device runs another rate.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setAudioApi(oboe::AudioApi::AAudio);
            // No DataCallback for playback — the decoder paces itself against
            // blocking write().

    oboe::Result result = builder.openStream(mPlaybackStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        mPlaybackStream.reset();
        return false;
    }

    mSampleRate.store(mPlaybackStream->getSampleRate(), std::memory_order_relaxed);
    mLocalMeanSq = 0.0f;   // new track: re-adapt the analysis AGC from scratch

    // Buffer ~35 ms: enough to absorb decode-thread scheduling / GC jitter,
    // small enough that the visual mirror (fed at enqueue time) stays within
    // a frame or two of what is audible. A capacity-sized buffer here would
    // make the visuals *lead* the audio by its full length.
    const int32_t burst = mPlaybackStream->getFramesPerBurst();
    if (burst > 0) {
        const int32_t target = (mPlaybackStream->getSampleRate() * 35) / 1000;
        const int32_t bursts = std::max(2, (target + burst - 1) / burst);
        mPlaybackStream->setBufferSizeInFrames(
                std::min(bursts * burst, mPlaybackStream->getBufferCapacityInFrames()));
    }

    result = mPlaybackStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed for playback: %s", oboe::convertToText(result));
        mPlaybackStream->close();
        mPlaybackStream.reset();
        return false;
    }

    LOGI("Playback stream started. rate=%d channels=%d burst=%d bufSize=%d",
         mPlaybackStream->getSampleRate(), mPlaybackStream->getChannelCount(),
         mPlaybackStream->getFramesPerBurst(), mPlaybackStream->getBufferSizeInFrames());
    return true;
}

void AudioEngine::pausePlayback() noexcept {
    if (mPlaybackStream) mPlaybackStream->requestPause();
    // Fade the visuals to silence rather than freezing them on the last window.
    clearVisualRings();
}

void AudioEngine::resumePlayback() noexcept {
    if (mPlaybackStream) mPlaybackStream->requestStart();
}

void AudioEngine::flushPlayback() noexcept {
    // Valid only while paused/stopped — used when a seek lands mid-pause so
    // resume doesn't replay the stale tail still queued in the stream.
    if (mPlaybackStream) mPlaybackStream->requestFlush();
}

void AudioEngine::stopPlayback() {
    if (mPlaybackStream) {
        mPlaybackStream->stop();
        mPlaybackStream->close();
        mPlaybackStream.reset();
        clearVisualRings();
    }
}


void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLifecycleLock);
    mRunning.store(false, std::memory_order_release);
    mInputChannels.store(0, std::memory_order_relaxed);
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

void AudioEngine::noteDeliveryPeriod() noexcept {
    // Per-producer-thread timestamps: whichever source is live (mic callback,
    // system-audio push, playback push) publishes its delivery period, so the
    // perf HUD's "Buffer" readout stays truthful across sources.
    static thread_local std::chrono::steady_clock::time_point last{};
    const auto now = std::chrono::steady_clock::now();
    if (last.time_since_epoch().count() != 0) {
        const auto dt = std::chrono::duration<float, std::milli>(now - last);
        mCallbackPeriodMs.store(dt.count(), std::memory_order_relaxed);
    }
    last = now;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // HOT PATH — runs on the real-time audio thread.
    noteDeliveryPeriod();

    const auto *samples = static_cast<const float *>(audioData);

    // Explicitly selected external devices (USB interface, line-in) can carry
    // near-full-scale line-level signal — far hotter than the built-in mic
    // the visuals were tuned on — so their analysis feed goes through the
    // attenuation-only AGC. The default route is exempt: unity, as always.
    const bool external = mInputDeviceId.load(std::memory_order_relaxed) != 0;

    if (stream->getChannelCount() == 2) {
        // Stereo external device: raw interleaved L/R for the phase-accurate
        // scopes, (L+R)/2 (AGC-scaled) into the analysis ring.
        mStereoBuffer->write(samples, static_cast<size_t>(numFrames) * 2);
        static thread_local std::vector<float> mono;
        if (mono.size() < static_cast<size_t>(numFrames)) mono.resize(numFrames);
        for (int i = 0; i < numFrames; ++i) {
            mono[i] = (samples[i * 2] + samples[i * 2 + 1]) * 0.5f;
        }
        const float gain = external ? adaptiveGainStep(mInputMeanSq, mono.data(),
                                                       static_cast<size_t>(numFrames), 1.0f)
                                    : 1.0f;
        for (int i = 0; i < numFrames; ++i) {
            mono[i] *= gain;
        }
        mBuffer->write(mono.data(), static_cast<size_t>(numFrames));
        return oboe::DataCallbackResult::Continue;
    }

    if (external) {
        // Mono external device (USB headset, mono interface): same AGC.
        static thread_local std::vector<float> scaled;
        if (scaled.size() < static_cast<size_t>(numFrames)) scaled.resize(numFrames);
        const float gain = adaptiveGainStep(mInputMeanSq, samples,
                                            static_cast<size_t>(numFrames), 1.0f);
        for (int i = 0; i < numFrames; ++i) {
            scaled[i] = samples[i] * gain;
        }
        mBuffer->write(scaled.data(), static_cast<size_t>(numFrames));
    } else {
        // Built-in mic / default route: the untouched tuned path.
        mBuffer->write(samples, static_cast<size_t>(numFrames));
    }
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
    // System-audio path: same real-time-safe ring write as the mic callback.
    // The caller (nativePushPcm) has already applied SYSTEM_AUDIO_GAIN to
    // this mono feed — the digital-source calibration lives at the producers,
    // never here.
    noteDeliveryPeriod();
    mBuffer->write(data, numSamples);
}

void AudioEngine::pushExternalPcmStereo(const float *interleaved, size_t numSamples) noexcept {
    mStereoBuffer->write(interleaved, numSamples * 2);
}

bool AudioEngine::pushPlaybackAudio(const float *interleaved, size_t numFrames) noexcept {
    if (!mPlaybackStream) return false;
    const int channels = mPlaybackStream->getChannelCount();
    // Generous per-chunk timeout — a healthy buffer drains a chunk in ~5 ms,
    // so this only trips when the stream is stalled or disconnected.
    constexpr int64_t kWriteTimeoutNanos = 250LL * 1000 * 1000;
    // Small chunks so the visual mirror advances every few ms — the scope
    // stays smooth at 120 fps instead of jumping once per decode buffer.
    constexpr size_t kChunkFrames = 256;

    size_t offset = 0;
    while (offset < numFrames) {
        const size_t chunk = std::min(numFrames - offset, kChunkFrames);
        auto result = mPlaybackStream->write(interleaved + offset * channels,
                                             static_cast<int32_t>(chunk), kWriteTimeoutNanos);
        if (!result) {
            LOGW("Playback write failed: %s", oboe::convertToText(result.error()));
            return false;   // disconnected / closed — caller stops decoding
        }
        const auto written = static_cast<size_t>(result.value());
        if (written == 0) {
            LOGW("Playback write timed out — stream stalled.");
            return false;
        }
        // Oboe's SRC wrapper reports device-rate counts; never advance past
        // the chunk we actually handed over.
        const size_t consumed = std::min(written, chunk);
        mirrorToVisualRings(interleaved + offset * channels, consumed, channels);
        offset += consumed;
    }
    return true;
}

void AudioEngine::mirrorToVisualRings(const float *interleaved, size_t frames,
                                      int channels) noexcept {
    // Runs once per ~256-frame chunk (paced by the blocking write), so this —
    // not the decoder's buffer size — is the visuals' true refresh cadence.
    noteDeliveryPeriod();
    // Kotlin downmixes anything exotic before pushing, so channels is 1 or 2.
    // The mono (analysis) ring gets the digital calibration gain — pulled
    // further down by localAnalysisGain() when a hot master would otherwise
    // pin the FFT's fixed dB windows — while the stereo ring stays full-scale
    // for the phase-accurate scope scenes, mirroring nativePushPcm()'s
    // convention.
    static thread_local std::vector<float> mono;
    if (mono.size() < frames) mono.resize(frames);
    if (channels == 2) {
        mStereoBuffer->write(interleaved, frames * 2);
        for (size_t i = 0; i < frames; ++i) {
            mono[i] = (interleaved[i * 2] + interleaved[i * 2 + 1]) * 0.5f;
        }
    } else {
        for (size_t i = 0; i < frames; ++i) {
            mono[i] = interleaved[i];
        }
        static thread_local std::vector<float> stereo;
        if (stereo.size() < frames * 2) stereo.resize(frames * 2);
        for (size_t i = 0; i < frames; ++i) {
            stereo[i * 2] = interleaved[i];
            stereo[i * 2 + 1] = interleaved[i];
        }
        mStereoBuffer->write(stereo.data(), frames * 2);
    }
    const float gain = localAnalysisGain(mono.data(), frames);
    for (size_t i = 0; i < frames; ++i) {
        mono[i] *= gain;
    }
    mBuffer->write(mono.data(), frames);
}

float AudioEngine::localAnalysisGain(const float *mono, size_t frames) noexcept {
    return adaptiveGainStep(mLocalMeanSq, mono, frames, kDigitalMonoGain);
}

// Attenuation-only AGC step shared by the local-playback mirror and the
// external-input path. `meanSqState` must be owned by exactly one producer
// thread (decode thread / RT input callback respectively).
float AudioEngine::adaptiveGainStep(float &meanSqState, const float *mono, size_t frames,
                                    float baseGain) noexcept {
    if (frames == 0) return baseGain;
    float sumSq = 0.0f;
    for (size_t i = 0; i < frames; ++i) {
        sumSq += mono[i] * mono[i];
    }
    const float meanSq = sumSq / static_cast<float>(frames);

    // Chunk-duration-aware EMA so the time constants hold at any chunk size.
    const float rate = static_cast<float>(mSampleRate.load(std::memory_order_relaxed));
    const float dtSec = static_cast<float>(frames) / std::max(rate, 1.0f);
    const float tau = meanSq > meanSqState ? kLocalAgcAttackSec : kLocalAgcReleaseSec;
    meanSqState += (meanSq - meanSqState) * std::min(dtSec / tau, 1.0f);

    const float rms = std::sqrt(meanSqState);
    if (rms * baseGain <= kLocalAgcTargetRms) return baseGain;
    return kLocalAgcTargetRms / rms;
}

void AudioEngine::clearVisualRings() noexcept {
    mBuffer->clear();
    mStereoBuffer->clear();
}

void AudioEngine::copyLatest(float *out, size_t numSamples) const noexcept {
    mBuffer->readLatest(out, numSamples);
}

void AudioEngine::copyLatestStereo(float *outInterleaved, size_t numSamples) const noexcept {
    mStereoBuffer->readLatest(outInterleaved, numSamples * 2);
}

uint64_t AudioEngine::copyLatestStereoCounted(float *outInterleaved, size_t numFrames) const noexcept {
    // Total is in interleaved samples; frames are what consumers reason in.
    return mStereoBuffer->readLatestCounted(outInterleaved, numFrames * 2) / 2;
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
    
    // Attempt a simple synchronous restart on the same device;
    // startMicrophone() itself falls back to the default route when an
    // explicit device is gone (the Kotlin device callback resets the
    // selection in parallel). If the OS audio HAL is stuck, we fail
    // gracefully rather than looping.
    startMicrophone(mInputDeviceId.load(std::memory_order_relaxed));
}
