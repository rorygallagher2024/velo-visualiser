# Oscillux

Oscillux is a professional-grade Android audio visualizer engineered around one obsession: **latency**. 

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders deliver **sub-10 ms audio-to-pixel** response at 120 Hz+.

That same low-latency philosophy is also able to drive **Philips Hue** lights in real time.

## Features

- **13 audio-reactive visualizers** — waveforms, spectra, particle/compute fluids, a scrolling spectrogram, and more (swipe or pick from the menu).
- **Sub-10 ms audio-to-pixel latency** via a lock-free native audio path, rendered at the panel's full 120 Hz+.
- **Real-time Philips Hue light sync** over the **Hue Entertainment streaming API** (local DTLS/UDP) — roughly an order of magnitude faster than the usual REST-based music apps. See [Latency — the whole point](#latency--the-whole-point).
- **HDR bloom** post-processing for real luminous glow (toggle).
- **Vibrate-on-beat haptics** (toggle).
- **Two audio sources** — raw *unprocessed* microphone, or internal/system audio (Spotify, YouTube) via screen-capture.
- **HDR output** (FP16 surface) on capable panels.
- **OLED burn-in protection** and **foldable / resizable** support — the render loop survives folds without recreating.

## ⚠️ Proprietary & Source-Available License
**This is NOT an Open Source project.** The source code is provided for educational, review, and personal compilation purposes only.

By accessing this repository, you agree to the following terms:
1. **No Commercial Use:** You may not monetize this software or any derivatives.
2. **No App Store Distribution:** You are strictly prohibited from compiling, repackaging, or distributing this software (or any modified version of it) to the Google Play Store, Amazon Appstore, or any other digital storefront.
3. **No Redistribution:** You may not redistribute modified binaries.

Any unauthorized commercial distribution will result in immediate DMCA takedown notices filed with the respective storefront and repository host.

---

## Latency — the whole point

Latency is Oscillux's reason to exist. 

Most "music + lights" apps drive Hue over the **legacy REST API**, which is rate-limited to ~10 commands/second and lands hundreds of milliseconds after the beat — visibly late. Oscillux instead streams over the **Hue Entertainment API**: a **DTLS-PSK encrypted UDP** channel (port 2100) pushing the binary *HueStream v2* protocol at ~50 Hz, fire-and-forget. That's the bridge's dedicated low-latency path.

**Audio → pixel (on-device):** **sub-10 ms.** Oboe runs the mic in `LowLatency · Exclusive · Unprocessed` mode straight into a lock-free ring buffer; the GL thread pulls the freshest window every vsync — no locks, no allocations, no extra buffering.

**Beat → light (end-to-end estimate):**

| Stage | Approx. |
|-------|---------|
| Mic capture (Oboe low-latency) | ~5–15 ms |
| Beat detection + packet build + DTLS encrypt | < 1 ms (on a 50 Hz sender, ≤ ~20 ms quantization) |
| Wi-Fi LAN hop to the bridge | ~1–5 ms |
| Bridge → Zigbee → bulb (Entertainment fast path) | ~25 ms |
| **Total, realistically** | **~40–70 ms** |

The app-side contribution is single-digit-to-~20 ms; the rest is the **Zigbee/bulb leg, which is inherent to Hue hardware** and no app can avoid. The win is that the streaming path is ~10× faster than the REST path a typical Hue app would use — so the lights feel locked to the beat, not chasing it.

> Honest caveats: numbers are engineering estimates (not lab-measured on your hardware); actual figures vary with device, Wi-Fi quality, and bulb model. The on-device audio→pixel path is the rigorously optimized part; the Hue figures are the realistic best case for the streaming API.

---

## The Visualizer Suite
Thirteen distinct, mathematically driven visualizers responsive to live audio with seamless state-machine transitions (swipe left/right, or pick from the Visualizers tab):

1. **Pro Oscilloscope:** High-precision distance-field "CRT phosphor" waveform monitor.
2. **Pro Tunnel:** A reactive 3D corridor depth-simulation that changes geometry on low frequencies.
3. **Fluid Dynamics:** A 100k-particle GPU compute-shader fluid driven by curl-noise and bass swirl.
4. **Volumetric Laser Array:** Raymarched atmospheric light shafts bound to a beat-synced rotation matrix.
5. **Topographic Bass Matrix:** A 3D vertex shader that deforms a wireframe plane into a reactive mountain range.
6. **Circular Spectrum Analyzer:** A logarithmic 128-segment ring with gravity peak-hold physics.
7. **Spectrum Bars:** A clean, classic bar spectrum with gravity peak-caps — correct at every screen size.
8. **Spectral Bloom:** A kaleidoscopic, domain-warped plasma mandala that blooms on the beat.
9. **Starscape:** A hyperspace star field that accelerates with the bass.
10. **Raw Oscilloscope:** A pure 1px waveform trace — no glow, no flourishes; every sample as-is (bypasses bloom).
11. **Spectrogram:** A scrolling time-frequency heatmap — the visual with *memory*; watch a beat's structure scroll by.
12. **Beat Fireworks:** Bass transients launch radial particle bursts over black.
13. **Phyllotaxis Bloom:** A golden-angle sunflower spiral whose dots are driven per-bin by the FFT.

All scenes (except the deliberately-pure Raw Oscilloscope) can be routed through an **HDR bloom** pipeline — scenes render to an offscreen FP16 buffer, highlights are bright-passed and Gaussian-blurred at quarter-res, then additively composited for real luminous glow. Toggle it under **Display → Glow**. It runs entirely within each frame (no added latency) and bypasses itself if disabled or unsupported.

---

## Installation & Setup

Compiled APKs for personal use are provided in the **Releases** tab.

### Two Input Sources
* **Environmental Mic:** Bypasses Android's background noise cancellation for raw, unprocessed transient detection. Started on launch.
* **System Audio:** Captures internal audio (like Spotify or YouTube) directly. *(Note: Apps can only capture playback from other apps that allow it; DRM/protected audio is excluded by the platform).*

---

## Smart Lighting — Philips Hue Sync

The app can drive **Philips Hue** lights in real time from the same audio it visualizes, using the **Hue Entertainment API** for low-latency streaming (not the slow per-bulb REST endpoints). For the latency rationale and figures, see [Latency — the whole point](#latency--the-whole-point).

Open the settings sheet (swipe up) and switch to the **Lighting** tab:

1. **Connect Hue Bridge** — discovers your bridge on the local network (mDNS, with a cloud-discovery fallback) and starts a 30-second pairing window. **Press the physical button on your Hue Bridge** when prompted.
2. **Select an Entertainment Area** — the list populates once paired. (Create areas in the official Philips Hue app first; this app does not create them.)
3. **Light Sync: On** — opens the realtime stream and starts driving the lights.

A live **connection indicator** shows the current state (Disconnected / Searching / Bridge connected / Connected — syncing).

> **Microphone source only (for now).** Light sync currently runs from the **Microphone** source. Internal/system audio arrives near full-scale and saturates the native 3-band analyzer, washing the lights to white — so sync is gated to the mic, where it reacts cleanly. (Visualizers work on both sources; only the lights are mic-only.)

### How it maps audio → light
- A dedicated ~50 Hz sender thread reads the live FFT bands and maps them to per-channel colour — bass→warm, treble→cool — with a low-band **beat flash** on transients.
- The audio tap from the render loop is allocation-free; all networking happens off the GL/UI thread.

### How it connects (technical)
- **Pairing** mints a `username` + `clientkey` via the bridge's create-user endpoint (`generateclientkey`).
- **Streaming** uses **DTLS-PSK** (BouncyCastle) over **UDP port 2100**, with the `username` as the DTLS identity and the hex-decoded `clientkey` as the pre-shared key, sending the binary **Hue Stream v2** protocol.
- The CLIP v2 REST calls (area list, stream start/stop) go over HTTPS to the bridge.

### Persistence & privacy
- **Your bridge is remembered between sessions.** The `username` + `clientkey` are stored in **`EncryptedSharedPreferences`**, along with your selected Entertainment Area. You won't need to press the bridge button again — on relaunch, "Connect" simply refreshes the area list. (Light sync itself is not auto-started; flip the toggle to resume.)
- **Local only.** Everything runs over your LAN — your phone and bridge must be on the same Wi-Fi. No Hue cloud account is used and nothing about your audio or lights leaves the network.
- **No bridge found?** If discovery turns up nothing, the app says so and re-enables the Connect button to retry — check that both devices are on the same network.

---

## Haptics — Vibrate on Beat

An optional setting (**Display → Vibrate on Beat**, off by default) fires a short vibration pulse on each detected bass transient, with pulse strength and length scaled to the beat energy (on devices with amplitude control). Detection is **bass-onset** (spectral flux on the low band of the raw PCM), so it tracks kicks within a busy mix rather than firing on overall level. Pulses are throttled (~120 ms minimum gap). Hidden/disabled automatically on devices without a vibrator.

Like Hue sync, haptics are **microphone-only**: system-audio capture is buffered, so its PCM lags what you hear and the buzz lands off-beat. The toggle greys out on the Internal Audio source.

---

## Developer Documentation & Architecture

Strict hybrid: a thin Kotlin shell over a native C++/Oboe audio engine, with an OpenGL ES render loop that pulls PCM straight from a lock-free native buffer. Direct microphone access bypasses Android's AGC for sub-10ms latency and real-time FFT frequency binning, driving 120 fps+ hardware-accelerated graphics.

    ┌──────────────────────────────────────────────────────────────┐
    │  Kotlin shell (UI / permissions / lifecycle)                   │
    │   MainActivity ── permissions, MediaProjection, 120 Hz mode     │
    │   AudioCaptureService ── system audio (AudioPlaybackCapture)    │
    │   VisualizerSurfaceView / VisualizerRenderer ── GLES loop       │
    │   hue/ ── Entertainment DTLS streaming · HapticController       │
    └───────────────┬───────────────────────────┬───────────────────┘
                    │ JNI (NativeBridge)          │ JNI push (system audio)
                    ▼                             ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  Native C++ engine                                             │
    │   AudioEngine (Oboe singleton) ── AAudio→OpenSL ES fallback     │
    │     • Input · LowLatency · Exclusive · Unprocessed              │
    │   CircularBuffer ── lock-free SPSC ring, zero-alloc writes      │
    └──────────────────────────────────────────────────────────────┘

### The Latency Path

1. Oboe AAudio callback (`AudioEngine::onAudioReady`) writes raw float PCM into the ring buffer — two `memcpy`s + one atomic store, no malloc, no lock.
2. The GL thread, every vsync, calls `NativeBridge.fillLatestAudioBuffer()` which copies the latest window directly into a reused Java `FloatArray`.
3. The renderer streams that array into a VBO and draws a `GL_LINE_STRIP`. The vertex Y is the PCM amplitude — the line *is* the waveform.

---

## File Map

    settings.gradle / build.gradle / gradle.properties   project config
    app/build.gradle                                     SDK 37, NDK arm64-v8a only
    app/src/main/AndroidManifest.xml                     perms, FGS, configChanges
    app/src/main/cpp/
      CMakeLists.txt        fetches + builds Oboe 1.9.0 via FetchContent
      CircularBuffer.h      lock-free SPSC ring buffer
      AudioEngine.{h,cpp}   Oboe low-latency input singleton
      FftProcessor.{h,cpp}  KissFFT 3-band analysis
      native-lib.cpp        JNI bridge (fillLatestAudioBuffer, etc.)
    app/src/main/java/com/lowlatency/visualizer/
      NativeBridge.kt              JNI surface
      MainActivity.kt              shell, permissions, projection, splash, settings
      AudioCaptureService.kt       MediaProjection system-audio capture
      HapticController.kt          vibrate-on-beat
      VisualizerSurfaceView.kt     high-refresh GLSurfaceView
      gl/VisualizerRenderer.kt     scene manager + HDR bloom pipeline
      gl/*Scene.kt                 the 13 visualizers + PostProcessor
      hue/                         Hue Entertainment setup + DTLS stream client

---

## Build Requirements

Open in Android Studio (Meerkat 2024.3.1 or higher), or run:

    ./gradlew :app:assembleDebug

* **Android SDK:** `targetSdk 37` (Android 17 / Cinnamon Bun)
* **NDK:** Version 28+
* **CMake:** 3.22.1

The first native build clones and compiles Oboe automatically (network access required once; it is then cached). Builds **arm64-v8a only** — use a physical ARM device, not an x86 emulator.

### Notes / Next Steps

* `getLatestAudioBuffer()` (allocating) is provided per spec; `fillLatestAudioBuffer(FloatArray)` is the zero-GC variant used by the loop.
* The Gradle wrapper JAR is not checked in; run `gradle wrapper` once or let Android Studio generate it.