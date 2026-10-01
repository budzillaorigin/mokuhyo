package app.mokuhyo.desktop

/** `--smoke-opi`: lands with the JNI bindings (one Japanese OPI turn on the Tier A model, audio in, transcript out). */
object SmokeOpi {
    fun run(@Suppress("UNUSED_PARAMETER") args: Array<String>): Int {
        println("smoke-opi: native bindings not built yet")
        return 2
    }
}
