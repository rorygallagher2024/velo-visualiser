# Oscillux Audio Visualizer

A professional-grade, high-performance, zero-latency audio visualizer for Android, targeting **sub-10 ms audio-to-pixel latency** for the most reactive visuals even at 120hz+. Built with a bare-metal C++ audio engine and custom OpenGL ES 3.1 shaders.

## ⚠️ Proprietary & Source-Available License
**This is NOT an Open Source project.** The source code is provided for educational, review, and personal compilation purposes only.

By accessing this repository, you agree to the following terms:
1. **No Commercial Use:** You may not monetize this software or any derivatives.
2. **No App Store Distribution:** You are strictly prohibited from compiling, repackaging, or distributing this software (or any modified version of it) to the Google Play Store, Amazon Appstore, or any other digital storefront.
3. **No Redistribution:** You may not redistribute modified binaries.

Any unauthorized commercial distribution will result in immediate DMCA takedown notices filed with the respective storefront and repository host.

---

## The Visualizer Suite
Five distinct, mathematically driven visualizers responsive to live audio with seamless state-machine transitions:

1. **Pro Oscilloscope:** High-precision vector waveform monitor.
2. **Pro Tunnel:** A reactive 3D corridor depth-simulation changing geometry dynamically based on low frequencies.
3. **Volumetric Laser Array:** Atmospheric light shafts bounded to a rotation matrix.
4. **Topographic Bass Matrix:** A 3D vertex shader that deforms a wireframe plane into a reactive mountain range.
5. **Circular Spectrum Analyzer:** A logarithmic 128-segment ring with peak-hold physics.

---

## Installation & Setup

Compiled APKs for personal use are provided in the **Releases** tab.

### Two Input Sources
* **Environmental Mic:** Bypasses Android's background noise cancellation for raw, unprocessed transient detection. Started on launch.
* **System Audio:** Captures internal audio (like Spotify or YouTube) directly. *(Note: Apps can only capture playback from other apps that allow it; DRM/protected audio is excluded by the platform).*

### Foldable & Desktop Support
The application features seamless window resizing. The UI and render loop are not destroyed upon folding/unfolding the device. The viewport automatically recalculates its extents so the waveform and shaders maintain proper aspect ratio compliance without stretching.

---

## Developer Documentation & Architecture

Strict hybrid: a thin Kotlin shell over a native C++/Oboe audio engine, with an OpenGL ES render loop that pulls PCM straight from a lock-free native buffer. Direct microphone access bypasses Android's AGC for sub-10ms latency and real-time FFT frequency binning, driving 60fps+ hardware-accelerated graphics.

    ┌──────────────────────────────────────────────────────────────┐
    │  Kotlin shell (UI / permissions / lifecycle)                   │
    │   MainActivity ── permissions, MediaProjection, 120 Hz mode     │
    │   AudioCaptureService ── system audio (AudioPlaybackCapture)    │
    │   OscilloscopeGLSurfaceView / Renderer ── GLES render loop      │
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
    app/build.gradle                                     SDK 36, NDK arm64-v8a only
    app/src/main/AndroidManifest.xml                     perms, FGS, configChanges
    app/src/main/cpp/
      CMakeLists.txt        fetches + builds Oboe 1.9.0 via FetchContent
      CircularBuffer.h      lock-free SPSC ring buffer
      AudioEngine.{h,cpp}   Oboe low-latency input singleton
      native-lib.cpp        JNI bridge (getLatestAudioBuffer, etc.)
    app/src/main/java/com/lowlatency/visualizer/
      NativeBridge.kt              JNI surface
      MainActivity.kt              shell, permissions, projection, refresh rate
      AudioCaptureService.kt       MediaProjection system-audio capture
      OscilloscopeGLSurfaceView.kt high-refresh GLSurfaceView
      OscilloscopeRenderer.kt      GLES 3.1 pipeline

---

## Build Requirements

Open in Android Studio (Meerkat 2024.3.1 or higher), or run:

    ./gradlew :app:assembleDebug

* **Android SDK:** `targetSdk 36` (Ready for Android 16 / Baklava)
* **NDK:** Version 28+
* **CMake:** 3.22.1

The first native build clones and compiles Oboe automatically (network access required once; it is then cached). Builds **arm64-v8a only** — use a physical ARM device, not an x86 emulator.

### Notes / Next Steps

* `getLatestAudioBuffer()` (allocating) is provided per spec; `fillLatestAudioBuffer(FloatArray)` is the zero-GC variant used by the loop.
* The Gradle wrapper JAR is not checked in; run `gradle wrapper` once or let Android Studio generate it.