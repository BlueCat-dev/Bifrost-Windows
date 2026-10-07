# OkHttp Proguard Rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines Proguard Rules
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ML Kit & CameraX
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ZXing Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Bifrost Models, Core, Data & Utils
-keep class com.bifrost.twp.model.** { *; }
-keep class com.bifrost.twp.core.** { *; }
-keep class com.bifrost.twp.data.** { *; }
-keep class com.bifrost.twp.util.** { *; }
