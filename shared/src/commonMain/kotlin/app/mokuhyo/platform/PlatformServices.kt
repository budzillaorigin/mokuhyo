package app.mokuhyo.platform

import okio.Path

/** Bytes free on the volume holding [path] (or its nearest existing parent); null when unknown. */
expect fun freeBytes(path: Path): Long?

/** Unicode NFC normalization: text is stored as NFC, never blindly NFKC. */
expect fun normalizeNfc(text: String): String

/** Unicode NFD normalization (used to strip combining marks for accent-insensitive comparison). */
expect fun normalizeNfd(text: String): String
