package com.example.vaultbox.data

import android.content.Context
import com.example.vaultbox.autofill.AutofillMatchIndex
import com.example.vaultbox.crypto.LocalSecretStore
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Vector
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RemoteSyncRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("remote_sync", Context.MODE_PRIVATE)
    private val localSecretStore = LocalSecretStore(appContext)

    fun config(): RemoteSyncConfig =
        RemoteSyncConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 22),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            remotePath = prefs.getString(KEY_REMOTE_PATH, DEFAULT_REMOTE_DIR).orEmpty().ifBlank { DEFAULT_REMOTE_DIR }
        )

    fun password(): String =
        runCatching { localSecretStore.getSecret(KEY_PASSWORD) }.getOrDefault("")

    fun saveConfig(config: RemoteSyncConfig, password: String) {
        require(config.host.isNotBlank()) { "主机不能为空" }
        require(config.username.isNotBlank()) { "用户名不能为空" }
        require(config.port in 1..65535) { "端口不正确" }
        require(config.remotePath.isNotBlank()) { "远程备份目录不能为空" }

        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_REMOTE_PATH, config.remotePath.trim())
            .apply()
        if (password.isNotEmpty()) {
            localSecretStore.putSecret(KEY_PASSWORD, password)
        }
    }

    fun hasConfig(): Boolean =
        config().host.isNotBlank() && config().username.isNotBlank() && password().isNotBlank()

    fun uploadBackup(): String {
        val config = config()
        val password = password()
        require(hasConfig()) { "请先配置远程同步" }
        val timestamp = System.currentTimeMillis()
        val backupFile = createBackupFile(timestamp)
        val remoteDir = config.remoteDirectory()
        val remoteFile = "$remoteDir/${backupFile.name}"
        val latestFile = "$remoteDir/$LATEST_BACKUP_NAME"
        connect(config, password).use { client ->
            client.sftp.ensureRemoteDirectory(remoteDir)
            FileInputStream(backupFile).use { input ->
                client.sftp.put(input, remoteFile)
            }
            FileInputStream(backupFile).use { input ->
                client.sftp.put(input, latestFile)
            }
            client.sftp.applyRetention(remoteDir)
        }
        return "已上传：${backupFile.name}"
    }

    fun listBackups(): List<RemoteBackupPoint> {
        val config = config()
        val password = password()
        require(hasConfig()) { "请先配置远程同步" }
        val remoteDir = config.remoteDirectory()
        return connect(config, password).use { client ->
            client.sftp.listBackupPoints(remoteDir)
        }
    }

    fun downloadBackup(point: RemoteBackupPoint): String {
        val config = config()
        val password = password()
        require(hasConfig()) { "请先配置远程同步" }
        val downloadFile = File(appContext.cacheDir, "vaultbox-remote-restore.zip")
        connect(config, password).use { client ->
            FileOutputStream(downloadFile).use { output ->
                client.sftp.get(point.remotePath, output)
            }
        }
        restoreBackup(downloadFile)
        return "已恢复：${point.label}"
    }

    private fun createBackupFile(timestampMillis: Long): File {
        val timestamp = FILE_FORMAT.format(Date(timestampMillis))
        val backupFile = File(appContext.cacheDir, "backup-$timestamp.zip")
        ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
            addFile(zip, appContext.getDatabasePath(DB_NAME), DB_NAME)
            addFile(zip, File(appContext.applicationInfo.dataDir, "shared_prefs/vault_meta.xml"), "vault_meta.xml")
        }
        return backupFile
    }

    private fun restoreBackup(backupFile: File) = synchronized(AutofillMatchIndex.lock) {
        AutofillMatchIndex(appContext).clear()
        val dbFile = appContext.getDatabasePath(DB_NAME)
        val prefsFile = File(appContext.applicationInfo.dataDir, "shared_prefs/vault_meta.xml")
        dbFile.parentFile?.mkdirs()
        prefsFile.parentFile?.mkdirs()

        ZipInputStream(backupFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    DB_NAME -> FileOutputStream(dbFile).use { zip.copyTo(it) }
                    "vault_meta.xml" -> FileOutputStream(prefsFile).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun connect(config: RemoteSyncConfig, password: String): SftpClient {
        val jsch = JSch()
        val session = jsch.getSession(config.username, config.host, config.port).apply {
            setPassword(password)
            setConfig(Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password")
            })
            timeout = 15_000
            connect(15_000)
        }
        val sftp = (session.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }
        return SftpClient(session, sftp)
    }

    private fun ChannelSftp.ensureRemoteDirectory(path: String) {
        if (path.isBlank()) return
        var current = if (path.startsWith("/")) "/" else ""
        path.split('/').filter { it.isNotBlank() }.forEach { part ->
            current = if (current == "/" || current.isBlank()) "$current$part" else "$current/$part"
            runCatching { mkdir(current) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ChannelSftp.listBackupPoints(remoteDir: String): List<RemoteBackupPoint> {
        val entries = ls(remoteDir) as Vector<ChannelSftp.LsEntry>
        return entries.mapNotNull { entry ->
            val timestamp = entry.filename.backupTimestamp() ?: return@mapNotNull null
            RemoteBackupPoint(
                fileName = entry.filename,
                remotePath = "$remoteDir/${entry.filename}",
                timestamp = timestamp,
                label = DISPLAY_FORMAT.format(Date(timestamp))
            )
        }.sortedByDescending { it.timestamp }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ChannelSftp.applyRetention(remoteDir: String) {
        val backups = listBackupPoints(remoteDir)
        val keep = retentionKeepSet(backups).map { it.fileName }.toSet()
        backups.filterNot { it.fileName in keep }.forEach { point ->
            runCatching { rm(point.remotePath) }
        }
    }

    private fun retentionKeepSet(backups: List<RemoteBackupPoint>): Set<RemoteBackupPoint> {
        val now = System.currentTimeMillis()
        val cutoff = now - 30L * 24L * 60L * 60L * 1000L
        val keep = linkedSetOf<RemoteBackupPoint>()
        keep += backups.filter { it.timestamp >= cutoff }
            .groupBy { DAY_FORMAT.format(Date(it.timestamp)) }
            .values
            .mapNotNull { day -> day.maxByOrNull { it.timestamp } }
        keep += backups.filter { it.timestamp < cutoff }
            .groupBy { MONTH_FORMAT.format(Date(it.timestamp)) }
            .values
            .mapNotNull { month -> month.maxByOrNull { it.timestamp } }
        return keep
    }

    private fun RemoteSyncConfig.remoteDirectory(): String =
        remotePath.trim().trimEnd('/').ifBlank { DEFAULT_REMOTE_DIR }

    private fun String.backupTimestamp(): Long? {
        if (!startsWith("backup-") || !endsWith(".zip")) return null
        val text = removePrefix("backup-").removeSuffix(".zip")
        return runCatching { FILE_FORMAT.parse(text)?.time }.getOrNull()
    }

    private class SftpClient(
        val session: Session,
        val sftp: ChannelSftp
    ) : AutoCloseable {
        override fun close() {
            runCatching { sftp.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    companion object {
        private const val DB_NAME = "vaultbox.db"
        private const val DEFAULT_REMOTE_DIR = "vaultbox"
        private const val LATEST_BACKUP_NAME = "latest.zip"
        private val FILE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val DISPLAY_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        private val MONTH_FORMAT = SimpleDateFormat("yyyyMM", Locale.US)
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMOTE_PATH = "remote_path"
    }
}
