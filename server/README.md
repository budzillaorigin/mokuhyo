# Tsumugi sync server

Optional, self-hostable sync between your devices (BRIEF §8). The apps are fully usable without it. It implements [docs/SYNC_PROTOCOL.md](../docs/SYNC_PROTOCOL.md): an append-only change log per user, which the server orders and dedupes but never merges or interprets.

- **Stack:** Kotlin + Ktor 3, Postgres 16 in production (SQLite for development), Flyway migrations.
- **Accounts:** email + password (Argon2id) or passkeys (WebAuthn). Sessions use short-lived access JWTs and rotating refresh tokens; reusing an old refresh token signs that whole login out.
- **End-to-end encryption:** optional. The server only ever sees ciphertext for those accounts, and computes no leaderboard stats for them.
- **Hosted instance:** free, with fair-use limits (below). Everything it runs is this folder, so you can run your own.

## Run your own (any VPS or home server)

```bash
cd server
cp .env.example .env        # set TSUMUGI_JWT_SECRET and POSTGRES_PASSWORD at least
docker compose up -d        # server on :8080 + Postgres
docker compose --profile tls up -d   # same, plus Caddy with automatic HTTPS for TSUMUGI_DOMAIN
```

Then in the app: **Me → Sync → Server URL** → `https://your.domain` (or `http://<lan-ip>:8080` at home).

Needs Docker with Compose v2 and BuildKit (the default), 1 vCPU, 512 MB RAM and about 1 GB of disk.

## Development

```bash
make dev     # http://localhost:8080 with a SQLite file, no Docker needed
make test    # unit tests on SQLite; the Postgres test also runs where Docker is available (CI)
```

Or without make: `TSUMUGI_DB=sqlite:./dev.db TSUMUGI_JWT_SECRET=<32+ chars> ./gradlew :server:run` from the repo root.

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `TSUMUGI_JWT_SECRET` | *(required)* | HMAC secret for access tokens, 32+ characters |
| `TSUMUGI_DB` | `sqlite:./dev.db` | `postgres://user:pass@host:5432/db`, a `jdbc:postgresql://…` URL, or `sqlite:<path>` |
| `TSUMUGI_PORT` | `8080` | HTTP port |
| `TSUMUGI_PUBLIC_URL` | `http://localhost:8080` | Base URL used in email-verification links |
| `TSUMUGI_RP_ID` / `TSUMUGI_RP_NAME` / `TSUMUGI_RP_ORIGINS` | `localhost` / `Tsumugi` / `http://localhost:8080` | Passkey relying party: your domain, display name, and comma-separated allowed origins |
| `TSUMUGI_SMTP_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_FROM` | unset | SMTP for verification emails (STARTTLS on 587, TLS on 465). Unset: the link is written to the log |
| `TSUMUGI_MAX_PUSH_CHANGES` | `5000` | Changes per push |
| `TSUMUGI_MAX_BODY_BYTES` | `8388608` | Largest request body, enforced while reading, so chunked uploads are capped too |
| `TSUMUGI_BLOB_QUOTA_BYTES` / `TSUMUGI_MAX_BLOB_BYTES` | 200 MB / 20 MB | Recording/image storage per user / per file |
| `TSUMUGI_RATE_LIMIT_PER_MINUTE` | `600` | Requests per user (per IP when signed out) |
| `TSUMUGI_ALLOW_REGISTRATION` | `true` | Set `false` on a private server once your accounts exist |

Fair-use limits on the hosted instance are only these values; there are no separate code paths.

## Backups

All state is in Postgres. `make backup` writes a gzipped `pg_dump` to `./backups/`. Restore with `gunzip -c file.sql.gz | docker compose exec -T postgres psql -U tsumugi -d tsumugi`. Losing the server loses nothing essential: each device holds its full data and re-pushes to a fresh server.

## Endpoints

All under `/v1`: `auth/register|login|refresh|logout|verify`, `auth/passkey/register/options|verify`, `auth/passkey/login/options|verify`, `account`, `devices`, `devices/{id}/packs`, `sync/push`, `sync/pull`, `blobs/{id}`, `leaderboard`, `health`. The details are in [docs/SYNC_PROTOCOL.md](../docs/SYNC_PROTOCOL.md).

Details beyond the protocol doc:
- **Push response:** `accepted` counts every valid change in the request, including ones already stored by a retried push, so the client can mark all of them synced.
- **Passkeys:**
  - Options are standard WebAuthn JSON with base64url binary fields, plus a `challengeId` to send back.
  - `verify` takes `{challengeId, credential}`, where `credential` is the `PublicKeyCredential.toJSON()` shape.
  - Only `none` attestation is requested. Challenges are single-use and expire after 5 minutes.
- **Leaderboard:** `period=day|week|month`. It lists only accounts that opted in and don't use end-to-end encryption. A streak counts consecutive UTC days with reviews.
- **Email verification:** sent when SMTP is configured, but not required to sign in.
