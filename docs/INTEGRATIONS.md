# Integrations

The optional online integrations: what each one sends, where it goes, and where its secret lives. All of them are off until the learner sets them up. None is needed for any feature (rule 1), and each secret goes only to the service it belongs to (rules 6 and 14). The decisions are D-119 (Notion, AnkiConnect, Bunpro) and D-111 (recordings sync).

| Integration | Direction | Host | Secret (keychain key) | Config (where) |
|---|---|---|---|---|
| WaniKani | import | `api.wanikani.com` | `wanikani.token` | `integration` row |
| Bunpro | import from a CSV/TSV export file | none (file) | none | none |
| Notion | push | `api.notion.com` only | `notion.token` | `integration` row `NOTION` (database ids) |
| AnkiConnect | push | the learner's desktop, e.g. `http://<lan-ip>:8765` | `ankiconnect.key` (optional) | device settings `ankiconnect.*` (LAN address, rule 16) |
| Recordings sync | both ways | the learner's own sync server (`/v1/blobs`) | the sync tokens | device setting `sync.recordings` (off by default) |

## Notion

Create an internal integration at notion.so/my-integrations, share the database(s) with it, and paste the token and database links in Settings → Integrations → Notion. The app (`NotionExport.connect`) checks that each database has the properties below with the right types before it stores anything. Only `https://api.notion.com` ever receives the token (`NotionClient.BASE_URL`, API version `2022-06-28`). Requests are spaced 350 ms apart (Notion allows about 3 per second), and a 429 waits for `Retry-After`.

Rows are matched on **Tsumugi ID**, so pushing again updates the existing rows instead of adding duplicates. Property names are case-sensitive.

### Items database (`pushItems`, `AppGraph.pushItemsToNotion`)

| Property | Notion type | Value |
|---|---|---|
| Name | title | the item's text (水, 食べる, a mined sentence) |
| Tsumugi ID | rich_text | the item id (`k:水`, `jmdict:1358280`, `clip:…`) |
| Reading | rich_text | kana reading (empty for kana-only items) |
| Meaning | rich_text | meanings joined with "; " |
| Kind | select | Kanji, Vocabulary, Grammar, Listening, Card, Kana, … |
| Stage | select | Apprentice, Guru, Master, Enlightened, Burned, or "Not started" |
| Level | number | kanji-path level (empty when none) |
| JLPT | select | N5…N1 (empty when none) |
| Source | select | pack, user, wanikani, anki, verified, or "AI-generated" for LLM content |

### Daily stats database (`pushStats`, `AppGraph.pushStatsToNotion`)

One row per day with answers, from the materialized `daily_stats` (D-046), in the device's time zone.

| Property | Notion type | Value |
|---|---|---|
| Name | title | the date, `2026-09-18` |
| Tsumugi ID | rich_text | `day:2026-09-18` |
| Date | date | the date |
| Reviews | number | answers that day (lesson introductions and undone answers aren't counted) |
| Accuracy | number | correct ÷ graded, 0–1 (empty when nothing was graded) |
| Streak | number | consecutive study days ending that day |
| Minutes | number | answer time, rounded to minutes |

The integration only writes to Notion. It never reads pages back or imports anything.

## AnkiConnect (desktop Anki over the LAN)

Install the AnkiConnect add-on in desktop Anki. To reach it from the phone, set its `webBindAddress` to `0.0.0.0` (and optionally an `apiKey`), then enter the desktop's address in Settings → Integrations → Anki. `AnkiConnectPush.configure` checks that Anki answers (`version`) and that the chosen note type has the front and back fields (`modelFieldNames`). The defaults are the "Basic" note type with the fields "Front" and "Back", in the deck `Tsumugi::Mined`.

Pushed notes are the learner's mined items (`source = user`: reader words, media clips, personal cards):

| Anki | From Tsumugi |
|---|---|
| Front | the word; the mined sentence below it, when there is one; a clip's audio as `[sound:…]` (sent as base64, stored in Anki's media folder) |
| Back | reading, meanings, "AI-generated" for LLM content |
| Tags | `tsumugi`, `tsumugi::<kind>`, `tsumugi-id::<item id>` |

Notes are added with `allowDuplicate: false`, so pushing again reports duplicates instead of adding copies. Timeouts are 3 s to connect and 15 s per request: a desktop that doesn't answer in time is asleep or off.

## Bunpro

Bunpro publishes no official, documented public API (checked 2026-09-18). An API-key setting exists, but the only references are community reverse-engineering projects, which could break at any time and might go against Bunpro's terms. Importing Bunpro progress stays with its **CSV/TSV export**: Settings → Import → Bunpro, handled by `BunproImporter` (BRIEF §9.2). Only which grammar points you know and your SRS levels are imported, never Bunpro's explanations. If Bunpro ships a documented API, a client can take the place of the file step without changing how points are matched.

## Recordings and pictures (opt-in blob sync)

Off by default, per device. When it's on, the learner's recordings and personal-card pictures are uploaded to their own sync server's blob store, sealed when end-to-end encryption is on. Each device publishes a small manifest, and the other devices download what's missing. Protocol details: D-111 and docs/SYNC_PROTOCOL.md "Blobs".
