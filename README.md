# Velo Visualiser

Velo is an Android audio visualizer engineered around one primary objective: **low latency**. 

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders bypass standard Android bottlenecks to deliver sub-10 ms audio-to-pixel response, while driving Philips Hue lights and device haptics in real-time.

## Features

- **18 audio-reactive visualizers** — Waveforms, spectra, particle fluids, scrolling spectrograms, dot-matrix LED meters, and more.
- **HDR Bloom** — Post-processing for real luminous glow on capable FP16 panels, with selectable glow strength.
- **Global colour themes** — A single post-process colour grade re-tints every visual (Neon, Warm, Cool, Mono…) at zero per-scene cost.
- **Two audio sources** — Raw microphone capture or internal/system audio via screen-share.
- **Real-time Philips Hue Sync** — Direct local UDP streaming over the Hue Entertainment API.
- **Vibrate-on-beat haptics** — Bass-onset detection triggers physical pulses.
- **Foldable & Resizable Support** — The render loop survives screen state changes without recreating.

## ⚠️ Proprietary & Source-Available License
**This is NOT an Open Source project.** The source code is provided for educational, review, and personal compilation purposes only.

By accessing this repository, you agree to the following terms:
1. **No Commercial Use:** You may not monetize this software or any derivatives.
2. **No App Store Distribution:** You are strictly prohibited from compiling, repackaging, or distributing this software (or any modified version of it) to the Google Play Store, Amazon Appstore, or any other digital storefront.
3. **No Redistribution:** You may not redistribute modified binaries.

Any unauthorized commercial distribution will result in immediate DMCA takedown notices.

---

## Why Velo is Fast (The Architecture)

Most Android visualizers suffer from inherent 100ms+ delays due to their reliance on high-level Java APIs like `AudioFlinger` or `android.media.audiofx.Visualizer`. Velo eliminates these bottlenecks by operating at the OS hardware floor.

1. **Direct-to-Metal Audio (AAudio/Oboe):** The app bypasses the Android system mixer. Using Oboe in `LowLatency · Exclusive · Unprocessed` mode, the C++ engine reads raw PCM float data directly from the ALSA hardware driver.
2. **Zero Garbage Collection (GC) Stutters:** Audio processing, FFT analysis (KissFFT), and IoT networking execute entirely within a native C++ loop using a lock-free, single-producer/single-consumer (SPSC) ring buffer. No Java memory is allocated during the render loop.
3. **Direct-to-Radio IoT Networking:** For smart lighting, DTLS payload encryption and UDP socket transmission execute inside the native C++ loop. The packet hits the Wi-Fi radio immediately after the FFT calculation.

### End-to-End Latency Estimates

**Audio → Pixel (On-Device): Sub-10 ms.** **Beat → Light (Philips Hue): ~40–70 ms.**
While the app's calculation is near-instant, total physical time to change a lightbulb is bottlenecked by the Hue Bridge's Zigbee mesh network.

| Stage | Approx. Time |
|-------|---------|
| Mic capture (Oboe low-latency) | ~5–15 ms |
| Beat detection + packet build + DTLS encrypt | < 1 ms |
| Wi-Fi LAN hop to the bridge | ~1–5 ms |
| Bridge → Zigbee mesh → physical bulb | ~25 ms |

---

## Input Sources & Permissions

* **Environmental Mic:** Bypasses Android's background noise cancellation. *Requires `RECORD_AUDIO` permission.*
* **System Audio:** Captures internal audio playback. *Requires `MediaProjection` screen-capture permission per session.*

---

## Smart Lighting (Philips Hue Sync)

Velo drives Hue lights using the **Hue Stream v2** protocol over **DTLS-PSK encrypted UDP** (Port 2100). 

1. **Pairing:** Swipe up to the **Lighting** tab. Tap *Connect Hue Bridge*. The app uses mDNS to find your local bridge. Press the physical button on the bridge when prompted.
2. **Setup:** Select an Entertainment Area (must be created in the official Philips Hue app first).
3. **Persistence:** The `username` and `clientkey` are stored locally via `EncryptedSharedPreferences`. 

---

## Demo

[![Velo Demo](https://img.youtube.com/vi/NZ0ZSBPTFWk/maxresdefault.jpg)](https://youtu.be/NZ0ZSBPTFWk)

---

## Installation & Setup

⚠️ **Current Status: Technical Alpha** Since Velo is distributed via GitHub, Android will display an "Unverified Developer" warning during installation. Tap **"More details"** and then **"Install anyway."**

Compiled APKs for personal testing are provided in the **Releases** tab.

### Build Requirements
Open in Android Studio (Meerkat 2024.3.1 or higher):
* **Android SDK:** `targetSdk 37`
* **NDK:** Version 28+
* **CMake:** 3.22.1
* **Architecture:** `arm64-v8a` only.

---

### Tested On
- Google Pixel 10 Pro Fold
- Google Pixel Tablet
- Samsung Galaxy Tab S8 Ultra
