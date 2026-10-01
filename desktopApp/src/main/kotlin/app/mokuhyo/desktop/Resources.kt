package app.mokuhyo.desktop

import java.io.File

/** Files shipped with the app: jar resources, and the installer's resources dir (native libs, bundled models, voices, packs). */
object Resources {
    fun text(path: String): String =
        Resources::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
            ?: error("missing bundled resource $path")

    fun textOrNull(path: String): String? =
        Resources::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    /** The packaged app's resources dir, or the source tree's `desktopApp/resources/common` when running from Gradle. */
    val dir: File? by lazy {
        System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }
            ?: repoDir?.let { File(it, "desktopApp/resources/common") }?.takeIf { it.isDirectory }
    }

    /** The repository root in development runs (null in the installed app). */
    val repoDir: File? by lazy {
        System.getProperty("mokuhyo.repo.dir")?.let(::File)?.takeIf { it.isDirectory } ?: run {
            var d: File? = File("").absoluteFile
            while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
            d
        }
    }

    fun bundledModel(fileName: String): File? = dir?.let { File(it, "models/$fileName") }?.takeIf { it.isFile }
}
