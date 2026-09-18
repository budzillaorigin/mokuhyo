package app.tsumugi.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with an AES-256-GCM key that never leaves the Android Keystore; ciphertexts are kept in
 * a private SharedPreferences file as "base64(iv):base64(ciphertext)".
 */
actual class SecretStore(context: Context) : Secrets {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    actual override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val (iv, ciphertext) = stored.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // key was reset (e.g. device credential change): treat as missing so the user re-enters it
        }
    }

    actual override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    actual override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "app.tsumugi.secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "app.tsumugi.secrets.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
