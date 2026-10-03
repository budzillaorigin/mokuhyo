# The `.mokuhyo` backup bundle (format 1)

One file per learner move (CLAUDE.md rule 12, BRIEF §8.2). Written by Settings → Backup → Export, read by Import, and
documented here so other tools can read it. Implementation: `shared/src/commonMain/kotlin/app/mokuhyo/backup/Bundle.kt`
(container) and `shared/src/jvmMain/kotlin/app/mokuhyo/backup/Backup.kt` (export, merge-import).

## Container

| Bytes | Content |
|---|---|
| 0–3 | ASCII `MKHY` |
| 4–5 | format version, u16 big-endian (1) |
| 6–7 | flags, u16 big-endian: bit 0 = encrypted |
| 8–11 | header length *n*, u32 big-endian |
| 12 … 12+*n* | header, UTF-8 JSON (below) |
| rest | payload: the zip (plain), or the sealed zip chunks (encrypted) |

Header JSON: `{"format":1,"created":"<ISO-8601>","appVersion":"0.1.0","encrypted":false}`; when encrypted it adds
`"kdf":{"alg":"argon2id","salt":"<hex 16 bytes>","memoryKiB":65536,"iterations":3,"parallelism":1}` and
`"noncePrefix":"<hex 16 bytes>"`. The header is in clear text so an importer can ask for the passphrase first.

### Encryption
key = Argon2id(passphrase UTF-8, salt, iterations, memoryKiB, parallelism, 32 bytes) — RFC 9106, version 0x13.
The zip is split into 1 MiB chunks (the last may be shorter). Chunk *i* (from 0) is written as a u32 big-endian
length followed by XChaCha20-Poly1305(key, nonce, plaintext, aad) where nonce = noncePrefix ‖ u64be(*i*) and
aad = (all header bytes from offset 0) ‖ u64be(*i*) ‖ (1 if last chunk else 0). Reordering, dropping, truncating or
changing a chunk, or the header, makes decryption fail.

## Payload zip

| Entry | Content |
|---|---|
| `manifest.json` | `{format, appVersion, created, learnerId, learnerName, languages[], schemaVersion, counts{table: rows}, checksums{entry: sha256 hex}}` — every other entry is checksummed |
| `db.sqlite` | SQLite copy of the learner database (`VACUUM INTO`), schema version `schemaVersion` (SQLDelight `MokuhyoDatabase`, `shared/src/commonMain/sqldelight/`) |
| `recordings/<conversation id>/turn-NN.wav` | 16 kHz mono PCM WAV of the learner's turns, paths as in the `recording` table |
| `settings.json` | learner-scope settings `{key: value}` (device-local settings never travel, CLAUDE.md rule 16 of Tsumugi kept) |
| `packs.json` | `{content: [lang…], voices: [voice id…], models: [model id…]}` installed on the exporting computer; never the files |

## Import = merge
- Bundles of a newer format or a newer database schema are refused with a message to update the app; older
  databases are migrated to the current schema before merging.
- Every row is merged by id with INSERT OR IGNORE: nothing already on this computer is overwritten. Rows of the
  bundle's learner are attributed to this computer's learner (single-learner app).
- Review items with the same (language, kind, ref) keep the local id; the bundle's reviews are re-keyed to it and the
  FSRS card is recomputed from the merged, append-only review log.
- Tombstones (`deleted` timestamps) propagate: a deletion on either computer stays a deletion; rows are never removed.
- Recordings are copied when the path doesn't exist yet. Learner settings missing here are added; differing ones are
  reported to the learner and left unchanged.
- Importing the same bundle again adds nothing (merge-idempotent; tested).
- Imported lexicon updates (`lexicon_package`, `lexicon_term`, database schema 2, BRIEF_PHASE8 C-04) merge by id like
  everything else; the receiving app re-derives which version is current.
- Schema 3 (BRIEF_PHASE8 C-11) adds `conversation.correctionsMode`, `conversation.aabJson` and the
  `conversation_turn_feedback` table; they merge by id with the rest.
