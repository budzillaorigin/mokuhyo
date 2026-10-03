# Lexicon update packages (format `mokuhyo-lexicon/1`)

A lexicon package updates one topic track (e.g. *Counter-UAS & Base Defense*) for one language without a new app
release (BRIEF_PHASE8 C-04, §B.3). Built and signed by `tools/release/lexicon.py` from the same track source the app
ships (`tools/tracks/<domain>.<lang>.json`), so the shipped track and the update channel share one source of truth.
Implementation in the app: `shared/.../lexicon/LexiconPackage.kt` (parse, canonical JSON, delta),
`shared/jvmMain/.../lexicon/LexiconVerifier.kt` (Ed25519), `shared/.../lexicon/LexiconRepository.kt` (import, merge).

## File

`lexicon-<lang>-<domain>-<version>.json`, UTF-8 JSON:

```json
{
 "format": "mokuhyo-lexicon/1",
 "manifest": {
  "id": "lexicon-ja-cuas-base-defense", "lang": "ja", "domain": "cuas-base-defense", "version": "1.1.0",
  "created": "2026-10-04T00:00:00Z", "publisher": "Mokuhyo project",
  "sha256": "<hex SHA-256 of the canonical JSON of terms>", "terms": 402, "previousVersion": "1.0.0"
 },
 "attribution": "…",
 "terms": [ { …TrackTerm… } ],
 "signature": { "alg": "ed25519", "keyId": "4c3f54aa7b5109a4", "value": "<base64 signature>" }
}
```

- `terms` is the **full** term set of the track for that language, not a diff; the app computes the delta.
- A term is the track's term object (`docs/CONTENT_PACKS.md`, "Topic tracks"): `id` (the seed id, stable across
  versions), `domain`, `priority`, `termEn`, `acronym`, `definitionEn` + `definitionEnSource {doc, page, sourceId}`
  (US public domain, or `{"doc": "authored"}`), `term`, `termKind` (native | calque | loanword | acronym),
  `radioEnglish`, `equivalents[] {text, kind, source, page, verified}`, `definition`, `status`
  (draft | checked | approved), `badge`, `registerNote`, `examples[] {text, english}`, `collocations[]`, `source`,
  `verified`. Unknown fields are ignored by older apps.
- `version` is dotted numeric (`1.10.0` > `1.9.2`). `previousVersion` is informational.
- Packages carry **no floating-point numbers** (canonicalization is defined for integers only).

## Canonical JSON and the signature
Canonical JSON = keys sorted by Unicode code point, no whitespace, UTF-8, non-ASCII characters literal, strings
escaped as `\"`, `\\`, `\n`, `\r`, `\t`, `\b`, `\f` and `\u00xx` for other control characters; integers, strings,
booleans, null, arrays, objects. (Python: `json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))`.)

- `manifest.sha256` = SHA-256 of the canonical JSON of `terms`.
- `signature.value` = Ed25519 over the canonical JSON of the whole object **without** its `signature` member
  (so it covers the manifest, the attribution and the terms).
- `signature.keyId` = the first 16 hex digits of SHA-256 of the raw 32-byte public key.

## Trust
The app trusts the keys in `tools/release/keys/lexicon-ed25519.pub.json` (shipped inside the app jar as
`keys/lexicon-ed25519.pub.json`). The private key is generated once by `lexicon.py keygen` into
`~/.mokuhyo-keys/lexicon-ed25519.key` (or `$MOKUHYO_LEXICON_KEY`) and is **never committed**.

| Package | What the app does |
|---|---|
| terms don't hash to `manifest.sha256` | refused ("damaged or altered") |
| signed by a shipped key, signature verifies | imported; labelled "Verified publisher" |
| signed, but the signature fails, or an unknown key | refused |
| no `signature` | the learner is warned and may import it anyway; labelled "unverified publisher" everywhere it shows |

## Import, merge and review
- Settings → Content → *Import lexicon update*: from a file, or from a URL the learner types (one HTTPS GET, connect
  5 s / request 120 s, at most 20 MB, cancellable; the only time this feature goes online).
- Stored append-only in the learner database (`lexicon_package`, `lexicon_term`; schema 2). Re-importing the same
  `id@version` adds nothing.
- Merge key `(lang, domain, termId)`: the highest imported version is current unless the app's shipped track has a
  higher version; current terms overlay the shipped track by id and new ids are appended.
- The delta is computed against what was current at import (previous package, else the shipped track): **added**,
  **changed** (any field differs), **removed** (no longer listed; the app keeps showing the shipped term).
- Added terms are queued into Review as new FSRS cards (kind `TERM`, context "lexicon update <version>"); changed and
  removed terms are listed in Lexicon → *What's new*.
- Imported packages travel in the `.mokuhyo` backup.

## Publishing (owner)
```
cd tools
uv run --group release python release/lexicon.py build --language ja --version 1.1.0 --previous 1.0.0
uv run --group release python release/lexicon.py verify ../dist/lexicon/lexicon-ja-cuas-base-defense-1.1.0.json
gh release upload <tag> ../dist/lexicon/lexicon-*.json      # learners import by file or URL
```
