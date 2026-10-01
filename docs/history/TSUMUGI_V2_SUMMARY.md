# Tsumugi v2: final summary

All of BRIEF_V2 (Phases 9 through 14) is built and pushed to the `v2` branch on GitHub (budzillaorigin/tsumugi). `main` still holds the Phase 0–8 snapshot.

## What was verified

These checks pass on the final state of `v2`:
- All 117 shared test suites.
- The database migrations, now at user-schema version 10.
- The sync-server tests.
- The Android debug build.
- The Python lint (`ruff`).

**The big caveat:** none of this has run on a phone. The iOS code written since CI was paused (D-140) has never been compiled. Your Mac build and device testing are the real test.

## What was built

### Phase 9: fixes
- Every release blocker and serious item from the audit (F-01 to F-34), plus most of the moderate ones (F-35 to F-45).
- Each fix has a regression test, listed in `docs/PROGRESS.md`.
- The main fixes:
  - app icon (dark and tinted variants included);
  - local-network access for your Ollama server (iOS permission and HTTP exceptions; Android cleartext config);
  - levels that never drop, and undo that survives sync (tombstones);
  - timeouts on every network call, and one key per endpoint;
  - background model downloads with incremental hashing and a free-space check;
  - resumable exams on a wall-clock timer;
  - a Reviews tab, reader translation, an audio session controller, error and retry states, and the notification permission moved to after the first session.
- Two audit findings turned out to be wrong: the stage crash (F-17) and the FSRS formula mismatch (F-18). The code was hardened and tested anyway.

### Phase 10: missing v1 features
- All six Today blocks: reviews, lessons, grammar, immersion, shadowing, speaking and writing.
- Free talk, a rolling level estimate and a recurring-error log.
- Media player: subtitle generation (Whisper), save clip to SRS, podcasts, and a hide-subtitle quiz.
- Export: CSV of reviews, a PDF study report, and JSON backup with merge-only restore.
- The kana course, and picture/audio cards.
- Notion and AnkiConnect.
- Grammar production reviews, minimal pairs on FSRS, streak freezes, and weekly challenges.
- Android: a Glance widget, an in-app language picker and Media3.
- Safari "Look up in Tsumugi" action extension.
- VOICEVOX audio packs, re-rendered without 青山龍星.

### Phase 11: immersion
- Media decks built from your own texts, EPUBs and subtitles, plus Core 2k/6k/10k decks.
- A coverage overlay ("you know X% of words") and a 0–100 difficulty score with JLPT/ILR labels.
- Marking words known, and sentences with exactly one unknown word surfaced for mining.
- A sentence bank from your own subtitles, with clip audio and thumbnails.
- Lyrics with karaoke and a cloze mode.
- The immersion log and a four-stage roadmap.
- Reader annotations, screenshot import, vocabulary lists per document, and a guides library (links only).

### Phase 12: content
All AI-drafted, and badged until you review it in the app (Me → Content review, developer toggle) or with `tools/items/review.py`.
- 120 graded readers, 20 at each level from level 0 to N1, with read-along audio, questions and genre tasks.
- 7 interest tracks: gaming/VTuber, business and keigo, family, daily-life admin, schoolchild vocabulary, military liaison, and performing culture.
- 90 scenarios, 125 dialogues (including 40 natural-style ones) and 31 drill sets.
- ILR 3+/4 exam passages and military-liaison passages. The DLPT bank is now 195 passages and 595 items.
- JLPT courses per level, with mastery checkboxes and a "one book to pass" view.
- Japanese explanations for all 829 grammar points, and a monolingual mode.
- 1,334 onomatopoeia across 12 themes.

### Phase 13: advanced
- Pitch-accent test, built on 300 pre-rendered pitch items.
- Kanji graph with 316 sound families.
- Dictionary improvements: a conjugation breakdown chip, search as you type, and frequency chips.
- Reflex and Atom mini-games.
- Translation workbench: 84 passages and a skill chart.
- Expression thesaurus (42 clusters, 6,941 collocations) and a writing studio.
- Poetry corner (48 poems) and a solo reading circle. Every author was checked as public domain.

### Phase 14: as you chose
- Sync is self-hosted only, and now requires email verification.
- A self-hosting guide in `server/README.md`.
- Android release preparation: release build and App Bundle, a signing config kept out of git, and target SDK 36.
- A draft privacy policy in `docs/PRIVACY.md`.

### Audio
- 7,820 clips (213 MB) in the private release `audio-packs-2026-09-19`, pinned in `tools/packs/audio.lock`.
- Pitch and minimal pairs ship inside the app. The rest (readers, exam, dialogues, tracks, grammar) you install from Files or a URL in Settings → Audio packs. There is no default URL (D-096).
- Voices: 春日部つむぎ, 四国めたん and 玄野武宏.

## Your next steps

1. **On the Mac:**
   ```bash
   git fetch && git checkout v2
   gh auth login                                   # so the scripts can reach the private repo
   cd tools
   uv run python packs/build_all.py                # content packs
   uv run python packs/audio_release.py fetch      # rendered audio packs
   cd ..
   bash tools/models/fetch_ios_frameworks.sh       # llama.cpp / whisper.cpp
   ```
   Then build with the commands in `docs/PROGRESS.md` under "iOS: unverified since CI paused". Expect some Swift/Kotlin naming errors; each likely spot is listed there with its file and line. If a name doesn't resolve, the generated `Shared.h` shows what Swift actually sees.
2. **Apple account:** register `app.tsumugi.ios` and its `.widget`, `.share` and `.action` bundle IDs with the App Group `group.app.tsumugi`. Then follow `docs/RELEASE.md` to archive and upload to TestFlight.
3. **Device testing:** work through `docs/QA.md`, Phase 9 first:
   - a real review session with your WaniKani and NihongoShark data;
   - your own Ollama server over the LAN;
   - silent-switch audio, background download and exam resume.

## Decisions only you can make

- **Export compliance:** "No" to non-exempt encryption may be wrong, because the optional end-to-end sync uses encryption the app implements itself (D-067).
- **ML Kit:** Android's text-recognition library sends usage diagnostics to Google, which the privacy policy now discloses. Keep it or replace it?
- **Play store AI rule:** Play expects a way to report offensive AI output, and the app has none yet.
- **Privacy policy:** it needs a contact line and a public URL before any store submission.
- **Voice credit:** "VOICEVOX:四国めたん" must appear in the store descriptions.
- **Gloss:** the gaming track glosses 天井 as the gacha "pity ceiling", which isn't a dictionary sense. Keep it or drop it?
- **Re-verification:** accounts that existed before email verification are marked verified on upgrade. Say if they should re-verify instead.

## Not built yet

- Overlapping dialogue lines playing at the same time (they show side by side, but play one after another).
- Furigana in graded stories.
- Pinch-zoom on the kanji graph.
- Perform mode playing its new audio clips.
- Review of the readers' genre-task templates.
- The hosted server, leaderboard and shared reading circles, deferred as you chose.

## Content you can extend later

Every content script accepts `--endpoint URL --model NAME` and adds new items without duplicating ids. You can run them against Ollama on your GPU machine. The full grammar-example audio (all 7,603 examples) can be rendered there too:
```bash
uv run python packs/render_audio.py grammar --grammar-all
```

## CI

CI is manual-only, since the GitHub Actions minutes ran out. To turn automatic runs back on, restore the `push`/`pull_request` triggers in `.github/workflows/ci.yml`. You can also run it once from the Actions tab (or `gh workflow run CI --ref v2`) before a TestFlight build.

## Where to look

| Topic | File |
|---|---|
| Phase-by-phase checkpoints, regression tests, unverified iOS spots | `docs/PROGRESS.md` |
| Every design decision (D-001 to D-319) and open decisions | `docs/DECISIONS.md` |
| Device test checklist | `docs/QA.md` |
| Build, sign, archive, TestFlight, Play | `docs/RELEASE.md` |
| Content packs, formats, counts, reviewing content | `docs/CONTENT_PACKS.md` |
| Licenses, credits, "inspiration only" sources | `docs/LICENSES.md` |
| Sync protocol | `docs/SYNC_PROTOCOL.md` |
| Integrations (Notion, AnkiConnect, Immersion Kit, Bunpro) | `docs/INTEGRATIONS.md` |
| Privacy policy draft | `docs/PRIVACY.md` |
| Self-hosting the sync server | `server/README.md` |
