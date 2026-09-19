# Sync protocol (v2)

v2 (BRIEF_V2, DECISIONS D-041, D-042, D-049) changes three things. Reviews can be tombstoned. `path_progress` and `path_unlock` are synced. With E2E on, keys are opaque. The server is unchanged: it still stores opaque changes and dedupes on `(table, key, updatedAt, deviceId, op)`. A v1 client ignores the `deleted_at` column it doesn't know, so it keeps an undone review live and doesn't apply the undo. All devices should upgrade together (the app is unreleased).

Self-hostable sync between a learner's devices (BRIEF §3.6, §8). Sync is optional; the apps are fully usable without it. The server is `server/` (Ktor + Postgres, `docker compose up`), and the client is `shared/src/commonMain/kotlin/app/tsumugi/sync/`.

## Principles

- **Append-only change log per user.** The server assigns a monotonically increasing `seq` per user. Clients pull everything after their last seen `seq` and push their own unsynced changes.
- **Conflict-free by construction** (BRIEF §8.2):
  - `review` rows are append-only facts, so reviews merge by set union on `id`. The one exception is the tombstone: an undo sets `deleted_at` (CLAUDE.md rule 12). A tombstone set anywhere reaches every device, and the earliest tombstone time wins. Replay ignores tombstoned rows.
  - `path_progress` merges by **MAX**: the higher `(generation, passed_level, updated_at)` wins, then the higher `device_id`, so progress never regresses through sync (rule 11). An explicit reset raises `generation`.
  - `card` FSRS state is **never synced**. Each device recomputes a card by replaying the union of its reviews (`SrsRepository.recomputeCard`), so devices converge deterministically: fuzz is seeded by `(cardId, index)`.
  - Everything else is **last-writer-wins by `(updated_at, device_id)`**, compared lexicographically. Deletes are tombstones (`deleted = 1` rows, or an op of `DELETE` for tables without a flag).
- **Opaque payloads.** The server stores payloads as opaque JSON strings. With end-to-end encryption on, payloads are ciphertext, and the server can't read them (so it computes no leaderboard stats for that user).
- **Content packs are not synced.** The server only stores which pack versions a user has (`packs` in the device registry), so a new device can prompt for downloads.

## Synced tables

| Table | Key | Merge |
|---|---|---|
| `item` | `id` | LWW by `updated_at`, `deleted` tombstone |
| `item_relation` | `(parent_id, child_id, kind)` | insert-only (union) |
| `review` | `id` | union + one-way `deleted_at` tombstone (earliest wins) → affected cards recomputed from live reviews. Wire `updatedAt` = `deleted_at` once tombstoned, else `ts` |
| `path_progress` | `track` | MAX by `(generation, passed_level, updated_at)`, then `device_id` |
| `path_unlock` | `item_id` | insert-only (union) |
| `note` | `item_id` | LWW by `updated_at` |
| `setting` | `key` | LWW by `updated_at` |
| `word_list` | `id` | LWW by `updated_at`, `deleted` tombstone |
| `word_list_entry` | `(list_id, ref)` | LWW by `updated_at`, `deleted` tombstone |
| `exam_attempt` | `id` | union (immutable once submitted) |
| `conversation` | `id` | union (written once when the conversation ends; no audio paths, D-101) |
| `today_block_done` | `(day, block)` | insert-only (union, D-100) |
| `streak_freeze` | `day` | insert-only (union, D-106) |
| `immersion_session` | `id` | union + one-way `deleted_at` tombstone (earliest wins), like reviews; wire `updatedAt` = `deleted_at` once tombstoned, else `created_at` (D-167) |
| `reader_annotation` | `id` | LWW by `updated_at`, `deleted` tombstone; keyed to a document by `doc_key` (`url:…` or `sha256:` of the body), since reader documents don't sync (D-164) |
| `card` | `id` | **not synced**: rows are created from items/reviews; `suspended` syncs as a `setting`-like LWW field via the `card_flags` change type |

Not synced: `app_meta` and `device_setting` (device-local: AI engine, model, endpoint URLs and audio engine, rule 16), `daily_stats` (derived from each device's own review log by triggers), `card.blocked_reason` (derived from the installed packs), `integration` (tokens stay on each device), `session`, reader documents (fetched content stays on the device, BRIEF §4), recordings and personal-card pictures (unless the learner turns on recordings sync on that device, which uses blobs; see "Blobs" below), and the Phase 10 device-local tables (`recording`, `user_image`, `subtitle_cache`, `media_clip`, `podcast_feed`, `podcast_episode`, `reader_question`, `content_review_verdict`), and the Phase 11 device-local tables (`media_index`, `media_cue`, `media_cue_token`, `lyrics_song`, `reader_doc_meta`, `reader_doc_vocab`; D-160, D-163, D-165).

## Change record (wire format)

```json
{
  "table": "review",
  "key": "c8a0…",                 // primary key; composite keys joined with U+001F
  "op": "UPSERT",                 // UPSERT | DELETE
  "row": { … },                   // full row as a JSON object (column → value); absent for DELETE
  "updatedAt": 1789000000000,     // epoch ms: the row's updated_at (review: ts)
  "deviceId": "3f1e…",
  "seq": 1234                     // assigned by the server; absent on push
}
```

With E2E encryption on, `row` is replaced by `"sealed": "<base64 nonce ‖ XChaCha20-Poly1305 ciphertext of the row JSON>"`. The key is derived with Argon2id from the user's sync passphrase and a per-account salt stored on the server (`GET /v1/account` → `e2eSalt`).

**v2 key ids.** With E2E on, `key` on the wire is an opaque id: `base64url(HMAC-SHA256(k_id, table ‖ U+001F ‖ key))`, where `k_id = HMAC-SHA256(e2eKey, "tsumugi-sync-key-id-v1")`. The same row always gets the same id, so the server can still order and dedupe it. Because the table name is part of the hash, one key in two tables gets unrelated ids. The sealed plaintext is the envelope `{"v": 2, "key": "<real key>", "row": {…} | null}`. DELETEs are sealed too, so they can carry the real key. A receiving client opens the envelope, checks that the real key hashes to the wire id, and merges on the real key. A sealed payload without an envelope is read as v1 (the row itself, with the key in clear). `table`, `op`, `updatedAt` and `deviceId` stay in clear because the server orders and dedupes on them.

## Endpoints

All under `/v1`, JSON, `Authorization: Bearer <access JWT>` except auth and health.

| Method | Path | Body → Response |
|---|---|---|
| POST | `/auth/register` | `{email, password, displayName?}` → `{userId}` (email verification if SMTP is configured) |
| POST | `/auth/login` | `{email, password, deviceName, platform}` → `{accessToken, refreshToken, deviceId, expiresIn}` |
| POST | `/auth/refresh` | `{refreshToken}` → new pair (refresh tokens rotate; reuse revokes the family) |
| POST | `/auth/logout` | `{refreshToken}` → 204 |
| POST | `/auth/passkey/register/options` · `/verify`, `/auth/passkey/login/options` · `/verify` | WebAuthn ceremonies |
| GET | `/account` | → `{userId, email, displayName, e2eEnabled, e2eSalt, leaderboardOptIn}` |
| PATCH | `/account` | `{displayName?, e2eEnabled?, e2eSalt?, leaderboardOptIn?}` |
| GET | `/devices` · DELETE `/devices/{id}` | device registry (`{id, name, platform, lastSeenAt, packs}`) |
| PUT | `/devices/{id}/packs` | `{packs: {file: version}}` |
| POST | `/sync/push` | `{changes: [Change…]}` (max 5,000 per call) → `{accepted: n, lastSeq}` |
| GET | `/sync/pull?since=<seq>&limit=<n>` | → `{changes: [Change…], lastSeq, hasMore}` (ordered by `seq`) |
| PUT / GET | `/blobs/{id}` | opaque bytes (recordings/images), optional, per-user quota |
| GET | `/leaderboard?period=week` | opt-in users only: `[{displayName, reviews, streak}]` (no E2E users) |
| GET | `/health` | `{status: "ok", version}` |

Server extras beyond the table above:
- `GET /auth/verify?token=`: email verification link.
- `DELETE /blobs/{id}`.

## Blobs: opt-in recordings and pictures (DECISIONS D-111)

The client uses the blob endpoints only when the device setting `sync.recordings` is on (default off). The server needs nothing special: blobs are opaque.
- File blobs: `rec-<recording id>` and `img-<picture id>`, holding the file bytes.
- Manifest blob per device: `man-<server device id>`, holding JSON `{version: 1, updatedAt, recordings: [{id, kind, ref, fileName, mime, durationMs, referenceKey, origin, createdAt}], images: [{id, fileName, mime, origin, createdAt}], deleted: [id…]}`. It lists the files this device recorded and uploaded, plus every deletion it has seen.
- One round: upload new local files → delete the blobs of deleted files → publish the manifest → read the other devices' manifests (found through `GET /devices`) → delete local copies of anything listed as deleted → download what's missing.
- End-to-end encryption: a blob id becomes `e-` + the sealer's keyed hash of the plain id, and the bytes are XChaCha20-Poly1305 (nonce ‖ ciphertext).
- Limits: 20 MB per blob and a per-user quota (`TSUMUGI_MAX_BLOB_BYTES`, `TSUMUGI_BLOB_QUOTA_BYTES`). A file that doesn't fit is reported, and the rest still syncs.
- `/leaderboard?period=day|week|month`.
- `accepted` in the push response counts every valid change, including duplicates of already-stored ones, so the client can mark all of them synced.

Idempotency: the server dedupes pushed changes on `(user, table, key, updatedAt, deviceId, op)`, so a retried push after a timeout doesn't duplicate.

## Client algorithm

1. Local writes to synced tables are captured by SQLite triggers into `change_log(seq, table_name, row_key, op, created_at, synced)`: dirty-row markers only, no payloads. A `sync_state.applying` flag disables the triggers while remote changes are applied, so they don't echo back.
2. **Push:** set `sync_state.pushing = 1`. Then, for each unsynced marker (oldest first, deduplicated per row), serialize the row's *current* state (or a DELETE), `POST /sync/push`, mark those markers synced, and clear `pushing`. An append-only row that is gone was deleted by the never-pushed fast path, and is skipped.
3. **Pull:** `GET /sync/pull?since=lastSeq` until `hasMore = false`. For each change, apply the merge rule above inside a transaction with `applying = 1`, and collect card ids whose reviews changed. Then recompute those cards, store `lastSeq` in `app_meta`, and report the changed `setting` keys to the app (`SyncEngine.onSettingsChanged`: new FSRS weights reload the scheduler and rebuild every card).
   - **Review undo:** a review is deleted outright only when it still has an unsynced insert marker and `pushing = 0`, so it provably never left the device. Otherwise the undo tombstones it, and the tombstone syncs as an UPSERT of the row (D-042).
4. Sync runs on app launch/foreground and after a review session ends, when sync is configured. Offline: markers wait until the next successful sync.
