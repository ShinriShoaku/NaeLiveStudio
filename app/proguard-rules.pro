# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =====================================================================================
# OPTIMASI: minifyEnabled diaktifkan di build.gradle (release). Rules di bawah menjaga
# supaya R8 tidak menghapus/rename kelas yang diakses lewat AIDL (IPC) atau refleksi,
# yang kalau dibiarkan default bisa BUKAN error compile tapi crash/silent-fail saat
# runtime (misal ClassNotFoundException pas komunikasi ke Kanae service).
# =====================================================================================

# AIDL: interface + stub/proxy generated (ame.project.nlsdk.IKanaeService, IKanaeCallback)
# dipanggil oleh proses LAIN (layanan Kanae terpisah) lewat nama kelas literal, jadi
# HARUS tetap ada nama aslinya, tidak boleh di-obfuscate/dihapus.
-keep class ame.project.nlsdk.** { *; }
-keep interface ame.project.nlsdk.** { *; }

# RootEncoder (com.pedro.*): library streaming RTMP/RTSP/SRT pakai banyak kelas native/JNI
# dan beberapa refleksi encoder internal. Aman di-keep semua drpd APK crash pas encode.
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# AndroidX Media3 Transformer/Effect: dipakai VideoOptimizer buat re-encode background
# video, internally pakai refleksi utk resolve encoder/decoder & effect pipeline.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Model data yang dipakai skema JSON manual (JSONObject.put/opt berdasarkan nama field) -
# kalau field-nya di-rename R8, hasil serialize/parse JSON jadi tidak konsisten.
-keepclassmembers class ame.project.nlstudio.scene.** { *; }
-keepclassmembers class ame.project.nlstudio.OBS.VideoOptimizer$CachedVideoEntry { *; }

# Firebase Crashlytics butuh nama kelas asli di stack trace supaya laporan crash kebaca.
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception