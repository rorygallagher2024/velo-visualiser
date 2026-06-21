#ifndef LLV_AUDIO_ENGINE_H
#define LLV_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
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
        SystemAudio
    };

    static AudioEngine &instance();

    // --- lifecycle (called from the JNI / main thread) ---
    bool startMicrophone();
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

    // System-audio push path. `data` is interleaved PCM already downmixed to
    // mono float by the caller. Real-time safe; forwards to the ring buffer.
    void pushExternalPcm(const float *data, size_t numSamples) noexcept;

    // --- consumer side (GL render thread) ---
    // Fills `out` with the latest `numSamples` samples (chronological order).
    void copyLatest(float *out, size_t numSamples) const noexcept;

    // Runs the FFT pipeline over the latest window and writes 3 band energies
    // (low, mid, high), each in [0, 1], into `outBands`. GL thread only.
    void computeBands(float *outBands) noexcept;

    // Full 128-bin spectrum for visuals.
    void computeFullSpectrum(float *outMagnitudes, float *outPeaks, float dt) noexcept;

    // Single-FFT combined path: bands + full spectrum from one transform.
    void computeAll(float *outBands, float *outMagnitudes, float *outPeaks, float dt) noexcept;

    int sampleRate() const { return mSampleRate.load(std::memory_order_relaxed); }
    float callbackPeriodMs() const { return mCallbackPeriodMs.load(std::memory_order_relaxed); }

    // --- Oboe callbacks ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    AudioEngine();
    ~AudioEngine() override = default;
    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    // Ring buffer sized for ~the most recent slice of audio we ever display.
    static constexpr size_t kBufferCapacity = 8192;

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<CircularBuffer> mBuffer;

    // FFT analysis state — touched only by the consumer (GL) thread.
    std::unique_ptr<FftProcessor> mFft;
    std::vector<float> mFftScratch;

    std::mutex mLifecycleLock;        // guards open/close only — never the hot path
    std::atomic<bool> mRunning{false};
    std::atomic<int> mSampleRate{48000};
    std::atomic<float> mCallbackPeriodMs{0};
    std::chrono::steady_clock::time_point mLastCallbackTime{};
};

#endif // LLV_AUDIO_ENGINE_H
