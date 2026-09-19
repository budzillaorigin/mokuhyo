# Manual QA

Run this on a real iPhone (and optionally an Android phone) before a TestFlight or App Store build. CI covers the shared logic, the Android build, the iOS simulator tests and the iOS build. This list is only for what CI can't see: real audio, the camera, on-device models, notifications, widgets and feel.

Setup: build the packs (`uv run python packs/build_all.py`), fetch the iOS frameworks (`bash tools/models/fetch_ios_frameworks.sh`), then install. Do everything below once in **airplane mode** as well, except the items marked 🌐.

## First run
- [ ] Onboarding appears. Pick a goal and a daily budget, and do the kanji check. "Start learning" lands on Today, and the kanji path starts at the suggested level.
- [ ] "Import my progress first" opens Import.
- [ ] Me → Licenses renders `LICENSES.md`.

## Dictionary (Phase 1)
- [ ] Each of these finds 食べる: 食べさせられなかった, たべる, taberu, "eat".
- [ ] A pasted sentence splits into words.
- [ ] The entry page shows furigana, pitch accent, example sentences, the conjugation table and the kanji with animated stroke order.
- [ ] Lookups feel instant. BRIEF §11 budget: under 5 ms per lookup on an iPhone 12 in a Release build (CI checks 10 ms on a Debug simulator build).
- [ ] Draw to search: drawing 語 finds it in the top 5.
- [ ] Scan text: camera OCR on printed Japanese, then tap a word to look it up.

## SRS and path (Phases 2–3)
- [ ] Lessons → quiz → reviews. Typed answers accept kana and romaji IME input, and wrong answers show the correct one.
- [ ] Undo works. Stages move Apprentice → Guru.
- [ ] Grammar lessons, then cloze and sentence-building reviews. A ghost card appears after a miss.
- [ ] Today's plan fits the daily budget. The review reminder notification arrives. The widget shows due counts.
- [ ] Imports: a WaniKani token 🌐, an Anki `.apkg` (NihongoShark), a Bunpro CSV and an imiwa export. Progress carries over.

## Reading and writing (Phase 4)
- [ ] Reader: paste text, import a URL 🌐, add an RSS feed 🌐, open an Aozora text 🌐.
- [ ] Tap-a-word popup, "Add to reviews", and the sentence panel's Listen button.
- [ ] Writing practice: wrong direction or order is flagged, and a hint appears after 3 misses. Writing cards appear in reviews when turned on.

## Sync (Phase 5) 🌐
- [ ] Run the server (`docker compose -f server/docker-compose.yml up -d`) and sign up from the phone.
- [ ] Review on two devices while offline, then sync both. The review counts and next-due dates match.
- [ ] Turn on end-to-end encryption on one device, then enter the passphrase on the other. Data arrives.

## AI, speaking and listening (Phase 6)
- [ ] Settings → AI & speech: download Qwen2.5-1.5B (about 1.1 GB) 🌐. Progress shows, pausing and resuming work, and it appears as installed after the check.
- [ ] Set the engine to On-device. A role-play reply arrives within about 10 s and carries the AI badge.
- [ ] With the model loaded, use the app for 10 minutes. No memory-warning crash. The model unloads when you leave the practice screens.
- [ ] "My server" 🌐: an Ollama URL → Test connection lists its models. Role-play works through it.
- [ ] Engine Off: role-play plays scripted turns, a banner explains why, and hints show sample answers.
- [ ] Microphone: permission prompt. The recognizer transcribes Japanese in airplane mode, both with the iOS on-device recognizer and with Whisper once whisper-base is downloaded.
- [ ] Pronunciation panel on はし (箸 vs 橋): saying each accent gives different ↑↓ verdicts. The score and notes are sensible.
- [ ] Listening dialogue: two different voices, gap-fill, line ordering, questions.
- [ ] Minimal pairs: the audio is clear, and accuracy is counted.
- [ ] Media player: open a video plus Japanese and English `.srt` files. Dual subtitles stay in sync. Tapping a cue opens the dictionary, and the A-B loop works.
- [ ] Pomodoro: the 25-minute timer runs, activities advance, and the break screen shows a summary.

## Exams (Phase 7)
- [ ] JLPT N5 full mock: the section timers match 20/40/30 minutes, and a section closes when its time runs out. Listening audio plays once only. The underline, ★ and table markup render correctly.
- [ ] Results: scaled scores, pass or not yet, and a by-type table. "Add missed items to SRS" adds items.
- [ ] Item-type drill (grammar_form) is untimed and audio can be replayed.
- [ ] DLPT Reading, 30 minutes: English questions on Japanese passages. The result shows an ILR estimate marked provisional.
- [ ] DLPT Listening, 30 minutes: voices play at a natural speed.
- [ ] OPI with the model: the interview runs by voice and the transcript stays hidden until the end. The rating has the AI badge and the disclaimer.
- [ ] OPI with the engine Off: scripted questions, then the self-rating checklist.
- [ ] Attempt history reopens a past attempt. "Explain with AI" is labeled.
- [ ] Import a question bank JSON. Bad files list their errors.

## Accessibility and polish (Phase 8)
- [ ] Largest Dynamic Type setting (including the Accessibility sizes): no clipped text on Today, reviews, the dictionary entry page or the exam runner. The review grade buttons stack vertically when they don't fit in one row. The pronunciation score and the radical and handwriting candidate strips grow with the text size.
- [ ] VoiceOver: main tabs, the review answer field and exam choices are announced. The chosen exam answer is announced as "selected", and the question grid says which questions are answered.
- [ ] VoiceOver reads Japanese with a Japanese voice: dictionary headwords and examples, review prompts, reader words, exam passages and JLPT questions. DLPT questions and answers, which are English, are read with the English voice.
- [ ] VoiceOver: icon-only buttons (search, furigana, read aloud, play line, media controls, send) have spoken names. The writing canvas accepts strokes with VoiceOver on (direct touch) and announces how many strokes are drawn.
- [ ] Reduce Motion on: stroke order is shown complete, without animating, and role-play chat jumps to the newest line without scrolling animation.
- [ ] Dark mode on every screen.
- [ ] Set the device language (or Settings → Apps → Tsumugi → Language) to Japanese: tabs, titles, buttons, section headers, settings, empty states, the exam/OPI disclaimer and onboarding are in Japanese. Learning content (Japanese examples, English glosses) doesn't change.

## Share extension (Phase 8)
- [ ] In Safari, Share → Tsumugi on a Japanese article. The sheet says "Saved for Tsumugi". Opening Tsumugi imports the page 🌐 and opens it in the reader.
- [ ] Select Japanese text in Notes, then Share → Tsumugi. Opening Tsumugi opens it in the reader. This works in airplane mode.
- [ ] Share a link while offline, then open Tsumugi. The link waits in the inbox and imports on a later launch once online. After three failed tries it is dropped.

## Phase 9: Stabilize (v2)
- [ ] Archive and upload from the Mac as in RELEASE.md §4. Validation passes and the icon shows on TestFlight.
- [ ] Settings → AI → own server `http://<lan-ip>:11434`. The local-network prompt appears, then Test connection lists the Ollama models. Also try a Tailscale `*.ts.net` name.
- [ ] Android: the same test over plain http.
- [ ] Import WaniKani and NihongoShark (.apkg), then do a real review session. Undo one review. Sync to a second device and check the undo is there too.
- [ ] Lapse a few kanji in a level you've passed. The level doesn't drop and the next level's lessons stay available.
- [ ] Double-tap Submit quickly in reviews. Only one review is recorded.
- [ ] With the silent switch on, dialogue and exam audio still play. A phone call pauses playback, and unplugging headphones pauses it.
- [ ] Start a 1 GB model download and background the app. It keeps going, and resumes after a relaunch.
- [ ] Take a JLPT mock, answer a few questions, force-quit and reopen. "Resume attempt" appears with the answers kept, and sections whose time ran out while away are closed.
- [ ] Draw vertical strokes on the writing canvas. The page doesn't scroll.
- [ ] Turn off Wi-Fi while an AI engine is set. The app falls back within seconds instead of hanging.
- [ ] The notification permission is asked only after the first finished review session.

## Phase 11: Immersion pipeline (sentence bank, lyrics, log, annotations)
- [ ] Load a video with an .srt. Look up a word from it in the dictionary: lines from your media come first, then Tatoeba. Tap a line: the clip plays and a frame shows.
- [ ] "Mine this line" as a sentence card and as a word card. In reviews the sentence shows the word highlighted, with the frame and the clip audio. When the audio cut fails, TTS reads the line.
- [ ] Immersion Kit is off in a fresh install and nothing is requested. Turn it on: lines appear under an "Immersion Kit" label. In airplane mode they fail quietly and the other results stay.
- [ ] Import a song you own with an enhanced .lrc: words highlight in time. Import plain lyrics, run "Align with Whisper" (progress, cancel), and check the lines follow the singing. Hide a word in cloze mode: it stays hidden until sung, and typing it in kana or romaji counts.
- [ ] Read for a few minutes, watch a video and add a manual entry. The Me heat-map and per-source minutes show them, and the Today immersion block completes at the daily target. Sync to a second device: the minutes match. Delete the manual entry on one device and it disappears on both.
- [ ] Highlight a phrase and add a note in an article. Open the same URL on a second device after sync: the annotation is there. Look up three words: the document's word list has them, and Drill shows each one inside its sentence.
- [ ] Import two screenshots of a manga page: the OCR text becomes one document and each page shows its picture.
