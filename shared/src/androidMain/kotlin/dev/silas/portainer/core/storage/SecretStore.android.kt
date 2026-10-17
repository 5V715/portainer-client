package dev.silas.portainer.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Must be called once (e.g. from Application.onCreate) before [createSecretStore]. */
object AndroidPlatform {
    @Volatile
    internal var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

private const val KEY_ALIAS = "portainer_api_keys"
private const val PREFS_NAME = "portainer_secrets"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12

/** AES/GCM with a non-exportable AndroidKeyStore key; ciphertext (IV + data) is base64 in private prefs. */
private class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null // unreadable (e.g. key lost after restore): treat as absent so the user re-enters it
        }
    }

    override suspend fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual fun createSecretStore(): SecretStore {
    val context = AndroidPlatform.appContext
        ?: throw IllegalStateException("AndroidPlatform.init(context) must be called before createSecretStore()")
    return KeystoreSecretStore(context)
}
