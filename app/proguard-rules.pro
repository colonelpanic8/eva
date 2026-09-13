# EVA-specific keep rules belong here as integrations are introduced.

# Minification is disabled for release builds (see app/build.gradle.kts), so these
# rules are currently inert. They are necessary but NOT sufficient: with them in
# place every org.webrtc class is still present and unrenamed in the minified dex,
# and the build nonetheless aborts inside WebRTC's JNI_OnLoad. Keep them as the
# starting point for anyone re-attempting minification.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp ships optional hooks for platforms and providers that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
