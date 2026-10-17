# --- Natív hidak (JNI) – a natív kód név szerint keresi ezeket ---
-dontwarn java.awt.**
-keep class com.redravencomputing.whispercore.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# --- LiteRT-LM (Gemma 4) – JNI-n keresztül név szerint hívott osztályok ---
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- JavaMail (napi összefoglaló e-mail) ---
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**

# --- Room / WorkManager (a Worker osztályokat reflexióval példányosítja) ---
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class hu.orajegyzet.data.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlin coroutines ---
-dontwarn kotlinx.coroutines.debug.**

# --- Összeomlási jelentésekhez ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
