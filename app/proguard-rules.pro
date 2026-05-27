# LiteRT / TensorFlow Lite AI rules
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keep class com.google.tensorflow.lite.** { *; }
-keep interface com.google.tensorflow.lite.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.tensorflow.lite.**

# Llamatik / GGUF AI rules
-keep class com.llamatik.library.platform.** { *; }
-keep interface com.llamatik.library.platform.** { *; }
-dontwarn com.llamatik.library.platform.**

# ML Kit Core & GenAI / AICore rules
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Google Play Services Tasks, Common, and AICore APIs
-keep class com.google.android.gms.tasks.** { *; }
-keep interface com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep interface com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.aicore.** { *; }
-keep interface com.google.android.gms.aicore.** { *; }
-dontwarn com.google.android.gms.**

# Keep JNI native methods (used by ML Kit, TensorFlow Lite, LiteRT)
-keepclasseswithmembernames class * {
    native <methods>;
}