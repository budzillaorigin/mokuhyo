# Privacy

Mokuhyo works entirely on your computer. There are no accounts, no telemetry, no analytics and no ads.

## What goes over the network (and only when you ask)
| When | Where | What |
|---|---|---|
| You download an AI or speech model (first run or Settings) | huggingface.co | An HTTPS download of the model file you chose. Nothing about you is sent. |
| You turn on "Check for updates" (off by default) | api.github.com | Once a day, a request for the latest release of Mokuhyo. |
| You press "Look for Ollama" in Settings → AI | localhost:11434 only | A request to an Ollama server on this computer. Never the network. |
| You type a URL in Settings → Content → "Import lexicon update" and press Download | the address you typed | One HTTPS download of that lexicon package (at most 20 MB). Nothing about you is sent. Importing from a file needs no network. |

Everything else — reading, listening, interviews, recordings, transcripts, ratings, review, reports — happens on this
computer with no network connection.

## Where your data lives
- Windows: `%APPDATA%\Mokuhyo` · macOS: `~/Library/Application Support/Mokuhyo` · Linux: `~/.local/share/mokuhyo`
- `db/` your history, review queue and settings (SQLite); `recordings/` your spoken answers (WAV); `models/`
  downloaded models; `logs/`.
- Recordings and transcripts never leave the computer unless you export a backup or a PDF report yourself.

## Backups and reports
- A `.mokuhyo` backup contains your history, recordings, review queue and learner settings. You choose where to save it;
  you can protect it with a passphrase (Argon2id + XChaCha20-Poly1305). Mokuhyo has no copy of the passphrase and can't
  recover it.
- The PDF report contains what you select (transcripts are off by default).

## Audio watermark in pre-rendered clips

Listening clips rendered with Chatterbox Multilingual (BRIEF_PHASE8 N-00; owner decision D-041) carry Resemble AI's
PerTh watermark. It is an inaudible mark that identifies the audio as AI-generated, embedded by the model at render
time on the build machine. It carries no information about you; nothing in the app adds or reads it, and your own
recordings never pass through Chatterbox.

## Deleting
Deleting an item from History hides it everywhere in the app (it stays in the database as a marked-deleted record so
backups merge correctly). To remove everything, quit Mokuhyo and delete the data folder above.
