# Tsumugi release keep rules.
#
# The release build is NOT minified today (build.gradle.kts: isMinifyEnabled = false), so R8 doesn't read this file
# yet. The rules are here so that turning minification on can't silently break the JNI bridges: native code finds
# its entry points and callbacks by name.

# JNI entry points (src/main/cpp/llama_jni.cpp, whisper_jni.cpp): Java_app_tsumugi_android_platform_<Object>_<method>.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class app.tsumugi.android.platform.LlamaNative { *; }
-keep class app.tsumugi.android.platform.WhisperNative { *; }

# llama_jni.cpp calls Sink.onPiece([B)Z on the object it is handed, looked up by name.
-keep interface app.tsumugi.android.platform.LlamaNative$Sink { *; }
-keep class * implements app.tsumugi.android.platform.LlamaNative$Sink {
    boolean onPiece(byte[]);
}

# The bridges themselves (constructed by TsumugiApplication, loaded via System.loadLibrary).
-keep class app.tsumugi.android.platform.LlamaJni { *; }
-keep class app.tsumugi.android.platform.WhisperJni { *; }
