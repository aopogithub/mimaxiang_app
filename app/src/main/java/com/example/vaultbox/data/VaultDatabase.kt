package com.example.vaultbox.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.vaultbox.autofill.AutofillTarget
import com.example.vaultbox.crypto.CryptoBox
import com.example.vaultbox.crypto.EncryptedBytes

class VaultDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title_iv BLOB NOT NULL,
                title_ct BLOB NOT NULL,
                category_iv BLOB NOT NULL,
                category_ct BLOB NOT NULL,
                username_iv BLOB NOT NULL,
                username_ct BLOB NOT NULL,
                password_iv BLOB NOT NULL,
                password_ct BLOB NOT NULL,
                website_iv BLOB NOT NULL,
                website_ct BLOB NOT NULL,
                app_package_iv BLOB,
                app_package_ct BLOB,
                notes_iv BLOB NOT NULL,
                notes_ct BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entries_updated_at ON entries(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Existing rows cannot be re-encrypted here because the vault data key is not available
            // during SQLiteOpenHelper migration. Reads treat missing category values as "密码".
            db.execSQL("ALTER TABLE entries ADD COLUMN category_iv BLOB")
            db.execSQL("ALTER TABLE entries ADD COLUMN category_ct BLOB")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN app_package_iv BLOB")
            db.execSQL("ALTER TABLE entries ADD COLUMN app_package_ct BLOB")
        }
    }

    fun listEntrySummaries(dataKey: ByteArray): List<VaultEntrySummary> {
        val entries = mutableListOf<VaultEntrySummary>()
        readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf(
                "id",
                "title_iv",
                "title_ct",
                "category_iv",
                "category_ct",
                "username_iv",
                "username_ct",
                "website_iv",
                "website_ct",
                "app_package_iv",
                "app_package_ct",
                "updated_at"
            ),
            null,
            null,
            null,
            null,
            "updated_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += cursor.toSummary(dataKey)
            }
        }
        return entries
    }

    fun listAutofillTargets(dataKey: ByteArray): List<AutofillTarget> {
        val targets = mutableListOf<AutofillTarget>()
        readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf("website_iv", "website_ct", "app_package_iv", "app_package_ct", "password_iv", "password_ct"),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.decryptField("password", dataKey).isNotBlank()) {
                    targets += AutofillTarget(
                        website = cursor.decryptField("website", dataKey),
                        appPackage = cursor.decryptFieldOrDefault("app_package", dataKey, "")
                    )
                }
            }
        }
        return targets
    }

    fun getEntry(id: Long, dataKey: ByteArray): VaultEntry? {
        readableDatabase.query(
            TABLE_ENTRIES,
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toEntry(dataKey) else null
        }
    }

    fun insertEntry(entry: VaultEntry, dataKey: ByteArray): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            putEncrypted("title", entry.title, dataKey)
            putEncrypted("category", entry.category, dataKey)
            putEncrypted("username", entry.username, dataKey)
            putEncrypted("password", entry.password, dataKey)
            putEncrypted("website", entry.website, dataKey)
            putEncrypted("app_package", entry.appPackage, dataKey)
            putEncrypted("notes", entry.notes, dataKey)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insertOrThrow(TABLE_ENTRIES, null, values)
    }

    fun updateEntry(entry: VaultEntry, dataKey: ByteArray) {
        val values = ContentValues().apply {
            putEncrypted("title", entry.title, dataKey)
            putEncrypted("category", entry.category, dataKey)
            putEncrypted("username", entry.username, dataKey)
            putEncrypted("password", entry.password, dataKey)
            putEncrypted("website", entry.website, dataKey)
            putEncrypted("app_package", entry.appPackage, dataKey)
            putEncrypted("notes", entry.notes, dataKey)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE_ENTRIES,
            values,
            "id = ?",
            arrayOf(entry.id.toString())
        )
    }

    fun deleteEntry(id: Long) {
        writableDatabase.delete(TABLE_ENTRIES, "id = ?", arrayOf(id.toString()))
    }

    fun clearAllEntries() {
        writableDatabase.delete(TABLE_ENTRIES, null, null)
    }

    private fun ContentValues.putEncrypted(field: String, value: String, dataKey: ByteArray) {
        val encrypted = CryptoBox.encrypt(
            dataKey,
            value.toByteArray(Charsets.UTF_8),
            field.toByteArray(Charsets.UTF_8)
        )
        put("${field}_iv", encrypted.iv)
        put("${field}_ct", encrypted.cipherText)
    }

    private fun Cursor.toEntry(dataKey: ByteArray): VaultEntry =
        VaultEntry(
            id = getLong(column("id")),
            title = decryptField("title", dataKey),
            category = decryptFieldOrDefault("category", dataKey, VaultCategory.DEFAULT_PASSWORD),
            username = decryptField("username", dataKey),
            password = decryptField("password", dataKey),
            website = decryptField("website", dataKey),
            appPackage = decryptFieldOrDefault("app_package", dataKey, ""),
            notes = decryptField("notes", dataKey),
            createdAt = getLong(column("created_at")),
            updatedAt = getLong(column("updated_at"))
        )

    private fun Cursor.toSummary(dataKey: ByteArray): VaultEntrySummary =
        VaultEntrySummary(
            id = getLong(column("id")),
            title = decryptField("title", dataKey),
            category = decryptFieldOrDefault("category", dataKey, VaultCategory.DEFAULT_PASSWORD),
            username = decryptField("username", dataKey),
            website = decryptField("website", dataKey),
            appPackage = decryptFieldOrDefault("app_package", dataKey, ""),
            updatedAt = getLong(column("updated_at"))
        )

    private fun Cursor.decryptField(field: String, dataKey: ByteArray): String {
        val encrypted = EncryptedBytes(
            iv = getBlob(column("${field}_iv")),
            cipherText = getBlob(column("${field}_ct"))
        )
        val plainText = CryptoBox.decrypt(
            dataKey,
            encrypted,
            field.toByteArray(Charsets.UTF_8)
        )
        return plainText.toString(Charsets.UTF_8)
    }

    private fun Cursor.decryptFieldOrDefault(field: String, dataKey: ByteArray, defaultValue: String): String {
        val ivIndex = getColumnIndex("${field}_iv")
        val ctIndex = getColumnIndex("${field}_ct")
        if (ivIndex < 0 || ctIndex < 0 || isNull(ivIndex) || isNull(ctIndex)) {
            return defaultValue
        }
        val encrypted = EncryptedBytes(iv = getBlob(ivIndex), cipherText = getBlob(ctIndex))
        val plainText = CryptoBox.decrypt(
            dataKey,
            encrypted,
            field.toByteArray(Charsets.UTF_8)
        )
        return plainText.toString(Charsets.UTF_8)
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    companion object {
        private const val DB_NAME = "vaultbox.db"
        private const val DB_VERSION = 3
        private const val TABLE_ENTRIES = "entries"
    }
}
