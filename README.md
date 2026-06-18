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


* **Bare-Metal Audio Engine (C++ / Oboe):** 
  * Direct memory access to the device microphone.
  * Bypasses Android's Automatic Gain Control (AGC) for raw, unprocessed transient detection.
  * Sub-10ms round-trip audio latency.
  * Real-time Fast Fourier Transform (FFT) frequency binning.
* **Hardware-Accelerated Graphics:** 
  * Written entirely in OpenGL ES 3.1.
  * Continuous 60fps+ rendering at full native display resolution.
  * Seamless state-machine handling to swap between complex vertex and fragment shaders without frame drops or memory leaks.
---

## The Visualizer Suite

The application features 5 distinct, mathematically driven visualizers.

### 1. Pro Oscilloscope
### 2. Pro Tunnel
### 3. Volumetric Laser Array
### 4. Topographic Bass Matrix
### 5. Circular Spectrum Analyzer
---

## Installation & Setup

Compiled APKs for personal use are provided in the **Releases** tab.


## Build Requirements
* Android Studio Meerkat (2024.3.1) or higher
* Android NDK (Version 28+)
* `targetSdk 36` (Ready for Android 16 / Baklava)
