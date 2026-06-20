# Velo Visualiser

Velo visualiser is a professional-grade android audio visualiser engineered around one primary objective: **low latency**. 

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders bypass standard Android bottlenecks to deliver sub-10 ms audio-to-pixel response, while driving Philips Hue lights and device haptics in real-time.

The stand-out features:
1. **The MOST reactive visualiser** with sub 10ms latency from sound to pixel
2. **Two audio sources** supporting microphone and device audio
2. **HDR capable 3D visuals** targeting 120+ fps for most visuals (Device dependent)
3. **Ableton Link support** so you can supplement the visuals with reliable beat detection coming directly from music & DJ software
4. **Philips Hue Lighting Support** for reactive room lighting powered by a low Latency lighting integration
5. **No nonsense**. This app is local only with no data collection, and no ads. I don't want your data and none of us want ads.

## Demo

**Velo Visualiser Demo Video**
[![Velo Demo](https://img.youtube.com/vi/J6lmJJCc-Lo/maxresdefault.jpg)](https://youtu.be/J6lmJJCc-Lo)
`▶ Watch on YouTube`

**Velo Visualiser: Ableton Link & Philips Hue Lighting Integration**
[![Velo Lighting Demo](https://img.youtube.com/vi/DZwIyPs-1f8/maxresdefault.jpg)](https://www.youtube.com/watch?v=DZwIyPs-1f8)
`▶ Watch on YouTube`


## The full feature list

- **20 audio-reactive visualizers**: Waveforms, spectra, particle fluids, scrolling spectrograms, dot-matrix LED meters, and more.
- **HDR Effects**: Including post-processing for real luminous glow on capable FP16 panels and selectable glow strength.
- **Global colour themes**: A single post-process colour grade re-tints every visual (Neon, Warm, Cool, Mono…) at zero per-scene cost.
- **Real-time Philips Hue Sync Integration**: Direct local UDP streaming over the Hue Entertainment API. Also works in co-ordination with Ableton Link to drive a sychronised beat to the bulbs. Included advanced controls for calibration and the ability to send Ableton Link beats early for perfect synchronisation.
- **Ableton Link sync**: Lock beat-driven effects to Traktor, Ableton Live, and other Link software over Wi-Fi; the mic still drives the visuals while Link sets the beat.
- **Two audio sources**: Raw microphone capture or internal/system audio via screen-share.
- **Vibrate-on-beat haptics**: Bass-onset detection triggers physical pulses.
- **Foldable & Resizable Support**: The render loop survives screen state changes without recreating.
- **Diagnostics Overlay Toggle**: Displays FPS, audio latency, Ableton link status, Hue drop rates and more.

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

**Audio → Pixel (On-Device): Sub-10 ms.** 
**Beat → Light (Philips Hue): ~40–70 ms.**

While the app's calculation is near-instant, total physical time to change a lightbulb is bottlenecked by the Hue Bridge's Zigbee mesh network.

| Stage | Approx. Time |
|-------|---------|
| Mic capture (Oboe low-latency) | ~5–15 ms |
| Beat detection + packet build + DTLS encrypt | < 1 ms |
| Wi-Fi LAN hop to the bridge | ~1–5 ms |
| Bridge → Zigbee mesh → physical bulb | ~25 ms |

---

## Smart Lighting (Philips Hue Sync)

Velo drives Hue lights using the **Hue Stream v2** protocol over **DTLS-PSK encrypted UDP** (Port 2100). 

1. **Pairing:** Swipe up, and select the **Lighting** tab. Tap *Connect Hue Bridge*. The app uses mDNS to find your local bridge. Press the physical button on the bridge when prompted.
2. **Setup:** Select an Entertainment Area (must be created in the official Philips Hue app first).
3. **Persistence:** The `username` and `clientkey` are stored locally via `EncryptedSharedPreferences`. 

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

### Tested and validated on
- Google Pixel 10 Pro Fold
- Google Pixel Tablet
- Samsung Galaxy Tab S8 Ultra
