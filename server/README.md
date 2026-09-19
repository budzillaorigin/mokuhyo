# Tsumugi sync server

Optional, self-hostable sync between your devices (BRIEF §8). The apps are fully usable without it. It implements [docs/SYNC_PROTOCOL.md](../docs/SYNC_PROTOCOL.md): an append-only change log per user, which the server orders and dedupes but never merges or interprets.

- **Stack:** Kotlin + Ktor 3, Postgres 16 in production (SQLite for development), Flyway migrations.
- **Accounts:** email + password (Argon2id), with passkeys (WebAuthn) as an extra sign-in for an existing account. Sessions use short-lived access JWTs and rotating refresh tokens; reusing an old refresh token signs that whole login out.
- **Email verification:** required before an account can sync (D-310). Configure SMTP, or read the link from the server log, or turn the requirement off on a single-user server (below).
- **End-to-end encryption:** optional. The server only ever sees ciphertext for those accounts, and computes no leaderboard stats for them.
- **No public instance.** Sync is self-hosted only (D-313): there's no hosted service and no pricing. Each learner runs their own server, or uses one a friend or family member runs.

## Run it for yourself or your family

You need a machine that stays on: a home server, a NAS that runs Docker, a Raspberry Pi 4/5 (arm64) or a small VPS. Requirements: Docker with Compose v2 and BuildKit (the default), 1 vCPU, 512 MB RAM and about 1 GB of disk, plus whatever your recordings use if you turn on recordings sync (200 MB per account by default).

First, on that machine:

```bash
git clone <this repository> tsumugi && cd tsumugi/server
cp .env.example .env
openssl rand -hex 32        # paste as TSUMUGI_JWT_SECRET in .env
openssl rand -hex 16        # paste as POSTGRES_PASSWORD in .env
```

Then pick **one** of the three ways to reach it.

### Option A: Tailscale only (simplest private setup)

Nothing is exposed to the internet; only devices signed in to your tailnet can reach the server. Install [Tailscale](https://tailscale.com) on the server and on every phone that syncs.

1. In `.env`, set `TSUMUGI_BIND=127.0.0.1` so the plain port is only reachable from the machine itself.
2. Start the server: `docker compose up -d`.
3. Give it HTTPS on your tailnet: `tailscale serve --bg 8080`. It prints the address, `https://<machine>.<tailnet>.ts.net`, and Tailscale provides the certificate. (Enable HTTPS certificates for the tailnet in the Tailscale admin console the first time.)
4. Set `TSUMUGI_PUBLIC_URL=https://<machine>.<tailnet>.ts.net` in `.env` so verification links point there, then `docker compose up -d` again.

If you'd rather skip `tailscale serve`, leave `TSUMUGI_BIND` unset and use `http://<machine>.<tailnet>.ts.net:8080`. The traffic is still encrypted by Tailscale (WireGuard); both apps allow plain http to `*.ts.net` names.

### Option B: a domain with automatic HTTPS (Caddy)

For a VPS, or a home server with ports 80 and 443 forwarded to it.

1. Point a DNS name (e.g. `sync.example.com`) at the machine's public IP.
2. In `.env`, set `TSUMUGI_DOMAIN`, `TSUMUGI_PUBLIC_URL=https://sync.example.com`, `TSUMUGI_RP_ID` and `TSUMUGI_RP_ORIGINS` to that name, and `TSUMUGI_BIND=127.0.0.1` so only Caddy is reachable from outside.
3. Start the server with Caddy: `docker compose --profile tls up -d`. Caddy gets and renews a Let's Encrypt certificate for `TSUMUGI_DOMAIN` by itself.
4. Once your family's accounts exist, set `TSUMUGI_ALLOW_REGISTRATION=false` and run `docker compose --profile tls up -d` again, so strangers can't sign up.

### Option C: home network only

`docker compose up -d` and use `http://<lan-ip>:8080` from devices on the same Wi-Fi. There's no TLS, so use this only on a network you trust. On iOS the first connection shows the local-network permission prompt.

### Email verification: SMTP, the log, or off

Accounts must confirm their email before they can sync. There are three ways to handle that:

- **SMTP (for several people).** Set `TSUMUGI_SMTP_HOST`, `TSUMUGI_SMTP_PORT` (587 for STARTTLS, 465 for TLS), `TSUMUGI_SMTP_USER`, `TSUMUGI_SMTP_PASSWORD` and `TSUMUGI_SMTP_FROM`. Any provider's SMTP works: your mail host, or an app password for a personal mailbox.
- **The log (no SMTP).** The link is written to the server log instead: `docker compose logs server | grep "verification link"`. Open it in a browser, or send it to the person yourself.
- **Off (one person).** For a server only you use, set `TSUMUGI_REQUIRE_EMAIL_VERIFICATION=false`.

Links work for 48 hours (`TSUMUGI_VERIFY_TOKEN_TTL_HOURS`). The app's Sync screen shows "Check your email to finish setting up sync" with a **Resend email** button while an account is unverified. Resends are limited to one every 2 minutes (`TSUMUGI_VERIFY_RESEND_SECONDS`). Nothing on the device is lost while it waits: changes stay queued and go up on the first sync after the link is opened.

Accounts that existed before verification became required are marked verified by the upgrade (migration V2), so they keep syncing.

### Point the app at it

In the app, open **Me → Sync**:

1. Enter the **Server URL**: `https://<machine>.<tailnet>.ts.net`, `https://sync.example.com` or `http://<lan-ip>:8080`.
2. Enter your email and a password of 10 or more characters, then tap **Create account**.
3. Open the verification link (or read it from the log), then **Sign in** on each device.
4. Optional: set an end-to-end encryption passphrase, and turn on recordings sync per device.

## Backups

All state is in Postgres. `make backup` writes a gzipped `pg_dump` to `./backups/`; it runs:

```bash
docker compose exec -T postgres pg_dump -U tsumugi -d tsumugi | gzip > backups/tsumugi-$(date +%Y%m%d-%H%M%S).sql.gz
```

Run it from cron (e.g. nightly) and copy `backups/` off the machine. To restore into a fresh install, start only Postgres, load the dump, then start the server:

```bash
docker compose up -d postgres
gunzip -c backups/tsumugi-….sql.gz | docker compose exec -T postgres psql -U tsumugi -d tsumugi
docker compose up -d
```

Losing the server loses nothing essential: each device holds its full data and re-pushes to a fresh server. Recordings uploaded with recordings sync live in the database too, so they're in the dump.

## Upgrades and migrations

```bash
make backup                           # first, always
git pull
docker compose up -d --build          # add --profile tls if you use Caddy
docker compose logs -f server         # Flyway logs "Successfully applied N migrations" on the first start
```

Schema migrations (`src/main/resources/db/migration/postgres/V*.sql`) run automatically when the server starts, inside a transaction; a failed migration stops the server and leaves the database as it was. To go back, restore the backup and check out the previous version. Postgres itself (`postgres:16-alpine`) gets patch updates with `docker compose pull && docker compose up -d`; a major version upgrade (16 → 17) needs a dump and restore.

## Development

```bash
make dev     # http://localhost:8080 with a SQLite file, no Docker needed; email verification off
make test    # unit tests on SQLite; the Postgres test also runs where Docker is available (CI)
```

Or without make: `TSUMUGI_DB=sqlite:./dev.db TSUMUGI_REQUIRE_EMAIL_VERIFICATION=false TSUMUGI_JWT_SECRET=<32+ chars> ./gradlew :server:run` from the repo root.

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `TSUMUGI_JWT_SECRET` | *(required)* | HMAC secret for access tokens, 32+ characters. Changing it signs everyone out |
| `POSTGRES_PASSWORD` | *(required with compose)* | The Postgres password |
| `TSUMUGI_DB` | `sqlite:./dev.db` | `postgres://user:pass@host:5432/db`, a `jdbc:postgresql://…` URL, or `sqlite:<path>`. Compose sets it for you |
| `TSUMUGI_PORT` | `8080` | HTTP port. With compose, the host port (the container always uses 8080) |
| `TSUMUGI_BIND` | `0.0.0.0` | Compose only: host address the port is published on. `127.0.0.1` behind Caddy or `tailscale serve` |
| `TSUMUGI_DOMAIN` | unset | Compose `tls` profile: the name Caddy gets a certificate for |
| `TSUMUGI_PUBLIC_URL` | `http://localhost:8080` | Base URL used in email-verification links |
| `TSUMUGI_RP_ID` / `TSUMUGI_RP_NAME` / `TSUMUGI_RP_ORIGINS` | `localhost` / `Tsumugi` / `http://localhost:8080` | Passkey relying party: your domain, display name, and comma-separated allowed origins |
| `TSUMUGI_REQUIRE_EMAIL_VERIFICATION` | `true` | Refuse sync, blobs, pack registry and leaderboard (403 `email_unverified`) until the email is confirmed. `make dev` sets `false` |
| `TSUMUGI_VERIFY_TOKEN_TTL_HOURS` | `48` | How long a verification link works |
| `TSUMUGI_VERIFY_RESEND_SECONDS` | `120` | Shortest gap between two verification emails to one account |
| `TSUMUGI_SMTP_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_FROM` | unset | SMTP for verification emails (STARTTLS on 587, TLS on 465). Unset: the link is written to the log |
| `TSUMUGI_MAX_PUSH_CHANGES` | `5000` | Changes per push |
| `TSUMUGI_MAX_BODY_BYTES` | `8388608` | Largest request body, enforced while reading, so chunked uploads are capped too |
| `TSUMUGI_BLOB_QUOTA_BYTES` / `TSUMUGI_MAX_BLOB_BYTES` | 200 MB / 20 MB | Recording/image storage per user / per file |
| `TSUMUGI_RATE_LIMIT_PER_MINUTE` | `600` | Requests per user (per IP when signed out) |
| `TSUMUGI_ALLOW_REGISTRATION` | `true` | Set `false` on a private server once your accounts exist |

## Endpoints

All under `/v1`: `auth/register|login|refresh|logout|verify|verify/resend`, `auth/passkey/register/options|verify`, `auth/passkey/login/options|verify`, `account`, `devices`, `devices/{id}/packs`, `sync/push`, `sync/pull`, `blobs/{id}`, `leaderboard`, `health`. The details are in [docs/SYNC_PROTOCOL.md](../docs/SYNC_PROTOCOL.md).

Details beyond the protocol doc:
- **Errors** are `{"error": "<message>", "code": "<code>"?}`. Codes clients act on: `email_unverified` (403), `resend_too_soon` (429), `already_verified` (409).
- **Push response:** `accepted` counts every valid change in the request, including ones already stored by a retried push, so the client can mark all of them synced.
- **Passkeys:**
  - Options are standard WebAuthn JSON with base64url binary fields, plus a `challengeId` to send back.
  - `verify` takes `{challengeId, credential}`, where `credential` is the `PublicKeyCredential.toJSON()` shape.
  - Only `none` attestation is requested. Challenges are single-use and expire after 5 minutes.
  - A passkey is added to an account that already has an email, so every account has one to verify (D-311).
- **Leaderboard:** `period=day|week|month`. It lists only accounts that opted in and don't use end-to-end encryption. A streak counts consecutive UTC days with reviews. The apps keep the leaderboard switched off for now (D-314).
- **Email verification:** `GET /auth/verify?token=` is the link in the email (plain-text page: 200 confirmed, 410 expired, 404 unknown or used). `POST /auth/verify/resend` (signed in) mails a fresh link and invalidates the old one.
