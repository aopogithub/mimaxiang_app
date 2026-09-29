package com.example.vaultbox.data

data class VaultEntry(
    val id: Long = 0,
    val title: String,
    val category: String = VaultCategory.DEFAULT_PASSWORD,
    val username: String,
    val password: String,
    val website: String,
    val appPackage: String = "",
    val notes: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
