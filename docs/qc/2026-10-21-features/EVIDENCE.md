# Evidence — feature QC, 2026-10-21

Read the [report](../../QC_REPORT_2026-10-21_FEATURES.md) for verdict, limitations and findings. All data here is synthetic. There are **117 feature rows: 37 Works, 11 Partial, 6 Broken, 38 Not testable here, 25 Code-reviewed only**. There are 10 distinct findings (3 P0, 1 P1, 6 P2); rows can share a finding.

## Revisions and isolation

- Pulled `main`: `262004b8a8aed06d501c55fcede521782aff3e8f`.
- Detached execution target `v0.4.0`: `8bbbe8f58f2d8ccf26f074ca9bd7aff57bc1531d`.
- `git diff v0.4.0..262004b8 --name-only` returned only `BRIEF_FEATURE_QC_2026-10-21.md`.
- Review branch: `codex/feature-qc-2026-10-21`. Main working tree only receives the report, this evidence tree and the fixes brief.
- Build/gate execution used `/workspace/mokuhyo-v040-qc`; native/Piper builds used a separate source export under `/tmp/mokuhyo-feature-qc/native-attempt`, because the build helpers update their lock records. No such updates are included in the review. Pack/font build output was isolated from the delivery checkout. Existing ignored staged resources/font cache were reused in the detached checkout.
- Read CLAUDE.md, BRIEF §2/§9, BRIEF_PHASE8, QA, RELEASE_NOTES, D-042–045 and all three previous QC reports. No distribution-limited source document was read, processed or committed. `MOKUHYO_NO_SOURCES=1` was set for gates.

Host: Debian 13 x86_64, no interactive desktop or physical audio devices, 4 exposed cores, 16 GB reported RAM. Managed cloud runtime setup supplied JDK 21.0.12.1, Gradle cache, Python/uv cache, CMake and trust/proxy settings. Commands requiring Gradle's loopback coordination used the environment's network permission; model and release hosts remained blocked by its proxy. Environment credentials were not dumped or included in evidence.

## Linux and release evidence

The Linux investigation preceded feature probes. Read through the GitHub connector:

```text
GET /repos/budzillaorigin/mokuhyo/actions/runs/39355829686/jobs
GET job logs for 118417993762
GET /repos/budzillaorigin/mokuhyo/releases/tags/v0.4.0
GET /repos/budzillaorigin/mokuhyo/actions/runs/39355829686/artifacts
```

[linux-ci-excerpt.log](linux-ci-excerpt.log) preserves runner version, checksum success, generic dpkg failure and exit 100. The log does not identify a dependency. [release-jobs.json](release-jobs.json) records Windows, macOS arm64 and macOS x64 fresh-install success and Linux failure. Remote packaged-smoke build steps succeeded. No local installer execution is claimed.

Requested attempt:

```bash
gh release download v0.4.0 --repo budzillaorigin/mokuhyo \
  --pattern '*linux-x64-portable.tar.gz' --pattern '*linux-x64.deb' \
  --pattern SHA256SUMS --dir /tmp/mokuhyo-feature-qc/release
```

Result: `Get "https://api.github.com/repos/budzillaorigin/mokuhyo/releases/tags/v0.4.0": Forbidden` ([log](release-download.log)). Workflow artifact fallback for Linux archive `12235946855` failed with `InvalidInputException: Artifact exceeds max size of 536870912 bytes: 3381711500 bytes`. This is a tool size limit, not a corrupt archive. [release-assets.json](release-assets.json) holds the nine published asset names, sizes and GitHub-reported digests; it contains no SBOM. Digests were not independently verified against file bytes or SHA256SUMS.

```bash
python docs/qc/2026-10-21-features/release-probes.py
```

[release-probes.py](release-probes.py) creates only temporary dummy files and stub executables. It intercepts apt-get before any package operation, proving loss of diagnostic stdout/retention of exit 100. It also invokes the real Python release collector against a synthetic installer and absent SBOM: exit 0, no SBOM. [Result](logs/release-probes.log). These reproduce Q5's logging defect and Q6's missing-artifact guard, not the real dpkg cause.

## Native builds and model attempts

After exporting the tag's `native/` and `voices/` into the disposable directory:

```bash
source /workspace/.mokuhyo-cloud/env.sh
cd /tmp/mokuhyo-feature-qc/native-attempt
bash native/build.sh cpu
bash voices/build.sh
```

Both succeeded. [CPU build excerpt](logs/build-native-excerpt.log), [Piper build excerpt](logs/build-voices-excerpt.log). CPU build took approximately 110 seconds, Piper approximately 72 seconds; these are build times, not runtime latency.

```text
native/build/linux-x86_64/cpu/libmokuhyo_native.so
  bytes: 6206448
  sha256: 9a39a7237140abbc60640402e15397ba9521883550e03c61fdefbf59f1856ae3
voices/build/linux-x86_64/piper
  build completed; 370 staged files; tree identifier 8f18a82de43ba85a
```

Only approved model URLs from `content/models/manifest.json` were attempted: `phi-4-mini-instruct-q4km` and `whisper-small`, with `curl --fail --location --range 0-63` to test access before a multi-GB download. Both returned HTTP 403, curl exit 22. The model manifest has no Piper weights; its approved voice catalogue is `voices/manifest.json`. A HEAD availability check of its pinned `es_ES-davefx-medium.onnx` URL also returned HTTP 403; no alternative or unapproved weights were fetched. Exact URLs/errors: [model-downloads.log](logs/model-downloads.log).

The source CLI used `MOKUHYO_NATIVE_DIR=/tmp/mokuhyo-feature-qc/native-attempt/native/build/linux-x86_64` and loaded CPU successfully. The existing shared NativeRuntimeTest uses its own default discovery and reported early skips, as did real Piper tests without local weights. Their passing JUnit case counts are not native inference coverage. No need to hide missing native support with `--allow-no-native` in the final source smoke, because that smoke really loaded the library.

## Standard gates and suites

Executed on the detached tag after sourcing the runtime environment:

```bash
export MOKUHYO_NO_SOURCES=1
export SKIP_EVAL=1 SKIP_FULL=1
export MOKUHYO_REPORT_OUT=/tmp/mokuhyo-feature-qc/all-scripts.pdf
tools/gates/gate_core.sh
tools/gates/gate_content.sh
tools/gates/gate_data.sh
tools/gates/gate_exam.sh
tools/gates/gate_lang.sh
tools/gates/gate_speaking.sh
tools/gates/gate_terms.sh
```

Each gate was run separately and its status captured; an earlier failure did not prevent the other gates from being attempted. `SKIP_EVAL/SKIP_FULL` deliberately limit real-model/full speaking work; **gate_speaking is a reduced pass only**.

| Command | Exit | Result and retained evidence |
| --- | ---: | --- |
| gate_core | 1 | PDF label line-wrap assertion Q7; downstream Python stages run separately. [Excerpt](logs/gate-core-excerpt.log) |
| gate_content | 1 | Strict 22 banks: zero errors/warnings. Feed check: 22 links, 15 errors from proxy denial; later stages not reached. [Excerpt](logs/gate-content-excerpt.log) |
| gate_data | 1 | Same SpeakingLabelTest failure; bundle/crypto fixture coverage otherwise passes. [Excerpt](logs/gate-data-excerpt.log) |
| gate_exam | 1 | Same included history/PDF assertion after bank work. [Excerpt](logs/gate-exam-excerpt.log) |
| gate_lang | 1 | Japanese tokenizer and JMdict build succeeded in the source checkout; the Spanish Kaikki dictionary download then failed: `URLError: <urlopen error Tunnel connection failed: 403 Forbidden>`. Complete pack staging/language tests not reached. [Excerpt](logs/gate-lang-excerpt.log) |
| gate_speaking | 0 | Packs, scripted sessions/mock models/golden fixtures; full/evaluation skipped by flags. [Excerpt](logs/gate-speaking-excerpt.log) |
| gate_terms | 0 | Term checks pass, overlap explicitly skipped without sources. [Excerpt](logs/gate-terms-excerpt.log) |
| Gradle source smoke | 0 | SQLite, native CPU, 11 languages, 10-model manifest, nonblank frame, smoke OK. [Excerpt](logs/gradle-smoke-excerpt.log) |

Exact requested source smoke:

```bash
MOKUHYO_NATIVE_DIR=/tmp/mokuhyo-feature-qc/native-attempt/native/build/linux-x86_64 \
  ./gradlew :desktopApp:run --args='--smoke --no-network' --offline --console=plain
```

To preserve full test evidence after filtered gates, ran:

```bash
MOKUHYO_REQUIRE_PACKS=1 MOKUHYO_REPORT_OUT=/tmp/mokuhyo-feature-qc/all-scripts.pdf \
  ./gradlew :shared:jvmTest :desktopApp:test --rerun-tasks --no-build-cache --continue --offline --console=plain
```

The final uncached run executed all 18 Gradle tasks (48 seconds), failed shared tests, and `--continue` completed desktop tests. [tests-summary.json](tests-summary.json) was parsed from this run's JUnit XML, without diagnostic test injection: shared 435 cases / **2 failures** / 0 errors; desktop 56 / 0 / 0. The failures are Q7 and `required pack missing: es dictionary: content/packs/es/dictionary.sqlite not built`, matching the blocked Kaikki download. Japanese dictionary/lattice contract checks now pass after gate_lang built them. JUnit reports zero skips, but stdout has **18 conditional-skip notices**: Piper 7, NativeRuntime 9, real GGUF 1, real OS voice 1. The notices describe actual missing assets and are preserved verbatim. `MOKUHYO_REQUIRE_PACKS=1` correctly exposes the missing Spanish dictionary; it does not require real native models/voices.

An earlier attempt used task-local `--rerun` after the desktop task, allowing shared results from cache and stale XML to remain. The evidence audit caught this; all final counts above come from the explicit `--rerun-tasks --no-build-cache` run. The asset failure is an environment limitation, not an additional product finding.

Separately ran in `tools/` because core stopped before these stages:

```bash
uv run --locked ruff check .
uv run --locked python run_tests.py
uv run --locked python gates/check_licenses.py
uv run --locked python models/models_doc.py --check
uv run --locked python gates/check_provenance.py
uv run --locked python gates/check_culture.py
```

All passed: ruff clean, 13/13 Python test scripts, licenses/provenance zero problems, model docs current, culture 11 languages zero errors. [Log](logs/python-checks.log). Content validation is structural/licensing evidence, not native-language editorial review. Feed HTTP 403 responses and proxy refusals do not establish whether the sites work outside this environment.

## Review probes

[FeatureQcTest.kt](probes/FeatureQcTest.kt) is opt-in diagnostic source, outside normal application/test source trees. It creates fresh temporary learners, uses actual AppGraph/SQLite/bundle services, and sends Compose semantic actions/key events. No real microphone data is used. **Defect tests assert the current defect**, so a green diagnostic run is not release acceptance.

[probes.init.gradle](probes.init.gradle) attaches the diagnostic directory only to that invocation. The final complete run: **10 tests, 0 failures, 43 seconds**. [JUnit summary](probe-results.json), [observations](logs/probe-observations.log), [build excerpt](logs/final-probes-excerpt.log). During diagnostic development, an AWT-to-Compose event conversion error was corrected in this evidence-only helper; it was not reported as an application defect.

From a prepared checkout with the same resources/toolchain:

```bash
./gradlew -I /path/to/evidence/probes.init.gradle \
  -Dqc.evidence=/path/to/evidence -Dqc.out=/tmp/mokuhyo-feature-qc/probes \
  :desktopApp:test --tests '*FeatureQcTest' \
  --no-configuration-cache --offline --console=plain
```

| Probe | What actually ran |
| --- | --- |
| ungradedReviewItemLosesItsScheduleOnImport | Add ungraded word → actual bundle export/import → total=1, card=null, due=0 (Q1). |
| selfRatingReplacesFactAndDoesNotMergeIntoEarlierImport | Save/export/import unrated conversation → save same ID with self-rating → second import loses rating (Q2). |
| historyShowsScoreWithoutRequiredDisclaimer | Synthetic rated conversation → actual History Open → score semantics without unofficial disclaimer (Q3). |
| lexiconLanguageSwitchKeepsPreviousLanguagesDrills | Spanish drill → app language Arabic in same composition → old prompt under new heading (Q4). |
| allScriptsAndNineDestinationsRenderOffline | Nine destinations, 11 language reading samples, density 1/1.5/2; screenshots. |
| readingPracticeAndTimedResumeAcrossThreeScripts | ja/ar/es actual UI answer/feedback/finish; timed answer → app close/reopen → Resume → cancel submit → submit; persisted provisional estimates and language isolation. |
| firstRunSkipAndReviewGradeUndo | First-run hardware scan/skip/Finish; real Review reveal/Good/empty/Undo; retained review tombstone. |
| realHttpEngineIsBlockedBeforeSocketConnection | Real Java HTTP engine + real loopback listener positive control; disabled request throws NetworkDisabledException, connection count remains one. |
| privacyCopyOmitsLanInferenceAndUrlImports | Default update false; real Privacy text omits LAN inference/Lexicon URLs (Q8). |
| listeningSpaceShortcutIsAdvertisedButUnhandled | Synthetic WAV prepares real Listening UI; key 1 works, Space unhandled, button reaches output error (Q9). No real-speech pass. |

## Offline monitor and CLI

Obtain the source-build runtime classpath without editing the app:

```bash
./gradlew -I /path/to/evidence/probes.init.gradle \
  -Dqc.evidence=/path/to/evidence -Dqc.classpath=/tmp/mokuhyo-feature-qc/classpath \
  :desktopApp:qcClasspath --no-configuration-cache --offline
```

Launch template used by all CLI probes (classpath read by the launcher, no credentials in arguments):

```bash
"$JAVA_HOME/bin/java" -Xss4m -Djava.awt.headless=true -Dmokuhyo.version=0.4.0 \
  -cp "$(cat /tmp/mokuhyo-feature-qc/classpath)" app.mokuhyo.desktop.MainKt <arguments>
```

Network trace wrapped this same Java launch with:

```text
strace -f -qq -e trace=connect,sendto,sendmsg -o /tmp/mokuhyo-feature-qc/network.trace
  <Java launch> --screenshots /tmp/mokuhyo-feature-qc/offline-shots
  --no-network --language es --data-dir /tmp/mokuhyo-feature-qc/offline-data
```

Exit 0, all nine destination PNGs written. [network.trace](network.trace) has six lines, **zero AF_INET/AF_INET6 connect/send operations**. Its local AF_UNIX name-service attempts and JVM-handled SIGSEGV probe signals are retained; the JVM completed successfully, not a crash. This passive initial-screen trace is complemented by the active real-HTTP blocked-socket probe, not presented as exhaustive coverage of unavailable model/device operations. [Summary](logs/offline-monitor-summary.log), [CLI completion](logs/offline-monitor.log).

| Arguments after MainKt | Exit | Evidence/limit |
| --- | ---: | --- |
| `--smoke --no-network` with fresh data dir | 0 | [smoke](logs/cli-smoke.log); native CPU loaded. |
| `--smoke-opi --no-network` | 1 | [requires Tier A model](logs/cli-smoke-opi.log). |
| `--smoke-lang --no-network` | 1 | [requires Whisper](logs/cli-smoke-lang.log). |
| `--roundtrip --no-network` | 1 | [requires jobs.jsonl](logs/cli-roundtrip.log); prefix cosmetic, no hang. |
| `--bench-interview --no-network --language es --turns 2 --out /tmp/mokuhyo-feature-qc/bench --data-dir /tmp/mokuhyo-feature-qc/bench-data` | 0 | [dry-run log](logs/cli-bench.log), [JSON](bench-interview.json), [Markdown](bench-interview.md). Missing LLM/ASR/voice and null playback explicitly disclosed. |
| `--prompt-audio --no-network` | 1 | [requires language](logs/cli-prompt-audio.log); did not write pack audio. |
| `--listening-kit --no-network --language es,ja,ar --prompts 1 --out /tmp/mokuhyo-feature-qc/listening-kit --data-dir /tmp/mokuhyo-feature-qc/kit-data` | 0 | [no voice, zero audio files](logs/cli-listening-kit.log). |
| `--screenshots /tmp/mokuhyo-feature-qc/cli-shots --no-network --language es --only home` with fresh data dir | 0 | [log](logs/cli-screenshots.log); full nine-screen invocation monitored separately. |

## Screenshots

These are actual headless app renders, not design mockups. Default images are 1024×700; practice/test/result/defect probes use larger headless frames for inspection. Density images preserve 1024×700 logical layout at 1536×1050 and 2048×1400 physical pixels. The existing short-rail test also checks a smaller logical viewport at 150%.

| Required destination | Image |
| --- | --- |
| Home | [Home](screenshots/shell-home.png) |
| Reading | [Reading](screenshots/shell-reading.png) |
| Listening | [Listening](screenshots/shell-listening.png) |
| Speaking | [Speaking](screenshots/shell-speaking.png) |
| Lexicon | [Lexicon](screenshots/shell-lexicon.png) |
| Review | [Review](screenshots/shell-review.png) |
| History | [History](screenshots/shell-history.png) |
| Report | [Report](screenshots/shell-report.png) |
| Settings | [Settings](screenshots/shell-settings.png) |

All 11 script samples: [ja](screenshots/reading-ja.png), [es](screenshots/reading-es.png), [fr](screenshots/reading-fr.png), [de](screenshots/reading-de.png), [pt-BR](screenshots/reading-pt-BR.png), [ru](screenshots/reading-ru.png), [zh-Hans](screenshots/reading-zh-Hans.png), [ko](screenshots/reading-ko.png), [ar](screenshots/reading-ar.png), [fa](screenshots/reading-fa.png), [id](screenshots/reading-id.png); [contact sheet](screenshots/all-reading-contact.png). No tofu seen in these samples; ar/fa content is RTL and controls stay LTR. This is not a claim about every possible character in every screen.

Results: [Japanese practice](screenshots/practice-result-ja.png), [Arabic practice](screenshots/practice-result-ar.png), [Spanish practice](screenshots/practice-result-es.png), [Japanese resumed test](screenshots/test-result-ja.png), [Arabic resumed test](screenshots/test-result-ar.png), [Spanish resumed test](screenshots/test-result-es.png). Scaling: [150%](screenshots/home-scale-1.5.png), [200%](screenshots/home-scale-2.0.png).

Findings: [History disclaimer](screenshots/history-missing-disclaimer.png), [stale Lexicon language](screenshots/lexicon-language-stale.png), [Privacy copy](screenshots/privacy-network-copy.png), [Listening keyboard/output error](screenshots/listening-keyboard.png).

## PDF and all-language inventory

The existing ReportFontTest wrote [all-scripts.pdf](pdf/all-scripts.pdf), 12 pages, using synthetic Test Learner and sample strings. Its PDFBox glyph inspection found no `.notdef` glyphs and no rasterized text pages. Then ran:

```bash
pdftotext -layout /tmp/mokuhyo-feature-qc/all-scripts.pdf /tmp/mokuhyo-feature-qc/all-scripts.txt
pdftoppm -png -scale-to 950 /tmp/mokuhyo-feature-qc/all-scripts.pdf /tmp/mokuhyo-feature-qc/pdf-pages/page
```

[Extracted text](pdf/all-scripts.txt), [all-page contact sheet](pdf/pdf-contact-sheet.png). All pages rendered; the contact sheet and full-size Arabic page were inspected. Reading-direction/extraction artifacts are not confused with missing glyphs. The separate guided PDF's wrapped qualifier is retained in the failing test's full assertion message in tests-summary.json. No PDF AAB export or native save dialog/cancel pass is claimed.

```bash
python docs/qc/2026-10-21-features/content-inventory.py
```

[content-inventory.py](content-inventory.py) reads only shipped pack JSON; [result](content-inventory.json) records all eleven languages' Reading/Listening counts, formats, terms, drills, scenarios, personas, role-plays, exemplar levels, official links and culture counts. Six personas per language except Arabic twelve; track scenarios twelve and OPI role-plays 24 per language; exemplars 90 at levels 1+/2/3. These are different content categories, not contradictory counts. Existing staged dev audio has 0 recorded clips; do not extrapolate that to the downloaded release.

## Source index

Report source references are repo-relative and line numbers refer to the reviewed commits. Useful entry points:

- [Shell](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/Shell.kt), [Home](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/HomeScreen.kt), [FirstRun](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/FirstRun.kt), [TierPicker](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/TierPicker.kt).
- [Reading/Listening SkillScreen](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/SkillScreen.kt), [PassageText](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/PassageText.kt), [PassageAudio](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/PassageAudio.kt).
- [Speaking](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt), [Lexicon](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/lexicon/LexiconScreen.kt), [Review](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/ReviewScreen.kt), [History](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/HistoryScreen.kt).
- [Report screen](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/ReportScreen.kt), [PDF renderer](../../../shared/src/jvmMain/kotlin/app/mokuhyo/report/ProgressReport.kt), [Settings](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/SettingsScreen.kt), [AudioCheck](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/AudioCheck.kt), [Timings](../../../desktopApp/src/main/kotlin/app/mokuhyo/desktop/Timings.kt).
- [Backup](../../../shared/src/jvmMain/kotlin/app/mokuhyo/backup/Backup.kt), [ConversationRepository](../../../shared/src/commonMain/kotlin/app/mokuhyo/history/ConversationRepository.kt), [history SQL](../../../shared/src/commonMain/sqldelight/app/mokuhyo/db/history.sq), [SpeakingLabelTest](../../../shared/src/jvmTest/kotlin/app/mokuhyo/history/SpeakingLabelTest.kt).
- [fresh-install script](../../../tools/gates/fresh_install_smoke.sh), [collector](../../../tools/release/collect.py), [release workflow](../../../.github/workflows/release.yml), [security profile](../../SECURITY_PROFILE.md).

No application change, model weight, pack output, native binary, learner database, private source document or credential is part of this evidence tree. The only PDF/transcript text is synthetic test content. Raw long build logs are summarized; short failure messages, test skip notices and screenshots are retained.
