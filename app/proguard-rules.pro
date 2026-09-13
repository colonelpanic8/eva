# EVA-specific keep rules belong here as integrations are introduced.

# WebRTC's native library resolves its Java classes, constructors, methods, and
# fields by name through JNI. R8 has no way to see those references, so a
# minified release build aborts with "JNI DETECTED ERROR IN APPLICATION:
# java_class == null" as soon as a peer connection is created.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp ships optional hooks for platforms and providers that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
