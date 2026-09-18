# Sync protocol (v1)

Self-hostable sync between a learner's devices (BRIEF §3.6, §8). Sync is optional; the apps are fully usable without it. The server is `server/` (Ktor + Postgres, `docker compose up`), and the client is `shared/src/commonMain/kotlin/app/tsumugi/sync/`.

## Principles

- **Append-only change log per user.** The server assigns a monotonically increasing `seq` per user. Clients pull everything after their last seen `seq` and push their own unsynced changes.
- **Conflict-free by construction** (BRIEF §8.2):
  - `review` rows are immutable facts, so reviews merge by set union on `id`.
  - `card` FSRS state is **never synced**. Each device recomputes a card by replaying the union of its reviews (`SrsRepository.recomputeCard`), so devices converge deterministically: fuzz is seeded by `(cardId, index)`.
  - Everything else is **last-writer-wins by `(updated_at, device_id)`**, compared lexicographically. Deletes are tombstones (`deleted = 1` rows, or an op of `DELETE` for tables without a flag).
- **Opaque payloads.** The server stores payloads as opaque JSON strings. With end-to-end encryption on, payloads are ciphertext, and the server can't read them (so it computes no leaderboard stats for that user).
- **Content packs are not synced.** The server only stores which pack versions a user has (`packs` in the device registry), so a new device can prompt for downloads.

## Synced tables

| Table | Key | Merge |
|---|---|---|
| `item` | `id` | LWW by `updated_at`, `deleted` tombstone |
| `item_relation` | `(parent_id, child_id, kind)` | insert-only (union) |
| `review` | `id` | union (immutable) → affected cards recomputed |
| `note` | `item_id` | LWW by `updated_at` |
| `setting` | `key` | LWW by `updated_at` |
| `word_list` | `id` | LWW by `updated_at`, `deleted` tombstone |
| `word_list_entry` | `(list_id, ref)` | LWW by `updated_at`, `deleted` tombstone |
| `exam_attempt` | `id` | union (immutable once submitted) |
| `card` | `id` | **not synced**: rows are created from items/reviews; `suspended` syncs as a `setting`-like LWW field via the `card_flags` change type |

Not synced: `app_meta` (device-local), `integration` (tokens stay on each device), `session`, reader documents (fetched content stays on the device, BRIEF §4), recordings (unless the user turns on "include recordings", which uses blobs).

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

With E2E encryption on, `row` is replaced by `"sealed": "<base64 nonce ‖ XChaCha20-Poly1305 ciphertext of the row JSON>"`. The key is derived with Argon2id from the user's sync passphrase and a per-account salt stored on the server (`GET /v1/account` → `e2eSalt`). `table`, `key`, `op`, `updatedAt` and `deviceId` stay in clear so the server can order and dedupe.

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
- `/leaderboard?period=day|week|month`.
- `accepted` in the push response counts every valid change, including duplicates of already-stored ones, so the client can mark all of them synced.

Idempotency: the server dedupes pushed changes on `(user, table, key, updatedAt, deviceId, op)`, so a retried push after a timeout doesn't duplicate.

## Client algorithm

1. Local writes to synced tables are captured by SQLite triggers into `change_log(seq, table_name, row_key, op, created_at, synced)`: dirty-row markers only, no payloads. A `sync_state.applying` flag disables the triggers while remote changes are applied, so they don't echo back.
2. **Push:** for each unsynced marker (oldest first, deduplicated per row), serialize the row's *current* state (or a DELETE), `POST /sync/push`, then mark those markers synced.
3. **Pull:** `GET /sync/pull?since=lastSeq` until `hasMore = false`. For each change, apply the merge rule above inside a transaction with `applying = 1`, and collect card ids whose reviews changed. Then recompute those cards and store `lastSeq` in `app_meta`.
4. Sync runs on app launch/foreground and after a review session ends, when sync is configured. Offline: markers wait until the next successful sync.
