# Keep the JNI bridge intact — native methods are resolved by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.lowlatency.visualizer.NativeBridge { *; }

# BouncyCastle DTLS/TLS provider (used for the Hue Entertainment stream). The
# TLS layer loads crypto primitives reflectively; keep it and silence warnings.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp / Okio reference optional platform classes absent on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Tink (via androidx.security-crypto) references compile-only ErrorProne
# annotations and optional HTTP/time deps (KeysDownloader — unused here) that
# aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
