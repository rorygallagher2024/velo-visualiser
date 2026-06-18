# Keep the JNI bridge intact — native methods are resolved by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.lowlatency.visualizer.NativeBridge { *; }
