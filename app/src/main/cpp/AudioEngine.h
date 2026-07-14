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
    static AudioEngine &instance();

    // --- capture lifecycle (called from the JNI / main thread) ---
    // deviceId selects a specific input (AudioDeviceInfo.getId()); 0 follows
    // the system default route. An explicit device is opened in stereo when
    // it supports it — true L/R for the phase-accurate scope scenes.
    bool startMicrophone(int32_t deviceId = 0);
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

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

    // Opens + starts the input stream on the given device/channel config.
    // Must be called with mLifecycleLock held.
    bool openInputStream(int32_t deviceId, oboe::ChannelCount channelCount);
    // Publishes the interval between audio deliveries on the calling thread
    // (mic callback / system push / playback push) for the perf HUD.
    void noteDeliveryPeriod() noexcept;
    // Mirrors one written chunk into the mono + stereo visualizer rings.
    void mirrorToVisualRings(const float *interleaved, size_t frames, int channels) noexcept;
    // Attenuation-only AGC for the local-playback analysis feed: the mono-ring
    // gain for this chunk, at most kDigitalMonoGain. Decode thread only.
    float localAnalysisGain(const float *mono, size_t frames) noexcept;
    // Shared AGC step; meanSqState must be owned by a single producer thread.
    float adaptiveGainStep(float &meanSqState, const float *mono, size_t frames,
                           float baseGain) noexcept;
    // Zeroes both rings so visuals decay to silence (pause / end of playback).
    void clearVisualRings() noexcept;

    // Ring buffer sized for ~the most recent slice of audio we ever display.
    static constexpr size_t kBufferCapacity = 8192;

    // Mono-ring gain for local playback's visual mirror. Digitally mastered
    // audio sits near 0 dBFS while the mic averages far lower, so the mono
    // (analysis) ring — which feeds the FFT, the beat gate and the reactive
    // visuals — takes this attenuation, while the stereo ring stays full
    // scale for the scope scenes. MUST match the system-audio path's
    // AudioCaptureService.SYSTEM_AUDIO_GAIN so all digital sources drive the
    // visuals identically.
    static constexpr float kDigitalMonoGain = 0.30f;

    // Attenuation-only analysis AGC, shared by local playback (base gain
    // kDigitalMonoGain, decode thread) and explicitly selected external
    // inputs (base gain 1.0, RT callback thread). Hot sources — loudness-war
    // masters, line-level USB feeds — would otherwise pin FftProcessor's
    // fixed dB windows and wash the spectrum scenes white. The gain never
    // exceeds the base, so quiet/normal material keeps its calibration —
    // only sustained hot program is pulled down until the analysis RMS sits
    // at the target. Fast attack so a wash dies within ~a second; slow
    // release so quiet passages don't pump.
    static constexpr float kLocalAgcTargetRms  = 0.06f;  // post-gain, ~-24 dBFS
    static constexpr float kLocalAgcAttackSec  = 1.0f;
    static constexpr float kLocalAgcReleaseSec = 8.0f;
    float mLocalMeanSq = 0.0f;  // EMA of the pre-gain mono mean-square

    // Same AGC, external-input path (explicitly selected USB/wired devices,
    // which can carry hot line-level signal). RT input callback thread only;
    // reset under mLifecycleLock before the stream starts.
    float mInputMeanSq = 0.0f;

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
    // Input device of the live/last mic session (0 = default route); read by
    // the error callback to restart capture on the same device.
    std::atomic<int32_t> mInputDeviceId{0};
    std::atomic<float> mCallbackPeriodMs{0};
};

#endif // LLV_AUDIO_ENGINE_H
