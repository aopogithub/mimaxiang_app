package com.example.vaultbox.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalSecretStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_secrets", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun putSecret(name: String, value: String) {
        ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${name}_iv", cipher.iv.b64())
            .putString("${name}_ct", cipherText.b64())
            .apply()
    }

    fun getSecret(name: String): String {
        val iv = prefs.getString("${name}_iv", null)?.b64Decoded() ?: return ""
        val cipherText = prefs.getString("${name}_ct", null)?.b64Decoded() ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    fun removeSecret(name: String) {
        // Invalidation must reach disk before a vault mutation or restore replaces its data.
        prefs.edit().remove("${name}_iv").remove("${name}_ct").commit()
    }

    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    private fun getKey(): SecretKey =
        keyStore.getKey(ALIAS, null) as SecretKey

    private fun ByteArray.b64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.b64Decoded(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val ALIAS = "VaultBoxLocalSecretStore"
    }
}
