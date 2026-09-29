package com.example.vaultbox.autofill

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutofillDiagnostics {
    private const val PREFS = "autofill_diagnostics"
    private const val KEY_TIME = "time"
    private const val KEY_APP_PACKAGE = "app_package"
    private const val KEY_WEB_DOMAIN = "web_domain"
    private const val KEY_FIELD_COUNT = "field_count"
    private const val KEY_USERNAME_DETECTED = "username_detected"
    private const val KEY_PASSWORD_DETECTED = "password_detected"
    private const val KEY_FOCUSED_FIELD = "focused_field"
    private const val KEY_RESULT = "result"

    fun record(
        context: Context,
        appPackage: String?,
        webDomain: String?,
        fieldCount: Int,
        usernameDetected: Boolean,
        passwordDetected: Boolean,
        focusedField: String?,
        result: String
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putString(KEY_APP_PACKAGE, appPackage.orEmpty())
            .putString(KEY_WEB_DOMAIN, webDomain.orEmpty())
            .putInt(KEY_FIELD_COUNT, fieldCount)
            .putBoolean(KEY_USERNAME_DETECTED, usernameDetected)
            .putBoolean(KEY_PASSWORD_DETECTED, passwordDetected)
            .putString(KEY_FOCUSED_FIELD, focusedField.orEmpty())
            .putString(KEY_RESULT, result)
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L) {
            return "最近自动填充请求：暂无。切到 Chrome 点一下账号或密码输入框后，再回来看这里。"
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val appPackage = prefs.getString(KEY_APP_PACKAGE, null).orEmpty().ifBlank { "未知应用" }
        val webDomain = prefs.getString(KEY_WEB_DOMAIN, null).orEmpty().ifBlank { "未提供网站域名" }
        val fieldCount = prefs.getInt(KEY_FIELD_COUNT, 0)
        val username = if (prefs.getBoolean(KEY_USERNAME_DETECTED, false)) "是" else "否"
        val password = if (prefs.getBoolean(KEY_PASSWORD_DETECTED, false)) "是" else "否"
        val focused = prefs.getString(KEY_FOCUSED_FIELD, null).orEmpty().ifBlank { "未知" }
        val result = prefs.getString(KEY_RESULT, null).orEmpty().ifBlank { "未知结果" }
        return "最近自动填充请求：${formatter.format(Date(time))}\n应用：$appPackage\n网站：$webDomain\n可填字段：$fieldCount，账号：$username，密码：$password\n焦点字段：$focused\n结果：$result"
    }
}
