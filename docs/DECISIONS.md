# Decisions

Mokuhyo decisions, newest last. Tsumugi's decision log (D-001…) is kept in `docs/history/TSUMUGI_DECISIONS.md`;
Mokuhyo numbering restarts at D-001. Entries marked *(unattended default)* were taken during the unattended
Phase 0–7 run (CLAUDE.md "Unattended run") without owner review.

## D-001 Drafting endpoint address (unattended default)
The launch message carried the literal placeholder `<5090-ip>`. The owner's §7.1 Ollama server was found by probing
port 11434 on the hosts already in this Mac's ARP cache (no subnet sweep): `<server-ip>` serves exactly the six §7.1
tags (`mistral-small3.2:24b-instruct-2506-q8_0`, `gpt-oss:20b`, `mistral-nemo:12b`, `phi4:14b`, `granite3.3:8b`,
`phi4-mini:3.8b`) and answered a test completion. `tools/.env.example` sets `LLM_ENDPOINT=http://<server-ip>:11434/v1`.
If the DHCP reservation changes, edit `tools/.env` (git-ignored) — no code change.

## D-002 Phase 0 pruning: rewrite-in-place, not a fresh tree (unattended default)
Kept Tsumugi modules were moved (`git mv`, history preserved) into `app.mokuhyo.*`: `ai/` (gateway, local/endpoint
models, model manager, speech engines, validation, the OPI/role-play/free-talk/correction prompts), `exam/`
(models, assembler, session, bank validator, ILR estimator), `opi/` (session, probe map, bank models), `srs/`
(FSRS, optimizer, answer checker), `lang/ja/` (tokenizer, deinflector, conjugator, kana, furigana, pitch, mora),
`backup/crypto/` (Argon2id, BLAKE2b, XChaCha20-Poly1305), `net/Http.kt`. Everything else (reader, media, tracks,
courses, onomatopoeia, thesaurus, literature, sync, integrations, study, kanji path, writing, games, JLPT, the
upper-range DLPT banks and tests, server, iOS and Android apps) was removed. The JMdict-specific `dictionary/`
module was removed too: Phase 2 replaces it with the language-neutral schema (BRIEF §5.2); `tools/packs/build_dictionary.py`
stays as the starting point of the `ja` adapter.

## D-003 Lock only the shipping classpaths (unattended default)
`gate_core` requires a LICENSES.md row for "every dependency in the Gradle lockfile". Locking every configuration
would put compiler and test-only artifacts in the lockfile; only `runtimeClasspath`/`jvmRuntimeClasspath` (what ends
up in the jlink image) are locked. Rows may use glob patterns (`io.ktor:*`). Build tooling is listed separately.

## D-004 JVM-only KMP target with expect/actual kept
`shared` declares only `jvm()`. `commonMain` keeps `expect` declarations (NFC normalization, free space) with JVM
actuals in `jvmMain`, so an Android or iOS target could return without moving code (BRIEF §3.1).

## D-005 CI cost (unattended default)
The account's Actions minutes ran out under Tsumugi (its D-entry of 2026-09-18 paused automatic runs). CLAUDE.md
rule 11 now requires three-OS CI on every push to main, so `ci.yml` runs on push again. To keep cost bounded the
unattended run pushes once per phase (plus fixes), not per commit. If GitHub refuses the runs for billing, the gates
are run locally on macOS and the Windows/Linux part is recorded as a known gap in PROGRESS.
