<p align="center">
  <img src="app/src/main/res/drawable-nodpi/velo_logo.png" alt="Velo Visualiser" width="420">
</p>

## Velo Visualiser: Low Latency Music Visuals and Lighting

Velo is an Android audio visualiser engineered around one primary objective: **low latency**. 

When you open Velo, you'll see the default oscilloscope visualisation which responds to microphone input in **less than 10ms**. That is faster than:
* **The blink of an eye** (which takes 100–400 ms).
* **Human tactile perception** (the 20–30 ms it takes your brain to register touching a drum pad).
* **The physical speed of sound** travelling from speakers on one side of the room to another (3.4 metres in 10 ms).

A bare-metal C++/Oboe audio engine and custom OpenGL ES 3.1 shaders bypass standard Android bottlenecks to deliver sub-10 ms audio-to-pixel response, whilst also capable of driving smart home lighting and device haptics in real-time.

Beyond its absolute speed and zero-lag smart lighting integration, Velo's visuals are a great showcase for premium Android hardware because it can push modern flagship devices to their limits combining 120Hz tear-free OLED motion, true HDR luminance, and dynamic scaling for tablet displays and foldables.

## Demo
**Velo: Low Latency Music Visualiser Demo Video**
[![Velo Demo](https://img.youtube.com/vi/ql0CwtlYDyI/maxresdefault.jpg)](https://youtu.be/ql0CwtlYDyI)
`▶ Watch on YouTube`

## Beta Testers Needed

**Velo is currently in Google Play Closed Beta.** I am actively looking for testers to help me hit the Google Play testing requirements for a full production launch.

To install the beta via the Play Store, please follow these steps in order:
1. Join the testing group: [Velo Testers Google Group](https://groups.google.com/g/velo-testers)
2. Opt-in on the web: [Play Store Beta Opt-in](https://play.google.com/apps/testing/com.lowlatency.visualizer)
3. Download the app: [Velo on Google Play](https://play.google.com/store/apps/details?id=com.lowlatency.visualizer)

*(Alternatively, prebuilt APKs for direct sideloading are available in the **Releases** tab. Note: Android will show an "Unverified Developer" warning on install — tap "More details" → "Install anyway.")*

## Reacts to the sound. Locks to the set.

Velo listens to the **actual sound** in the room and moves with it instantly. The sub-10 ms reaction is what the visuals are built on. Switch on **[Ableton Link](https://www.ableton.com/en/link/)** (Works with Traktor, Ableton Live, Serato and more) and Velo also locks onto your set's tempo, layering a tightly-timed **extra punch and bloom** over the top — even *anticipating* each beat a hair before it lands, the way only a shared musical clock can. The faithful "instrument" visuals (oscilloscope, spectrum, meters) stay a pure readout of the sound, while the reactive scenes get that grid-locked accent. Sound drives the picture; Link adds the polish.

*(And as experimental extras: Bar-synced glow and drop-triggered surges - for when you want the visuals to follow the arrangement, not just the beat.)*

## Core Features
* **High-FPS, HDR-Capable 3D Visuals:** Targets 120+ fps for fluid, tear-free rendering (device and preset dependent).
* **Ableton Link Integration:** Supplement the microphone input with perfect phase-synchronization and predictive beat detection broadcast directly from your DJ software (Traktor, Live, Serato).
* **Room Lighting Control:** Drive your physical room lighting with the exact same zero-lag transient detection used for the on-screen visuals. Supports Philips Hue, Lifx bulbs, and Nanoleaf panels.
* **No Nonsense:** 100% local processing. No data collection. No ads. I don't want your data, and nobody wants ads.

## The full feature list

- **30 audio and beat reactive visualizers**: Waveforms, spectra, particle fluids, scrolling spectrograms, dot-matrix LED meters, and more.
- **HDR Effects**: Including post-processing for real luminous glow on capable HDR displays and a selectable glow strength.
- **Ableton Link sync**: Lock beat-driven effects to Traktor, Ableton Live, and other Link software over Wi-Fi; the mic still drives the visuals while Link sets the beat.
- **Real-time Room Lighting Control with Philips Hue, LIFX & Nanoleaf Integration**: Direct local UDP streaming over the Hue Entertainment API, LIFX LAN Protocol, and Nanoleaf ExtControl. Also works in co-ordination with Ableton Link to drive a synchronised beat to the bulbs. Includes advanced controls for calibration and the ability to send Ableton Link beats early for perfect synchronisation.
- **Two audio sources**: Raw low-latency microphone capture or internal/system audio via screen-share (Warning: Low latency not supported via screen-sharing).
- **Global colour themes**: Re-tint visuals to your desired colour scheme (Neon, Warm, Cool, Mono…).
- **Vibrate-on-beat haptics**: Bass-onset detection triggers physical pulses.
- **Supports External Displays**: Plug an external display into your android device to display visuals on a large display.
- **Ambient Mode**: A burn-in-safe standby screen featuring an  clock, live BPM, and an audio-presence meter over the dimmed visuals so you can use turn a propped-up phone into a desk/shelf piece. Swipe to browse visuals without leaving.
- **Foldable & Tablet Support**: Open a foldable phone and the render loop will survive the screen state changes without recreating.
- **Diagnostics Overlay Toggle**: Displays FPS, audio latency, Ableton link status, Hue drop rates and more.

## Why Velo is Fast

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

### 5. Smart Lighting Latency (LIFX)
LIFX bulbs connect directly to your local Wi-Fi router, bypassing the need for a Zigbee bridge. This eliminates the extra bridge processing hop found in Hue setups. Total time from beat-detection to physical light change is generally **~15–30 ms**, depending heavily on your router's performance and the Wi-Fi signal strength at the bulb.

| Stage | Approx. Time |
|-------|---------|
| UDP Packet build | < 0.5 ms |
| Wi-Fi LAN hop directly to bulb | ~1–5 ms |
| Bulb processing & illumination | ~15–25 ms |

### 6. Smart Lighting Latency (Nanoleaf)
Nanoleaf panels also connect directly to your local Wi-Fi and support a high-speed "ExtControl" UDP streaming mode. Latency is similar to LIFX, with the total time from beat-detection to physical light change generally around **~15–30 ms**.

| Stage | Approx. Time |
|-------|---------|
| ExtControl V2 Packet build | < 0.5 ms |
| Wi-Fi LAN hop directly to controller | ~1–5 ms |
| Panel processing & illumination | ~15–25 ms |

## Smart Lighting (Philips Hue Sync)

Velo drives Hue lights using the **Hue Stream v2** protocol over **DTLS-PSK encrypted UDP** (Port 2100). 

1. **Pairing:** Swipe up, and select the **Lighting** tab. Tap *Connect Hue Bridge*. The app uses mDNS to find your local bridge. Press the physical button on the bridge when prompted.
2. **Setup:** Select an Entertainment Area (must be created in the official Philips Hue app first).
3. **Persistence:** The `username` and `clientkey` are stored locally via `EncryptedSharedPreferences`. 

## Smart Lighting (LIFX Sync)

Velo drives LIFX lights using the **LIFX LAN Protocol** over **raw UDP** (Port 56700).

1. **Discovery:** Tap *Scan for LIFX Bulbs* in the LIFX tab. The app uses UDP broadcast (`Device::GetService`) to find your local bulbs. 
2. **Selection:** Check the boxes next to the bulbs you wish to sync. 
3. **Control:** Velo sends high-speed `Light::SetColor` packets to update the bulbs at up to 50Hz. No bridge or cloud account is required. 

## Smart Lighting (Nanoleaf Sync)

Velo drives Nanoleaf panels using the **ExtControl V2 Protocol** over **raw UDP** (Port 60222).

1. **Discovery:** Open the Nanoleaf tab and click *Scan*. The app uses mDNS (`_nanoleafapi._tcp`) to detect your controller.
2. **Pairing:** Hold the power button on your Nanoleaf physical controller for 5-7 seconds until the lights flash, then tap *Pair* in the app to establish an API token.
3. **Control:** Once paired, tapping *Light Sync* enables high-speed streaming directly to your panels. No cloud account is required.

## Building from Source

If you want to compile Velo yourself, open the project in Android Studio (Meerkat 2024.3.1 or higher).

**Build Requirements:**
* **Android SDK:** `targetSdk 37`
* **NDK:** Version 28+
* **CMake:** 3.22.1
* **Architecture:** `arm64-v8a` only.
  
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

## License

Velo Visualiser is **free and open source software**, licensed under the
**GNU General Public License v3.0** — see [LICENSE](LICENSE).

You're free to use, study, modify, and redistribute it. Any distributed
derivative must also remain GPLv3.

The **Velo Visualiser name, logo, and icons are trademarks and are not covered by the GPL**
— see [TRADEMARKS.md](TRADEMARKS.md). Forks must rebrand.

No data collection, no ads, no tracking — local-only, and it'll stay that way.

## Support

Velo is free but its development cost is not, so if it brings some colour to your music and you'd
like to chip in for coffee, it's hugely appreciated:

<a href="https://www.buymeacoffee.com/rorygallagher2024"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="42"></a>

## About the Developer

Velo was engineered by me, Rory Gallagher. I am a **Solutions Engineer** with over 14 years of experience in enterprise software architecture and currently leading innovation and experimentation teams. My day-to-day focus centers on leading teams to evaluate and build enterprise capabilities using emerging technologies.

With interests in music technology, live performance, hardware and IoT, I built Velo to serve as both a practical tool for live sets and a personal deep-dive to explore the bare-metal performance limits of native Android audio pipelines and network hardware coordination.

Feel free to connect:
* **LinkedIn:** [linkedin.com/in/rory-gallagher-51822532](https://www.linkedin.com/in/rory-gallagher-51822532)
* **YouTube:** [youtube.com/@rorygallagher-redslug](https://www.youtube.com/@rorygallagher-redslug)
* **Medium:** [medium.com/@rorygallagher2010](https://medium.com/@rorygallagher2010)

