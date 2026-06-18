# Oscillux

Oscillux is a professional-grade Android audio visualizer engineered around one obsession: **latency**. 

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders bypass standard Android bottlenecks to deliver sub-10 ms audio-to-pixel response, while driving Philips Hue lights and device haptics in real-time.

## Features

- **13 audio-reactive visualizers** — Waveforms, spectra, particle fluids, scrolling spectrograms, and more.
- **HDR Bloom** — Post-processing for real luminous glow on capable FP16 panels.
- **Two audio sources** — Raw microphone capture or internal/system audio via screen-share.
- **Real-time Philips Hue Sync** — Direct local UDP streaming over the Hue Entertainment API.
- **Vibrate-on-beat haptics** — Bass-onset detection triggers physical pulses.
- **Foldable & Resizable Support** — The render loop survives screen state changes without recreating.

---

## ⚠️ Proprietary & Source-Available License
**This is NOT an Open Source project.** The source code is provided for educational, review, and personal compilation purposes only.

By accessing this repository, you agree to the following terms:
1. **No Commercial Use:** You may not monetize this software or any derivatives.
2. **No App Store Distribution:** You are strictly prohibited from compiling, repackaging, or distributing this software (or any modified version of it) to the Google Play Store, Amazon Appstore, or any other digital storefront.
3. **No Redistribution:** You may not redistribute modified binaries.

Any unauthorized commercial distribution will result in immediate DMCA takedown notices filed with the respective storefront and repository host.

---

## Why Oscillux is Fast (The Architecture)

Many visualizers suffer from inherent 100ms+ delays due to their reliance on high-level Java APIs like `AudioFlinger` or `android.media.audiofx.Visualizer`. Oscillux eliminates these bottlenecks by operating almost entirely at the operating system's hardware floor.

1. **Direct-to-Metal Audio (AAudio/Oboe):** The app completely bypasses the Android system mixer (`AudioFlinger`). Using Oboe in `LowLatency · Exclusive · Unprocessed` mode, the C++ engine reads raw PCM float data directly from the ALSA hardware driver.
2. **Zero Garbage Collection (GC) Stutters:** Audio processing, FFT analysis (KissFFT), and IoT networking execute entirely within a native C++ loop using a lock-free, single-producer/single-consumer (SPSC) ring buffer. Because there are no Java memory allocations during the render loop, the Dalvik/ART Garbage Collector never pauses the visualizer.
3. **Direct-to-Radio IoT Networking:** For smart lighting, the DTLS payload encryption and UDP socket transmission execute inside the native C++ loop. The packet hits the phone's Wi-Fi radio immediately after the FFT calculation, bypassing standard Java networking delays.

### End-to-End Latency Estimates

**Audio → Pixel (On-Device): Sub-10 ms.** The GL thread pulls the freshest buffer every vsync. Rendered at 120 Hz+, the hardware display panel is the only significant limiting factor.

**Beat → Light (Philips Hue): ~40–70 ms.**
While the app's calculation is near-instant, the total physical time to change a lightbulb is bottlenecked by the Hue Bridge's Zigbee mesh network.

| Stage | Approx. Time |
|-------|---------|
| Mic capture (Oboe low-latency) | ~5–15 ms |
| Beat detection + packet build + DTLS encrypt | < 1 ms |
| Wi-Fi LAN hop to the bridge | ~1–5 ms |
| Bridge → Zigbee mesh → physical bulb | ~25 ms |

> *Note: Figures vary with device hardware, Wi-Fi congestion, and specific bulb generations.*

---

## Input Sources & Permissions

The app visualizes audio from two selectable sources. 
*(Note: Philips Hue Sync and Beat Haptics are currently restricted to the **Microphone** source, as system audio arrives near full-scale and washes out the transient detectors).*

* **Environmental Mic:** Bypasses Android's background noise cancellation for raw transient detection. 
  * *Requires `RECORD_AUDIO` permission.*
* **System Audio:** Captures internal audio playback (e.g., Spotify, YouTube). 
  * *Requires `MediaProjection` screen-capture permission per session. DRM-protected audio is blocked by the OS.*

### Android 17 Local Network Protections (API 37)
Because Oscillux targets **Android 17 (`targetSdk 37`)**, it is subject to the OS's strict Local Network Protections. To reach the Hue Bridge on your local LAN, Oscillux requests the **Local Network** runtime permission (`ACCESS_LOCAL_NETWORK` / `NEARBY_WIFI_DEVICES`). 
* If denied or revoked, Android will silently drop all outbound packets to your `192.168.x.x` subnet. The app will not crash, but Hue discovery will fail, and direct IP connections will result in silent 5000ms timeouts. 

---

## Smart Lighting (Philips Hue Sync)

Oscillux drives Hue lights using the binary **Hue Stream v2** protocol over **DTLS-PSK encrypted UDP** (Port 2100) at ~50 Hz. 

1. **Pairing:** Swipe up to the **Lighting** tab. Tap *Connect Hue Bridge*. The app uses mDNS (with an N-UPnP cloud fallback) to find your local bridge. Press the physical button on the bridge when prompted to mint a local `clientkey`.
2. **Setup:** Select an Entertainment Area (these must be created in the official Philips Hue app first).
3. **Persistence:** The `username`, `clientkey`, and Area ID are stored locally via `EncryptedSharedPreferences`. Oscillux operates entirely on your LAN without pinging the Hue Cloud.

---

## The Visualizer Suite
Thirteen mathematically driven visualizers with seamless state-machine transitions (swipe left/right on the canvas):

1. **Pro Oscilloscope:** High-precision distance-field "CRT phosphor" waveform monitor.
2. **Pro Tunnel:** A reactive 3D corridor depth-simulation that changes geometry on low frequencies.
3. **Fluid Dynamics:** A 100k-particle GPU compute-shader fluid driven by curl-noise and bass swirl.
4. **Volumetric Laser Array:** Raymarched atmospheric light shafts bound to a beat-synced rotation matrix.
5. **Topographic Bass Matrix:** A 3D vertex shader that deforms a wireframe plane into a reactive mountain range.
6. **Circular Spectrum Analyzer:** A logarithmic 128-segment ring with gravity peak-hold physics.
7. **Spectrum Bars:** A clean, classic bar spectrum with gravity peak-caps.
8. **Spectral Bloom:** A kaleidoscopic, domain-warped plasma mandala that blooms on the beat.
9. **Starscape:** A hyperspace star field that accelerates with the bass.
10. **Raw Oscilloscope:** A pure 1px waveform trace — no glow, no flourishes; every sample as-is.
11. **Spectrogram:** A scrolling time-frequency heatmap.
12. **Beat Fireworks:** Bass transients launch radial particle bursts over black.
13. **Phyllotaxis Bloom:** A golden-angle sunflower spiral whose dots are driven per-bin by the FFT.

---

## Developer Documentation

Strict hybrid architecture: a thin Kotlin shell manages UI, permissions, and lifecycle, while delegating all real-time processing to the C++ core.

    ┌──────────────────────────────────────────────────────────────┐
    │  Kotlin shell (UI / permissions / lifecycle)                 │
    │   MainActivity ── permissions, MediaProjection, 120 Hz mode  │
    │   AudioCaptureService ── system audio (AudioPlaybackCapture) │
    │   VisualizerSurfaceView / VisualizerRenderer ── GLES loop    │
    │   hue/ ── Entertainment DTLS streaming · HapticController    │
    └───────────────┬───────────────────────────┬──────────────────┘
                    │ JNI (NativeBridge)          │ JNI push (system audio)
                    ▼                             ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  Native C++ engine                                           │
    │   AudioEngine (Oboe singleton) ── AAudio→OpenSL ES fallback  │
    │    • Input · LowLatency · Exclusive · Unprocessed            │
    │   CircularBuffer ── lock-free SPSC ring, zero-alloc writes   │
    └──────────────────────────────────────────────────────────────┘

### File Map

    app/build.gradle                     SDK 37, NDK arm64-v8a only
    app/src/main/AndroidManifest.xml     perms, FGS, configChanges
    app/src/main/cpp/
      CMakeLists.txt                     fetches + builds Oboe 1.9.0 via FetchContent
      CircularBuffer.h                   lock-free SPSC ring buffer
      AudioEngine.{h,cpp}                Oboe low-latency input singleton
      FftProcessor.{h,cpp}               KissFFT 3-band analysis
      native-lib.cpp                     JNI bridge
    app/src/main/java/com/lowlatency/visualizer/
      NativeBridge.kt                    JNI surface
      MainActivity.kt                    shell, permissions, projection, UI sheet
      AudioCaptureService.kt             MediaProjection system-audio capture
      HapticController.kt                vibrate-on-beat
      VisualizerSurfaceView.kt           high-refresh GLSurfaceView
      gl/VisualizerRenderer.kt           scene manager + HDR bloom pipeline
      gl/*Scene.kt                       the 13 visualizers + PostProcessor
      hue/                               Hue Entertainment REST/DTLS clients

### Build Requirements

Open in Android Studio (Meerkat 2024.3.1 or higher), or run: `./gradlew :app:assembleDebug`

* **Android SDK:** `targetSdk 37` (Android 17 / Cinnamon Bun)
* **NDK:** Version 28+
* **CMake:** 3.22.1

*Builds **arm64-v8a only** — use a physical ARM device, not an x86 emulator. The first native build clones and compiles Oboe automatically.*
