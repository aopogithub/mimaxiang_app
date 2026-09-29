package com.example.vaultbox.crypto

import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object MasterPassword {
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun wipe(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0)
    }

    fun wipe(chars: CharArray?) {
        if (chars != null) Arrays.fill(chars, '\u0000')
    }
}
