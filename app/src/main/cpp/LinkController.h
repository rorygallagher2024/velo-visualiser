#pragma once

// Thin, exception-free-at-the-boundary wrapper around ableton::Link.
//
// This is the ONLY translation unit that touches Link (and, transitively,
// asio + C++ exceptions). It is compiled as its own static library WITH
// exceptions/RTTI enabled (see CMakeLists.txt), while the rest of the
// latency-critical engine stays -fno-exceptions. Every function below catches
// anything Link might throw so no exception ever escapes into the no-exceptions
// engine that calls it.
namespace velo {

// Enable/disable the Link session (joins/leaves the local-network tempo group).
// Safe to call from the UI thread.
void linkSetEnabled(bool enabled);

// Whether the Link session is currently enabled.
bool linkIsEnabled();

// Beats elapsed since the previous call — usually 0, or 1 on a beat boundary.
// Realtime-safe; call once per frame from the render (GL) thread only.
int linkPollBeats();

// Current shared session tempo in BPM (0 if unavailable). UI-thread safe.
double linkTempo();

// Number of other Ableton Link peers on the network. UI-thread safe.
int linkNumPeers();

} // namespace velo
