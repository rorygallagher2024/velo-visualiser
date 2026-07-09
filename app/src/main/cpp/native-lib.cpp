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

JNIEXPORT jboolean JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeStartPlayback(JNIEnv *, jobject, jint sampleRate, jint channelCount) {
    return AudioEngine::instance().startPlayback(sampleRate, channelCount) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativePausePlayback(JNIEnv *, jobject) {
    AudioEngine::instance().pausePlayback();
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeResumePlayback(JNIEnv *, jobject) {
    AudioEngine::instance().resumePlayback();
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeFlushPlayback(JNIEnv *, jobject) {
    AudioEngine::instance().flushPlayback();
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeStopPlayback(JNIEnv *, jobject) {
    AudioEngine::instance().stopPlayback();
}

JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeStop(JNIEnv *, jobject) {
    AudioEngine::instance().stop();
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeGetSampleRate(JNIEnv *, jobject) {
    return AudioEngine::instance().sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillLatestStereoAudioBuffer(JNIEnv *env, jobject,
                                                                        jfloatArray outInterleaved) {
    if (outInterleaved == nullptr) return 0;
    const jsize len = env->GetArrayLength(outInterleaved);
    
    jfloat *dst = env->GetFloatArrayElements(outInterleaved, nullptr);
    if (dst == nullptr) return 0;
    AudioEngine::instance().copyLatestStereo(dst, static_cast<size_t>(len / 2));
    env->ReleaseFloatArrayElements(outInterleaved, dst, 0);
    return len;
}

// ---------------------------------------------------------------------------
// Single-FFT render-loop path: bands + full spectrum from one transform,
// filling caller-owned arrays in place (no per-frame GC pressure).
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_fillLatestAll(JNIEnv *env, jobject,
                                                          jfloatArray bandsOut,
                                                          jfloatArray magnitudes,
                                                          jfloatArray peaks,
                                                          jfloat dt) {
    if (bandsOut == nullptr || magnitudes == nullptr || peaks == nullptr) return 0;
    jfloat *b = env->GetFloatArrayElements(bandsOut, nullptr);
    jfloat *m = env->GetFloatArrayElements(magnitudes, nullptr);
    jfloat *p = env->GetFloatArrayElements(peaks, nullptr);
    if (!b || !m || !p) return 0;

    AudioEngine::instance().computeAll(b, m, p, dt);

    env->ReleaseFloatArrayElements(bandsOut, b, 0);
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

    auto start = std::chrono::steady_clock::now();
    long long nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(start.time_since_epoch()).count();
    long long lastNs = gSystemAudioLastPushNs.exchange(nowNs);
    if (lastNs > 0) {
        gSystemAudioLastIntervalMs.store(static_cast<float>(nowNs - lastNs) / 1000000.0f);
    }

    jshort *src = env->GetShortArrayElements(pcm, nullptr);
    if (src == nullptr) return;

    static thread_local std::vector<float> mono;
    if (mono.size() < static_cast<size_t>(frames)) mono.resize(frames);

    static thread_local std::vector<float> stereo;
    if (stereo.size() < static_cast<size_t>(frames * 2)) stereo.resize(frames * 2);

    const float kNorm = 1.0f / 32768.0f;

    if (channels == 2) {
        for (int f = 0; f < frames; ++f) {
            float L = static_cast<float>(src[f * 2]) * kNorm;
            float R = static_cast<float>(src[f * 2 + 1]) * kNorm;
            stereo[f * 2] = L;
            stereo[f * 2 + 1] = R;
            mono[f] = (L + R) * 0.5f * gain;
        }
    } else {
        // Generic fallback for mono or surround
        for (jint f = 0; f < frames; ++f) {
            float acc = 0.0f;
            for (jint c = 0; c < channels; ++c) {
                acc += static_cast<float>(src[f * channels + c]);
            }
            float mRaw = acc * (kNorm / static_cast<float>(channels));
            stereo[f * 2] = mRaw;
            stereo[f * 2 + 1] = mRaw;
            mono[f] = mRaw * gain;
        }
    }

    AudioEngine::instance().pushExternalPcmStereo(stereo.data(), static_cast<size_t>(frames));
    AudioEngine::instance().pushExternalPcm(mono.data(), static_cast<size_t>(frames));
    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT); // read-only, no copy-back

    auto end = std::chrono::steady_clock::now();
    gSystemAudioConvTimeUs.store(std::chrono::duration_cast<std::chrono::microseconds>(end - start).count());
}

JNIEXPORT jboolean JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativePushPlaybackAudio(JNIEnv *env, jobject, jfloatArray pcm, jint frames) {
    if (pcm == nullptr || frames <= 0) return JNI_FALSE;

    jfloat *src = env->GetFloatArrayElements(pcm, nullptr);
    if (src == nullptr) return JNI_FALSE;

    const bool ok = AudioEngine::instance().pushPlaybackAudio(src, static_cast<size_t>(frames));

    env->ReleaseFloatArrayElements(pcm, src, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
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
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkBarPhase(JNIEnv *, jobject) {
    return velo::linkBarPhase();
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
