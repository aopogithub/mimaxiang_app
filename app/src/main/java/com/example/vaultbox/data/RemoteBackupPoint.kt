package com.example.vaultbox.data

data class RemoteBackupPoint(
    val fileName: String,
    val remotePath: String,
    val timestamp: Long,
    val label: String
)
