# --- WebRTC: JNI calls back into Java by name; keep everything in org.webrtc ---
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { native <methods>; }
-dontwarn org.webrtc.**
# libwebrtc m120+ generates JNI glue in org.jni_zero (looked up by name from native code).
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# --- Ktor server (CIO) ---
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**

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
