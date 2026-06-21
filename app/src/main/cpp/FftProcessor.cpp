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
        : mCfg(kiss_fftr_alloc(kFftSize, /*inverse_fft=*/0, nullptr, nullptr)),
          mWindow(kFftSize),
          mWindowed(kFftSize),
          mSpectrum(kFftSize / 2 + 1) {
    // Precompute the Hann window once.
    for (int i = 0; i < kFftSize; ++i) {
        mWindow[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * static_cast<float>(i) /
                                             (kFftSize - 1)));
    }
}

FftProcessor::~FftProcessor() {
    kiss_fftr_free(mCfg);
}

void FftProcessor::runFft(const float *pcm) noexcept {
    for (int i = 0; i < kFftSize; ++i) {
        mWindowed[i] = pcm[i] * mWindow[i];
    }
    kiss_fftr(mCfg, mWindowed.data(), mSpectrum.data());
}

void FftProcessor::computeBands(int sampleRate, float *outBands) noexcept {
    const int bins = kFftSize / 2 + 1;
    const float binHz = static_cast<float>(sampleRate) / kFftSize;

    float sum[kBands] = {0.f, 0.f, 0.f};
    int count[kBands] = {0, 0, 0};

    for (int b = 1; b < bins; ++b) {
        const float freq = b * binHz;
        int band;
        if (freq < kLowMax)       band = 0;
        else if (freq < kMidMax)  band = 1;
        else if (freq < kHighMax) band = 2;
        else break;

        const kiss_fft_cpx &c = mSpectrum[b];
        sum[band] += std::sqrt(c.r * c.r + c.i * c.i);
        ++count[band];
    }

    for (int k = 0; k < kBands; ++k) {
        const float avg = count[k] > 0 ? sum[k] / count[k] : 0.f;
        const float db = 20.0f * std::log10(avg + 1e-6f);
        const float target = clamp01((db - kFloorDb) / (kCeilDb - kFloorDb));

        const float coeff = target > mSmoothed[k] ? kAttack : kDecay;
        mSmoothed[k] += (target - mSmoothed[k]) * coeff;
        outBands[k] = mSmoothed[k];
    }
}

void FftProcessor::computeFullSpectrum(float *outMagnitudes, float *outPeaks, float dt) noexcept {
    const int half = kFftSize / 2;
    const auto logSpan = static_cast<float>(half);
    const float ampNorm = 4.0f / kFftSize;

    constexpr float kFloorDbFull = -88.0f;
    constexpr float kCeilDbFull = -12.0f;
    constexpr float kTiltDbPerOct = 3.0f;
    constexpr float kTiltRefBin = 20.0f;
    constexpr float kPeakFall = 0.4f;

    auto magAt = [&](int k) {
        const kiss_fft_cpx &c = mSpectrum[k];
        return std::sqrt(c.r * c.r + c.i * c.i);
    };

    auto magInterp = [&](double pos) {
        int i0 = std::max(1, std::min(static_cast<int>(pos), half - 1));
        int i1 = std::min(i0 + 1, half - 1);
        float w = std::max(0.0f, std::min(static_cast<float>(pos - i0), 1.0f));
        return magAt(i0) * (1.0f - w) + magAt(i1) * w;
    };

    for (int b = 0; b < kFullBins; b++) {
        float posLo = std::pow(logSpan, static_cast<float>(b) / kFullBins);
        float posHi = std::pow(logSpan, static_cast<float>(b + 1) / kFullBins);
        int iLo = static_cast<int>(std::floor(posLo));
        int iHi = static_cast<int>(std::floor(posHi));

        float avg;
        if (iHi > iLo) {
            float sum = 0.0f;
            int count = 0;
            int k = std::max(iLo, 1);
            int end = std::min(iHi, half - 1);
            while (k <= end) {
                sum += magAt(k);
                count++;
                k++;
            }
            avg = (count > 0) ? (sum / count) : magInterp((posLo + posHi) * 0.5);
        } else {
            avg = magInterp((posLo + posHi) * 0.5);
        }

        float centerBin = std::pow(logSpan, (b + 0.5f) / kFullBins);
        float tilt = kTiltDbPerOct * std::log2(centerBin / kTiltRefBin);
        float db = 20.0f * std::log10(avg * ampNorm + 1e-9f) + tilt;
        float target = clamp01((db - kFloorDbFull) / (kCeilDbFull - kFloorDbFull));

        float cur = mFullMagnitudes[b];
        float coeff = (target > cur) ? 0.6f : 0.2f;
        mFullMagnitudes[b] = cur + (target - cur) * coeff;

        mFullPeaks[b] = (mFullMagnitudes[b] >= mFullPeaks[b]) ? mFullMagnitudes[b]
                                                              : std::max(mFullMagnitudes[b], mFullPeaks[b] - kPeakFall * dt);

        outMagnitudes[b] = mFullMagnitudes[b];
        outPeaks[b] = mFullPeaks[b];
    }
}

void FftProcessor::process(const float *pcm, int sampleRate, float *outBands) noexcept {
    runFft(pcm);
    computeBands(sampleRate, outBands);
}

void FftProcessor::processFullSpectrum(const float *pcm, int sampleRate, float *outMagnitudes, float *outPeaks, float dt) noexcept {
    runFft(pcm);
    computeFullSpectrum(outMagnitudes, outPeaks, dt);
}

void FftProcessor::processAll(const float *pcm, int sampleRate, float *outBands,
                              float *outMagnitudes, float *outPeaks, float dt) noexcept {
    runFft(pcm);
    computeBands(sampleRate, outBands);
    computeFullSpectrum(outMagnitudes, outPeaks, dt);
}
