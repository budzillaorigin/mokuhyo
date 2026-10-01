package app.mokuhyo.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.Shell
import app.mokuhyo.lang.Languages
import java.nio.file.Files

/**
 * `--smoke` (BRIEF §11.1 gate_build): headless start-up check of a packaged app. Opens a fresh database, loads the
 * native library (CPU variant), lists the registered languages and renders one Compose frame offscreen. Exit 0 = OK.
 * `--data-dir DIR` uses DIR instead of a temp dir; `--allow-no-native` tolerates a missing native library (dev only).
 */
object Smoke {
    fun run(args: Array<String>): Int {
        System.setProperty("java.awt.headless", "true")
        val dir = arg(args, "--data-dir")?.let { java.io.File(it) } ?: Files.createTempDirectory("mokuhyo-smoke").toFile()
        System.setProperty("mokuhyo.data.dir", dir.absolutePath)
        val problems = mutableListOf<String>()
        val app = AppGraph(app.mokuhyo.platform.AppDirs.ensure(dir))
        println("smoke: database ${app.dataDir}/db/mokuhyo.sqlite (learner ${app.learnerId})")
        val native = NativeRuntime.create(preferCpu = true)
        println("smoke: native ${native.status}")
        if (!native.available && "--allow-no-native" !in args) problems += "native library did not load: ${native.status}"
        println("smoke: languages ${Languages.all.joinToString(",") { it.code }}")
        if (Languages.all.size != 11) problems += "expected 11 languages, got ${Languages.all.size}"
        println("smoke: models in manifest ${app.manifest.models.size}")
        val scene = ImageComposeScene(1180, 800, Density(1f)) { MokuhyoTheme(dark = false) { Shell(app) } }
        val image: org.jetbrains.skia.Image = scene.render()
        scene.close()
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val distinct = (0 until 1180 step 37).flatMap { x -> (0 until 800 step 41).map { y -> bitmap.getColor(x, y) } }.toSet().size
        println("smoke: rendered frame ${image.width}x${image.height}, $distinct distinct sampled colors")
        if (image.width != 1180 || distinct < 3) problems += "offscreen render produced a blank frame"
        app.close()
        problems.forEach { println("smoke: FAIL $it") }
        println(if (problems.isEmpty()) "smoke: OK" else "smoke: FAILED")
        return if (problems.isEmpty()) 0 else 1
    }

    fun arg(args: Array<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
}
