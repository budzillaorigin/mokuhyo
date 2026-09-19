# Tsumugi privacy policy

*Draft for the App Store and Google Play listings (BRIEF_V2 Phase 14, D-317). Last updated 2026-09-19. **Owner:** fill in the contact line, publish this at a stable URL (for example a page in the public repository or on your own site), and put that URL in App Store Connect and the Play Console. Re-read it whenever a feature that uses the network is added.*

Tsumugi is a Japanese-learning app that works offline. It has no accounts of its own, no ads, no analytics and no tracking. The developer runs no server that receives your data.

## What stays on your device

Everything you create or do in the app is stored only on your phone or tablet, in the app's private storage:

- your reviews, study progress, notes, word lists, exam attempts, writing drafts and settings;
- recordings you make in speaking and shadowing practice, and pictures you add to cards;
- documents, subtitles, EPUBs and feeds you import into the reader;
- downloaded AI models and audio packs.

The AI features (the tutor, grammar correction, speech recognition, pronunciation scoring) run on the device by default. What you say or type to them is processed on the device and isn't sent anywhere.

You can export all of your data (JSON and Anki `.apkg`) from Me → Import & export, and deleting the app deletes it.

## When the app uses the network

Tsumugi connects to the internet only for features you turn on or start yourself. Each connection goes only to the service it's for.

| Feature | Where the data goes | What is sent |
|---|---|---|
| **Sync** (optional, off until you sign in) | The sync server **you** choose: one you run yourself or one a friend or family member runs. There is no public Tsumugi server. | Your email address and password (to create the account and sign in), a device name, and your study data: reviews, progress, notes, lists and settings. With an end-to-end passphrase, the study data is encrypted on the device and the server can't read it. |
| **Recordings sync** (optional, off by default, per device) | The same sync server | Your recordings and card pictures, encrypted on the device if end-to-end encryption is on. |
| **Your own AI server** (optional) | The OpenAI-compatible, Whisper or VOICEVOX server whose address you enter (for example Ollama or LM Studio on your computer) | The text or audio of the request you make, and the API key you entered for that server. |
| **Model and audio downloads** | Hugging Face (models), or the address you enter for audio packs | An ordinary download request. Nothing about you or your study data. |
| **WaniKani import** (optional) | WaniKani | Your WaniKani API token, to read your progress. |
| **Notion export** (optional) | Notion | Your Notion token and the items or daily stats you choose to push. |
| **AnkiConnect** (optional) | The Anki desktop app on your own network | The cards you choose to send. |
| **Reader imports and feeds** | The web page, Aozora Bunko or the RSS feed you open | An ordinary web request. |
| **Immersion Kit examples** (optional, off by default) | Immersion Kit | The word you look up. Results aren't stored. |

API keys and tokens (sync, WaniKani, Notion, your AI servers) are kept in the system keychain (iOS) or the Android Keystore, and sent only to the service they belong to.

## Platform services

- **Speech recognition.** When you use the phone's built-in speech recognizer instead of the on-device Whisper model, the app asks it to work offline. On iOS, Tsumugi requires on-device recognition. On Android, whether the system recognizer (for example Google's) stays offline is up to that recognizer and its own privacy policy.
- **Text recognition (Android only).** Scan text uses Google's ML Kit text-recognition library, which runs on the device; the images aren't sent anywhere. Google states that ML Kit sends Google diagnostic and usage information (device and app information, a per-installation identifier, performance metrics, and the size of the input and output, not the image or the text) to improve the library. On iOS, text recognition uses Apple's Vision framework on the device.
- **Backups.** Your device's own backup (iCloud or Google) can include your study data, under your account with Apple or Google. Downloaded models, content packs and stored keys are left out.

## The sync server

If you use sync, whoever runs the server you sign in to (you, or the friend or family member who gave you the address) holds your email address, a hash of your password, your devices' names and the study data described above. They're responsible for it, and the server software keeps it only for syncing. The server sends one email, to confirm your address; it needs that confirmation before it syncs. To delete your synced data, sign out and ask the server's operator to delete your account, or, on your own server, delete it yourself.

## Children

Tsumugi doesn't collect personal information from anyone, children included, unless they choose to set up sync with a server someone runs for them.

## Changes

If a future version changes what leaves your device, this policy will be updated first, and the change will be described in the release notes.

## Contact

**Owner:** add a contact email or address here.
