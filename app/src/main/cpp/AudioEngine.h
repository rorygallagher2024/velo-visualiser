#ifndef LLV_AUDIO_ENGINE_H
#define LLV_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <thread>

#include <chrono>
#include <memory>
#include <mutex>
#include <vector>
#include "CircularBuffer.h"
#include "FftProcessor.h"

/**
 * Singleton ultra-low-latency audio capture engine built on Google Oboe.
 *
 * Two capture sources share one downstream CircularBuffer:
 *
 *   1. MICROPHONE  — owned entirely in native code. We open an Oboe input
 *      stream configured for the lowest achievable latency:
 *        - Direction       = Input
 *        - PerformanceMode = LowLatency
 *        - SharingMode     = Exclusive   (falls back to Shared if denied)
 *        - InputPreset     = Unprocessed (no AGC/NS/echo-cancel coloration)
 *        - API             = AAudio preferred, OpenSL ES automatic fallback
 *
 *   2. SYSTEM_AUDIO — Android's AudioPlaybackCapture API is only reachable
 *      through AudioRecord + a MediaProjection token, which lives in the Java
 *      world. The Kotlin AudioCaptureService reads PCM on a dedicated thread
 *      and pushes it straight into this engine's buffer via pushExternalPcm().
 *      That keeps a single consumer-facing API for the renderer regardless of
 *      source.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    enum class InputSource {
        Microphone,
        SystemAudio,
        LocalPlayback
    };

    static AudioEngine &instance();

    // --- lifecycle (called from the JNI / main thread) ---
    bool startMicrophone();
    bool startPlayback(int sampleRate, int channelCount);
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

    // System-audio push path.
    void pushExternalPcm(const float *data, size_t numSamples) noexcept;
    void pushExternalPcmStereo(const float *interleaved, size_t numSamples) noexcept;

    // Local playback push path (Blocking write to DAC).
    void pushPlaybackAudio(const float *interleaved, size_t numFrames) noexcept;

    // --- consumer side (GL render thread) ---
    void copyLatest(float *out, size_t numSamples) const noexcept;
    void copyLatestStereo(float *outInterleaved, size_t numSamples) const noexcept;
    void computeBands(float *outBands) noexcept;
    void computeFullSpectrum(float *outMagnitudes, float *outPeaks, float dt) noexcept;
    void computeAll(float *outBands, float *outMagnitudes, float *outPeaks, float dt) noexcept;

    int sampleRate() const { return mSampleRate.load(std::memory_order_relaxed); }
    float callbackPeriodMs() const { return mCallbackPeriodMs.load(std::memory_order_relaxed); }

    // --- Oboe callbacks (Used for Mic input only) ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    AudioEngine();
    ~AudioEngine() override = default;
    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    static constexpr size_t kBufferCapacity = 8192;

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<CircularBuffer> mBuffer;
    std::unique_ptr<CircularBuffer> mStereoBuffer;

    std::unique_ptr<FftProcessor> mFft;
    std::vector<float> mFftScratch;

    std::mutex mLifecycleLock;
    std::atomic<bool> mRunning{false};
    std::atomic<int> mSampleRate{48000};
    std::atomic<float> mCallbackPeriodMs{0};
    std::chrono::steady_clock::time_point mLastCallbackTime{};
};

#endif // LLV_AUDIO_ENGINE_H
