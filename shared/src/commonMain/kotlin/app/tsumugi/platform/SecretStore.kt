package app.tsumugi.platform

/**
 * Small string secrets (API tokens for WaniKani, Bunpro, Notion, custom LLM endpoints). CLAUDE.md rule 6:
 * secrets live only in the platform keychain/keystore and never leave the device except to their own service.
 * Integration code depends on this interface so tests can use an in-memory fake.
 */
interface Secrets {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Keychain (iOS) / Android Keystore–backed [Secrets]. Android: `SecretStore(context)`, iOS: `SecretStore()`. */
expect class SecretStore : Secrets {
    override fun get(key: String): String?
    override fun put(key: String, value: String)
    override fun remove(key: String)
}
