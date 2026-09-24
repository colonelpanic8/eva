package com.colonelpanic.eva.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets file encrypted with a non-exportable Android Keystore key. The
 * preferences file is excluded from backup and device transfer; a restored copy
 * would be undecryptable anyway because the key never leaves this device.
 */
class SecretStore(
    context: Context,
    private val key: () -> SecretKey = ::keystoreKey,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun write(
        name: String,
        value: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        prefs.edit { putString(name, stored) }
    }

    fun clear(name: String) {
        prefs.edit { remove(name) }
    }

    private companion object {
        const val FILE = "eva.secrets"
        const val ALIAS = "eva.secrets"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
