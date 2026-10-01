package app.mokuhyo.desktop

/** Placeholder until the JNI bindings land (native/ + shared jvmMain ai/jni). */
object JniRuntime {
    fun tryCreate(@Suppress("UNUSED_PARAMETER") preferCpu: Boolean): NativeRuntime = NativeRuntime.Unavailable("native library not built")
}
