#include "LinkController.h"

#include <ableton/Link.hpp>

#include <atomic>
#include <cmath>

namespace {

// One process-wide Link instance, default 120 BPM. Lazily constructed so the
// networking thread only spins up once Link is actually used.
ableton::Link& link() {
    static ableton::Link instance{120.0};
    return instance;
}

// Shared phase reference: 4 beats = one bar. Beat counting itself is per
// quarter-note regardless of this value.
constexpr double kQuantum = 4.0;

// Beat-counting state, touched only on the polling (GL) thread. `gRebaseline`
// is the one cross-thread flag: the UI thread sets it on enable so the next
// poll re-zeroes the counter instead of emitting a burst of missed beats.
std::atomic<bool> gRebaseline{true};
double gLastBeat = 0.0;

} // namespace

namespace velo {

void linkSetEnabled(bool enabled) {
    try {
        link().enable(enabled);
        if (enabled) gRebaseline.store(true, std::memory_order_relaxed);
    } catch (...) {
    }
}

bool linkIsEnabled() {
    try {
        return link().isEnabled();
    } catch (...) {
        return false;
    }
}

int linkPollBeats() {
    try {
        auto& l = link();
        if (!l.isEnabled()) {
            gRebaseline.store(true, std::memory_order_relaxed);
            return 0;
        }

        // Query the beat position using Link's OWN clock domain — this is what
        // keeps us phase-aligned with Traktor rather than drifting against
        // Android's System.nanoTime().
        const auto time = l.clock().micros();
        const auto state = l.captureAudioSessionState();   // realtime-safe
        const double beat = state.beatAtTime(time, kQuantum);

        if (gRebaseline.exchange(false, std::memory_order_relaxed)) {
            gLastBeat = beat;
            return 0;                       // never punch on the first poll
        }

        const long prev = static_cast<long>(std::floor(gLastBeat));
        const long curr = static_cast<long>(std::floor(beat));
        gLastBeat = beat;

        long delta = curr - prev;
        if (delta < 0) delta = 0;           // transport / tempo jumped backwards
        if (delta > 4) delta = 1;           // clamp pathological jumps to one beat
        return static_cast<int>(delta);
    } catch (...) {
        return 0;
    }
}

double linkBeatPhase() {
    try {
        auto& l = link();
        if (!l.isEnabled()) return 0.0;
        const auto time = l.clock().micros();
        const auto state = l.captureAppSessionState();
        const double beat = state.beatAtTime(time, kQuantum);
        return beat - std::floor(beat);
    } catch (...) {
        return 0.0;
    }
}

double linkBarPhase() {
    try {
        auto& l = link();
        if (!l.isEnabled()) return 0.0;
        const auto time = l.clock().micros();
        const auto state = l.captureAppSessionState();
        // phaseAtTime returns 0..quantum (position within the bar); normalise to 0..1.
        const double phase = state.phaseAtTime(time, kQuantum);
        return phase / kQuantum;
    } catch (...) {
        return 0.0;
    }
}

double linkTempo() {
    try {
        return link().captureAppSessionState().tempo();
    } catch (...) {
        return 0.0;
    }
}

int linkNumPeers() {
    try {
        return static_cast<int>(link().numPeers());
    } catch (...) {
        return 0;
    }
}

} // namespace velo
