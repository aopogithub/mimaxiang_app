package com.example.vaultbox.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedBytes(
    val iv: ByteArray,
    val cipherText: ByteArray
)

object CryptoBox {
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private val random = SecureRandom()

    fun generateDataKey(): ByteArray = randomBytes(AES_KEY_BITS / 8)

    fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plainText: ByteArray, aad: ByteArray? = null): EncryptedBytes {
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return EncryptedBytes(iv, cipher.doFinal(plainText))
    }

    fun decrypt(key: ByteArray, encryptedBytes: EncryptedBytes, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, encryptedBytes.iv)
        )
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(encryptedBytes.cipherText)
    }
}
