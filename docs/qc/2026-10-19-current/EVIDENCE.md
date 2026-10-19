# Evidence for the current-code QC

Revision: `033f0efd401b047b770c7a975950d90fd4eef99e`, 19 October 2026. Environment: Linux x86_64, JDK 21.0.12.1, Python 3.12, headless Compose, existing CPU native build. This was not a physical laptop/audio benchmark.

**CI context:** the owner confirms green Windows, macOS and Linux CI on this revision, including `gate_build` and the packaged smoke. Those CI results were supplied by the owner and were not independently rerun in this review. The three fixture-isolation failures recorded below are specific to this sandbox's staged-resource setup, not CI or packaged-build failures.

## Results

| Check | Current result |
| --- | --- |
| Standard shared tests, required packs enabled | 377 reported; 2 failures, 0 errors, 0 runner skips. Missing Japanese dictionary and tokenizer development databases. |
| Standard desktop tests in this sandbox | 31 reported; 3 sandbox-specific fixture-isolation failures in `PassageAudioLoadTest`, 0 errors, 0 runner skips. Staged resources shadow the corrupt-clip fixtures; see C7. |
| Isolated-resource contrast for C7 | All 7 `PassageAudioLoadTest` cases pass with an empty bundled-resource root, unchanged source/assertions, and the original staged files left in place. |
| Additional diagnostic probes | 3 reported; all three reproduced the asserted undesirable behavior. They are not counted as successful product behavior. |
| Conditional integration coverage | 16 stdout `SKIP` messages in the shared suite despite zero runner skips: missing real model files, tiny GGUF fixture, Whisper weights, Piper build/weights and Linux OS voices. |
| Python suite | 12/12 test scripts passed. |
| Ruff | Passed. |
| Strict content validation | All 22 language/skill banks: 0 errors, 0 warnings. This is structural validation, not native-speaker/instructor approval. |
| License and model provenance | Both report 0 problems. Model documentation consistency check exits successfully. |
| Startup `--smoke --no-network` | Passed, including CPU native runtime, SQLite, 11 languages, 10 model entries and a nonblank 1180×800 frame. |
| Three-OS CI (owner-confirmed) | Green on `033f0ef` for Windows, macOS and Linux, including `gate_build` and the packaged smoke. Separate from the sandbox checks above. |

The sandbox's standard suite totals **408 cases and 5 environment-related failures**: two missing development assets and three sandbox-specific fixture-isolation failures. With diagnostics, the recorded run totals **411 and 5 failures**. Passing counts include the conditional early-return integrations described above. This is not a complete release-gate run and does not contradict the owner-confirmed green three-OS CI. Authorized source-PDF overlap validation, installed-release verification, model/voice quality, and Windows/macOS hardware runs were not performed in this pass.

Logs: [JVM run](jvm-checks.log), [isolated loader tests](isolated-packs.log), [Python run](python-checks.log), [startup](startup.log). Full XML results and other logs are retained in `/workspace/.mokuhyo-qc-current/` in this workspace. The bundled diagnostic sources are opt-in and do not enter normal application/test source sets.

## Diagnostic observations

From [QcCurrentProbeTest.kt](probes/QcCurrentProbeTest.kt):

```text
capture-lifecycle: disposed=true, cancelled=true, busy=true, submitted=false
topic-after-action: replyGenerated=true, returnedToVoice=false, modelCalls=2
vad-immediate-speech: events=[NO_AUDIO], heard=false
```

- **Capture:** a minimal Compose reproduction of the production `!busy` parent branch and child-owned coroutine pattern, with a deferred transcription result. It proves the lifecycle mechanism; the production microphone/recognizer UI still needs an injected-device end-to-end regression.
- **Topic:** actual `TopicSession` and `AiGateway`, fake language model, immediate valid reply and a blocked critique. After-action mode does not return the reply until critique is released.
- **VAD:** actual `Vad`; 40 frames of level `0.15` from startup produce NO_AUDIO. A quiet lead-in followed by the same level produces SPEECH_START. This models a startup edge case rather than quantifying real-world prevalence.

The diagnostics intentionally assert the observed defect. After fixes, turn the corresponding assertions into expectations of correct behavior in the maintained test suite. Do not weaken production tests to preserve these diagnostic assertions.

## Commands

Activate the configured environment (`source /workspace/.mokuhyo-cloud/env.sh` in this workspace), then from the repository root:

```bash
MOKUHYO_REQUIRE_PACKS=1 ./gradlew \
  -I docs/qc/2026-10-19-current/probes.init.gradle \
  :shared:jvmTest --rerun :desktopApp:test --rerun \
  --continue --no-configuration-cache --offline --console=plain --max-workers=4

./gradlew :desktopApp:run --args='--smoke --no-network' \
  --offline --console=plain --max-workers=4

# Only the three diagnostic probes:
./gradlew -I docs/qc/2026-10-19-current/probes.init.gradle \
  :desktopApp:test --tests '*QcCurrentProbeTest' \
  --no-configuration-cache --offline --console=plain --max-workers=4

# Contrast run for C7, with a fresh test process and an empty resource root:
./gradlew -I docs/qc/2026-10-19-current/isolated-packs.init.gradle \
  :desktopApp:test --tests '*PassageAudioLoadTest' --rerun \
  --no-configuration-cache --offline --console=plain --max-workers=4
```

The run log used equivalent init scripts in the temporary QC directory; the bundled versions use repository-relative paths for portability. `--offline` assumes dependencies are already cached. The initial sandboxed Gradle attempt could not bind its lock-contention socket; rerunning with supported network permission resolved that environment failure. It is not counted as an application defect.

From `tools/`:

```bash
uv run --locked --offline ruff check .
uv run --locked --offline --group content --group release python -W ignore run_tests.py
uv run --locked --offline --group content python -W ignore items/gen_dlpt.py validate --language all --strict
uv run --locked --offline python gates/check_licenses.py
uv run --locked --offline python gates/check_provenance.py
uv run --locked --offline python models/models_doc.py --check
```
