package com.example.vaultbox.data

data class VaultEntrySummary(
    val id: Long,
    val title: String,
    val category: String,
    val username: String,
    val website: String,
    val appPackage: String,
    val updatedAt: Long
)
