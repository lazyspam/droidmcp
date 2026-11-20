# Termux terminal: native code (libtermux.so) calls into these classes by name.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
# NanoHTTPD uses reflection-free code but keep it whole to be safe.
-keep class fi.iki.elonen.** { *; }
# OkHttp / Okio optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Keep line numbers so crash reports in the app stay readable.
-keepattributes SourceFile,LineNumberTable
