package com.example.vaultbox.autofill

import android.content.Context
import com.example.vaultbox.crypto.LocalSecretStore
import org.json.JSONArray
import org.json.JSONObject

/** Device-local routing metadata only; credentials and vault keys never enter this index. */
class AutofillMatchIndex(context: Context) {
    private val secretStore = LocalSecretStore(context.applicationContext)

    fun rebuild(loadTargets: () -> List<AutofillTarget>) = synchronized(lock) {
        clear()
        // Index failure must not prevent unlocking the vault with a valid password.
        runCatching {
            val rows = JSONArray()
            loadTargets().distinct().forEach { target ->
                val host = AutofillMatcher.normalizeHost(target.website).orEmpty()
                val packages = AutofillMatcher.packages(target.appPackage)
                    .filterNot { AutofillMatcher.isBrowserPackage(it) }.joinToString(",")
                if (host.isNotEmpty() || packages.isNotEmpty()) {
                    rows.put(JSONObject().put("website", host).put("appPackage", packages))
                }
            }
            secretStore.putSecret(SECRET_NAME, rows.toString())
        }.onFailure { clear() }
        Unit
    }

    fun hasMatch(appPackage: String?, webDomain: String?): Boolean = synchronized(lock) {
        runCatching {
            val stored = secretStore.getSecret(SECRET_NAME)
            if (stored.isEmpty()) return@synchronized false
            val rows = JSONArray(stored)
            // Parse the entire document before matching; a partially corrupt index is not trusted.
            val targets = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AutofillTarget(row.getString("website"), row.getString("appPackage"))
            }
            targets.any { AutofillMatcher.matches(it, appPackage, webDomain) }
        }.getOrDefault(false)
    }

    fun clear() = synchronized(lock) {
        secretStore.removeSecret(SECRET_NAME)
    }

    companion object {
        // Serialize reads, rebuilds and mutations across repository/service instances in this process.
        internal val lock = Any()
        private const val SECRET_NAME = "autofill_match_index_v1"
    }
}
