package com.example.vaultbox.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceUnlockKeyStore(private val context: Context) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun canUseDeviceUnlock(): Boolean {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        return keyguardManager?.isDeviceSecure == true
    }

    fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }
            .generateKey()
    }

    fun encrypt(data: ByteArray): EncryptedBytes {
        return finishEncrypt(createEncryptCipher(), data)
    }

    fun decrypt(encryptedBytes: EncryptedBytes): ByteArray {
        try {
            return finishDecrypt(createDecryptCipher(encryptedBytes), encryptedBytes)
        } catch (error: KeyPermanentlyInvalidatedException) {
            deleteKey()
            throw error
        }
    }

    fun createEncryptCipher(): Cipher {
        ensureKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getKey())
        }
    }

    fun createDecryptCipher(encryptedBytes: EncryptedBytes): Cipher {
        ensureKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, encryptedBytes.iv))
        }
    }

    fun finishEncrypt(cipher: Cipher, data: ByteArray): EncryptedBytes =
        EncryptedBytes(cipher.iv, cipher.doFinal(data))

    fun finishDecrypt(cipher: Cipher, encryptedBytes: EncryptedBytes): ByteArray =
        cipher.doFinal(encryptedBytes.cipherText)

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) {
            keyStore.deleteEntry(ALIAS)
        }
    }

    private fun getKey(): SecretKey =
        keyStore.getKey(ALIAS, null) as SecretKey

    companion object {
        private const val ALIAS = "VaultBoxDeviceUnlockKey"
    }
}
