# Feature QC — v0.4.0 — 2026-10-21

## Summary

**Verdict: suitable for controlled owner hardware testing with a disposable synthetic learner, but not yet for a wider pre-release.** Reading practice and interrupted timed-test recovery worked in Japanese, Arabic and Spanish; the nine destinations rendered offline; real SQLite/backup probes found two data-safety defects. Fix Q1–Q3 before relying on retained progress or distributing score-bearing screens. Resolve Q4 and the release/verification gaps, then complete the supported-machine checklist below before calling the spoken experience ready.

There are **10 findings: 3 P0, 1 P1, 6 P2, 0 P3**. These priorities follow this brief's rubric: Q3 is P0 because the required score disclaimer is a non-negotiable rule, not because it loses data. There is no demonstrated false proficiency estimate or outbound connection in offline mode. Linux is unofficial (D-025); its install failure is not a supported-Windows/macOS release blocker by itself.

The review branch starts from pulled `main` **262004b8a8aed06d501c55fcede521782aff3e8f**. Execution used a detached checkout of `v0.4.0`, **8bbbe8f58f2d8ccf26f074ca9bd7aff57bc1531d**. The only difference between those commits is the QC brief itself; application behavior reviewed here is identical. This PR changes documentation, screenshots and opt-in diagnostics only. All learner records, transcripts and PDF names used here are synthetic.

Environment: Debian 13 x86_64 container, 4 exposed cores/16 GB reported RAM, JDK 21.0.12, Gradle 9.7.1, Python 3.12, headless Compose. Native CPU and Piper builds succeeded from the pinned sources in a disposable copy. Approved Tier A and Whisper downloads returned HTTP 403; the approved Piper voice URL also returned 403. No physical microphone, speaker, desktop session, Windows/Mac host, real inference or latency measurement was available. The source checkout's staged packs omit recorded listening audio and some dictionary/tokenizer assets. These absences are **not** evidence that the published installers omit them.

The final uncached suites reported 435 shared cases (two failures: PDF assertion Q7 and the unavailable required Spanish dictionary) and 56 desktop cases (all passing). Some tests return early rather than report a JUnit skip: [the preserved summary](qc/2026-10-21-features/tests-summary.json) includes 18 such diagnostic notices. Do not interpret the aggregate as a real-model or all-dictionary pass. Ten additional review probes exercised real storage, headless UI and an actual HTTP engine; defect probes deliberately assert the observed bad behavior. Standard gate results and exact reproduction commands are in [EVIDENCE.md](qc/2026-10-21-features/EVIDENCE.md).

The matrix contains **117 rows: 37 Works, 11 Partial, 6 Broken, 38 Not testable here, 25 Code-reviewed only**. Multiple rows can refer to the same finding.

## Linux fresh-install investigation — performed first

[Release run 39355829686](https://github.com/budzillaorigin/mokuhyo/actions/runs/39355829686), Linux job `118417993762`, used Ubuntu 22.04.5, runner image `20261012.322.1`. Its `.deb` checksum passed, then installation emitted `E: Sub-process /usr/bin/dpkg returned an error code (1)` and the step exited 100. The installed app was never launched. Windows and both macOS fresh-install jobs succeeded according to the remote job metadata; those are CI results, not local hardware tests.

**Could not reproduce the package failure:** this host is Debian 13, not Ubuntu 22.04; the requested `gh release download v0.4.0 --repo budzillaorigin/mokuhyo` was refused at `api.github.com` with `Forbidden`. The connector could read release metadata/logs, but the Linux workflow archive is 3,381,711,500 bytes, exceeding its 536,870,912-byte download limit. Neither Linux installer nor portable bytes nor `SHA256SUMS` were downloaded, so no independent checksum verification or install is claimed. [Release asset digests](qc/2026-10-21-features/release-assets.json) are metadata only.

The available logs **do not establish a missing dependency**. `tools/gates/fresh_install_smoke.sh:45` discards `apt-get` stdout, where detailed `dpkg` failures can appear. A safe stubbed diagnostic reproduces that loss of information (Q5). Name the fix accurately: preserve install output and failure diagnostics, then rerun the exact `.deb` on a clean Ubuntu 22.04 runner and fix the demonstrated packaging cause. Do not guess a dependency or claim that changing logging repairs the installer. [CI excerpt](qc/2026-10-21-features/linux-ci-excerpt.log), [download error](qc/2026-10-21-features/release-download.log), [logging probe](qc/2026-10-21-features/logs/release-probes.log).

## Feature matrix

Each row has exactly one requested status. **Works** means the stated end-to-end scope was exercised; a headless UI action proves that flow, not OS mouse/keyboard integration or real sound. **Code-reviewed only** means the complete user flow was not run, even where supporting unit tests passed. Speech, physical playback and latency rows remain **Not testable here** despite mock coverage. Every **Partial** or **Broken** row names a finding.

Evidence abbreviations: **P** = [review probes and observations](qc/2026-10-21-features/EVIDENCE.md#review-probes); **T** = [existing suite results](qc/2026-10-21-features/tests-summary.json); **S** = [screenshots](qc/2026-10-21-features/EVIDENCE.md#screenshots); **I** = [all-language inventory](qc/2026-10-21-features/content-inventory.json). Source locations use **UI/** = `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/`, **SH/** = `shared/src/`; file paths are relative to the reviewed repository. The [source index](qc/2026-10-21-features/EVIDENCE.md#source-index) links the principal files. Counts are recorded in the evidence index and PR description.

### 1. Install, first run and shell

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Install | Linux `.deb` fresh install and packaged smoke | Broken | CI checksum OK → dpkg failure → exit 100; no launch. Local reproduction blocked as above. | Q5 |
| Install | Linux portable starts and smoke passes | Not testable here | Artifact download refused; portable never launched. | — |
| Install | Windows and macOS installers/portables, packaged smoke | Not testable here | Release CI jobs passed; no local Windows/Mac hardware or artifact execution. | — |
| First run | Welcome → languages → hardware scan → tier page | Works | P `firstRunSkipAndReviewGradeUndo`: real controls and asynchronous scan reach tier selection. | — |
| First run | Tier download, N2 speed check, audio check, Home | Not testable here | Tier/Whisper/voice weights HTTP 403, no audio devices; UI/FirstRun.kt:42 and TierPicker.kt:175 reviewed. | — |
| First run | Reading/Listening-only skip | Works | P skip → audio-check page → Finish sets firstRunDone/readingOnly; Reading works with no model. Real Listening sound not implied. | — |
| Shell | All nine destinations | Works | P and CLI render Home, Reading, Listening, Speaking, Lexicon, Review, History, Report, Settings; S nine images. | — |
| Shell | Rail scrolls at short heights | Works | T `NavigationRailRenderTest` renders/scrolls the short rail; UI/Shell.kt:70. | — |
| Shell | Title-bar language switch updates every feature | Partial | P Spanish→Arabic while Lexicon Drills is open retains Spanish drill; other Reading screens change language. | Q4 |
| Shell | Per-language learner data isolation | Works | P actual practice/test attempts in ja/ar/es; another language stays empty after restart. | — |
| Shell | 1024×700, 150%/200%, dark mode | Works | S nine 1024×700 frames, Home at density 1.5/2; dark rendering inspected. OS DPI/focus remains owner check. | — |
| Shell | Update check initially off | Works | P reads fresh persisted Settings default=false and opens Privacy; T `UpdateCheckTest`. | — |

### 2. Home

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Home | Modality estimates, trends and provisional stars | Code-reviewed only | UI/HomeScreen.kt:34,64 uses persisted IlrPoint values; T `SpeakingTrendTest`, `HistoryAndReviewTest`; populated dashboard not driven end to end. | — |
| Home | Next activity and review count | Partial | Empty recommendation rendered (S Home); Q1 makes imported ungraded items invisible to due count. Recommendation branches reviewed at HomeScreen.kt:79. | Q1 |
| Home | New-learner empty state | Works | P/S Home: all three modalities “No tests yet”, no estimate, recommendation and disclaimer visible. | — |

### 3. Reading

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Reading | Practice selection by level and text type | Code-reviewed only | UI/exam/SkillScreen.kt:152; defaults exercised; exhaustive selector combinations not clicked. I covers all six levels in all languages via gate content. | — |
| Reading | Practice answer → feedback/explanation → save | Works | P ja/ar/es real UI, answer feedback and finish; S `practice-result-*`; real stored attempts. | — |
| Reading | Tap-to-define using mouse and keyboard | Not testable here | No desktop input session; complete dictionary staging blocked by Spanish download. Later Japanese contract lookup passes, but UI lookup not driven. UI/exam/PassageText.kt:70,151 reviewed. | — |
| Reading | Furigana and pinyin aids | Code-reviewed only | PassageText.kt:199; T `FuriganaTest`, Japanese lattice/reading-aid contract cases pass; real aids not toggled through UI. | — |
| Reading | Arabic/Persian RTL with LTR chrome | Works | P/S `reading-ar`, `reading-fa`; Arabic practice/resume completed. PassageText confines content direction. | — |
| Reading | Add a lookup or missed question to Review | Code-reviewed only | PassageText.kt:151 and SkillScreen.kt:454 explicitly call ReviewService; missed-answer add button is opt-in. Review storage itself exercised separately. | — |
| Reading | Signs, badge forms, chats, shift logs, notices, schedules | Works | T `FormatRenderTest`: all six renderers in es/ar; I includes six formats for each language; S includes real bank formats. Semantic accuracy not a language-expert audit. | — |
| Reading | 30/60/180-minute, 60-item form assembly | Code-reviewed only | T `ExamSessionTest`, `RealExamPacksTest`; UI/SkillScreen.kt:152 chooses FormLength. Only the default short form was submitted through UI. | — |
| Reading | Real-time countdown and expiry | Code-reviewed only | SkillScreen.kt:319 ticks each second; T fake-clock session tests. No 30/60/180-minute wall-clock run. | — |
| Reading | Quit/relaunch → resume; cancel submission | Works | P ja/ar/es closes/reopens AppGraph on same SQLite dir, Resume → Keep going → Submit. | — |
| Reading | Scoring, estimate and required disclaimer | Works | P real saved short Reading tests, rendered result and disclaimer; S `test-result-*`; T estimator cases. | — |
| Reading | Short/incomplete form marked provisional | Works | P incomplete ja/ar/es test exports provisional estimates and result labels; T `IlrEstimatorTest`. | — |
| Reading | Generate more, local label, estimate/statistics exclusion | Not testable here | No Tier A weights; UI/SkillScreen.kt:258,360 and HistoryRepository separate local bank. Generation/cancel/error not exercised with real model. | — |

### 4. Listening

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Listening | Practice reveal after answer, replay, 0.8/0.9/1.0× WSOLA, dictation | Not testable here | No real clips/voice/output. Keyboard probe uses synthetic WAV only; T `TimeStretchTest` measures synthetic signal; UI/SkillScreen.kt:471,591. | — |
| Listening | Test blueprint play count; count on start; failed-play refund | Not testable here | T `ExamSessionTest`, `PlaybackOpenTest` and `PlaybackCancelTest` support control flow; no physical start/disconnect. | — |
| Listening | Missing/damaged clip Try again / Leave out; provisional scoring | Not testable here | T seven `PassageAudioLoadTest` cases and session void/recovery pass; actual packaged clips and output unavailable. UI/SkillScreen.kt:494,577. | — |
| Listening | Degraded practice; rejected in tests | Not testable here | T `DegradeTest` exercises refusal and DSP; practice-only UI/SkillScreen.kt:479,678; no audible comparison. | — |
| Listening | Pre-rendered credits vs voice-service/OS labels | Code-reviewed only | PassageAudio source selection and SkillScreen.kt:556 inspected; recorded Chatterbox/VOICEVOX and OS paths not heard. | — |
| Listening | Numbers-under-stress drill | Not testable here | T `NumbersTest`; no voice/output for real drill or stressed intelligibility. | — |

### 5. Speaking

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Speaking | Interview practice/test: warm-up, checks, probes, role-play, wind-down | Not testable here | T `OpiSessionTest`, `RealOpiPacksTest`, reduced gate_speaking; real Tier A/Whisper/voice absent. | — |
| Speaking | Readiness preflight before Start (N4) | Not testable here | T `InterviewPreflightTest`, `PreflightTest`, `ReadinessTest`; actual device/model warm-up not possible. | — |
| Speaking | Question-delivery gate in tests (F2) | Not testable here | Six `QuestionDeliveryTest` cases pass; mock cancellation/delivery is not an audible gate verification. | — |
| Speaking | Cancel / Retry / End at every stage (C2) | Not testable here | `InterviewCancelTest` passes delayed-engine UI paths; real inference/ASR/TTS cancellation needs devices and weights. | — |
| Speaking | Checkpoint and interruption recovery (C3) | Not testable here | `InterviewCheckpointTest`, `InterviewStorageFailureTest`, actual bundle snapshot tests pass; no physical interrupted interview. | — |
| Speaking | Deadline fallback and guided label (N5) | Not testable here | `ReplyDeadlineTest`, `GuidedInterviewTest`; guided History/PDF qualifier present. Need real deadline/voice run; Q7 is assertion-only. | — |
| Speaking | Text-practice fallback | Code-reviewed only | UI/speaking/SpeakingScreen.kt:336; `QuestionDeliveryTest` proves explicit fallback gating; complete typed interview not run. | — |
| Speaking | Model rating and evidence quotes | Not testable here | Golden-schema/rating tests pass; no real inference or calibration. | — |
| Speaking | No-model self-rating and persistence | Broken | P same conversation saved before/after self-rating: original row overwritten, later import ignores rating. | Q2 |
| Speaking | SpeakingTrend qualifiers in results, History and PDF (F4) | Works | Real stored synthetic ratings → History render and PDF extraction; `SpeakingLabelTest` four pass, failing fifth retains wrapped qualifier (Q7). This tests labels only. | — |
| Speaking | Topic pick/type; speech precedes feedback (C4) | Not testable here | `SpeechPriorityTest`, `CorrectionsModeTest`, `TopicCaptureTest`; real reply audio/feedback ordering and latency unavailable. | — |
| Speaking | Live / After action / Off; After Action Brief | Not testable here | `AfterActionStorageTest`, `AfterActionRenderTest` persist/render synthetic AAB; no model produces a real AAB. | — |
| Speaking | Scenarios, role-plays and personas | Not testable here | I: six personas/language, twelve Arabic; 24 OPI role-plays and 12 track scenarios/language. Model/audio missing. | — |
| Speaking | Cultural notes “not part of the ILR scale” | Code-reviewed only | SpeakingScreen.kt:1188 and HistoryScreen.kt:133; `RealCulturePacksTest` + culture gate all 11; rendered scenario not navigated. | — |
| Speaking | Consecutive, radio relay and sight translation | Not testable here | `InterpretTest` fixtures; interpretation needs model and spoken input/output. No claim of real target-language grading. | — |
| Speaking | Exercise-week counterpart memory and daily stand-to | Not testable here | `StorylineTest`, `StandToTest`; SpeakingScreen.kt:1274, persisted synthetic structures only. Full exercise needs model/voice. | — |
| Speaking | Exemplar answers at 1+, 2, 3 | Code-reviewed only | I: 90 exemplars/language, all three levels; `ExemplarsTest`. Actual exemplar playback unavailable. | — |
| Speaking | Mic picker, level meter, VAD and immediate speech (C5) | Not testable here | `VadStartupTest`, `VadTest`, `InterviewDeviceTest`; no microphone or actual frame-one speech. | — |
| Speaking | Confirm/edit/retake transcript and responsive capture (C1/C6) | Not testable here | `InterviewCaptureTest`, `TopicCaptureTest`, `CaptureResampleTest`; no live Whisper/device timing. | — |
| Speaking | No-model Live disabled; scripted questions described | Code-reviewed only | SpeakingScreen.kt:256 and interview setup inspected; empty Speaking screen rendered, complete no-model flow not driven. | — |

### 6. Lexicon

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Lexicon | Counter-drone/base-defense terms, definitions, examples, unconfirmed labels | Code-reviewed only | S initial terms view; I 402 terms/language; UI/lexicon/LexiconScreen.kt terms renderer reviewed, every term detail not opened. | — |
| Lexicon | Drills and language switching | Partial | P Drills works in es but stays es under ar heading; remembers by kind, not language/track. | Q4 |
| Lexicon | Signed file import, change list, tamper refusal | Works | T `LexiconPackageTest` verifies real Python-signed Ed25519 fixtures, imports, refuses tampering, computes 20 added/5 changed, queues reviews and checks idempotence. Native file dialog not exercised. | — |
| Lexicon | Signed package import by URL, download errors/cancel | Not testable here | External network restricted; URL path/source guard reviewed. No real publisher download. | — |
| Lexicon | “This month” official links for each language | Code-reviewed only | I feeds for all 11; Reading's This month tab (not Lexicon). Feed gate proxy refusal is not evidence of dead official sites. | — |
| Lexicon | Paste-to-practice and no automatic news fetch | Code-reviewed only | T `ThisMonthRenderTest` renders the empty reader in es/ja/ar; paste→read action not driven. Source opens browser only on request; offline nine-screen trace silent. | — |
| Lexicon | Side-by-side terms | Works | T `SideBySideTest`, `SideBySideRenderTest` real multilingual pack lookup/render; headless. | — |

### 7. Review

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Review | Per-language FSRS grading and scheduling | Partial | P reveal → Good → empty → Undo works; T 21 FSRS cases. Imported ungraded cards lose due schedule (Q1). | Q1 |
| Review | Empty queue without focus warning | Works | P empty queue rendered after grading; T `ReviewFocusRenderTest`; no focus-request exception. | — |
| Review | Reading misses, lookups and Lexicon drills feed queue | Code-reviewed only | SkillScreen.kt:454, PassageText.kt:151, LexiconScreen drills add explicitly; signed import queues exercised, individual acquisition UI not all clicked. | — |
| Review | Undo last answer (additional feature) | Works | P grade/undo restores due count=1 and reps=0; tombstone preserves original review fact. | — |

### 8. History

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| History | Attempts/conversations, search/filter, score/detail views | Partial | P stored rated conversation → Open: ILR shown with no required disclaimer; search/filter source reviewed. | Q3 |
| History | Recording playback | Not testable here | No physical output/recording; synthetic bytes round-trip without claiming playback. UI/HistoryScreen.kt recording controls reviewed. | — |
| History | Interrupted interview snapshots visible | Code-reviewed only | `InterviewCheckpointTest`/bundle cases preserve latest immutable snapshot; UI/HistoryScreen.kt:66 renders interrupted text from draft. Actual snapshot in History not opened. | — |
| History | Delete uses tombstones, preserves records | Works | T `HistoryAndReviewTest`, bundle tombstone cases; source delete path uses tombstone including recovery snapshots. No physical row deletion observed for these facts. | — |

### 9. PDF report

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Report | Export with date range/language selection and cancel | Code-reviewed only | UI/ReportScreen.kt:43 and renderer filters reviewed; renderer exercised directly, native save dialog and in-progress cancel not driven. | — |
| Report | All 11 languages: glyphs, extracted text, rendered pages | Works | T `ReportFontTest`; 12-page synthetic PDF, pdftotext, all pages rasterized and contact sheet inspected; Arabic page inspected at full size. | — |
| Report | Disclaimer, provisional footnotes and speaking qualifiers | Works | Retained PDF/extracted text; `SpeakingLabelTest` label exists across line break. UI missing disclaimer is Q3, not a PDF defect. | — |
| Report | After Action Brief sections | Code-reviewed only | SH/jvmMain/kotlin/app/mokuhyo/report/ProgressReport.kt:134 emits AAB. `AfterActionStorageTest` covers bundle storage and `AfterActionRenderTest` UI, neither proves PDF AAB export. | — |

### 10. Backup

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Backup | Export/import plain or encrypted; wrong passphrase/damage | Partial | Real `BundleRoundTripTest`/`CryptoTest` pass graded-record fixtures; P ungraded item survives without a card/due schedule. | Q1 |
| Backup | Merge without duplicate or overwritten learner facts | Broken | P self-rating rewrites existing fact; second import retains old unrated row. Ordinary duplicate bundle import passes existing tests. | Q2 |
| Backup | Bundles from older app/database versions | Not testable here | Legacy StoredConversation JSON compatibility runs, but no authentic older DB-schema bundle available; current-version fixture is not migration proof. | — |
| Backup | Interrupted checkpoints survive repeated round trips | Works | T `BundleRoundTripTest.recoverySnapshotsMergeAfterAnEarlierBundleWithoutLosingTurns`: two immutable snapshots, latest transcript retained. | — |

### 11. Settings

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Settings AI | Tier picker, timing check and recommendation reason | Not testable here | `TimingAwareTierTest`, UI/TierPicker.kt:61,175; no actual Tier A speed sample. | — |
| Settings AI | “Slower — better for end-of-session grading” | Code-reviewed only | TierPicker.kt:97 renders label from timing/recommendation policy; real slower model unavailable. | — |
| Settings AI | Use the CPU | Not testable here | Native CPU build/CLI smoke loads; SettingsScreen.kt:93 preference reviewed. No GPU/model to test switching through UI. | — |
| Settings AI | Ollama connect, disallowed models greyed with reason | Not testable here | SettingsScreen.kt:215, model filters and `LanUrlTest`; no Ollama server/models. PRC/Gemma/Llama rules source-reviewed. | — |
| Settings AI | Thread-cap overrides and turn deadline | Code-reviewed only | SettingsScreen.kt:117,269; `ThreadBudgetTest`, `PiperThreadsTest`, `ReplyDeadlineTest`; deadline control is under Speech & audio. | — |
| Settings AI | Test the model | Not testable here | SettingsScreen.kt:137; model download blocked; no real streamed answer/error/cancel cycle. | — |
| Settings AI | Side-load model with checksum (additional feature) | Not testable here | SettingsScreen.kt:184; `ModelManagerTest` integrity paths pass on fixtures; no approved GGUF file to install. | — |
| Settings Speech | Input/output devices, voices per language, rescan | Not testable here | UI/AudioCheck.kt:34, SettingsScreen.kt:331; no devices or OS/bundled voice weights. | — |
| Settings Speech | Play test voice follows interview path | Not testable here | AudioCheck calls Voice.say; `VoiceServiceTest` verifies routing; real sound unavailable. | — |
| Settings Speech | Per-language readiness panel | Not testable here | ReadinessSection + `ReadinessTest`; actual runtime readiness across 11 voices not measurable. | — |
| Settings Speech | Diagnostics display/export excludes text/audio | Works | `TimingsTest` writes/reloads/exports real JSON/Markdown; `TurnTimelineTest`; retained dry-run timing export contains identifiers/counts/times, no transcript/audio fields. Not a latency result. | — |
| Settings Content | Lexicon updates | Code-reviewed only | SettingsScreen.kt:81 routes to import/update controls; file import API tested above, dialogs/download UI not driven. | — |
| Settings Content | Content review | Code-reviewed only | SettingsScreen.kt:396,405; developer toggle under About & licenses, not Content. No verified flag/content mutated during review. | — |
| Settings Content | Flag an item / suggest a term and export suggestions (additional feature) | Code-reviewed only | UI/SuggestButton.kt:26,54; T SuggestionsTest checks storage; native export dialog and submission UI not driven. Notes remain local until explicit export. | — |
| Settings Privacy | Explain every network call | Partial | P Privacy page omits Lexicon URLs/LAN inference; SECURITY_PROFILE also says localhost-only and data never leaves except exports. | Q8 |
| Settings Updates | Off by default | Works | P new settings false; `UpdateCheckTest` verifies version/release selection only. | — |
| Settings Updates | Manual/automatic update success, errors and cancel | Code-reviewed only | UI/SettingsScreen.kt:375 exposes Failed state; update service policy/source inspected. Actual GitHub request success/error UI not driven. | — |

### 12. Cross-cutting and developer tools

| Area | Feature | Status | Evidence | Finding ID |
| --- | --- | --- | --- | --- |
| Offline | Every destination under `--no-network`, no socket connection | Works | strace on nine CLI-rendered screens: zero AF_INET/AF_INET6 connect/send; P real Java HTTP positive-control loopback accepts one, disabled request is refused before socket. Does not claim unavailable speech works. | — |
| Labels | Required unofficial-practice disclaimer on every score screen | Partial | Reading/PDF/Home show it; History list and detail omit it. P screenshot and semantic assertion. | Q3 |
| Labels | AI-drafted/unreviewed badging until verified | Partial | All 11 Reading screenshots say “AI-generated” but omit the requested unreviewed qualification; Lexicon drills include it. Conditions inspected; no verified flag mutated. | Q10 |
| Labels | Generated bank separate from shipped estimates/statistics | Code-reviewed only | SkillScreen.kt:361 plus HistoryRepository bank checks; actual model generation unavailable. | — |
| Accessibility | Number choices, Space play/pause, Enter navigation/submit | Partial | P same focused listening scene handles 1; Space is unhandled despite hint. Enter source advances; final submit keyboard path not fully exercised. | Q9 |
| Accessibility | Screen-reader labels | Not testable here | Compose semantics inspected, but no NVDA/VoiceOver/AT desktop bridge. Text semantics do not establish spoken labels. | — |
| Accessibility | Focus order and keyboard-first full app | Not testable here | Empty Review focus regression passes and choices accept key events; no full Tab traversal with actual window/OS. | — |
| Scripts | ar/fa RTL, LTR chrome, no CJK tofu | Works | 11 Reading images + nine shell images + all-script PDF; `FontCoverageTest`. Limited to rendered samples, not every possible code point/screen state. | — |
| Data | Attempts, conversations, recordings, estimates and SRS append-only | Broken | Q2 actual INSERT OR REPLACE conversation path; Q1 loses derived schedule. Attempts/estimates/recordings use insert-ignore, deletes are tombstones; exam_in_progress/card are mutable derived/scratch state. | Q1, Q2 |
| Security | SBOM shipped with release | Broken | Release assets contain eight installers/portables and SHA256SUMS, no SBOM; local collector succeeds without one. | Q6 |
| Security | Profile network/data claims | Partial | Offline guard proven; exported-checksum bytes unavailable; profile omits supported LAN inference and overstates data locality. | Q8 |
| Gates | Strict validation of all 22 skill/language banks | Works | gate_content strict stage: all 22 zero errors/warnings; later feed reachability blocked. | — |
| Gates | License, provenance, model-doc and culture checks | Works | Separate commands after core abort: licenses/provenance zero issues, models_doc --check pass, culture 11 languages zero errors. | — |
| Gates | Overlap against restricted/non-verbatim source material | Not testable here | MOKUHYO_NO_SOURCES=1; explicit skip retained. No distribution-limited source read or copied. | — |
| Gates | Core/data/exam standard verification | Broken | Reproducible SpeakingLabelTest line-wrap assertion blocks all three gates, despite intact product label. | Q7 |
| CLI | `--smoke-opi` clear failure handling | Works | Real CLI exit 1: `--model <path to the Tier A gguf> is required`. Successful real-model path untested. | — |
| CLI | `--smoke-lang` clear failure handling | Works | Real CLI exit 1: `--whisper <ggml-*.bin> is required`. Successful real-ASR path untested. | — |
| CLI | `--roundtrip` clear failure handling | Works | Real CLI exit 1: `--roundtrip <jobs.jsonl> is required` (prefix says smoke-lang); no voice round trip claimed. | — |
| CLI | `--bench-interview` honest dry-run behavior | Works | Real CLI exit 0; explicitly “Real hardware and models: NO”, missing-stage list, writes JSON/MD. No p50/p95 claim. | — |
| CLI | `--prompt-audio` clear failure handling | Works | Real CLI exit 1: `--language`; no prompts generated or content altered. | — |
| CLI | `--listening-kit` no-voice behavior | Works | Real CLI es,ja,ar exit 0, explicitly “nothing can speak” per language and zero files; no audio-quality pass. | — |
| CLI | `--screenshots` | Works | Real CLI writes headless PNGs; monitored run visits all nine destinations offline. | — |
| CLI | `--smoke --no-network` source build (additional check) | Works | Exact requested Gradle run passes SQLite, CPU native loading, 11 languages, 10-model manifest and nonblank render. Not packaged smoke. | — |

## Findings, in priority order

### Q1 — P0 — Fresh review items lose their due schedule when imported

**Evidence:** `shared/src/jvmMain/kotlin/app/mokuhyo/backup/Backup.kt:48,176,276`. `card` is correctly treated as derived state, but the rebuild set comes only from incoming `review` rows. The [probe](qc/2026-10-21-features/probes/FeatureQcTest.kt) `ungradedReviewItemLosesItsScheduleOnImport` uses real temporary databases and a real bundle.

**Reproduce:** create learner A; add a Spanish word to Review without grading it (due=1). Export a plain bundle. Import into empty learner B. Read total, card and due count. **Expected:** one fresh card due now. **Actual:** total=1, card=null, due=0; the queued learning item disappears from normal Review. The same merge code follows decryption. This is loss of usable SRS state under rule 5, even though the review-item row remains.

**Recommended fix:** rebuild cards for every mapped imported review item, including those with zero review facts; preserve local history, id remapping, tombstones and idempotence. Do not export/overwrite the derived card cache as authoritative history.

**Acceptance:** one regression exports an ungraded item, imports into a new learner, asserts due=1/card present, imports again without duplicates, then repeats with encrypted export. It fails on current main. Keep graded/undo/merge tests passing.

### Q2 — P0 — Self-rating overwrites a conversation and cannot merge into an earlier import

**Evidence:** `shared/src/commonMain/kotlin/app/mokuhyo/history/ConversationRepository.kt:61,66`; `shared/src/commonMain/sqldelight/app/mokuhyo/db/history.sq:62`; `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt:793`; `Backup.kt:258`. Probe `selfRatingReplacesFactAndDoesNotMergeIntoEarlierImport` follows the repository calls made by the no-model result screen.

**Reproduce:** save an unrated completed conversation on A; export/import into B. On A assign self-rating 2 using the same conversation ID; export/import again into B. **Expected:** original conversation remains immutable and a distinct rating fact reaches B once. **Actual:** A still has only one conversation row (replaced in place), A displays rating 2, B remains unrated because import ignores the existing ID. This is an active UI call path, not merely an unused upsert query.

**Recommended fix:** represent rating/finalization changes as immutable revisions or separate append-only facts with deterministic display/merge semantics. Preserve old bundle compatibility and one visible conversation; do not “fix” import by overwriting B's row.

**Acceptance:** one regression covers export before and after self-rating, repeated imports in both directions, original transcript bytes unchanged, rating visible once on both learners, and no resurrected tombstones. It must fail on the reviewed implementation.

### Q3 — P0 — History displays scores without the required disclaimer

**Evidence:** `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/HistoryScreen.kt:43,61,101,122`; [screenshot](qc/2026-10-21-features/screenshots/history-missing-disclaimer.png); probe `historyShowsScoreWithoutRequiredDisclaimer`.

**Reproduce:** save a synthetic rated interview; open History, then Open. **Expected:** every score-bearing list/detail includes the rule-7 unofficial-practice/not-official/not-affiliated disclaimer. **Actual:** ILR 2 and its SpeakingTrend qualification appear, but no unofficial disclaimer appears in rendered semantics. Reading attempt detail also renders scores without calling Disclaimer. F4's provisional/typed/guided qualifiers are intact and are not being re-reported.

**Recommended fix:** place the shared Disclaimer visibly in History's score-bearing surface, including expanded attempt/conversation details. Retain existing SpeakingTrend text.

**Acceptance:** one parameterized headless rendering regression seeds scored attempt and conversation cases, opens list/details, asserts the complete required disclaimer remains visible at minimum size, and preserves the provisional/guided qualifiers. Current main fails.

### Q4 — P1 — Changing language leaves the previous Lexicon drill on screen

**Evidence:** `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/lexicon/LexiconScreen.kt:67,69,174`; [screenshot](qc/2026-10-21-features/screenshots/lexicon-language-stale.png); probe `lexiconLanguageSwitchKeepsPreviousLanguagesDrills`.

**Reproduce:** choose Spanish → Lexicon → Drills. Keep that view mounted and switch the app language to Arabic. **Expected:** Arabic drill, answer choices and counters use the Arabic track. **Actual:** heading changes to Arabic, but the Spanish `mando y control` drill remains. `remember(kind)` captures the previous track; current module/term lookup can now disagree with the drill.

**Recommended fix:** key the drill selection and progress state by track/language as well as kind, and invalidate on imported track changes where needed. Do not reset unrelated saved learner history.

**Acceptance:** one UI regression switches es→ar→ja in the same drill view, asserts displayed prompt/choices belong to each selected pack, and grading/queue insertion is attributed to that language. It fails before the fix.

### Q5 — P2 — Linux install failure suppresses the output needed to diagnose it

**Evidence:** `tools/gates/fresh_install_smoke.sh:45`; remote Linux CI excerpt; [release-probes.py](qc/2026-10-21-features/release-probes.py) intercepts apt-get safely and reproduces missing stdout while retaining exit 100. The real package failure remains unreproduced for the reasons above.

**Reproduce diagnostic defect:** run the committed probe: a dummy checksum-valid filename and stub apt command emit a detailed stdout error plus generic stderr and exit 100. **Expected:** both diagnostic streams survive and the gate fails. **Actual:** only generic stderr survives. This probe never installs a package.

**Recommended fix:** capture/tee both install streams, retain the real command status under pipefail, and print/preserve useful dpkg diagnostics on failure. Then rerun the unchanged release `.deb` on clean Ubuntu 22.04; open a separate cause-specific packaging fix only after its error is known.

**Acceptance:** one shell regression uses the stub failure and asserts stdout/stderr and exit 100 survive. A clean Ubuntu rerun must either reach packaged `smoke: OK` or leave a specific reproducible cause and logs. Passing the logging regression alone does not close the installer failure.

### Q6 — P2 — The published release has no promised SBOM

**Evidence:** [release-assets.json](qc/2026-10-21-features/release-assets.json) lists nine assets and no CycloneDX file; `.github/workflows/release.yml:101,107` packages/collects without explicitly generating it; `desktopApp/build.gradle.kts:98` defines the separate task; `tools/release/collect.py:42` silently skips missing SBOM.

**Reproduce:** inspect v0.4.0 release assets. Separately run release-probes.py: supply a synthetic installer to the real collector without `desktopApp/build/reports/cyclonedx-direct/bom.json`. **Expected:** mandatory SBOM generated/shipped, or collection refuses incomplete release. **Actual:** collection succeeds (0) and the published assets omit it.

**Recommended fix:** wire SBOM generation into release assembly and make missing/invalid SBOM a collection failure; ensure naming/checksums and upload cover it. Do not publish or amend the release as part of this review.

**Acceptance:** one collector regression fails without SBOM and passes only with a valid expected file; a clean release dry run includes the runtime CycloneDX artifact plus its checksum. Current main accepts the negative fixture.

### Q7 — P2 — PDF line wrapping breaks the standard core/data/exam gates

**Evidence:** `shared/src/jvmTest/kotlin/app/mokuhyo/history/SpeakingLabelTest.kt:64,106`; [test summary](qc/2026-10-21-features/tests-summary.json). The extractor yields `guided interview — scripted\nquestions)` and the assertion searches only the first line.

**Reproduce:** with report fonts staged, run `./gradlew :shared:jvmTest --tests '*SpeakingLabelTest' --rerun`. **Expected:** complete correct guided qualifier passes regardless of layout wrapping. **Actual:** one assertion fails; the generated PDF contains all required words. Core, data and exam gates fail on that same assertion. This does not reopen fixed F4.

**Recommended fix:** assert against a bounded extracted conversation entry with normalized whitespace, while still failing if any qualification is omitted. Preserve meaningful label coverage rather than removing the assertion or changing product layout to fit it.

**Acceptance:** one regression with a narrow/wrapped guided entry fails on current extraction logic, passes after normalization, and a missing `scripted questions` qualifier still fails. Rerun the three blocked gates.

### Q8 — P2 — Privacy and security text omit opt-in network behavior

**Evidence:** `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/SettingsScreen.kt:224,367`; `shared/src/commonMain/kotlin/app/mokuhyo/ai/LanUrl.kt:13`; `OllamaDetector.kt:29`; `OpenAICompatibleModel.kt:63`; `docs/SECURITY_PROFILE.md` Network behaviour/Data on the computer. [Screenshot](qc/2026-10-21-features/screenshots/privacy-network-copy.png); probe `privacyCopyOmitsLanInferenceAndUrlImports`.

**Reproduce:** open Privacy & updates with network enabled. Compare its “goes online only” list with Content's URL import and AI's explicit home-network Ollama option. **Expected:** list all user-triggered connections and say prompts/answers can go to the selected LAN model server. **Actual:** privacy lists model download, update checks and local detection only; security profile says detection is localhost-only and data leaves only through exports. These are documentation mismatches with an explicitly supported opt-in feature; no unsolicited network transmission was observed.

**Recommended fix:** align both explanations with the implemented opt-in LAN model requests, Lexicon URLs, optional update/manual checks and browser links; state what leaves the device and how offline mode disables requests. Avoid suggesting that LAN inference stays on this computer.

**Acceptance:** one parameterized privacy-content regression renders the enabled-network explanation and verifies every actual network capability, especially LAN prompt/answer transmission and Lexicon URL download, is represented. Check source profile consistency in the same focused commit. Current copy fails.

### Q9 — P2 — Listening's advertised Space play/pause shortcut does nothing

**Evidence:** `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/SkillScreen.kt:344,424,506`; probe `listeningSpaceShortcutIsAdvertisedButUnhandled`; [screenshot](qc/2026-10-21-features/screenshots/listening-keyboard.png).

**Reproduce:** enter Listening practice with audio prepared; leave initial session focus in place; press Space. In the headless diagnostic, the same scene handles key 1, ignores Space, and clicking Play reaches the actual output path (which reports this host's missing device). **Expected:** advertised Space shortcut controls playback using the same accounting/error path as the button. **Actual:** no key handler processes Space. Synthetic WAV isolates key dispatch; it is not proof of real speech.

**Recommended fix:** share a playback command between button and session shortcut, with focus scoping so text entry does not steal spaces; decide pause/resume versus stop/replay wording consistently and preserve test play allowances.

**Acceptance:** one UI regression sends real Compose key events with a controllable output fixture, verifies Space starts/stops or pauses as advertised, no duplicate play is counted, failed output refunds correctly, and text-entry spaces remain text. Current main fails at the first playback command.

### Q10 — P2 — Reading/Listening's AI badge does not disclose that content is unreviewed

**Evidence:** `desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/SkillScreen.kt:360`; [all-language Reading contact sheet](qc/2026-10-21-features/screenshots/all-reading-contact.png). The shared session renderer displays only `AI-generated` when the item/passage is unverified AI content. Lexicon already uses an explicit unreviewed qualifier.

**Reproduce:** open Reading practice in any of the eleven sampled languages at ILR 0+ and inspect the badge. **Expected:** the checklist's explicit unreviewed label remains until human verification. **Actual:** AI origin is disclosed, but review status is not. This is a P2 missing qualification under the checklist, not a claim that provenance or all rule-7 badging is absent. Listening uses the same badge branch; its rendered source was reviewed, not real audio tested.

**Recommended fix:** make the existing unverified-content badge explicitly say unreviewed, consistently across Reading/Listening, without changing pack metadata or human-verification status.

**Acceptance:** one parameterized renderer regression verifies unverified AI item/passage cases show both origin and unreviewed status and verified cases do not keep the unreviewed badge. The unverified assertion fails on current main. Preserve the separate “Generated on this computer” local-bank label.

## Earlier findings re-check

These are supporting regression results, not real-device certification. No fixed item was reproduced and re-reported as that old finding.

| Earlier ID | Current evidence | Result/limit |
| --- | --- | --- |
| F1 | PassageAudioLoadTest 7/7, including broken/cancelled/cache cases | Recovery control-flow fix retained. No real clips here. |
| F2 | QuestionDeliveryTest 6/6 | Cancellation does not grant unheard-test permission in fixtures. |
| F3 | PlaybackCancelTest 5/5, PlaybackOpenTest 3/3 | Ownership/cancel accounting retained; physical driver disconnect pending. |
| F4 | SpeakingTrendTest 6/6, HistoryLabelRenderTest, PDF extraction | Qualification present. Q7 is a wrapping-sensitive test, Q3 a different missing disclaimer. |
| C1 | InterviewCaptureTest 3/3, TopicCaptureTest 1/1 | Delayed transcription lifecycle regression passes. |
| C2 | InterviewCancelTest 2/2 | Retry/End generation-cancel controls retained. |
| C3 | InterviewCheckpointTest 2/2, InterviewStorageFailureTest, bundle snapshot case | Immutable checkpoints survive failure/import fixtures. Q2 concerns later completed-record self-rating. |
| C4 | SpeechPriorityTest 3/3, CorrectionsModeTest 7/7 | Scheduling/ordering regressions pass; no real latency result. |
| C5 | VadStartupTest 2/2 | Immediate synthetic speech recognized; physical mic pending. |
| C6 | CaptureResampleTest, InterviewDeviceTest 2/2, capture tests | Off-thread/resampling/device fixtures pass; physical responsiveness pending. |
| C7 | PassageAudioLoadTest 7/7 with explicit fixture pack root | Old staged-resource shadowing failure did not recur. |

## Owner checklist — not testable here

Use a fresh synthetic learner directory on each Mac/Windows laptop; retain existing real data separately. Follow [docs/BENCHMARK.md](BENCHMARK.md) for devices, profiles, commands and measurement targets; this report does not replace that procedure or fill its empty result table with dry-run numbers.

- [ ] Verify installer and portable checksums, install on a fresh account, run packaged `--smoke --require-packs`; test Gatekeeper/SmartScreen handling, first-run download/cancel/resume, audio check and model-skip flow. Test native window 1024×700 and actual 150%/200% scaling.
- [ ] Install approved Tier A, Whisper small and bundled voices. Complete N2 timing/recommendation, CPU preference, thread override, deadline, model-test cancellation, checksum side-load and denied/damaged download paths. If using Ollama, check allowed models and disabled PRC/Gemma/Llama reasons on a deliberate local/LAN server.
- [ ] For **all eleven languages**, hear a recorded Listening passage, synthesized fallback, interviewer prompt and exemplar. Check accurate language, casting, credits and source labels. At minimum complete ja/ar/es practice and timed Listening tests with real output; check replay limits, refund on unplug, Try again, Leave out, provisional score, transcript reveal, dictation, all speeds, degraded practice/test refusal and numbers-under-stress.
- [ ] Complete practice/test OPI phases with the real mic, Whisper and model; check readiness and question-delivery gate. Cancel/retry/end during warm-up, generation, ASR, TTS, feedback and final grading. Speak from frame one, edit/retake transcript, switch/disconnect devices and verify UI remains responsive. Crash/restart mid-answer and before rating, then confirm all accepted turns/recordings survive.
- [ ] Force the turn deadline and verify audible guided fallback plus honest labels in results/History/PDF. Complete typed/no-model mode, self-rating, topic corrections modes, AAB, scenarios/personas (Arabic variants), cultural notes, all interpretation modes, exercise-week memory, daily stand-to and exemplar playback. Recheck Q2 after its fix before trusting self-ratings.
- [ ] Generate local Reading/Listening practice, verify local/unreviewed labels and no shipped-estimate contribution. Test genuine dictionaries with mouse and keyboard, furigana/pinyin, lookup→Review and every acquisition path.
- [ ] Run real-device latency/endurance and export timing diagnostics using BENCHMARK.md. Confirm JSON/Markdown contain no transcript/audio. Run History recording playback, PDF/save-dialog cancel, encrypted backup cancel/errors, actual older-version bundle migration, signed URL import/tamper refusal and optional update success/failure.
- [ ] Use NVDA/VoiceOver to check names and focus order, number keys, Enter, Space after Q9, text-entry focus, small-window scroll, Arabic/Persian direction and CJK glyphs across populated screens. A headless semantic tree is insufficient for this step.
- [ ] Monitor the packaged app offline while navigating and performing real speech/backup/report activities, then attempt each opt-in network action under `--no-network`. Run remaining full language/speaking gates and a lawful overlap check when permitted non-limited source inputs are available. Never read distribution-limited sources.

## Scope and follow-up

No app fix is included. [BRIEF_FEATURE_FIXES_2026-10-21.md](../BRIEF_FEATURE_FIXES_2026-10-21.md) is the prioritized coding handoff. The evidence distinguishes local execution, remote CI, source-only checks and unavailable hardware. Human review of all multilingual content, calibration against instructors, external service reachability and physical speech quality remain unestablished.
