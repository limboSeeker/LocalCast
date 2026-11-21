# --- WebRTC (io.github.webrtc-sdk:android ships no consumer rules) ---
# Native code reaches Java only by name: jni_zero looks classes up through WebRtcClassLoader and
# calls members annotated @CalledByNative / @CalledByNativeUnchecked; Java calls into native via
# `native` methods bound by their Java_org_webrtc_* symbol names. Keep exactly those, plus every
# class in their signatures (includedescriptorclasses: GetMethodID matches full descriptors).
# Everything else in org.webrtc (cameras, renderers, unused codecs) is shrunk and obfuscated.
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNative <init>(...);
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative <init>(...);
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.AccessedByNative <fields>;
}
-keepclasseswithmembernames,includedescriptorclasses class org.webrtc.** {
    native <methods>;
}
# jni_zero runtime (~3 KB): looked up by name from native code and uses reflection itself.
-keep class org.jni_zero.** { *; }
# Generated JNI glue that the AAR does not ship; referenced only from an unused code path.
-dontwarn org.jni_zero.JniZeroJni
# org.webrtc.TunedVideoEncoderFactory (app code) uses package-private HardwareVideoEncoder,
# MediaCodecWrapper etc. Without -repackageclasses R8 renames them inside org.webrtc, so that
# access stays valid. HardwareVideoEncoder's overrides of the @CalledByNative VideoEncoder
# methods keep their names because the interface methods are kept.

# --- Ktor server (CIO) ---
# The engine is created directly (embeddedServer(CIO)), nothing in it is looked up by name;
# only AtomicFU-updated volatile fields must keep their names.
-keepclassmembers class io.ktor.** { volatile <fields>; }
# kotlin-reflect is excluded in build.gradle.kts. Ktor touches it only to load modules by name and
# for auto-reload in development mode (both off here; the latter is wrapped in runCatching).
# Exact classes only: new code that needs kotlin-reflect (e.g. call.receive<T>()) must fail R8.
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping
# JVM-only API used by Ktor's IntelliJ debugger detector.
-dontwarn java.lang.management.**

# --- kotlinx.serialization (protocol messages) ---
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.localcast.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.localcast.app.**$$serializer { *; }
-keepclasseswithmembers class com.localcast.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
