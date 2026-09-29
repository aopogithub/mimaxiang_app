package com.example.vaultbox.autofill

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.vaultbox.R
import com.example.vaultbox.crypto.MasterPassword
import com.example.vaultbox.data.VaultEntrySummary
import com.example.vaultbox.data.VaultRepository
import javax.crypto.Cipher
import kotlin.concurrent.thread

class AutofillAuthActivity : Activity() {
    private lateinit var repository: VaultRepository
    private val handler = Handler(Looper.getMainLooper())
    private var dataKey: ByteArray? = null
    private var passwordId: AutofillId? = null
    private var usernameId: AutofillId? = null
    private var responseAuth: Boolean = false
    private var webDomain: String? = null
    private var appPackage: String? = null
    private var biometricCancellation: CancellationSignal? = null
    private var triedAutoBiometric = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        repository = VaultRepository(this)
        passwordId = intent.autofillIdExtra(AutofillContract.EXTRA_PASSWORD_ID)
        usernameId = intent.autofillIdExtra(AutofillContract.EXTRA_USERNAME_ID)
        responseAuth = intent.getBooleanExtra(AutofillContract.EXTRA_RESPONSE_AUTH, false)
        webDomain = intent.getStringExtra(AutofillContract.EXTRA_WEB_DOMAIN)
        appPackage = intent.getStringExtra(AutofillContract.EXTRA_APP_PACKAGE)

        if (passwordId == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        if (!repository.hasVault()) {
            showMessage("还没有创建保险箱", "请先打开密码箱创建本地保险箱。")
            return
        }

        if (!AutofillMatchIndex(this).hasMatch(appPackage, webDomain)) {
            finishCanceled()
            return
        }
        showUnlock(autoPrompt = true)
    }

    override fun onDestroy() {
        biometricCancellation?.cancel()
        MasterPassword.wipe(dataKey)
        dataKey = null
        if (::repository.isInitialized) repository.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_AUTH && resultCode == RESULT_OK) {
            runBusy("正在解锁") { repository.unlockWithDevice() }
                .onSuccess { key ->
                    dataKey = key
                    showEntryPicker()
                }
                .onError { error -> showUnlock(error.readableMessage(), autoPrompt = false) }
        }
    }

    private fun showUnlock(error: String? = null, autoPrompt: Boolean = false) {
        val passwordInput = passwordInput("主密码")
        val canBiometric = repository.hasDeviceUnlock() && canUseBiometric()

        setContentView(page {
            addView(header("密码箱", unlockSubtitle()))
            error?.let { addView(messageText(it, COLOR_DANGER)) }
            if (canBiometric) {
                addView(primaryButton("指纹解锁") { startBiometricUnlock() })
                addView(dividerLabel("或使用主密码"))
            } else {
                addView(messageText("当前可使用主密码解锁后填充。", COLOR_PRIMARY))
            }
            addView(fieldLabel("主密码"))
            addView(passwordInput)
            addView(primaryButton("主密码解锁") { unlockWithMasterPassword(passwordInput) })
        })

        if (autoPrompt && canBiometric && !triedAutoBiometric) {
            triedAutoBiometric = true
            handler.postDelayed({ startBiometricUnlock() }, 250L)
        }
    }

    private fun showEntryPicker() {
        val key = dataKey ?: return showUnlock(autoPrompt = true)
        val entries = runCatching {
            repository.listEntrySummaries(key).filter { entry ->
                AutofillMatcher.matches(AutofillTarget(entry.website, entry.appPackage), appPackage, webDomain) &&
                    repository.getEntry(entry.id, key)?.password?.isNotBlank() == true
            }
        }.getOrElse {
            showMessage("无法读取匹配条目", "请关闭后重新打开密码箱检查保险箱。")
            return
        }

        setContentView(page {
            addView(header("选择条目", "匹配 ${webDomain ?: appPackage.orEmpty()}"))
            if (entries.isEmpty()) {
                addView(messageText("没有匹配当前网站或 App 的密码，条目可能已被修改或删除。", COLOR_DANGER))
                addView(secondaryButton("关闭") { finishCanceled() })
            } else {
                entries.forEach { entry ->
                    addView(entryRow(entry) { fillEntry(entry.id) })
                }
            }
        })
    }

    private fun fillEntry(id: Long) {
        val key = dataKey ?: return showUnlock(autoPrompt = true)
        val entry = runCatching { repository.getEntry(id, key) }.getOrNull()
        if (entry == null || entry.password.isBlank() ||
            !AutofillMatcher.matches(AutofillTarget(entry.website, entry.appPackage), appPackage, webDomain)
        ) {
            Toast.makeText(this, "条目已不存在或不再匹配", Toast.LENGTH_SHORT).show()
            showEntryPicker()
            return
        }

        val passwordAutofillId = passwordId ?: return finishCanceled()
        val usernameAutofillId = usernameId
        val presentation = AutofillContract.presentation(
            this,
            entry.title.ifBlank { "密码箱" },
            entry.username.ifBlank { entry.website.ifBlank { "填充密码" } }
        )
        val dataset = Dataset.Builder(presentation)
            .apply {
                setValue(passwordAutofillId, AutofillValue.forText(entry.password), presentation)
                if (usernameAutofillId != null && entry.username.isNotBlank()) {
                    setValue(usernameAutofillId, AutofillValue.forText(entry.username), presentation)
                }
            }
            .build()
        val authenticationResult = if (responseAuth) {
            FillResponse.Builder().addDataset(dataset).build()
        } else {
            dataset
        }
        val result = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authenticationResult)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun unlockWithMasterPassword(passwordInput: EditText) {
        val password = passwordInput.text.toString()
        if (password.isBlank()) {
            showUnlock("请输入主密码", autoPrompt = false)
            return
        }
        val chars = password.toCharArray()
        passwordInput.text.clear()
        runBusy("正在解锁") {
            repository.unlockWithMaster(chars)
        }.onSuccess { key ->
            MasterPassword.wipe(chars)
            dataKey = key
            showEntryPicker()
        }.onError { error ->
            MasterPassword.wipe(chars)
            showUnlock(error.readableMessage(), autoPrompt = false)
        }
    }

    private fun startBiometricUnlock() {
        if (!repository.hasDeviceUnlock()) {
            showUnlock("还没有开启指纹解锁", autoPrompt = false)
            return
        }
        if (!canUseBiometric()) {
            requestLegacyDeviceAuth()
            return
        }
        val cipher = try {
            repository.createDeviceUnlockDecryptCipher()
        } catch (error: UserNotAuthenticatedException) {
            repository.disableDeviceUnlock()
            showUnlock("指纹解锁配置已更新，请用主密码解锁后重新开启", autoPrompt = false)
            return
        } catch (error: KeyPermanentlyInvalidatedException) {
            repository.disableDeviceUnlock()
            showUnlock("指纹设置已变化，请用主密码重新开启指纹解锁", autoPrompt = false)
            return
        } catch (error: Throwable) {
            showUnlock(error.readableMessage(), autoPrompt = false)
            return
        }
        authenticateWithBiometric(cipher)
    }

    private fun authenticateWithBiometric(cipher: Cipher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            requestLegacyDeviceAuth()
            return
        }
        biometricCancellation?.cancel()
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        val builder = BiometricPrompt.Builder(this)
            .setTitle("指纹解锁")
            .setSubtitle("解锁密码箱以自动填充")
            .setNegativeButton("使用主密码", mainExecutor) { _, _ ->
                biometricCancellation = null
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        builder.build().authenticate(
            BiometricPrompt.CryptoObject(cipher),
            cancellation,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricCancellation = null
                    val authedCipher = result.cryptoObject?.cipher ?: cipher
                    unlockWithBiometricCipher(authedCipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    biometricCancellation = null
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BIOMETRIC_ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(
                            this@AutofillAuthActivity,
                            errString?.toString() ?: "指纹验证失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(this@AutofillAuthActivity, "指纹不匹配", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun unlockWithBiometricCipher(cipher: Cipher) {
        runBusy("正在解锁") { repository.unlockWithDevice(cipher) }
            .onSuccess { key ->
                dataKey = key
                showEntryPicker()
            }
            .onError { error ->
                if (error is UserNotAuthenticatedException) {
                    repository.disableDeviceUnlock()
                    showUnlock("指纹解锁配置已更新，请用主密码解锁后重新开启", autoPrompt = false)
                } else {
                    showUnlock(error.readableMessage(), autoPrompt = false)
                }
            }
    }

    private fun requestLegacyDeviceAuth() {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent("密码箱", "确认身份后填充")
        if (intent == null) {
            showUnlock("系统未设置锁屏密码，请使用主密码", autoPrompt = false)
            return
        }
        startActivityForResult(intent, REQUEST_DEVICE_AUTH)
    }

    private fun canUseBiometric(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!repository.canUseDeviceUnlock()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(BiometricManager::class.java) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                    BiometricManager.BIOMETRIC_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
        }
        @Suppress("DEPRECATION")
        val fingerprintManager = getSystemService(FingerprintManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return fingerprintManager.isHardwareDetected && fingerprintManager.hasEnrolledFingerprints()
    }

    private fun unlockSubtitle(): String =
        webDomain?.let { "为 $it 填充密码" }
            ?: appPackage?.let { "为 $it 填充密码" }
            ?: "解锁后选择要填充的条目"

    private fun showMessage(title: String, subtitle: String) {
        setContentView(page {
            addView(header(title, subtitle))
            addView(secondaryButton("关闭") { finishCanceled() })
        })
    }

    private fun finishCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun page(build: LinearLayout.() -> Unit): ScrollView =
        ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
            addView(LinearLayout(this@AutofillAuthActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), statusTopPadding(), dp(20), dp(20))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                build()
            })
        }

    private fun header(title: String, subtitle: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(20))
            layoutParams = spacedParams(bottom = 4)
            addView(ImageView(this@AutofillAuthActivity).apply {
                setImageResource(R.drawable.app_icon)
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                    setMargins(0, 0, 0, dp(12))
                }
            })
            addView(TextView(this@AutofillAuthActivity).apply {
                text = title
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@AutofillAuthActivity).apply {
                text = subtitle
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun entryRow(entry: VaultEntrySummary, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(COLOR_SURFACE, 14, COLOR_STROKE)
            layoutParams = spacedParams(bottom = 10)
            setOnClickListener { action() }
            addView(TextView(this@AutofillAuthActivity).apply {
                text = entry.title.ifBlank { entry.website.ifBlank { "未命名条目" } }
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@AutofillAuthActivity).apply {
                text = listOf(entry.username, entry.website)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { entry.category }
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    private fun fieldLabel(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_MUTED)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(8), 0, dp(6))
        }

    private fun dividerLabel(text: String): View =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

    private fun messageText(text: String, color: Int): View =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(if (color == COLOR_DANGER) COLOR_DANGER_SOFT else COLOR_PRIMARY_SOFT, 12, 0)
            layoutParams = spacedParams(bottom = 12)
        }

    private fun passwordInput(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_HINT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_INPUT, 12, COLOR_STROKE)
            layoutParams = spacedParams(height = dp(50), bottom = 10)
        }

    private fun primaryButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_PRIMARY, Color.WHITE, action)

    private fun secondaryButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_SURFACE, COLOR_TEXT, action)

    private fun button(text: String, bgColor: Int, fgColor: Int, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(fgColor)
            backgroundTintList = ColorStateList.valueOf(bgColor)
            setOnClickListener { action() }
            layoutParams = spacedParams(height = dp(48), bottom = 10)
        }

    private fun roundedBackground(color: Int, radius: Int, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    private fun spacedParams(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        bottom: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(0, 0, 0, dp(bottom))
        }

    private fun statusTopPadding(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        return statusBarHeight + dp(18)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun <T> runBusy(message: String, block: () -> T): PendingResult<T> {
        setContentView(page {
            addView(header("密码箱", message))
        })
        val result = PendingResult<T>()
        thread(name = "VaultBoxAutofillWorker") {
            try {
                val value = block()
                handler.post { result.deliverSuccess(value) }
            } catch (error: Throwable) {
                handler.post { result.deliverError(error) }
            }
        }
        return result
    }

    private fun Throwable.readableMessage(): String =
        message ?: "操作失败"

    private fun Intent.autofillIdExtra(key: String): AutofillId? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    private class PendingResult<T> {
        private var success: ((T) -> Unit)? = null
        private var error: ((Throwable) -> Unit)? = null
        private var deliveredSuccess: T? = null
        private var deliveredError: Throwable? = null
        private var hasSuccess = false
        private var hasError = false

        fun onSuccess(callback: (T) -> Unit): PendingResult<T> {
            success = callback
            if (hasSuccess) {
                @Suppress("UNCHECKED_CAST")
                callback(deliveredSuccess as T)
            }
            return this
        }

        fun onError(callback: (Throwable) -> Unit): PendingResult<T> {
            error = callback
            if (hasError) callback(deliveredError ?: IllegalStateException("Unknown error"))
            return this
        }

        fun deliverSuccess(value: T) {
            hasSuccess = true
            deliveredSuccess = value
            success?.invoke(value)
        }

        fun deliverError(throwable: Throwable) {
            hasError = true
            deliveredError = throwable
            error?.invoke(throwable)
        }
    }

    companion object {
        private const val REQUEST_DEVICE_AUTH = 91
        private const val BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13
        private val COLOR_BACKGROUND = Color.rgb(247, 247, 242)
        private val COLOR_SURFACE = Color.rgb(255, 255, 255)
        private val COLOR_PANEL = Color.rgb(239, 243, 239)
        private val COLOR_INPUT = Color.rgb(252, 252, 249)
        private val COLOR_TEXT = Color.rgb(31, 41, 51)
        private val COLOR_MUTED = Color.rgb(93, 105, 112)
        private val COLOR_HINT = Color.rgb(143, 151, 154)
        private val COLOR_PRIMARY = Color.rgb(36, 92, 78)
        private val COLOR_PRIMARY_SOFT = Color.rgb(226, 239, 233)
        private val COLOR_DANGER = Color.rgb(176, 54, 54)
        private val COLOR_DANGER_SOFT = Color.rgb(250, 232, 232)
        private val COLOR_STROKE = Color.rgb(220, 226, 220)
    }
}
