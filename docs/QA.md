# Manual QA (per OS)

The automated gates (`tools/gates/gate_*.sh`) run in CI on every push. This list is what a human checks on a real
machine before a release (BRIEF §10). Record results with date, OS version and hardware.

## Install and first run
- [ ] Installer runs: Windows MSI (SmartScreen "More info → Run anyway"), macOS DMG (Gatekeeper "Open anyway" or
      `xattr -d com.apple.quarantine`), Linux DEB.
- [ ] First run: welcome → languages → hardware scan shows the right RAM/GPU/VRAM → recommended tier is sensible →
      audio check → Home.
- [ ] "Reading/Listening only for now" skips the model download and the app still works.

## AI runtime
- [ ] GPU variant selected (Settings → AI shows Metal/Vulkan (GPU)); toggling "Use the CPU" + restart shows CPU.
- [ ] A machine without a Vulkan driver falls back to CPU and says so.
- [ ] Model download: progress pill in the title bar; pause, quit, relaunch, resume continues from the partial file;
      a corrupted partial is discarded on checksum mismatch.
- [ ] Tier A interview turn on an 8 GB laptop within ~25 s; Tier B within ~10 s on a 16 GB machine.
- [ ] "Use my Ollama" lists a local Ollama's models; PRC-origin and Gemma/Llama models are greyed out with a reason.

## Audio
- [ ] Microphone picker lists devices; level meter moves; record-and-play-back works.
- [ ] macOS asks for microphone permission once; denying it shows the explanation.
- [ ] Test voice plays in each installed language (Korean may fall back; see DECISIONS).

## Offline
- [ ] With networking off, every screen works after the model download (Settings → Privacy explains what goes online).

## Languages and display
- [ ] Arabic and Persian content renders right-to-left; the app chrome stays left-to-right.
- [ ] CJK text never shows boxes on Windows (bundled Noto fonts).
- [ ] Window at 1024×700 has no clipped controls; OS text scaling 150% works; dark mode follows the OS.

## Data
- [ ] PDF report fonts render for all 11 languages (open the PDF in the OS viewer).
- [ ] `.mokuhyo` bundle exported on one OS imports on another and merges without duplicates.

## Reading and listening
- [ ] Practice at each level shows feedback and explanations; a 30-minute test runs to the end and shows an estimate
      with the disclaimer; quitting mid-test and relaunching offers to resume.
- [ ] Tap-to-define works with the mouse and with the keyboard (Tab to the passage, ←/→, Enter).
- [ ] A listening test plays each clip once and shows the questions after the first play; Japanese, Chinese, Korean,
      Arabic and Indonesian listening use the OS voice (none on Linux).
- [ ] "Generate more" produces a passage labelled "Generated on this computer" that is excluded from estimates.

## Speaking
- [ ] An interview test runs 19 turns with the microphone and the model's voice; the rating shows evidence quotes.
- [ ] Interview quality on Tier B: does the interviewer move between topics, or fixate on one the learner mentioned?
- [ ] Recordings play back from History.

## Updates
- [ ] The update check is off by default; "Check now" reports the result (private repository: "isn't public yet").
