package app.mokuhyo.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mokuhyo.desktop.ui.Destination
import app.mokuhyo.desktop.ui.FirstRun
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.Shell
import app.mokuhyo.desktop.ui.exam.SessionPreview
import app.mokuhyo.exam.Skill
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `--screenshots DIR [--language xx] [--dark]` (development/QA): renders every rail destination offscreen to PNG,
 * after a few frames so async loads settle. Uses the normal data dir unless `--data-dir` is given.
 */
object Screenshots {
    fun run(args: Array<String>): Int {
        System.setProperty("java.awt.headless", "true")
        val out = File(Smoke.arg(args, "--screenshots") ?: "screenshots").apply { mkdirs() }
        Smoke.arg(args, "--data-dir")?.let { System.setProperty("mokuhyo.data.dir", it) }
        val app = AppGraph()
        Smoke.arg(args, "--language")?.let { app.setLanguage(it) }
        val only = Smoke.arg(args, "--only")?.split(",")
        Destination.entries.filter { only == null || it.name.lowercase() in only }.forEach { d ->
            val scene = ImageComposeScene(1280, 840, Density(1f)) { MokuhyoTheme { Shell(app, start = d) } }
            var image = scene.render(0)
            for (i in 1..12) {
                Thread.sleep(150)
                image = scene.render(i * 16_000_000L)
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "${d.name.lowercase()}.png").writeBytes(png)
            scene.close()
        }
        if ("--first-run" in args) {
            val scene = ImageComposeScene(1280, 840, Density(1f)) { MokuhyoTheme { FirstRun(app) {} } }
            var image = scene.render(0)
            for (i in 1..8) { Thread.sleep(150); image = scene.render(i * 16_000_000L) }
            File(out, "first-run.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        Smoke.arg(args, "--session")?.let { spec ->
            val (skillId, level) = spec.split(":")
            val skill = Skill.of(skillId)
            val scene = ImageComposeScene(1280, 840, Density(1f)) {
                MokuhyoTheme { SessionPreview(app, skill, level, answer = false) }
            }
            var image = scene.render(0)
            for (i in 1..20) { Thread.sleep(200); image = scene.render(i * 16_000_000L) }
            File(out, "session-$skillId-$level.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        app.close()
        println("screenshots: ${out.absolutePath}")
        return 0
    }
}
