# Native inference: llama.cpp + whisper.cpp

Offline LLM and speech-to-text (BRIEF §3.2, §3.5, §7.1). Both engines are MIT-licensed and share the ggml tensor library (MIT); see `docs/LICENSES.md`. They sit behind the shared interfaces `app.tsumugi.ai.LocalLlmBridge` and `LocalSttBridge`. All prompt building, grammars, parsing and model management live in `shared/`.

| | iOS | Android |
|---|---|---|
| LLM | `LlamaBridge()` (`iosApp/Tsumugi/Platform/LlamaBridge.swift`) | `LlamaJni(context)` (`androidApp/.../platform/LlamaJni.kt`) |
| STT | `WhisperBridge()` (`WhisperBridge.swift`) | `WhisperJni(context)` (`WhisperJni.kt`) |
| Engine source | prebuilt xcframeworks, llama.cpp **b11040** and whisper.cpp **b5130** | built from the same tags' source tarballs with CMake FetchContent (`androidApp/src/main/cpp/`) |
| Acceleration | Metal (all layers offloaded, `n_gpu_layers = -1`) | CPU, NEON + ARMv8.2 dot-product, arm64-v8a only |

## Pinned versions

| What | Pin | SHA-256 |
|---|---|---|
| `llama-b11040-xcframework.zip` | llama.cpp b11040 | `b04aa78c994c9c781ed5e864d6e072275994d8990f3d91b31e1382640c4173ce` |
| `whisper-b5130-xcframework.zip` | whisper.cpp b5130 | `033a43b0174e8cf9b366f72e4a428cdcf126f93ad1c87d3fa119a96bed6f231a` |
| llama.cpp source `b11040.tar.gz` | b11040 | `5205d346d8a5cc005202128b68fe88afe11c1c4b9a004b9215af19d7046eab8f` |
| whisper.cpp source `b5130.tar.gz` | b5130 | `a9ad0f82f30cb6ac5b627874895f89571dd64f7c49d0df3a38550fdb9325fe9b` |

Both tags are real GitHub releases. They are the projects' automated build releases, which GitHub marks "pre-release", not the numbered `v1.x` releases. Checked 2026-09-18 (F-44) against the GitHub releases API:

| Tag | Published | Commit | Asset | Size | GitHub-reported digest |
|---|---|---|---|---|---|
| [llama.cpp b11040](https://github.com/ggml-org/llama.cpp/releases/tag/b11040) | 2026-09-18 | `5b335f413e4f73b0809c4fe39af894efbcc6a0d2` | `llama-b11040-xcframework.zip` | 57,802,059 B | matches the pin above |
| [whisper.cpp b5130](https://github.com/ggml-org/whisper.cpp/releases/tag/b5130) | 2026-09-11 (same day as v1.9.4) | `927cfce34f31707e17f2bff35c349632fb9e2c3a` | `whisper-b5130-xcframework.zip` | 57,180,543 B | matches the pin above |

`fetch_ios_frameworks.sh` downloads from `https://github.com/ggml-org/<repo>/releases/download/<tag>/<asset>` and refuses a file whose SHA-256 differs. CI runs it in the iOS job.

To bump a pin, change the tag and the hash together in `fetch_ios_frameworks.sh` and `androidApp/src/main/cpp/CMakeLists.txt`. Then check the bridges against the new `llama.h` and `whisper.h`, because the C API moves often.

## Setup

**iOS (on a Mac):**

```sh
bash tools/models/fetch_ios_frameworks.sh   # → iosApp/Frameworks/{llama,whisper}.xcframework (git-ignored)
```

The "Embed Native Frameworks" build phase copies the right slice into `Tsumugi.app/Frameworks` and code-signs it.

- **Both are dynamic frameworks.** Each bundles its own ggml, which is fine for two dylibs. Swift imports only `llama`. whisper is reached through the C shim `iosApp/Tsumugi/Platform/tsumugi_whisper.{h,c}` via the bridging header, because the two frameworks ship different `ggml.h` copies and Clang rejects both modules in one Swift module on device builds.
- **No llama simulator slice.** The pinned llama.xcframework has no iOS Simulator slice. In the Simulator, `LlamaBridge` compiles as a stub that reports "not available in this build"; use a device to test the LLM. whisper works in the Simulator (CPU only).
- **Frameworks are required to build.** The app target links them explicitly (`OTHER_LDFLAGS[sdk=…]`: whisper on the simulator, llama + whisper on devices), because Swift did not autolink these prebuilt frameworks. Run `fetch_ios_frameworks.sh` once before the first Xcode build.
- **CI** fetches the frameworks, runs the simulator tests, and also builds for `generic/platform=iOS` so the llama code path compiles.

**Android:** nothing to fetch by hand.

- `./gradlew :androidApp:assembleDebug` downloads the pinned tarballs (hash-checked) and builds `libtsumugi_llama.so` and `libtsumugi_whisper.so`.
- It needs NDK 29.0.14206865 and CMake 3.31.6. Install them with `sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"`, or let AGP install them.
- The first build takes several minutes. Pass `-Ptsumugi.native=false` to skip the native build for UI-only work; the bridges then return "On-device AI isn't included in this build".
- **ggml sharing:** llama.cpp is added first and defines the `ggml` target, and whisper.cpp reuses it. Each `.so` statically links ggml and exports only its JNI symbols (`--exclude-libs,ALL`, hidden visibility).
- **Minimum CPU:** ARMv8.2 with dot-product, roughly every arm64 SoC since 2018. `NativeLibs.check` reads `/proc/cpuinfo` for `asimddp` and returns an error message on older CPUs instead of crashing with SIGILL.

## Models (not in git)

GGUF LLMs and whisper `ggml-*.bin` models are downloaded at runtime by the model manager in `shared/`, or placed in `content/models/` (git-ignored) for development. Suggested defaults (BRIEF §7.1):

- **LLM:** a 3–4B instruct model at Q4_K_M, about 2–2.5 GB.
- **STT:** `ggml-small` or `ggml-base` (multilingual, not `.en`).

## Integration notes (for the AppGraph wiring)

- **Construction is cheap.** The native libraries and models load on `load(...)`; do that when the feature is first used, not at launch.
- **Threading:**
  - Each bridge owns one serial worker: a `DispatchQueue` on iOS, a single-thread executor on Android.
  - Calls are processed in order. `onToken` and `onDone` fire **on that worker thread**, never on the main thread, so hop to the main thread or a coroutine before touching UI state.
  - Don't block the worker from inside a callback, for example by calling `load` and waiting on it.
  - `isLoaded()` is lock-free and safe from any thread.
- **Cancellation:**
  - `cancel()` sets a flag that is checked before each token, so generation stops within one token and `onDone(partialText, null)` fires.
  - Prompt evaluation (prefill) can't be interrupted.
  - `unload()` cancels, then frees on the worker after any in-flight call.
- **Stop strings:** stop strings are matched on the accumulated text. The text returned in `onDone` is cut before the stop string, and the chunk containing it is not streamed.
- **Grammar:** a non-empty `grammar` is compiled as GBNF with start symbol `root`. An invalid grammar fails fast with `"Invalid GBNF grammar"`.
- **Memory:**
  - Use a context of **4096** tokens (`load(path, 4096)`). The KV cache for a 3–4B model at 4096 is about 150–300 MB, on top of the weights.
  - Load one LLM at a time: `load` frees the previous model first.
  - On iOS, call `unload()` on a memory warning and when the Talk screen goes to the background.
  - On Android, call `unload()` from `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` or above.
- **KV-cache reuse (implemented):**
  - The bridge remembers which tokens are in the KV cache (prompt plus generated reply).
  - When the next prompt starts with the same tokens (a conversation re-rendered with the new turn appended), only the new suffix is decoded. Anything else clears the cache.
  - For this to work, build each prompt by **appending** to the previous one, with the same chat template and system prompt, and include the model's previous reply verbatim.
  - Starting a new conversation needs no special call: a different prefix simply misses the cache.
  - If the conversation outgrows the context, `generate` fails with "Prompt too long"; the shared layer should summarize or trim older turns.
- **STT input:** 16 kHz mono float samples in [-1, 1].
  - Results are JSON `[{"t0": ms, "t1": ms, "text": "…"}]`, with segment-level timestamps only (no token timestamps).
  - Transcription isn't cancellable; keep clips short (under 30 s) for interactive use.
