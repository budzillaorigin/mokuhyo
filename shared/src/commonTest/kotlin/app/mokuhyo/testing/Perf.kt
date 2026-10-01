package app.mokuhyo.testing

/**
 * Multiplier for wall-clock budgets in tests. Kotlin/Native test binaries are unoptimized debug builds, so the
 * iOS simulator gets a looser budget; release performance is checked on a device (docs/QA.md).
 */
expect val perfScale: Int
