<p align="center">
  <img src="app/src/main/res/drawable-nodpi/velo_logo.png" alt="Velo Visualiser" width="420">
</p>

## Velo: Low Latency Visualiser

Velo is a Android audio visualiser engineered around one primary objective: **low latency**. 

When you open Velo, you'll see the default oscilloscope visualisation which responds to microphone input in **less than 10ms**. That is faster than:
* **The blink of an eye** (which takes 100–400 ms).
* **Human tactile perception** (the 20–30 ms it takes your brain to register touching a drum pad).
* **The physical speed of sound** travelling from speakers on one side of the room to another (3.4 metres in 10 ms).

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders bypass standard Android bottlenecks to deliver sub-10 ms audio-to-pixel response, whilst also capable of driving Philips Hue lights and device haptics in real-time.

Beyond its absolute speed and zero-lag smart lighting integration, Velos visuals are a great showcase for premium Android hardware because it can push modern flagship devices to their limits combining 120Hz tear-free OLED motion, true HDR luminance, and dynamic scaling for tablet displays and foldables.

## Demo
**Velo: Low Latency Music Visualiser Demo Video**
[![Velo Demo](https://img.youtube.com/vi/ql0CwtlYDyI/maxresdefault.jpg)](https://youtu.be/ql0CwtlYDyI)
`▶ Watch on YouTube`

## Reacts to the sound. Locks to the set.

Velo listens to the **actual sound** in the room and moves with it instantly. The sub-10 ms reaction is what the visuals are built on. Switch on **Ableton Link** (Works with Traktor, Ableton Live, Serato and more) and Velo also locks onto your set's tempo, layering a tightly-timed **extra punch and bloom** over the top — even *anticipating* each beat a hair before it lands, the way only a shared musical clock can. The faithful "instrument" visuals (oscilloscope, spectrum, meters) stay a pure readout of the sound, while the reactive scenes get that grid-locked accent. Sound drives the picture; Link adds the polish.

*(And as experimental extras: Bar-synced glow and drop-triggered surges - for when you want the visuals to follow the arrangement, not just the beat.)*

## Core Features
* **High-FPS, HDR-Capable 3D Visuals:** Targets 120+ fps for fluid, tear-free rendering (device and preset dependent).
* **Ableton Link Integration:** Supplement the microphone input with perfect phase-synchronization and predictive beat detection broadcast directly from your DJ software (Traktor, Live, Serato).
* **Philips Hue Sync:** Drive your physical room lighting with the exact same zero-lag transient detection used for the on-screen visuals.
* **No Nonsense:** 100% local processing. No data collection. No ads. I don't want your data, and nobody wants ads.

## The full feature list

- **25 audio and beat reactive visualizers**: Waveforms, spectra, particle fluids, scrolling spectrograms, dot-matrix LED meters, and more.
- **HDR Effects**: Including post-processing for real luminous glow on capable HDR displays and a selectable glow strength.
- **Ableton Link sync**: Lock beat-driven effects to Traktor, Ableton Live, and other Link software over Wi-Fi; the mic still drives the visuals while Link sets the beat.
- **Real-time Philips Hue Sync Integration**: Direct local UDP streaming over the Hue Entertainment API. Also works in co-ordination with Ableton Link to drive a sychronised beat to the bulbs. Includes lighting controls, advanced controls for calibration and the ability to send Ableton Link beats early for perfect synchronisation.
- **Two audio sources**: Raw low-latency microphone capture or internal/system audio via screen-share (Warning: Low latency not supported via screen-sharing).
- **Global colour themes**: Re-tint visuals to your desired colour scheme (Neon, Warm, Cool, Mono…).
- **Vibrate-on-beat haptics**: Bass-onset detection triggers physical pulses.
- **Foldable & Tablet Support**: Open a foldable phone and the render loop will survive the screen state changes without recreating.
- **Diagnostics Overlay Toggle**: Displays FPS, audio latency, Ableton link status, Hue drop rates and more.

## License

Velo Visualiser is **free and open source software**, licensed under the
**GNU General Public License v3.0** — see [LICENSE](LICENSE).

You're free to use, study, modify, and redistribute it. Any distributed
derivative must also remain GPLv3. (The GPL is required here because the app
integrates [Ableton Link](https://github.com/Ableton/link), which is GPLv2+
unless used under a commercial licence from Ableton.)

No data collection, no ads, no tracking — local-only, and it'll stay that way.

## Support

Velo is free but its development cost is not, so if it brings some colour to your music and you'd
like to chip in for coffee, it's hugely appreciated:

<a href="https://www.buymeacoffee.com/rorygallagher2024"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="42"></a>

---

## About the Developer

Velo was engineered by me, Rory Gallagher (he/him). I have a background in software engineering and interests in music technology, live performance, hardware and IoT.

Feel free to connect:
* **LinkedIn:** [linkedin.com/in/rory-gallagher-51822532](https://www.linkedin.com/in/rory-gallagher-51822532)
* **YouTube:** [youtube.com/@rorygallagher-redslug](https://www.youtube.com/@rorygallagher-redslug)

---

## Why Velo is Fast

Many Android visualizers suffer from inherent 100ms+ delays due to their reliance on high-level Java APIs. Velo eliminates these bottlenecks by operating at the OS hardware floor.

### 1. The Simple Breakdown
By bypassing the Android system mixer and utilizing C++ zero-copy memory transfers, Velo processes the sound, calculates the frequencies, and paints the pixels on your screen in **~8.0 ms**.

### 2. The Detailed Breakdown (Hardware-to-Retina)
Here is the end-to-end latency estimate for Velo's gold-standard path (Microphone to Screen) on a modern Android device:

| Stage | Component | Est. Latency |
|---|---|---|
| **Capture** | Microphone Hardware + Oboe exclusive path | ~2.0 ms |
| **Ingest** | Native Engine Ring Buffer write | < 0.1 ms |
| **Analysis** | Native C++ 128-bin FFT (KissFFT) | ~0.3 ms |
| **Transfer** | Shared DirectBuffer (Zero-Copy) | < 0.1 ms |
| **Render** | GPU Shader execution (120Hz frame budget) | ~4.0 ms |
| **Display** | OLED Panel Scan-out response | ~1.5 ms |
| **TOTAL** | **Hardware-to-Retina** | **~8.0 ms** |

Many visualizers suffer from inherent 100ms+ delays due to their reliance on high-level Java APIs like `AudioFlinger` or `android.media.audiofx.Visualizer`. Velo eliminates these bottlenecks by operating at the OS hardware floor.

### 3. System Audio Latency (The Shared Path)
Velo allows you to visualize internal device audio (like Spotify or YouTube) using screen-capture APIs, but the latency profile is fundamentally different:

* **Inherent OS Buffer:** ~40–80 ms
* **Total End-to-End:** ~60–100 ms

This higher latency is an unavoidable Android OS limitation when intercepting shared system audio. However, Velo utilizes NEON CPU optimizations for this path. While NEON cannot lower the OS buffer delay, it ensures the CPU footprint remains microscopic during the 60ms capture window, guaranteeing the visualizer never stutters or drops frames while waiting for the system audio buffer.

### 4. Smart Lighting Latency (Philips Hue)
While the app's internal beat calculation is near-instant (< 1 ms), the physical time required to change a lightbulb is bottlenecked by your local network and the Hue Bridge's Zigbee mesh. Total time from beat-detection to physical light change is **~40–70 ms**:

| Stage | Approx. Time |
|-------|---------|
| Packet build + DTLS encrypt | < 0.5 ms |
| Wi-Fi LAN hop to the bridge | ~1–5 ms |
| Bridge processing | ~10–20 ms |
| Zigbee mesh → physical bulb | ~25 ms |

As part of the roadmap, I intend to add support for ESP32 WLED-based lights via UDP, which bypasses the Zigbee mesh entirely and will be significantly faster.

---

## Smart Lighting (Philips Hue Sync)

Velo drives Hue lights using the **Hue Stream v2** protocol over **DTLS-PSK encrypted UDP** (Port 2100). 

1. **Pairing:** Swipe up, and select the **Lighting** tab. Tap *Connect Hue Bridge*. The app uses mDNS to find your local bridge. Press the physical button on the bridge when prompted.
2. **Setup:** Select an Entertainment Area (must be created in the official Philips Hue app first).
3. **Persistence:** The `username` and `clientkey` are stored locally via `EncryptedSharedPreferences`. 

---

## Installation & Setup

Prebuilt APKs are available in the **Releases** tab. Since Velo is distributed
via GitHub rather than the Play Store, Android shows an "Unverified Developer"
warning on install — tap **"More details"** → **"Install anyway."**

### Build Requirements
Open in Android Studio (Meerkat 2024.3.1 or higher):
* **Android SDK:** `targetSdk 37`
* **NDK:** Version 28+
* **CMake:** 3.22.1
* **Architecture:** `arm64-v8a` only.

---

## Contributing

Contributions are welcome. Bug reports, fixes, and new visualizers especially.
Open an issue to discuss larger changes first. New scenes implement the
`GlScene` interface (`app/src/main/java/com/lowlatency/visualizer/gl/`) and are
registered in `VisualizerRenderer`.

By contributing, you agree your contributions are licensed under GPLv3.

## Acknowledgements

Velo stands on excellent open source work:
- [Oboe](https://github.com/google/oboe). Low-latency audio
- [Ableton Link](https://github.com/Ableton/link). Tempo sync
- [KissFFT](https://github.com/mborgerding/kissfft). FFT
- [OkHttp](https://square.github.io/okhttp/). Hue REST
- [Bouncy Castle](https://www.bouncycastle.org/). DTLS-PSK
- [Space Mono](https://fonts.google.com/specimen/Space+Mono). UI typeface
