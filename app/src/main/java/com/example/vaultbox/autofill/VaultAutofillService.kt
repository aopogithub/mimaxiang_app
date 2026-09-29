package com.example.vaultbox.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import java.util.concurrent.Executors

class VaultAutofillService : AutofillService() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        worker.execute {
            try {
                fill(request, cancellationSignal, callback)
            } catch (_: Exception) {
                callback.onSuccess(null)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun fill(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null || cancellationSignal.isCanceled) {
            AutofillDiagnostics.record(
                context = this,
                appPackage = null,
                webDomain = null,
                fieldCount = 0,
                usernameDetected = false,
                passwordDetected = false,
                focusedField = null,
                result = "系统没有提供页面结构"
            )
            callback.onSuccess(null)
            return
        }

        val form = FormParser().parse(structure)
        val passwordId = form.passwordId
        if (passwordId == null) {
            AutofillDiagnostics.record(
                context = this,
                appPackage = form.appPackage,
                webDomain = form.webDomain,
                fieldCount = form.fieldCount,
                usernameDetected = form.usernameId != null,
                passwordDetected = false,
                focusedField = form.focusedField,
                result = "未识别到密码框，未显示密码箱"
            )
            callback.onSuccess(null)
            return
        }

        val hasMatch = AutofillMatchIndex(this).hasMatch(form.appPackage, form.webDomain)
        if (!hasMatch || cancellationSignal.isCanceled) {
            AutofillDiagnostics.record(
                context = this,
                appPackage = form.appPackage,
                webDomain = form.webDomain,
                fieldCount = form.fieldCount,
                usernameDetected = form.usernameId != null,
                passwordDetected = true,
                focusedField = form.focusedField,
                result = if (cancellationSignal.isCanceled) "请求已取消" else
                    "无匹配密码或匹配索引未就绪，未显示密码箱"
            )
            callback.onSuccess(null)
            return
        }

        val authIntent = Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, structure)
            putExtra(AutofillContract.EXTRA_PASSWORD_ID, passwordId)
            form.usernameId?.let { putExtra(AutofillContract.EXTRA_USERNAME_ID, it) }
            putExtra(AutofillContract.EXTRA_RESPONSE_AUTH, true)
            form.webDomain?.let { putExtra(AutofillContract.EXTRA_WEB_DOMAIN, it) }
            form.appPackage?.let { putExtra(AutofillContract.EXTRA_APP_PACKAGE, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            request.id,
            authIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or pendingIntentMutableFlag()
        )

        val presentation = AutofillContract.presentation(
            this,
            "密码箱",
            form.webDomain ?: "选择要填充的条目"
        )
        val authenticationIds = listOfNotNull(passwordId, form.usernameId)
            .distinct()
            .toTypedArray()

        AutofillDiagnostics.record(
            context = this,
            appPackage = form.appPackage,
            webDomain = form.webDomain,
            fieldCount = form.fieldCount,
            usernameDetected = form.usernameId != null,
            passwordDetected = true,
            focusedField = form.focusedField,
            result = "已返回密码箱认证候选"
        )
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        callback.onSuccess(
            FillResponse.Builder()
                .setAuthentication(authenticationIds, pendingIntent.intentSender, presentation)
                .build()
        )
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun pendingIntentMutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    private data class ParsedForm(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val focusedTextId: AutofillId?,
        val firstTextId: AutofillId?,
        val fieldCount: Int,
        val focusedField: String?,
        val webDomain: String?,
        val appPackage: String?
    )

    private class FormParser {
        private var usernameCandidate: AutofillId? = null
        private var focusedUsernameCandidate: AutofillId? = null
        private var passwordCandidate: AutofillId? = null
        private var focusedPasswordCandidate: AutofillId? = null
        private var focusedTextCandidate: AutofillId? = null
        private var firstTextCandidate: AutofillId? = null
        private var fieldCount: Int = 0
        private var focusedField: String? = null
        private val fieldDomains = mutableMapOf<AutofillId, String?>()

        fun parse(structure: AssistStructure): ParsedForm {
            for (i in 0 until structure.windowNodeCount) {
                visit(structure.getWindowNodeAt(i).rootViewNode)
            }
            val passwordId = focusedPasswordCandidate ?: passwordCandidate
            val domain = fieldDomains[passwordId]
            return ParsedForm(
                usernameId = (focusedUsernameCandidate ?: usernameCandidate)
                    ?.takeIf { fieldDomains[it] == domain },
                passwordId = passwordId,
                focusedTextId = focusedTextCandidate,
                firstTextId = firstTextCandidate,
                fieldCount = fieldCount,
                focusedField = focusedField,
                webDomain = domain,
                appPackage = structure.activityComponent?.packageName
            )
        }

        private fun visit(node: AssistStructure.ViewNode, inheritedDomain: String? = null) {
            val domain = node.webDomain?.trim()?.takeIf { it.isNotEmpty() } ?: inheritedDomain
            val autofillId = node.autofillId
            if (autofillId != null && node.isTextLikeField()) {
                fieldDomains[autofillId] = domain
                fieldCount += 1
                if (firstTextCandidate == null) firstTextCandidate = autofillId
                if (node.isFocused) {
                    focusedTextCandidate = autofillId
                    focusedField = node.debugLabel()
                }
                if (node.looksLikePassword()) {
                    if (node.isFocused) focusedPasswordCandidate = autofillId
                    if (passwordCandidate == null) passwordCandidate = autofillId
                } else if (node.looksLikeUsername()) {
                    if (node.isFocused) focusedUsernameCandidate = autofillId
                    if (usernameCandidate == null) usernameCandidate = autofillId
                }
            }

            for (i in 0 until node.childCount) {
                visit(node.getChildAt(i), domain)
            }
        }

        private fun AssistStructure.ViewNode.isTextLikeField(): Boolean =
            autofillType == View.AUTOFILL_TYPE_TEXT ||
                autofillType == View.AUTOFILL_TYPE_NONE ||
                (inputType and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT ||
                htmlInfo?.attributes.orEmpty().any { attribute ->
                    val name = attribute.first.orEmpty()
                    val value = attribute.second.orEmpty()
                    name.equals("type", ignoreCase = true) &&
                        TEXT_HTML_TYPES.any { it.equals(value, ignoreCase = true) }
                }

        private fun AssistStructure.ViewNode.looksLikePassword(): Boolean {
            if (autofillHints?.any { it.contains("password", ignoreCase = true) } == true) {
                return true
            }
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if ((inputType and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            ) {
                return true
            }
            val haystack = nodeText().lowercase()
            return PASSWORD_WORDS.any { haystack.contains(it) }
        }

        private fun AssistStructure.ViewNode.looksLikeUsername(): Boolean {
            val hints = autofillHints.orEmpty()
            if (hints.any { hint ->
                    USERNAME_HINTS.any { expected -> hint.equals(expected, ignoreCase = true) } ||
                        hint.contains("username", ignoreCase = true) ||
                        hint.contains("email", ignoreCase = true)
                }
            ) {
                return true
            }
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if ((inputType and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
            ) {
                return true
            }
            val haystack = nodeText().lowercase()
            return USERNAME_WORDS.any { haystack.contains(it) }
        }

        private fun AssistStructure.ViewNode.nodeText(): String =
            listOfNotNull(
                idEntry,
                hint?.toString(),
                hintIdEntry,
                textIdEntry,
                contentDescription?.toString(),
                htmlInfo?.attributes
                    ?.joinToString(" ") { "${it.first} ${it.second}" }
            ).joinToString(" ")

        private fun AssistStructure.ViewNode.debugLabel(): String =
            listOfNotNull(
                idEntry?.let { "id=$it" },
                hint?.toString()?.takeIf { it.isNotBlank() }?.let { "hint=$it" },
                htmlInfo?.attributes
                    ?.firstOrNull { it.first.equals("type", ignoreCase = true) }
                    ?.second
                    ?.let { "type=$it" },
                "autofillType=$autofillType",
                "inputType=$inputType"
            ).joinToString(", ")

        companion object {
            private val TEXT_HTML_TYPES = setOf(
                "text",
                "email",
                "password",
                "search",
                "tel",
                "url",
                "number"
            )
            private val USERNAME_HINTS = setOf(
                "username",
                "email",
                "emailAddress",
                "login",
                "account"
            )
            private val USERNAME_WORDS = listOf(
                "user",
                "username",
                "login",
                "email",
                "mail",
                "account",
                "手机号",
                "手机",
                "邮箱",
                "账号",
                "帐号",
                "用户名"
            )
            private val PASSWORD_WORDS = listOf(
                "password",
                "passwd",
                "pwd",
                "pass",
                "密码",
                "口令"
            )
        }
    }
}
