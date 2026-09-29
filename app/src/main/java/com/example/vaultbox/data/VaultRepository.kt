package com.example.vaultbox.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.vaultbox.autofill.AutofillMatchIndex
import com.example.vaultbox.crypto.CryptoBox
import com.example.vaultbox.crypto.DeviceUnlockKeyStore
import com.example.vaultbox.crypto.EncryptedBytes
import com.example.vaultbox.crypto.MasterPassword
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException

class VaultRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("vault_meta", Context.MODE_PRIVATE)
    private val database = VaultDatabase(appContext)
    private val deviceKeyStore = DeviceUnlockKeyStore(appContext)
    private val autofillIndex = AutofillMatchIndex(appContext)

    var autoLockMillis: Long
        get() = prefs.getLong(KEY_AUTO_LOCK_MILLIS, DEFAULT_AUTO_LOCK_MILLIS)
        set(value) {
            prefs.edit().putLong(KEY_AUTO_LOCK_MILLIS, value).apply()
        }

    var preferredUnlock: String
        get() = prefs.getString(KEY_PREFERRED_UNLOCK, UNLOCK_DEVICE).orEmpty().ifBlank { UNLOCK_DEVICE }
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_UNLOCK, value).apply()
        }

    fun hasVault(): Boolean =
        prefs.contains(KEY_SALT) && prefs.contains(KEY_MASTER_WRAPPED_DATA_KEY_CT)

    fun hasDeviceUnlock(): Boolean =
        prefs.contains(KEY_DEVICE_WRAPPED_DATA_KEY_CT)

    fun canUseDeviceUnlock(): Boolean = deviceKeyStore.canUseDeviceUnlock()

    fun prepareDeviceUnlock() {
        deviceKeyStore.ensureKey()
    }

    fun createVault(masterPassword: CharArray): ByteArray {
        require(masterPassword.size >= MIN_MASTER_PASSWORD_LENGTH) {
            "主密码至少需要 $MIN_MASTER_PASSWORD_LENGTH 位"
        }

        val salt = CryptoBox.randomBytes(MasterPassword.SALT_BYTES)
        val dataKey = CryptoBox.generateDataKey()
        val masterKey = MasterPassword.deriveKey(
            masterPassword,
            salt,
            MasterPassword.DEFAULT_ITERATIONS
        )

        try {
            val wrapped = CryptoBox.encrypt(masterKey, dataKey, AAD_VAULT_DATA_KEY)
            prefs.edit()
                .clear()
                .putInt(KEY_VERSION, 1)
                .putString(KEY_SALT, salt.b64())
                .putInt(KEY_KDF_ITERATIONS, MasterPassword.DEFAULT_ITERATIONS)
                .putString(KEY_MASTER_WRAPPED_DATA_KEY_IV, wrapped.iv.b64())
                .putString(KEY_MASTER_WRAPPED_DATA_KEY_CT, wrapped.cipherText.b64())
                .putLong(KEY_AUTO_LOCK_MILLIS, DEFAULT_AUTO_LOCK_MILLIS)
                .putString(KEY_CATEGORIES, VaultCategory.defaults.joinToString(CATEGORY_SEPARATOR))
                .putString(KEY_PREFERRED_UNLOCK, UNLOCK_DEVICE)
                .apply()
            changeEntries(dataKey) { database.clearAllEntries() }
            return dataKey
        } finally {
            MasterPassword.wipe(masterKey)
        }
    }

    fun unlockWithMaster(masterPassword: CharArray): ByteArray {
        val salt = prefs.getRequiredString(KEY_SALT).b64Decoded()
        val iterations = prefs.getInt(KEY_KDF_ITERATIONS, MasterPassword.DEFAULT_ITERATIONS)
        val masterKey = MasterPassword.deriveKey(masterPassword, salt, iterations)
        return try {
            CryptoBox.decrypt(masterKey, getMasterWrappedDataKey(), AAD_VAULT_DATA_KEY)
                .also { refreshAutofillIndex(it) }
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("主密码不正确", error)
        } finally {
            MasterPassword.wipe(masterKey)
        }
    }

    fun enableDeviceUnlock(dataKey: ByteArray) {
        val wrapped = deviceKeyStore.encrypt(dataKey)
        prefs.edit()
            .putString(KEY_DEVICE_WRAPPED_DATA_KEY_IV, wrapped.iv.b64())
            .putString(KEY_DEVICE_WRAPPED_DATA_KEY_CT, wrapped.cipherText.b64())
            .apply()
    }

    fun createDeviceUnlockEncryptCipher(): Cipher =
        deviceKeyStore.createEncryptCipher()

    fun createDeviceUnlockDecryptCipher(): Cipher {
        check(hasDeviceUnlock()) { "尚未启用指纹解锁" }
        return deviceKeyStore.createDecryptCipher(getDeviceWrappedDataKey())
    }

    fun enableDeviceUnlock(dataKey: ByteArray, cipher: Cipher) {
        val wrapped = deviceKeyStore.finishEncrypt(cipher, dataKey)
        prefs.edit()
            .putString(KEY_DEVICE_WRAPPED_DATA_KEY_IV, wrapped.iv.b64())
            .putString(KEY_DEVICE_WRAPPED_DATA_KEY_CT, wrapped.cipherText.b64())
            .putString(KEY_PREFERRED_UNLOCK, UNLOCK_DEVICE)
            .apply()
    }

    fun unlockWithDevice(): ByteArray {
        check(hasDeviceUnlock()) { "尚未启用设备快速解锁" }
        return deviceKeyStore.decrypt(getDeviceWrappedDataKey()).also { refreshAutofillIndex(it) }
    }

    fun unlockWithDevice(cipher: Cipher): ByteArray {
        check(hasDeviceUnlock()) { "尚未启用指纹解锁" }
        return deviceKeyStore.finishDecrypt(cipher, getDeviceWrappedDataKey()).also { refreshAutofillIndex(it) }
    }

    fun disableDeviceUnlock() {
        prefs.edit()
            .remove(KEY_DEVICE_WRAPPED_DATA_KEY_IV)
            .remove(KEY_DEVICE_WRAPPED_DATA_KEY_CT)
            .apply()
        deviceKeyStore.deleteKey()
    }

    fun categories(): List<String> {
        val stored = prefs.getString(KEY_CATEGORIES, null)
            ?.split(CATEGORY_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return (VaultCategory.defaults + stored).distinct()
    }

    fun addCategory(name: String): List<String> {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "分类名称不能为空" }
        require(cleanName.length <= 24) { "分类名称太长" }
        val next = (categories() + cleanName).distinct()
        prefs.edit().putString(KEY_CATEGORIES, next.joinToString(CATEGORY_SEPARATOR)).apply()
        return next
    }

    fun deleteCategory(name: String, dataKey: ByteArray): Int {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "分类名称不能为空" }
        require(cleanName !in VaultCategory.defaults) { "默认分类不能删除" }
        val existing = categories()
        require(cleanName in existing) { "分类不存在" }

        var movedCount = 0
        listEntrySummaries(dataKey)
            .filter { it.category == cleanName }
            .forEach { summary ->
                getEntry(summary.id, dataKey)?.let { entry ->
                    database.updateEntry(entry.copy(category = VaultCategory.DEFAULT_PASSWORD), dataKey)
                    movedCount += 1
                }
            }

        val next = existing.filter { it != cleanName }
        prefs.edit().putString(KEY_CATEGORIES, next.joinToString(CATEGORY_SEPARATOR)).apply()
        return movedCount
    }

    fun listEntrySummaries(dataKey: ByteArray): List<VaultEntrySummary> =
        database.listEntrySummaries(dataKey)

    fun getEntry(id: Long, dataKey: ByteArray): VaultEntry? =
        database.getEntry(id, dataKey)

    fun saveEntry(entry: VaultEntry, dataKey: ByteArray): Long = changeEntries(dataKey) {
        if (entry.id == 0L) database.insertEntry(entry, dataKey) else {
            database.updateEntry(entry, dataKey)
            entry.id
        }
    }

    fun deleteEntry(id: Long, dataKey: ByteArray) {
        changeEntries(dataKey) { database.deleteEntry(id) }
    }

    private fun refreshAutofillIndex(dataKey: ByteArray) {
        autofillIndex.rebuild { database.listAutofillTargets(dataKey) }
    }

    private fun <T> changeEntries(dataKey: ByteArray, change: () -> T): T =
        synchronized(AutofillMatchIndex.lock) {
            autofillIndex.clear()
            val result = change()
            refreshAutofillIndex(dataKey)
            result
        }

    fun close() {
        database.close()
    }

    private fun getMasterWrappedDataKey(): EncryptedBytes =
        EncryptedBytes(
            iv = prefs.getRequiredString(KEY_MASTER_WRAPPED_DATA_KEY_IV).b64Decoded(),
            cipherText = prefs.getRequiredString(KEY_MASTER_WRAPPED_DATA_KEY_CT).b64Decoded()
        )

    private fun getDeviceWrappedDataKey(): EncryptedBytes =
        EncryptedBytes(
            iv = prefs.getRequiredString(KEY_DEVICE_WRAPPED_DATA_KEY_IV).b64Decoded(),
            cipherText = prefs.getRequiredString(KEY_DEVICE_WRAPPED_DATA_KEY_CT).b64Decoded()
        )

    private fun SharedPreferences.getRequiredString(key: String): String =
        getString(key, null) ?: throw IllegalStateException("保险箱元数据缺失: $key")

    private fun ByteArray.b64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.b64Decoded(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)

    companion object {
        const val MIN_MASTER_PASSWORD_LENGTH = 12
        const val DEFAULT_AUTO_LOCK_MILLIS = 2 * 60 * 1000L
        const val UNLOCK_DEVICE = "device"
        const val UNLOCK_MASTER = "master"
        private val AAD_VAULT_DATA_KEY = "vaultbox:data-key:v1".toByteArray(Charsets.UTF_8)
        private const val CATEGORY_SEPARATOR = "\u001F"

        private const val KEY_VERSION = "version"
        private const val KEY_SALT = "salt"
        private const val KEY_KDF_ITERATIONS = "kdf_iterations"
        private const val KEY_MASTER_WRAPPED_DATA_KEY_IV = "master_wrapped_data_key_iv"
        private const val KEY_MASTER_WRAPPED_DATA_KEY_CT = "master_wrapped_data_key_ct"
        private const val KEY_DEVICE_WRAPPED_DATA_KEY_IV = "device_wrapped_data_key_iv"
        private const val KEY_DEVICE_WRAPPED_DATA_KEY_CT = "device_wrapped_data_key_ct"
        private const val KEY_AUTO_LOCK_MILLIS = "auto_lock_millis"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_PREFERRED_UNLOCK = "preferred_unlock"
    }
}
