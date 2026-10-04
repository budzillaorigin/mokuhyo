package app.mokuhyo.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import app.mokuhyo.desktop.ui.FirstRun
import app.mokuhyo.desktop.ui.MokuhyoTheme
import app.mokuhyo.desktop.ui.Shell
import app.mokuhyo.desktop.ui.WindowBackground
import androidx.compose.runtime.LaunchedEffect
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // BRIEF_PHASE8 N-11: no network at all — not even the optional update check or Ollama detection.
    if ("--no-network" in args || System.getenv("MOKUHYO_NO_NETWORK") == "1") app.mokuhyo.net.NetworkPolicy.disabled = true
    when {
        "--smoke" in args -> exitProcess(Smoke.run(args))
        "--smoke-opi" in args -> exitProcess(SmokeOpi.run(args))
        "--chatterbox-jobs" in args -> exitProcess(ChatterboxJobs.jobs(args))
        "--import-clips" in args -> exitProcess(ChatterboxJobs.importClips(args))
        "--roundtrip" in args -> exitProcess(LangSmoke.roundtrip(args))
        "--smoke-lang" in args -> exitProcess(LangSmoke.run(args, app.mokuhyo.tts.VoiceService.create()))
        "--screenshots" in args -> exitProcess(Screenshots.run(args))
        "--render-audio" in args -> exitProcess(RenderAudio.run(args))
        "--eval-speaking" in args -> exitProcess(EvalSpeaking.run(args))
        "--calibrate" in args -> exitProcess(CalibrateSpeaking.run(args))
        "--smoke-opi-full" in args -> exitProcess(SmokeOpiFull.run(args))
    }
    val app = AppGraph()
    app.updates.startup()
    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 800.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = {
                app.close()
                exitApplication()
            },
            title = "Mokuhyo",
            state = state,
            icon = painterResource("icon.png"),
        ) {
            window.minimumSize = java.awt.Dimension(1024, 700)
            window.background = WindowBackground
            // The window may not be visible on the first call; DWM ignores hidden ones, so try a few times.
            LaunchedEffect(Unit) { repeat(3) { WindowsTitleBar.darken(); kotlinx.coroutines.delay(400) } }
            MokuhyoTheme {
                var setUp by remember { mutableStateOf(app.firstRunDone) }
                if (setUp) Shell(app) else FirstRun(app) { setUp = true }
            }
        }
    }
}
