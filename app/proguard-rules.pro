# ─── Nua ProGuard Rules ─────────────────────────────────────────────────────

# FlatBuffers schema classes (zero-copy binary access via reflection)
-keep class com.example.nua.data.schema.** { *; }
-keepclassmembers class * extends com.google.flatbuffers.Table { *; }

# Vosk ASR engine (JNI native methods)
-keep class org.vosk.** { *; }

# LiteRT-LM (on-device NPU inference)
-keep class com.google.ai.edge.litertlm.** { *; }

# kotlinx.serialization (legacy JSON manifest migration)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.nua.**$$serializer { *; }
-keepclassmembers class com.example.nua.** { *** Companion; }
-keepclasseswithmembers class com.example.nua.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Firebase / Google AI
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }
