# Giữ nguyên bộ giải mã FFmpeg (gọi qua JNI nên R8 không tự nhận ra)
-keep class androidx.media3.decoder.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.jellyfin.**
