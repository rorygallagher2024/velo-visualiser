#ifndef LLV_FFT_PROCESSOR_H
#define LLV_FFT_PROCESSOR_H

#include <vector>
#include <cstddef>
#include "kiss_fftr.h"

/**
 * Real-FFT spectral analyzer that reduces a PCM window into three perceptual
 * energy bands: Lows (kick/bass), Mids (synths/melody), Highs (hats/snare).
 *
 * Pipeline per call:
 *   1. Multiply the PCM window by a precomputed Hann (Hanning) window to cut
 *      spectral leakage.
 *   2. Real FFT via KissFFT (kFftSize -> kFftSize/2 + 1 complex bins).
 *   3. Magnitude per bin, summed/averaged into the three frequency ranges.
 *   4. Map to 0..1 on a dB scale, then apply asymmetric attack/decay smoothing
 *      so the visuals snap to transients but fall off gracefully.
 *
 * NOT real-time-safe and NOT thread-safe: intended to run on the single GL
 * render thread (driven by the JNI getLatestFrequencyBands call), never on the
 * audio callback thread.
 */
class FftProcessor {
public:
    static constexpr int kFftSize = 1024;   // matches the render PCM window
    static constexpr int kBands   = 3;
    static constexpr int kFullBins = 128;

    FftProcessor();
    ~FftProcessor();

    FftProcessor(const FftProcessor &) = delete;
    FftProcessor &operator=(const FftProcessor &) = delete;

    // Aggregate 3-band analysis for light-sync/beat.
    void process(const float *pcm, int sampleRate, float *outBands) noexcept;

    // Full 128-bin log-frequency spectrum for visualizers.
    void processFullSpectrum(const float *pcm, int sampleRate, float *outMagnitudes, float *outPeaks, float dt) noexcept;

private:
    kiss_fftr_cfg mCfg;
    std::vector<float> mWindow;                 // Hann coefficients
    std::vector<float> mWindowed;               // scratch (windowed PCM)
    std::vector<kiss_fft_cpx> mSpectrum;        // kFftSize/2 + 1 bins
    float mSmoothed[kBands] = {0.f, 0.f, 0.f};

    // Full spectrum state
    float mFullMagnitudes[kFullBins] = {0.f};
    float mFullPeaks[kFullBins] = {0.f};
};

#endif // LLV_FFT_PROCESSOR_H
