package com.example.vaultbox.autofill

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import com.example.vaultbox.crypto.LocalSecretStore
import com.example.vaultbox.crypto.MasterPassword
import com.example.vaultbox.data.RemoteSyncRepository
import com.example.vaultbox.data.VaultEntry
import com.example.vaultbox.data.VaultRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("DEPRECATION")
class AutofillMatchIndexTest : InstrumentationTestCase() {
    private lateinit var sandbox: TestContext
    private lateinit var repository: VaultRepository
    private lateinit var key: ByteArray
    private lateinit var index: AutofillMatchIndex
    private val master = "autofill test password only".toCharArray()

    override fun setUp() {
        super.setUp()
        sandbox = TestContext(instrumentation.targetContext)
        repository = VaultRepository(sandbox)
        key = repository.createVault(master)
        index = AutofillMatchIndex(sandbox)
    }

    override fun tearDown() {
        repository.close()
        MasterPassword.wipe(key)
        MasterPassword.wipe(master)
        sandbox.cleanUp()
        super.tearDown()
    }

    fun testMissingEmptyAndUnknownTargetsStaySilent() {
        assertFalse(index.hasMatch("com.example.app", "example.com"))
        save()
        assertFalse(index.hasMatch(null, null))
        assertFalse(index.hasMatch("com.example.unknown", null))
        assertFalse(index.hasMatch("com.example.app", "other.com"))
        index.clear()
        assertFalse(index.hasMatch("com.example.app", "example.com"))
    }

    fun testMatchesSurviveClosingVaultAndCreatingNewIndexInstance() {
        save()
        repository.close()
        MasterPassword.wipe(key)
        val lockedIndex = AutofillMatchIndex(sandbox)
        assertTrue(lockedIndex.hasMatch("com.android.chrome", "www.example.com"))
        assertTrue(lockedIndex.hasMatch("com.example.app", null))
        assertFalse(lockedIndex.hasMatch("com.android.chrome", "other.com"))
    }

    fun testEditingAndDeletingRemoveOldMatches() {
        val id = save()
        val entry = repository.getEntry(id, key)!!
        repository.saveEntry(entry.copy(website = "new.example", appPackage = "com.example.new"), key)
        assertFalse(index.hasMatch(null, "example.com"))
        assertFalse(index.hasMatch("com.example.app", null))
        assertTrue(index.hasMatch(null, "new.example"))
        assertTrue(index.hasMatch("com.example.new", null))
        repository.deleteEntry(id, key)
        assertFalse(index.hasMatch(null, "new.example"))
        assertFalse(index.hasMatch("com.example.new", null))
    }

    fun testDeletingOneOfSeveralMatchesPreservesRemainingMatch() {
        val first = save()
        val second = save()
        repository.deleteEntry(first, key)
        assertTrue(index.hasMatch(null, "example.com"))
        repository.deleteEntry(second, key)
        assertFalse(index.hasMatch(null, "example.com"))
    }

    fun testBlankPasswordAndUnassociatedEntriesAreNotIndexed() {
        save(password = " ")
        save(website = "", appPackage = "")
        assertFalse(index.hasMatch(null, "example.com"))
        assertFalse(index.hasMatch("com.example.app", null))
        val id = save()
        repository.saveEntry(repository.getEntry(id, key)!!.copy(password = ""), key)
        assertFalse(index.hasMatch(null, "example.com"))
    }

    fun testSuccessfulMasterUnlockRebuildsMissingIndex() {
        save()
        index.clear()
        assertFalse(index.hasMatch(null, "example.com"))
        val unlocked = repository.unlockWithMaster(master)
        try {
            assertTrue(index.hasMatch(null, "example.com"))
        } finally {
            MasterPassword.wipe(unlocked)
        }
    }

    fun testIncorrectMasterPasswordDoesNotRebuildIndex() {
        save()
        index.clear()
        val wrong = "this is the wrong password".toCharArray()
        try {
            assertTrue(runCatching { repository.unlockWithMaster(wrong) }.isFailure)
            assertFalse(index.hasMatch(null, "example.com"))
        } finally {
            MasterPassword.wipe(wrong)
        }
    }

    fun testIndexStoresOnlyEncryptedRoutingMetadata() {
        save()
        val raw = sandbox.getSharedPreferences("local_secrets", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("example.com"))
        assertFalse(raw.contains("com.example.app"))
        val metadata = LocalSecretStore(sandbox).getSecret("autofill_match_index_v1")
        assertTrue(metadata.contains("example.com"))
        assertFalse(metadata.contains("private account"))
        assertFalse(metadata.contains("private password"))
        assertFalse(metadata.contains("private title"))
    }

    fun testCorruptIndexAndFailedRebuildFailClosed() {
        save()
        sandbox.getSharedPreferences("local_secrets", Context.MODE_PRIVATE).edit()
            .putString("autofill_match_index_v1_ct", "invalid ciphertext").commit()
        assertFalse(index.hasMatch(null, "example.com"))
        LocalSecretStore(sandbox).putSecret("autofill_match_index_v1", "not JSON")
        assertFalse(index.hasMatch(null, "example.com"))
        index.rebuild { error("unreadable entry") }
        assertFalse(index.hasMatch(null, "example.com"))
    }

    fun testIndexFailureDoesNotPreventValidMasterUnlock() {
        save()
        sandbox.openOrCreateDatabase("vaultbox.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE entries SET password_ct = X'00'")
        }
        val unlocked = repository.unlockWithMaster(master)
        try {
            assertTrue(key.contentEquals(unlocked))
            assertFalse(index.hasMatch(null, "example.com"))
        } finally {
            MasterPassword.wipe(unlocked)
        }
    }

    fun testNewVaultClearsOldMatches() {
        save()
        val replacementKey = repository.createVault(master)
        try {
            assertFalse(index.hasMatch(null, "example.com"))
        } finally {
            MasterPassword.wipe(replacementKey)
        }
    }

    fun testRestoreInvalidatesIndexUntilNextUnlock() {
        save()
        repository.close()
        val backup = File(sandbox.root, "restore-test.zip")
        ZipOutputStream(backup.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("vaultbox.db"))
            sandbox.getDatabasePath("vaultbox.db").inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val restore = RemoteSyncRepository::class.java.getDeclaredMethod("restoreBackup", File::class.java)
        restore.isAccessible = true
        restore.invoke(RemoteSyncRepository(sandbox), backup)
        assertFalse(index.hasMatch(null, "example.com"))
        repository = VaultRepository(sandbox)
        val unlocked = repository.unlockWithMaster(master)
        try {
            assertTrue(index.hasMatch(null, "example.com"))
        } finally {
            MasterPassword.wipe(unlocked)
        }
    }

    private fun save(
        website: String = "https://www.example.com/login",
        appPackage: String = "com.example.app",
        password: String = "private password"
    ): Long = repository.saveEntry(
        VaultEntry(title = "private title", username = "private account", password = password,
            website = website, appPackage = appPackage, notes = ""), key
    )

    /** Never touch an installed vault or its preferences, including during restore tests. */
    private class TestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "autofill_test_${System.nanoTime()}_"
        private val preferenceNames = mutableSetOf<String>()
        val root = File(base.cacheDir, prefix).apply { mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
            dataDir = root.absolutePath
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames += prefix + name
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
