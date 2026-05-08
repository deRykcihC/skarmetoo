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