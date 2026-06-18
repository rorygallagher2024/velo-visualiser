#include "FftProcessor.h"
#include <cmath>
#include <algorithm>

namespace {
// Band edges in Hz. Everything below kLowMax is "lows", up to kMidMax is
// "mids", up to kHighMax is "highs"; bins above that are ignored.
constexpr float kLowMax  = 250.0f;
constexpr float kMidMax  = 4000.0f;
constexpr float kHighMax = 16000.0f;

// dB window mapped onto 0..1. A bin/band at kFloorDb or quieter reads 0;
// at kCeilDb or louder reads 1. Tunable to taste / mic sensitivity.
constexpr float kFloorDb = -70.0f;
constexpr float kCeilDb  = -15.0f;

// Asymmetric smoothing: fast rise (transients pop), slower fall (no flicker).
constexpr float kAttack = 0.6f;
constexpr float kDecay  = 0.12f;

inline float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }
} // namespace

FftProcessor::FftProcessor()
        : mCfg(kiss_fftr_alloc(kFftSize, /*inverse=*/0, nullptr, nullptr)),
          mWindow(kFftSize),
          mWindowed(kFftSize),
          mSpectrum(kFftSize / 2 + 1) {
    // Precompute the Hann window once.
    for (int i = 0; i < kFftSize; ++i) {
        mWindow[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * i /
                                             (kFftSize - 1)));
    }
}

FftProcessor::~FftProcessor() {
    kiss_fftr_free(mCfg);
}

void FftProcessor::process(const float *pcm, int sampleRate, float *outBands) noexcept {
    // 1. Window.
    for (int i = 0; i < kFftSize; ++i) {
        mWindowed[i] = pcm[i] * mWindow[i];
    }

    // 2. Real FFT.
    kiss_fftr(mCfg, mWindowed.data(), mSpectrum.data());

    // 3. Accumulate magnitudes per band.
    const int bins = kFftSize / 2 + 1;
    const float binHz = static_cast<float>(sampleRate) / kFftSize;

    float sum[kBands] = {0.f, 0.f, 0.f};
    int count[kBands] = {0, 0, 0};

    for (int b = 1; b < bins; ++b) {          // skip DC (b == 0)
        const float freq = b * binHz;
        int band;
        if (freq < kLowMax)       band = 0;
        else if (freq < kMidMax)  band = 1;
        else if (freq < kHighMax) band = 2;
        else break;                            // bins only increase in freq

        const kiss_fft_cpx &c = mSpectrum[b];
        sum[band] += std::sqrt(c.r * c.r + c.i * c.i);
        ++count[band];
    }

    // 4. Normalize (dB scale) + asymmetric smoothing.
    for (int k = 0; k < kBands; ++k) {
        const float avg = count[k] > 0 ? sum[k] / count[k] : 0.f;
        const float db = 20.0f * std::log10(avg + 1e-6f);
        const float target = clamp01((db - kFloorDb) / (kCeilDb - kFloorDb));

        const float coeff = target > mSmoothed[k] ? kAttack : kDecay;
        mSmoothed[k] += (target - mSmoothed[k]) * coeff;
        outBands[k] = mSmoothed[k];
    }
}
