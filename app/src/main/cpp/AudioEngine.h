#ifndef LLV_AUDIO_ENGINE_H
#define LLV_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <thread>

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <vector>
#include "CircularBuffer.h"
#include "FftProcessor.h"

/**
 * Singleton ultra-low-latency audio engine built on Google Oboe.
 *
 * Three sources feed the same downstream CircularBuffers (mono analysis ring +
 * interleaved stereo ring), one source active at a time (the Kotlin
 * AudioSourceController owns that state machine):
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
 *
 *   3. LOCAL_PLAYBACK — decoded file audio from Kotlin's LocalAudioPlayer,
 *      played to the DAC through a dedicated Oboe *output* stream via blocking
 *      writes (the write pacing is the decoder's clock), and mirrored into the
 *      visualizer rings as it is written.
 *
 * Threading contract for the playback stream: mPlaybackStream is owned
 * EXCLUSIVELY by the decode thread. startPlayback / pushPlaybackAudio /
 * pausePlayback / resumePlayback / stopPlayback must all be called from that
 * one thread; no other thread ever touches the stream, so there is nothing to
 * lock and no close-during-write hazard. The Kotlin side stops playback by
 * flagging the decode loop and joining it — never by reaching in here.
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

    // --- capture lifecycle (called from the JNI / main thread) ---
    bool startMicrophone();
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

    /**
     * Selects the active source so analysis can apply a per-source gain
     * (digitally mastered sources run ~12 dB hotter than the mic — see
     * kDigitalAnalysisGain). Callable from any thread.
     */
    void setInputSource(InputSource source) noexcept;

    // System-audio push path. Real-time safe; forwards to the ring buffers.
    void pushExternalPcm(const float *data, size_t numSamples) noexcept;
    void pushExternalPcmStereo(const float *interleaved, size_t numSamples) noexcept;

    // --- local playback (ALL of these: decode thread only — see class docs) ---
    bool startPlayback(int sampleRate, int channelCount);
    /** Blocking write to the DAC + mirror into the visualizer rings.
     *  Returns false when the stream is dead (disconnected / timed out) so the
     *  decode loop can stop instead of free-running through the file. */
    bool pushPlaybackAudio(const float *interleaved, size_t numFrames) noexcept;
    void pausePlayback() noexcept;
    void resumePlayback() noexcept;
    void flushPlayback() noexcept;
    void stopPlayback();

    // --- consumer side (GL render thread) ---
    // Fills `out` with the latest `numSamples` samples (chronological order).
    void copyLatest(float *out, size_t numSamples) const noexcept;

    // Fills `outInterleaved` with the latest `numSamples` *pairs* of stereo
    // samples. If the source is mono, L and R are identical.
    void copyLatestStereo(float *outInterleaved, size_t numSamples) const noexcept;

    // Single-FFT pipeline over the latest window: 3 band energies + 128-bin
    // spectrum (magnitudes + falling peaks), all in [0, 1]. GL thread only.
    void computeAll(float *outBands, float *outMagnitudes, float *outPeaks, float dt) noexcept;

    int sampleRate() const { return mSampleRate.load(std::memory_order_relaxed); }
    float callbackPeriodMs() const { return mCallbackPeriodMs.load(std::memory_order_relaxed); }

    // --- Oboe callbacks (mic input stream only) ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    AudioEngine();
    ~AudioEngine() override = default;
    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    // Publishes the interval between audio deliveries on the calling thread
    // (mic callback / system push / playback push) for the perf HUD.
    void noteDeliveryPeriod() noexcept;
    // Mirrors one written chunk into the mono + stereo visualizer rings.
    void mirrorToVisualRings(const float *interleaved, size_t frames, int channels) noexcept;
    // Zeroes both rings so visuals decay to silence (pause / end of playback).
    void clearVisualRings() noexcept;
    // Copies the latest FFT window into mFftScratch with the analysis gain applied.
    void readAnalysisWindow() noexcept;

    // Ring buffer sized for ~the most recent slice of audio we ever display.
    static constexpr size_t kBufferCapacity = 8192;

    // Analysis gain for digitally mastered sources (system audio / local
    // files), which sit near 0 dBFS while mic input averages far lower. This
    // keeps the FFT's fixed dB window from saturating. Applied at the FFT
    // boundary ONLY — the rings always hold truthful full-scale samples for
    // the waveform/scope scenes and the beat gate.
    static constexpr float kDigitalAnalysisGain = 0.25f;

    // Capture stream (mic). Guarded by mLifecycleLock for open/close.
    std::shared_ptr<oboe::AudioStream> mStream;
    // Playback stream. Decode-thread owned; never locked (see class docs).
    std::shared_ptr<oboe::AudioStream> mPlaybackStream;

    std::unique_ptr<CircularBuffer> mBuffer;
    std::unique_ptr<CircularBuffer> mStereoBuffer;

    // FFT analysis state — touched only by the consumer (GL) thread.
    std::unique_ptr<FftProcessor> mFft;
    std::vector<float> mFftScratch;

    std::mutex mLifecycleLock;        // guards mic open/close only — never the hot path
    std::atomic<bool> mRunning{false};
    std::atomic<int> mSampleRate{48000};
    std::atomic<float> mAnalysisGain{1.0f};
    std::atomic<float> mCallbackPeriodMs{0};
};

#endif // LLV_AUDIO_ENGINE_H
