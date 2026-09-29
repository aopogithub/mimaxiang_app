package com.example.vaultbox.data

data class RemoteSyncConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val remotePath: String = "vaultbox"
)
