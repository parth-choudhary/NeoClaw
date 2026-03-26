# MobileClaw ProGuard rules

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.parth.mobileclaw.models.** { *; }
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Compose
-dontwarn androidx.compose.**
