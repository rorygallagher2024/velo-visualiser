# Privacy Policy for Velo Visualiser

Last Updated: June 19, 2026

Velo Visualiser ("we", "our", or "us") is committed to protecting your privacy. This Privacy Policy explains how we handle data when you use our mobile application.

## 1. Information We Collect

### Audio Data (Microphone and System Audio)
* **What we collect:** The app accesses your device's microphone and system audio (via MediaProjection) to generate real-time visualisations.
* **How we use it:** Audio data is processed **locally and in real-time** on your device. We use this data only to calculate frequency bands and waveforms for visual effects and haptic feedback.
* **Storage and Transmission:** We **do not record, store, or transmit** any audio data. The raw PCM data exists only in temporary volatile memory (RAM) for the duration of the processing (typically less than 100ms) and is immediately overwritten.

### Network Information
* **Local Network Access:** The app requests access to your local network to discover and communicate with Philips Hue Bridges.
* **Usage:** This access is used solely for local UDP/REST communication with your smart lighting hardware. No data is sent to external servers or the cloud.

### Device Information
* The app may check your device's display capabilities (such as HDR support and refresh rate) to optimize the OpenGL rendering. This information is not collected or transmitted.

## 2. Third-Party Services
* **Philips Hue:** If you enable Hue Sync, the app communicates directly with your Philips Hue Bridge over your local Wi-Fi. Please refer to the Philips Hue privacy policy for information on how they handle data.

## 3. Data Safety
Velo Visualiser does not collect, share, or sell any personal data. It is a utility tool designed to operate entirely on-device.

## 4. Changes to This Policy
We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page.

## 5. Contact Us
If you have any questions about this Privacy Policy, please contact us via our GitHub repository.
