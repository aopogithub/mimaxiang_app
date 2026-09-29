package com.example.vaultbox.data

import java.security.SecureRandom

object PasswordGenerator {
    private val random = SecureRandom()
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    fun generate(
        length: Int = 20,
        includeSymbols: Boolean = true
    ): String {
        val groups = mutableListOf(LOWER, UPPER, DIGITS)
        if (includeSymbols) groups += SYMBOLS

        val safeLength = length.coerceIn(groups.size, 64)
        val chars = mutableListOf<Char>()
        groups.forEach { chars += it.randomChar() }
        val allChars = groups.joinToString(separator = "")
        while (chars.size < safeLength) {
            chars += allChars.randomChar()
        }
        chars.shuffleWithSecureRandom()
        return chars.joinToString(separator = "")
    }

    private fun String.randomChar(): Char = this[random.nextInt(length)]

    private fun MutableList<Char>.shuffleWithSecureRandom() {
        for (index in lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val value = this[index]
            this[index] = this[swapIndex]
            this[swapIndex] = value
        }
    }
}
