# D-044 — interview readiness implementation evidence

19 October 2026. Branch: `codex/interview-readiness-d044`. Pulled baseline:
`9a1c7612fdb2fc2296ca13105ff34e816c993916` (the QC handoff on top of `033f0ef`).
Development version remains the build's default `0.1.0`; identify this change by commit.
This is implementation validation on Linux x86_64/JDK 21, not a real-microphone latency certification.

## Commits and failure-first evidence

| Finding | Commit | Regression and observed failure before the fix |
| --- | --- | --- |
| C1 | `1136aab`, completed by `f87d031` | `InterviewCaptureTest`: main removes capture on busy, cancelling ASR or preventing it from starting; confirmation/Cancel/retry/navigation covered. `TopicCaptureTest` exercises the actual Topic screen, confirmation, and synthetic audio output before CPU critique. |
| C2 | `12516ce` | `InterviewCancelTest`: Cancel before question one and after an answer leaves no Retry/End on main. Session test also verifies re-delivery without phase advancement or duplicate answers. |
| C3 | `62569a8` | `InterviewCheckpointTest`: main has no saved accepted answer while the next request waits; a failed Topic reply removes the learner's answer. Recovery is exercised by disposing and reopening the Speaking screen. Bundle test imports successive snapshots, then completion, without overwriting earlier records. |
| C4 | `a8c0e7d` | `SpeechPriorityTest`: both Live and After-action block the reply on critique; foreground work starts before running critique is cancelled. The fixed test checks cancellation cleanup before the next inference is issued. |
| C5 | `9b01bd7` | `VadStartupTest`: steady speech from frame one is classified as no audio on main. Quiet-fan/click and existing pause tests remain passing. |
| C6 | `7d0f0a0` | `InterviewDeviceTest`: one-second device open and Stop both block the UI on main. The fixed UI returns in under the test's 700 ms allowance and closes an input acquired during navigation. |
| C7 | `e9ffcad` | The existing seven `PassageAudioLoadTest` cases reproduce three failures with this sandbox's staged packs. All seven now pass both staged and isolated; fixtures assert the exact resolved file. Production pack precedence is unchanged. |
| Storage error follow-up | `11ab177` | `InterviewStorageFailureTest` reproduces a stranded answer after replacing the temporary recordings directory with a file. Retry saves the retained audio after repair, without duplicating the answer. This failure was reproduced after C1–C7 as well as the test failing on main. |

[Reproduction script](d044/reproduce-main.sh) archives the original main and copies **only tests** onto it.
No production changes or new production APIs are required to compile those tests on main.
The combined baseline run reports **4/5 shared and 10/11 desktop failures**, all runtime assertion/control-state
failures; the quiet-click VAD case and navigation cancellation are passing controls. The strengthened Topic audio
test was rerun separately on main and still fails at capture, before reaching playback.

Raw [baseline log](d044/main-regressions.log), individual `C1`–`C7` before/after logs, and the storage-error logs
are retained in [d044/](d044/). Earlier diagnostic probes in this evidence folder intentionally assert bad behavior;
they are not included in the product regression suite.

## Final validation

| Check | Result |
| --- | --- |
| `./gradlew :shared:allTests :desktopApp:test --console=plain` | **384 shared + 42 desktop, zero failures/errors.** See [log](d044/all-kotlin.log) and [summary](d044/test-summary.txt). |
| Conditional integration coverage | JUnit reports zero skips, but **27 stdout SKIP messages** record unavailable native model fixtures, Whisper/Piper/OS voices, and dictionaries. These integrations did not pass through real inference/audio. |
| Development asset preparation | Built the pinned MeCab-IPADIC tokenizer with `uv run --locked python packs/build_tokenizer.py`. This resolves the prior Japanese segmentation failures. Generated data is not committed. Full dictionary packs remain absent; this run does not claim `MOKUHYO_REQUIRE_PACKS=1` coverage. |
| Python/tools | Ruff passes; **12/12 test scripts**; license, model-documentation and provenance gates pass. [Log](d044/python.log). |
| Terms | Seeds/alignment/offline feeds pass with `MOKUHYO_NO_SOURCES=1`. Reference-PDF overlap remains skipped because authorized source PDFs are absent. [Log](d044/gate-terms.log). |
| Native CPU | Rebuilt successfully from the existing pinned source cache. Generated local artifact hash was excluded from the PR. |
| Desktop development smoke | `:desktopApp:run --args='--smoke --no-network'` passes with native CPU, DB, 11 languages and a nonblank frame. |
| Distributable + packaged smoke | `:desktopApp:createDistributable` succeeds; the built launcher passes `--smoke --no-network` with native CPU and an 1180×800 frame. [Build](d044/distributable.log), [smoke](d044/packaged-smoke.log). This uses available staged assets, without voice/model weights. |
| Complete `gate_build` | **Blocked locally** during staging: proxy HTTP 403 downloading the existing approved Piper Spanish voice. [Log](d044/gate-build.log). |
| Debian installer | **Blocked locally** because `fakeroot` is not installed. The distributable is separately verified. [Log](d044/package.log), [error](d044/package-error.log). |
| CI | PR workflow runs Linux. A narrowly scoped merged-PR workflow dispatches `ci.yml` on main after the owner merges this branch, requesting all three OS matrices. No merge or dispatch was performed during implementation. |

The owner-confirmed green three-OS `033f0ef` CI, including `gate_build` and packaged smoke, remains the baseline
CI evidence. C7's three failures were sandbox-specific; this PR does not relabel them as product/CI failures.

## Timing evidence and limits

| Stage / metric | Evidence in this change | Remaining measurement |
| --- | --- | --- |
| Microphone open / Stop UI response | Synthetic one-second blocking device; each UI call completes under a 700 ms test allowance. | Real driver delays and cancellation on supported Windows/macOS/Linux hardware. |
| ASR lifecycle | Delayed callback survives busy/recomposition; Cancel reaches the fake native bridge; retry ignores stale callbacks. | Whisper cold/warm inference p50/p95 with actual microphones and answer lengths. |
| Reply → speech | Topic reply returns before any critique; UI test observes synthetic audio output with only one model call. | Time to first meaningful audio with real LLM, ASR and TTS active together. |
| Inference contention | Running critique is cancelled and cleaned up before foreground starts; feedback queue capped at 32 and final drain at 30 s. | Native queue wait, cancellation latency and memory under sustained hardware load. |
| TTS quality / real-time factor | Not measured; no new engine/voice/model selected or installed. | Blind listening comparison, pronunciation/dialect review and TTS timing in the separate later phase. |

The proposed 2 s median / 4 s p95 and baseline 3 s / 6 s targets remain **unverified goals**. A 30-minute physical
interview, device-unplug tests, Windows/macOS hardware checks, and memory/latency support matrix are still required.
RMS VAD cannot distinguish a steady loud fan from a steady vowel: it now conservatively retains such audio for
ASR/manual Stop, with a two-minute per-recording cap, rather than discarding immediate speech as silence.

Model timing selection, compact local voices, prerecorded fallback questions, sentence streaming and bank
rerendering are deferred. No new model/voice provenance exception is introduced; rule 13 and D-041 still apply.
