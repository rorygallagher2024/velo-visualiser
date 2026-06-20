#include <jni.h>
#include <vector>
#include <android/log.h>
#include "AudioEngine.h"
#include "LinkController.h"

#define LOG_TAG "NativeBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Number of samples handed to the GPU per frame for the oscilloscope sweep.
// One screen-width worth of the most recent audio.
static constexpr jsize kRenderWindow = 1024;

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

// ---------------------------------------------------------------------------
// System-audio (AudioPlaybackCapture) push path. Kotlin reads 16-bit PCM from
// an AudioRecord backed by a MediaProjection token and forwards it here. We
// downmix to mono float and feed the same ring buffer the renderer consumes.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativePushPcm(JNIEnv *env, jobject,
                                                          jshortArray pcm,
                                                          jint frames,
                                                          jint channels) {
    if (pcm == nullptr || frames <= 0 || channels <= 0) return;

    jshort *src = env->GetShortArrayElements(pcm, nullptr);
    if (src == nullptr) return;

    static thread_local std::vector<float> mono;
    if (mono.size() < static_cast<size_t>(frames)) mono.resize(frames);

    constexpr float kInv = 1.0f / 32768.0f;
    for (jint f = 0; f < frames; ++f) {
        float acc = 0.0f;
        for (jint c = 0; c < channels; ++c) {
            acc += static_cast<float>(src[f * channels + c]) * kInv;
        }
        mono[f] = acc / static_cast<float>(channels);
    }

    AudioEngine::instance().pushExternalPcm(mono.data(), static_cast<size_t>(frames));
    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT); // read-only, no copy-back
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
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkTempo(JNIEnv *, jobject) {
    return velo::linkTempo();
}

JNIEXPORT jint JNICALL
Java_com_lowlatency_visualizer_NativeBridge_nativeLinkPeers(JNIEnv *, jobject) {
    return velo::linkNumPeers();
}

} // extern "C"
