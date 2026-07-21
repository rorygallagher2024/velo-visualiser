#ifndef LLV_CIRCULAR_BUFFER_H
#define LLV_CIRCULAR_BUFFER_H

#include <atomic>
#include <vector>
#include <cstring>
#include <cstddef>
#include <algorithm>

/**
 * Lock-free single-producer / single-consumer ring buffer of float samples.
 *
 * Producer: the real-time audio callback (Oboe AAudio thread, or the
 *           AudioPlaybackCapture push from Kotlin). It calls write() only.
 * Consumer: the GL render thread. It calls readLatest() only.
 *
 * Design constraints:
 *   - write() performs NO allocation and NO locking. The backing storage is
 *     allocated once at construction. The hot path is two memcpy()s plus one
 *     atomic store, which is wait-free and safe to run on the audio thread.
 *   - We only ever need the *latest* window of samples for an oscilloscope,
 *     so the buffer simply overwrites the oldest data. There is no "full"
 *     state to handle and no back-pressure on the audio thread.
 *
 * The visualizer tolerates a benign read/write race (a single torn sample at
 * the wrap boundary is imperceptible at 120 fps), which is what lets us avoid
 * any mutex on the audio thread entirely.
 */
class CircularBuffer {
public:
    explicit CircularBuffer(size_t capacity)
            : mCapacity(capacity),
              mBuffer(capacity, 0.0f),
              mWriteIndex(0) {}

    /** Producer side. Real-time safe: no malloc, no lock. */
    void write(const float *data, size_t numSamples) noexcept {
        const size_t produced = numSamples;
        // If a single callback delivers more than the whole buffer, only the
        // tail is relevant for visualization.
        if (numSamples >= mCapacity) {
            data += (numSamples - mCapacity);
            numSamples = mCapacity;
        }

        const size_t w = mWriteIndex.load(std::memory_order_relaxed);
        const size_t firstChunk = std::min(numSamples, mCapacity - w);

        std::memcpy(&mBuffer[w], data, firstChunk * sizeof(float));
        std::memcpy(&mBuffer[0], data + firstChunk,
                    (numSamples - firstChunk) * sizeof(float));

        // Single producer per buffer: plain add + release store is enough, and
        // the release pairs with readLatestCounted()'s acquire so the samples
        // behind the counted position are always published with the count.
        mTotalWritten.store(
            mTotalWritten.load(std::memory_order_relaxed) + produced,
            std::memory_order_release);
        mWriteIndex.store((w + numSamples) % mCapacity, std::memory_order_release);
    }

    /**
     * Consumer side. Copies the most recent `numSamples` into `out` in
     * chronological order (oldest -> newest). If fewer samples have been
     * written than requested, the front is zero-padded by the initial fill.
     */
    void readLatest(float *out, size_t numSamples) const noexcept {
        if (numSamples > mCapacity) numSamples = mCapacity;

        const size_t w = mWriteIndex.load(std::memory_order_acquire);
        const size_t start = (w + mCapacity - numSamples) % mCapacity;
        const size_t firstChunk = std::min(numSamples, mCapacity - start);

        std::memcpy(out, &mBuffer[start], firstChunk * sizeof(float));
        std::memcpy(out + firstChunk, &mBuffer[0],
                    (numSamples - firstChunk) * sizeof(float));
    }

    /**
     * Consumer side, splice-free variant: copies the most recent `numSamples`
     * with the window END derived from the same atomic total that is returned,
     * so the caller knows EXACTLY how many of the copied samples are new since
     * its last call. Consumers that instead estimate freshness from wall-clock
     * dt mis-splice the stream every frame — a phase jump (an audible-style
     * click) that per-sample filter banks turn into broadband phantom spikes.
     *
     * @return total samples ever written, as of this copy.
     */
    uint64_t readLatestCounted(float *out, size_t numSamples) const noexcept {
        if (numSamples > mCapacity) numSamples = mCapacity;

        const uint64_t total = mTotalWritten.load(std::memory_order_acquire);
        const size_t w = static_cast<size_t>(total % mCapacity);
        const size_t start = (w + mCapacity - numSamples) % mCapacity;
        const size_t firstChunk = std::min(numSamples, mCapacity - start);

        std::memcpy(out, &mBuffer[start], firstChunk * sizeof(float));
        std::memcpy(out + firstChunk, &mBuffer[0],
                    (numSamples - firstChunk) * sizeof(float));
        return total;
    }

    /**
     * Producer side: zero the contents so consumers see silence (used when
     * playback pauses/ends, letting visuals decay instead of freezing on the
     * last window). Same benign torn-read tolerance as write().
     */
    void clear() noexcept {
        std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    }

    size_t capacity() const noexcept { return mCapacity; }

private:
    const size_t mCapacity;
    std::vector<float> mBuffer;
    std::atomic<size_t> mWriteIndex;
    std::atomic<uint64_t> mTotalWritten{0};
};

#endif // LLV_CIRCULAR_BUFFER_H
