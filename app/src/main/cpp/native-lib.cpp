#include <jni.h>
#include <vector>
#include <android/log.h>
#include <arm_neon.h>
#include "AudioEngine.h"
#include "LinkController.h"

#define LOG_TAG "NativeBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Number of samples handed to the GPU per frame for the oscilloscope sweep.
// One screen-width worth of the most recent audio.
static constexpr jsize kRenderWindow = 1024;

// Shared DirectByteBuffer for zero-copy audio transfer to Java/GL.
static jobject gSharedBuffer = nullptr;
static void *gSharedBufferPtr = nullptr;
static jlong gSharedBufferSize = 0;

// System audio benchmarking metrics
static std::atomic<long long> gSystemAudioConvTimeUs{0};
static std::atomic<long long> gSystemAudioLastPushNs{0};
static std::atomic<float> gSystemAudioLastIntervalMs{0};

// Hardware Load Metrics
static std::atomic<long long> gGlThreadWorkTimeUs{0};
static std::atomic<long long> gGpuTaskTimeNs{0};
static std::atomic<bool> gGpuTimeAvailable{false};

extern "C" {

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeStartMicrophone(JNIEnv *, jobject) {
    return AudioEngine::instance().startMicrophone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeStop(JNIEnv *, jobject) {
    AudioEngine::instance().stop();
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeGetSampleRate(JNIEnv *, jobject) {
    return AudioEngine::instance().sampleRate();
}

// ---------------------------------------------------------------------------
// The single high-frequency consumer method (spec): returns the latest PCM
// window as a fresh jfloatArray. Convenient, but allocates a Java array each
// call — see fillLatestAudioBuffer() below for the zero-GC render-loop path.
// ---------------------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL
Java_com_lowlatency_visualizer_NativeBridge_getLatestAudioBuffer(JNIEnv *env, jobject) {
    jfloatArray result = env->NewFloatArray(kRenderWindow);
    if (result == nullptr) return nullptr;   // OOM

    // Pull samples into a stack/thread-local scratch then publish in one copy.
    static thread_local std::vector<float> scratch(kRenderWindow);
    AudioEngine::instance().copyLatest(scratch.data(), kRenderWindow);
    env->SetFloatArrayRegion(result, 0, kRenderWindow, scratch.data());
    return result;
}

// ---------------------------------------------------------------------------
// Zero-allocation variant for the 120 Hz render loop. The caller owns a
// persistent FloatArray and we fill it in place — no per-frame GC pressure.
// Returns the number of samples written.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillLatestAudioBuffer(JNIEnv *env, jobject,
                                                                  jfloatArray out) {
    if (out == nullptr) return 0;
    const jsize len = env->GetArrayLength(out);

    // Pin the Java array and write directly into it.
    jfloat *dst = env->GetFloatArrayElements(out, nullptr);
    if (dst == nullptr) return 0;
    AudioEngine::instance().copyLatest(dst, static_cast<size_t>(len));
    env->ReleaseFloatArrayElements(out, dst, 0); // commit + unpin
    return len;
}

// ---------------------------------------------------------------------------
// Frequency bands. Runs the windowed FFT over the latest PCM window and returns
// 3 normalized energies: [0]=Lows, [1]=Mids, [2]=Highs. Spec method (allocates).
// ---------------------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL
Java_com_lowlatency_visualizer_NativeBridge_getLatestFrequencyBands(JNIEnv *env, jobject) {
    float bands[3];
    AudioEngine::instance().computeBands(bands);
    jfloatArray result = env->NewFloatArray(3);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, 3, bands);
    return result;
}

// Zero-allocation render-loop variant: fills the caller-owned 3-element array.
JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillLatestFrequencyBands(JNIEnv *env, jobject,
                                                                     jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 3) return 0;
    float bands[3];
    AudioEngine::instance().computeBands(bands);
    env->SetFloatArrayRegion(out, 0, 3, bands);
    return 3;
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillLatestSpectrum(JNIEnv *env, jobject,
                                                               jfloatArray magnitudes,
                                                               jfloatArray peaks,
                                                               jfloat dt) {
    if (magnitudes == nullptr || peaks == nullptr) return 0;
    jfloat *m = env->GetFloatArrayElements(magnitudes, nullptr);
    jfloat *p = env->GetFloatArrayElements(peaks, nullptr);
    if (!m || !p) return 0;

    AudioEngine::instance().computeFullSpectrum(m, p, dt);

    env->ReleaseFloatArrayElements(magnitudes, m, 0);
    env->ReleaseFloatArrayElements(peaks, p, 0);
    return 128;
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeInitializeSharedBuffer(JNIEnv *env, jobject, jobject buffer) {
    if (gSharedBuffer) {
        env->DeleteGlobalRef(gSharedBuffer);
    }
    gSharedBuffer = env->NewGlobalRef(buffer);
    gSharedBufferPtr = env->GetDirectBufferAddress(buffer);
    gSharedBufferSize = env->GetDirectBufferCapacity(buffer);
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillSharedAudioBuffer(JNIEnv *, jobject) {
    if (!gSharedBufferPtr) return 0;
    auto len = static_cast<jlong>(gSharedBufferSize / sizeof(float));
    if (len > kRenderWindow) len = kRenderWindow;
    AudioEngine::instance().copyLatest(static_cast<float*>(gSharedBufferPtr), static_cast<size_t>(len));
    return static_cast<jint>(len);
}

// ---------------------------------------------------------------------------
// System-audio (AudioPlaybackCapture) push path. Kotlin reads 16-bit PCM from
// an AudioRecord backed by a MediaProjection token and forwards it here. We
// downmix to mono float and feed the same ring buffer the renderer consumes.
// Optimized with ARM NEON for common stereo downmixing and gain scaling.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativePushPcm(JNIEnv *env, jobject,
                                                          jshortArray pcm,
                                                          jint frames,
                                                          jint channels,
                                                          jfloat gain) {
    if (pcm == nullptr || frames <= 0 || channels <= 0) return;

    auto start = std::chrono::high_resolution_clock::now();
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    long long nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    long long lastNs = gSystemAudioLastPushNs.exchange(nowNs);
    if (lastNs > 0) {
        gSystemAudioLastIntervalMs.store(static_cast<float>(nowNs - lastNs) / 1000000.0f);
    }

    jshort *src = env->GetShortArrayElements(pcm, nullptr);
    if (src == nullptr) return;

    static thread_local std::vector<float> mono;
    if (mono.size() < static_cast<size_t>(frames)) mono.resize(frames);

    if (channels == 2) {
        // Optimized NEON path for stereo: (L+R)/2 * gain
        const float kInvStereo = (1.0f / (32768.0f * 2.0f)) * gain;
        int f = 0;
        // Process 4 stereo pairs (8 shorts) at a time.
        for (; f <= frames - 4; f += 4) {
            int16x8_t stereo = vld1q_s16(&src[f * 2]);
            // vpaddlq_s16 adds adjacent pairs: [L0+R0, L1+R1, L2+R2, L3+R3] as int32x4
            int32x4_t sum = vpaddlq_s16(stereo);
            float32x4_t fsum = vcvtq_f32_s32(sum);
            float32x4_t res = vmulq_n_f32(fsum, kInvStereo);
            vst1q_f32(&mono[f], res);
        }
        // Remainder
        for (; f < frames; ++f) {
            mono[f] = (static_cast<float>(src[f * 2]) + static_cast<float>(src[f * 2 + 1])) * kInvStereo;
        }
    } else {
        // Generic fallback for mono or surround
        const float kInv = (1.0f / (32768.0f * static_cast<float>(channels))) * gain;
        for (jint f = 0; f < frames; ++f) {
            float acc = 0.0f;
            for (jint c = 0; c < channels; ++c) {
                acc += static_cast<float>(src[f * channels + c]);
            }
            mono[f] = acc * kInv;
        }
    }

    AudioEngine::instance().pushExternalPcm(mono.data(), static_cast<size_t>(frames));
    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT); // read-only, no copy-back

    auto end = std::chrono::high_resolution_clock::now();
    gSystemAudioConvTimeUs.store(std::chrono::duration_cast<std::chrono::microseconds>(end - start).count());
}

JNIEXPORT jfloatArray JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeGetSystemAudioMetrics(JNIEnv *env, jobject) {
    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) return nullptr;
    float metrics[2];
    metrics[0] = static_cast<float>(gSystemAudioConvTimeUs.load());
    metrics[1] = gSystemAudioLastIntervalMs.load();
    env->SetFloatArrayRegion(result, 0, 2, metrics);
    return result;
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeUpdateHardwareLoad(JNIEnv *, jobject,
                                                                      jlong cpuUs, jlong gpuNs, jboolean gpuAvail) {
    gGlThreadWorkTimeUs.store(cpuUs);
    gGpuTaskTimeNs.store(gpuNs);
    gGpuTimeAvailable.store(gpuAvail == JNI_TRUE);
}

JNIEXPORT jfloatArray JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeGetHardwareLoad(JNIEnv *env, jobject) {
    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) return nullptr;
    float metrics[2];
    metrics[0] = static_cast<float>(gGlThreadWorkTimeUs.load());
    metrics[1] = gGpuTimeAvailable.load() ? static_cast<float>(gGpuTaskTimeNs.load()) / 1000000.0f : -1.0f;
    env->SetFloatArrayRegion(result, 0, 2, metrics);
    return result;
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------
JNIEXPORT jfloat JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeGetAudioCallbackMs(JNIEnv *, jobject) {
    return AudioEngine::instance().callbackPeriodMs();
}

// ---------------------------------------------------------------------------
// Ableton Link — wireless tempo/beat sync. The wrapper (LinkController) owns the
// ableton::Link instance and catches anything it throws, so these bindings stay
// trivial. Enable/tempo/peers are called from the UI thread; pollBeats is called
// once per frame from the GL render thread (realtime-safe).
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkSetEnabled(JNIEnv *, jobject,
                                                                 jboolean enabled) {
    velo::linkSetEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkPollBeats(JNIEnv *, jobject) {
    return velo::linkPollBeats();
}

JNIEXPORT jdouble JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkBeatPhase(JNIEnv *, jobject) {
    return velo::linkBeatPhase();
}

JNIEXPORT jdouble JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkTempo(JNIEnv *, jobject) {
    return velo::linkTempo();
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkPeers(JNIEnv *, jobject) {
    return velo::linkNumPeers();
}

} // extern "C"
