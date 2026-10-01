package app.mokuhyo.desktop

object BuildInfo {
    /** Set by the packaged launcher's JVM options (desktopApp/build.gradle.kts). */
    val version: String get() = System.getProperty("mokuhyo.version") ?: "0.1.0-dev"
}
