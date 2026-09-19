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
- [ ] Android: a fresh install plays minimal pairs with the pre-rendered voice (the note under the buttons says so). Settings → Audio packs lists pitch and minimal pairs with credits. Install the exam pack from a file, then run a strict JLPT listening item: it plays the pre-rendered lines once, and the Play button stays disabled afterwards. Remove the pack: the same item reads with TTS.
- [ ] Android: type your pack server's address in Audio packs, show the list, download the dialogue pack (progress, Cancel leaves nothing changed), and play a dialogue.
- [ ] Android: import an EPUB, open it: the coverage card shows known words, kanji and "N new words to reach 95%" with a difficulty badge. "Create deck" → preview → save → "Study this deck" → Start lessons shows deck words. The library's "Most readable" sort puts the easiest documents first.
- [ ] Android: a fresh install with the kanji check answered "I know it" at least once offers "Already know some words?"; mark a batch known and check the Core 2k deck counts them.
- [ ] Android: in the reader, 1T sentences are tinted with the new word underlined; the 1T tab mines one. Annotate: tap two words, choose Highlight, then Note; the Notes tab lists both.


## Phase 11 on iOS: decks, coverage, mark known, audio packs
- [ ] Settings → Audio packs lists the five sets as not installed. Install `audio-pitch.zip` from Files: progress shows, then the clip count, size and VOICEVOX credit appear. Remove it: the system voice is used again.
- [ ] Type your server's audio folder URL (a fresh install has none filled in) and tap "Show available packs". Download one, cancel halfway (the old version stays), then download it fully.
- [ ] With the exam pack installed, a JLPT listening item plays the VOICEVOX voices, still only once in strict mode. Dialogues, minimal pairs and grammar examples play pack audio when it's installed, and the system voice otherwise.
- [ ] Import an EPUB and open it: the coverage card shows known %, kanji % and words to 95%, with an N/ILR badge. Library → Coverage sorts by it, and "Measure coverage" fills in the rest with progress.
- [ ] Load a video with an .srt: the coverage card appears, "Lines with one new word" lists lines, and Mine adds the word. "Make a deck from these subtitles" → preview → Save with "Study this deck": the next lessons include deck words.
- [ ] Learn → Decks: Core 2k/6k/10k show counts. Swipe a word to Known: its deck counts and coverage update.
- [ ] Dictionary entry: tap "I know this word", then Undo. The Sentences section groups your media, Tatoeba and Immersion Kit (only when it's turned on in Settings → Example sentences).
- [ ] Onboarding on a fresh install: after the kanji check, "Words you already know" pages through common words. Skipping works.
- [ ] Reader: tap the pencil, tap two words, then Highlight. Add a note. Notes lists both. Words in this text → Drill.
- [ ] Reader library → Screenshots: pick 2 pictures. Progress shows per picture, and the document opens with its page strip.
- [ ] A grammar point shows its Guides links, and they open in Safari.
- [ ] Me: the Immersion card and the Roadmap card show. Log time by hand, change the daily target, and delete an entry.

## Phase 12 on Android: readers, tracks, courses, onomatopoeia, drill sets
- [ ] Learn → Graded readers: the levels run from Level 0 to N1 with counts, and the genre chips filter the list. Open a story: it has a difficulty badge and the AI badge. Without the readers audio pack, each sentence's ▶ reads it with TTS. Install `audio-readers.zip`: Play all highlights each sentence as it's spoken, and tapping a sentence plays from there.
- [ ] In a story, answer the quiz: the score shows, and after three stories the roadmap's comprehension figure appears on Me. In Tasks, start the skim timer (the text hides when it ends), then write a Japanese summary and grade it. With a model, the scores and feedback show with the AI badge. Without one, a clear reason and an "Open AI settings" action show.
- [ ] A fresh install shows the tracks step in onboarding; pick Business. Learn → Tracks shows it selected. "Switch to only this" on Gaming changes the selection, and Today's lessons include Gaming words.
- [ ] On a track page, open a scenario (role-play starts) and a dialogue (the listening player opens). Tick a can-do statement, sync, and check it's ticked on the second device.
- [ ] Business → keigo drills: type the answer in katakana or with 。 and it still counts. For an email drill, fill every slot and check it: the model email shows.
- [ ] Performing → a performance: speak your line and it's checked against the script. Self-rate a line. Pass a round: the prompts fade (half → initial → cue only), and after the cue-only round the session finishes.
- [ ] Learn → JLPT courses: N5–N1 bars show. Open N4: tick a grammar point's mastery box, and the module bar moves. The quiz step opens a type drill, the mock step opens a section, and "What remains" lists what's left. Sync: the tick shows on the second device.
- [ ] Settings → monolingual mode on, from N3: an N2 grammar point shows the Japanese explanation (English chip to switch). An N2 word's entry offers "Write one with AI"; the result is badged, and reopening the entry shows it cached. Word lists never trigger a model call.
- [ ] Learn → Onomatopoeia: 12 theme tiles show their glyphs in light and dark mode. Filter by 擬情語, search きらきら, open it: feel line, glosses and examples, and ▶ reads them. The quiz works both ways and reveals each option after answering.
- [ ] Practice → Drill sets: open a grammar set, choose Short, press Play, and lock the screen. The prompt, pause, answer and repeat keep their timing. Headset and lock-screen next/previous work, and the notification's pause stops it. Finishing removes the notification.
- [ ] A natural dialogue (N4 natural): fillers show in grey, and an overlap pair sits side by side.
- [ ] Finish an OPI interview: the probe map shows circles and diamonds by level, colored by outcome, with breakdown questions listed and the domains covered and missing. TalkBack reads a summary of the chart.
- [ ] Exams → DLPT: switch to the upper range; the counts change. Pick "Editorial" and start a 30-min reading: every passage is an editorial.

## Phase 12 on iOS: readers, tracks, courses, monolingual, onomatopoeia, drills
- [ ] Learn → Graded readers: switch levels (Level 0 … N1) and genre chips; each story shows its difficulty badge and the AI badge. Open one: Read along highlights each sentence as it's spoken (pack voices with `audio-readers.zip` installed, the system voice without it). Tap a sentence to start there. "Open in reader" opens it with lookups.
- [ ] Story → Quiz: answer and check; the library then shows "Quiz: x of y". Tasks: the skim timer shows the text only while it runs; the summary grades with a model (badge and engine), and without one it says why.
- [ ] Fresh install: onboarding offers tracks after "Words you already know"; pick Gaming. Learn → Tracks shows it selected; "Only this track" and the toggle work; Today's lessons include track words.
- [ ] A track page: words open the dictionary, kanji show hints (gaming), a role-play and a dialogue open, can-do ticks survive a relaunch, links open Safari.
- [ ] Drills: a keigo answer in kana passes; an email checks every slot and shows the model email; fill-in with choices; synonym, meaning and usage. Perform: say a line (or type, or "I said it") for every line of yours, then Next round: your lines fade; after the cue-only round it says the performance is learned.
- [ ] Learn → JLPT courses: bars per level; open N5: the current module is expanded; tick a grammar point (the bar moves, reviews don't change); start a quiz drill and a mock section; "One book to pass" lists what's left.
- [ ] Settings → Monolingual mode on (N2): an N2 grammar point shows Japanese with "Show in English". An N2 dictionary entry writes a Japanese paraphrase once (with a model) and folds the English; the list screens never show a spinner for it. Turn the mode off: English again, "Explain in Japanese" on tap.
- [ ] Learn → Onomatopoeia: 12 tiles with drawn glyphs (not SF Symbols), search ざあざあ, filter a theme by 擬態語, open a word with examples, and take the quiz in both kinds.
- [ ] Practice → Speaking drills: play a grammar set with the screen locked; the lock screen shows the cue and play/pause/next/previous work, and so do headset buttons. Change the pause length: the set restarts with the new timing.
- [ ] A natural dialogue greys its fillers ("Fillers" hides them) and shows overlapping lines side by side. After an OPI interview, the probe map shows the level line, tallies and breakdowns. Exams → DLPT: switch to the upper range and pick a text type; the form uses only that type.
