# Oscillux Audio Visualizer

A high-performance, zero-latency audio visualizer. Built with a bare-metal C++ audio engine and custom OpenGL ES 3.1 shaders.

## Proprietary & Source-Available License
**This is NOT an Open Source project.** The source code is provided for educational, review, and personal compilation purposes only. 

By accessing this repository, you agree to the following terms:
1. **No Commercial Use:** You may not monetize this software or any derivatives.
2. **No App Store Distribution:** You are strictly prohibited from compiling, repackaging, or distributing this software (or any modified version of it) to the Google Play Store, Amazon Appstore, or any other digital storefront.
3. **No Redistribution:** You may not redistribute modified binaries.

Any unauthorized commercial distribution will result in immediate DMCA takedown notices filed with the respective storefront and repository host.

---

## Core Capabilities & Architecture

## Core Architecture
* **Bare-Metal Audio (C++ / Oboe):** Direct microphone access, bypassing Android's AGC for sub-10ms latency and real-time FFT frequency binning.
* **Hardware-Accelerated Graphics:** 60fps+ OpenGL ES 3.1 rendering with seamless state-machine transitions between shaders.
---

## The Visualizer Suite
Five distinct, mathematically driven visualizers responsive to live audio:

1. Pro Oscilloscope
2. Pro Tunnel
3. Volumetric Laser Array
4. Topographic Bass Matrix
5. Circular Spectrum Analyzer
---

## Installation & Setup

Compiled APKs for personal use are provided in the **Releases** tab.


## Build Requirements
* Android Studio Meerkat (2024.3.1) or higher
* Android NDK (Version 28+)
* `targetSdk 36` (Ready for Android 16 / Baklava)
