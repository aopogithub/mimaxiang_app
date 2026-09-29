package com.example.vaultbox

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.vaultbox.autofill.AutofillDiagnostics
import com.example.vaultbox.crypto.MasterPassword
import com.example.vaultbox.data.FaviconRepository
import com.example.vaultbox.data.PasswordGenerator
import com.example.vaultbox.data.RemoteBackupPoint
import com.example.vaultbox.data.RemoteSyncConfig
import com.example.vaultbox.data.RemoteSyncRepository
import com.example.vaultbox.data.VaultCategory
import com.example.vaultbox.data.VaultEntry
import com.example.vaultbox.data.VaultEntrySummary
import com.example.vaultbox.data.VaultRepository
import javax.crypto.Cipher
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var repository: VaultRepository
    private lateinit var faviconRepository: FaviconRepository
    private lateinit var remoteSyncRepository: RemoteSyncRepository
    private val handler = Handler(Looper.getMainLooper())
    private var dataKey: ByteArray? = null
    private var lastBackgroundAt: Long = 0L
    private var pendingDeviceAction: DeviceAction? = null
    private var activeScreen: Screen = Screen.Locked
    private var searchQuery: String = ""
    private var selectedCategory: String? = null
    private var clipboardClearRunnable: Runnable? = null
    private var biometricCancellation: CancellationSignal? = null
    private var triedAutoBiometric = false
    private val faviconLoads = mutableSetOf<String>()
    private var homeListContainer: LinearLayout? = null
    private var homeEmptyText: String? = null
    private var autoSyncRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        repository = VaultRepository(this)
        faviconRepository = FaviconRepository(this)
        remoteSyncRepository = RemoteSyncRepository(this)

        if (repository.hasVault()) {
            showUnlock(autoPrompt = true)
            autoUploadBackup("启动同步", notifyOnSuccess = false)
        } else {
            showCreateVault()
        }
    }

    override fun onResume() {
        super.onResume()
        val key = dataKey
        if (key != null && lastBackgroundAt > 0L) {
            val awayFor = SystemClock.elapsedRealtime() - lastBackgroundAt
            if (awayFor >= repository.autoLockMillis) {
                lockSession()
                return
            }
        }
        lastBackgroundAt = 0L
        if (activeScreen == Screen.Settings) {
            showSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        biometricCancellation?.cancel()
        if (dataKey != null) {
            lastBackgroundAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onDestroy() {
        biometricCancellation?.cancel()
        clearClipboardTimer()
        MasterPassword.wipe(dataKey)
        dataKey = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTOFILL_SERVICE) {
            val message = if (isPasswordBoxAutofillEnabled()) {
                "系统自动填充已切换为密码箱"
            } else {
                "系统自动填充当前仍不是密码箱，Chrome 里会继续显示原来的服务"
            }
            showSettings(message)
            return
        }

        if (requestCode != REQUEST_DEVICE_AUTH) return

        val action = pendingDeviceAction
        pendingDeviceAction = null
        if (resultCode != RESULT_OK || action == null) {
            showToast("身份确认已取消")
            return
        }

        when (action) {
            DeviceAction.LegacyUnlock -> unlockWithLegacyDeviceAfterAuth()
            else -> showToast("当前系统不支持该操作")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (activeScreen) {
            Screen.Home -> super.onBackPressed()
            Screen.Editor,
            Screen.Settings -> showHome()
            Screen.Locked,
            Screen.Create -> super.onBackPressed()
        }
    }

    private fun showCreateVault(error: String? = null) {
        activeScreen = Screen.Create
        val passwordInput = passwordInput("主密码")
        val confirmInput = passwordInput("确认主密码")

        setContentView(page {
            addView(heroHeader("密码箱", "创建本地保险箱", showIcon = true))
            error?.let { addView(messageText(it, COLOR_DANGER)) }
            addView(infoPanel("主密码至少 ${VaultRepository.MIN_MASTER_PASSWORD_LENGTH} 位。创建后会优先引导开启指纹解锁。"))
            addView(fieldLabel("主密码"))
            addView(passwordInput)
            addView(fieldLabel("确认主密码"))
            addView(confirmInput)
            addView(primaryButton("创建保险箱") {
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()
                when {
                    password.length < VaultRepository.MIN_MASTER_PASSWORD_LENGTH ->
                        showCreateVault("主密码太短")
                    password != confirm ->
                        showCreateVault("两次输入不一致")
                    else -> {
                        val chars = password.toCharArray()
                        passwordInput.text.clear()
                        confirmInput.text.clear()
                        runBusy("正在创建保险箱") {
                            repository.createVault(chars)
                        }.onSuccess { key ->
                            MasterPassword.wipe(chars)
                            dataKey = key
                            if (canUseBiometric()) {
                                showHome("保险箱已创建，请验证指纹以开启默认解锁")
                                handler.postDelayed({ startBiometricEnable() }, 350L)
                            } else {
                                showHome("保险箱已创建")
                            }
                        }.onError { throwable ->
                            MasterPassword.wipe(chars)
                            showCreateVault(throwable.readableMessage())
                        }
                    }
                }
            })
        })
    }

    private fun showUnlock(error: String? = null, autoPrompt: Boolean = false) {
        activeScreen = Screen.Locked
        val passwordInput = passwordInput("主密码")
        val shouldShowBiometric = repository.hasDeviceUnlock() && canUseBiometric()

        setContentView(page {
            addView(heroHeader("密码箱", if (shouldShowBiometric) "使用指纹解锁" else "保险箱已锁定", showIcon = true))
            error?.let { addView(messageText(it, COLOR_DANGER)) }
            if (shouldShowBiometric) {
                addView(fingerprintPanel { startBiometricUnlock() })
                addView(dividerLabel("或使用主密码"))
            } else {
                addView(infoPanel("首次解锁后可在设置中开启指纹解锁。"))
            }
            addView(fieldLabel("主密码"))
            addView(passwordInput)
            addView(primaryButton("主密码解锁") {
                unlockWithMasterPassword(passwordInput)
            })
        })

        if (autoPrompt &&
            shouldShowBiometric &&
            repository.preferredUnlock == VaultRepository.UNLOCK_DEVICE &&
            !triedAutoBiometric
        ) {
            triedAutoBiometric = true
            startBiometricUnlock()
        }
    }

    private fun unlockWithMasterPassword(passwordInput: EditText) {
        val password = passwordInput.text.toString()
        if (password.isBlank()) {
            showUnlock("请输入主密码")
            return
        }
        val chars = password.toCharArray()
        passwordInput.text.clear()
        runBusy("正在解锁") {
            repository.unlockWithMaster(chars)
        }.onSuccess { key ->
            MasterPassword.wipe(chars)
            dataKey = key
            showHome()
        }.onError { throwable ->
            MasterPassword.wipe(chars)
            showUnlock(throwable.readableMessage())
        }
    }

    private fun showHome(message: String? = null) {
        activeScreen = Screen.Home
        val key = dataKey ?: return showUnlock(autoPrompt = true)
        val categories = repository.categories()
        val allEntries = try {
            repository.listEntrySummaries(key)
        } catch (error: Exception) {
            lockSession()
            showToast(error.readableMessage())
            return
        }
        val filtered = filterEntries(allEntries)
        val searchInput = searchInput(searchQuery)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        setContentView(page(topPadding = 18) {
            addView(toolbar("密码箱") {
                addView(iconButton("+", COLOR_PRIMARY, Color.WHITE) { showEditor(null) })
                addView(iconButton("设置", COLOR_SURFACE, COLOR_TEXT) { showSettings() })
            })
            message?.let { addView(messageText(it, COLOR_PRIMARY)) }
            addView(statsBand(allEntries))
            addView(searchInput)
            addView(categoryStrip(categories))
            addView(listContainer)
        })
        homeListContainer = listContainer
        homeEmptyText = if (allEntries.isEmpty()) "暂无条目" else "没有匹配结果"
        renderHomeList(filtered, homeEmptyText.orEmpty())
        searchInput.setSelection(searchInput.text.length)
        prefetchFavicons(filtered)
    }

    private fun refreshHomeList() {
        val key = dataKey ?: return
        val allEntries = runCatching { repository.listEntrySummaries(key) }.getOrElse {
            lockSession()
            return
        }
        val filtered = filterEntries(allEntries)
        homeEmptyText = if (allEntries.isEmpty()) "暂无条目" else "没有匹配结果"
        renderHomeList(filtered, homeEmptyText.orEmpty())
        prefetchFavicons(filtered)
    }

    private fun filterEntries(entries: List<VaultEntrySummary>): List<VaultEntrySummary> =
        entries.filter { entry ->
            val categoryMatch = selectedCategory == null || entry.category == selectedCategory
            val queryMatch = if (searchQuery.isBlank()) true else {
                val needle = searchQuery.lowercase()
                entry.title.lowercase().contains(needle) ||
                    entry.username.lowercase().contains(needle) ||
                    entry.website.lowercase().contains(needle) ||
                    entry.appPackage.lowercase().contains(needle) ||
                    entry.category.lowercase().contains(needle)
            }
            categoryMatch && queryMatch
        }

    private fun renderHomeList(entries: List<VaultEntrySummary>, emptyText: String) {
        val container = homeListContainer ?: return
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(emptyPanel(emptyText))
        } else {
            entries.forEach { entry -> container.addView(entryRow(entry)) }
        }
    }

    private fun showEditor(entry: VaultEntry?) {
        activeScreen = Screen.Editor
        var selected = entry?.category ?: selectedCategory ?: VaultCategory.DEFAULT_PASSWORD
        val titleInput = plainInput("例如 GitHub").apply { setText(entry?.title.orEmpty()) }
        val usernameInput = plainInput("用户名、邮箱或账号 ID").apply { setText(entry?.username.orEmpty()) }
        val websiteInput = plainInput("https://example.com").apply { setText(entry?.website.orEmpty()) }
        var selectedAppPackage = entry?.appPackage.orEmpty()
        lateinit var appAssociationText: TextView
        fun updateAppAssociationText() {
            appAssociationText.text = appAssociationLabel(selectedAppPackage)
        }
        val passwordInput = passwordInput(if (selected == VaultCategory.DEFAULT_API_KEY) "API 密钥" else "密码")
            .apply { setText(entry?.password.orEmpty()) }
        val notesInput = plainInput("备注").apply {
            setText(entry?.notes.orEmpty())
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val showPassword = CheckBox(this).apply {
            text = "显示内容"
            setTextColor(COLOR_MUTED)
            buttonTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            setOnCheckedChangeListener { _, checked ->
                passwordInput.inputType = if (checked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                passwordInput.setSelection(passwordInput.text.length)
            }
        }
        lateinit var categoryContainer: LinearLayout
        fun rebuildCategoryChoices() {
            categoryContainer.removeAllViews()
            repository.categories().forEach { category ->
                categoryContainer.addView(chip(category, selected == category) {
                    selected = category
                    rebuildCategoryChoices()
                })
            }
            categoryContainer.addView(chip("+ 分类", false) {
                promptAddCategory { name ->
                    selected = name
                    rebuildCategoryChoices()
                }
            })
        }

        setContentView(page(topPadding = 18) {
            addView(toolbar(if (entry == null) "新增条目" else "编辑条目") {
                addView(iconButton("返回", COLOR_SURFACE, COLOR_TEXT) { showHome() })
            })
            addView(formCard {
                addView(fieldLabel("分类"))
                categoryContainer = horizontalWrap()
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = spacedParams(bottom = 8)
                    addView(categoryContainer)
                })
                rebuildCategoryChoices()
                addView(fieldLabel("标题"))
                addView(titleInput)
                addView(fieldLabel("网址"))
                addView(websiteInput)
                addView(fieldLabel("关联 App"))
                appAssociationText = TextView(context).apply {
                    setTextColor(COLOR_MUTED)
                    textSize = 14f
                    background = roundedBackground(COLOR_PANEL, 12, 0)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = spacedParams(bottom = 8)
                }
                updateAppAssociationText()
                addView(appAssociationText)
                addView(horizontal {
                    addView(rowButton("选择 App") {
                        showAppPicker(
                            currentPackages = selectedAppPackage,
                            onSelected = { packageName ->
                                selectedAppPackage = mergePackageAssociation(selectedAppPackage, packageName)
                                updateAppAssociationText()
                            },
                            onClear = {
                                selectedAppPackage = ""
                                updateAppAssociationText()
                            }
                        )
                    })
                    addView(rowButton("清除关联") {
                        selectedAppPackage = ""
                        updateAppAssociationText()
                    })
                })
                addView(fieldLabel("账号"))
                addView(usernameInput)
                addView(fieldLabel("秘密内容"))
                addView(passwordInput)
                addView(horizontal {
                    addView(rowButton("生成") {
                        passwordInput.setText(
                            PasswordGenerator.generate(
                                if (selected == VaultCategory.DEFAULT_API_KEY) 32 else 20,
                                true
                            )
                        )
                        passwordInput.setSelection(passwordInput.text.length)
                    })
                    addView(rowButton("复制") { copySecret("secret", passwordInput.text.toString()) })
                })
                addView(showPassword)
                addView(fieldLabel("备注"))
                addView(notesInput)
            })
            addView(primaryButton("保存") {
                val key = dataKey ?: return@primaryButton lockSession()
                val newEntry = VaultEntry(
                    id = entry?.id ?: 0L,
                    title = titleInput.text.toString().trim(),
                    category = selected,
                    username = usernameInput.text.toString().trim(),
                    password = passwordInput.text.toString(),
                    website = websiteInput.text.toString().trim(),
                    appPackage = selectedAppPackage.trim(),
                    notes = notesInput.text.toString(),
                    createdAt = entry?.createdAt ?: System.currentTimeMillis()
                )
                when {
                    newEntry.title.isBlank() -> showToast("标题不能为空")
                    newEntry.password.isBlank() -> showToast("秘密内容不能为空")
                    else -> {
                        repository.saveEntry(newEntry, key)
                        showHome("已保存")
                        fetchFavicon(newEntry.website)
                        autoUploadBackup("已保存并同步", notifyOnSuccess = false)
                    }
                }
            })
            if (entry != null) {
                addView(dangerButton("删除条目") { confirmDelete(entry) })
            }
        })
    }

    private fun showSettings(message: String? = null) {
        activeScreen = Screen.Settings
        val canBiometric = canUseBiometric()
        setContentView(page(topPadding = 18) {
            addView(toolbar("设置") {
                addView(iconButton("返回", COLOR_SURFACE, COLOR_TEXT) { showHome() })
            })
            message?.let { addView(messageText(it, COLOR_PRIMARY)) }
            addView(settingsCard("默认解锁") {
                addView(label(if (repository.hasDeviceUnlock()) "指纹解锁已启用" else "指纹解锁未启用"))
                val unlockOptions = mutableListOf("主密码")
                if (canBiometric && repository.hasDeviceUnlock()) {
                    unlockOptions.add("指纹")
                }
                addView(spinner(
                    options = unlockOptions,
                    selected = if (repository.preferredUnlock == VaultRepository.UNLOCK_DEVICE && unlockOptions.contains("指纹")) "指纹" else "主密码"
                ) { selected ->
                    repository.preferredUnlock = if (selected == "指纹") VaultRepository.UNLOCK_DEVICE else VaultRepository.UNLOCK_MASTER
                    showSettings("默认解锁方式已更新")
                })
                if (canBiometric && !repository.hasDeviceUnlock()) {
                    addView(primaryButton("开启指纹解锁") { startBiometricEnable() })
                } else if (!canBiometric) {
                    addView(label("当前设备未录入可用指纹，或系统版本不支持。"))
                }
                if (repository.hasDeviceUnlock()) {
                    addView(secondaryButton("关闭指纹解锁") {
                        repository.disableDeviceUnlock()
                        repository.preferredUnlock = VaultRepository.UNLOCK_MASTER
                        showSettings("已关闭指纹解锁")
                    })
                }
            })
            addView(settingsCard("分类") {
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = spacedParams(bottom = 0)
                    addView(settingsCategoryWrap())
                })
            })
            addView(settingsCard("自动填充") {
                addView(label("仅当当前网站或 App 有匹配密码时显示密码箱。请在条目中填写登录网址或选择“关联 App”；网址按域名精确匹配，不自动匹配其他子域名。升级或恢复备份后，请先打开并解锁一次以更新匹配索引。"))
                addView(label(autofillStatusLabel()))
                addView(label("Android 同一时间只会调用一个系统自动填充服务；如果当前是 Google，Chrome 里不会同时出现密码箱。"))
                addView(label("Chrome 还需要在 Chrome 设置里选择“Autofill using another service”；部分系统自带浏览器如果不支持 Android 自动填充，就不会调用密码箱。"))
                addView(primaryButton("启用系统自动填充") { openAutofillSettings() })
                addView(secondaryButton("查看调用日志") { showAutofillDiagnosticsDialog() })
            })
            addView(settingsCard("远程备份") {
                val config = remoteSyncRepository.config()
                val status = if (remoteSyncRepository.hasConfig()) {
                    "${config.username}@${config.host}:${config.remotePath}"
                } else {
                    "未配置"
                }
                addView(label(status))
                addView(secondaryButton("配置 SSH/SFTP") { showRemoteSyncDialog() })
                addView(primaryButton("上传备份") { uploadRemoteBackup() })
                addView(dangerButton("选择备份恢复") { chooseRemoteBackupToRestore() })
            })
            addView(settingsCard("自动锁定") {
                val options = autoLockOptions()
                addView(spinner(
                    options = options.map { it.first },
                    selected = options.firstOrNull { it.second == repository.autoLockMillis }?.first ?: "2 分钟"
                ) { selected ->
                    repository.autoLockMillis = options.first { it.first == selected }.second
                    showSettings("自动锁定时间已更新")
                })
            })
            addView(settingsCard("会话") {
                addView(dangerButton("立即锁定") { lockSession() })
            })
        })
    }

    private fun showRemoteSyncDialog() {
        val config = remoteSyncRepository.config()
        val hostInput = plainInput("主机，例如 192.168.1.10").apply { setText(config.host) }
        val portInput = plainInput("端口").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.port.toString())
        }
        val usernameInput = plainInput("用户名").apply { setText(config.username) }
        val passwordInput = passwordInput("SSH 密码").apply {
            setText(remoteSyncRepository.password())
        }
        val remotePathInput = plainInput("远程备份目录").apply { setText(config.remotePath) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldLabel("主机"))
            addView(hostInput)
            addView(fieldLabel("端口"))
            addView(portInput)
            addView(fieldLabel("用户名"))
            addView(usernameInput)
            addView(fieldLabel("密码"))
            addView(passwordInput)
            addView(fieldLabel("远程备份目录"))
            addView(remotePathInput)
        }

        AlertDialog.Builder(this)
            .setTitle("SSH/SFTP 备份")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                try {
                    val next = RemoteSyncConfig(
                        host = hostInput.text.toString(),
                        port = portInput.text.toString().toIntOrNull() ?: 22,
                        username = usernameInput.text.toString(),
                        remotePath = remotePathInput.text.toString()
                    )
                    remoteSyncRepository.saveConfig(next, passwordInput.text.toString())
                    showSettings("远程备份配置已保存")
                } catch (error: Exception) {
                    showSettings(error.readableMessage())
                }
            }
            .show()
    }

    private fun openAutofillSettings() {
        val directSettings = listOf(
            Intent(ACTION_AUTOFILL_SETTINGS),
            Intent(ACTION_AUTOFILL_SERVICE_SETTINGS)
        )
        if (directSettings.any { intent -> tryStartActivity(intent) }) {
            showToast("请在自动填充设置中选择密码箱")
            return
        }

        val requestIntent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra("android.provider.extra.AUTOFILL_SERVICE", "$packageName/.autofill.VaultAutofillService")
            data = android.net.Uri.parse("package:$packageName")
        }
        runCatching { startActivityForResult(requestIntent, REQUEST_AUTOFILL_SERVICE) }
            .onFailure {
                openAutofillSettingsFallback()
            }
    }

    private fun autofillStatusLabel(): String {
        val secureService = currentAutofillServiceSetting()
        if (secureService?.contains(packageName) == true) {
            return "当前系统自动填充服务：密码箱"
        }
        val manager = getSystemService(AutofillManager::class.java)
            ?: return "当前设备未提供系统自动填充管理器"
        if (!manager.isAutofillSupported) return "当前设备不支持系统自动填充"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val component = manager.autofillServiceComponentName
                ?: return if (secureService.isNullOrBlank()) {
                    "当前系统自动填充服务：未设置"
                } else {
                    "当前系统自动填充服务：$secureService"
                }
            return if (component.packageName == packageName) {
                "当前系统自动填充服务：密码箱"
            } else {
                "当前系统自动填充服务：${component.packageName}，不是密码箱"
            }
        }
        return if (manager.hasEnabledAutofillServices()) {
            "系统已启用自动填充；Android 8.0/8.1 无法在应用内确认当前服务"
        } else {
            "当前系统自动填充服务：未设置"
        }
    }

    private fun isPasswordBoxAutofillEnabled(): Boolean {
        if (currentAutofillServiceSetting()?.contains(packageName) == true) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = getSystemService(AutofillManager::class.java) ?: return false
        return manager.autofillServiceComponentName?.packageName == packageName
    }

    private fun currentAutofillServiceSetting(): String? =
        Settings.Secure.getString(contentResolver, "autofill_service")

    private fun openAutofillSettingsFallback() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val opened = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (opened) {
            showToast("请在系统设置中选择密码箱作为自动填充服务")
        } else {
            showToast("无法打开系统设置")
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)

    private fun showAutofillDiagnosticsDialog() {
        AlertDialog.Builder(this)
            .setTitle("自动填充调用日志")
            .setMessage(AutofillDiagnostics.summary(this))
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun uploadRemoteBackup() {
        runBusy("正在上传备份") {
            remoteSyncRepository.uploadBackup()
        }.onSuccess { message ->
            showSettings(message)
        }.onError { error ->
            showSettings(error.readableMessage())
        }
    }

    private fun autoUploadBackup(successMessage: String, notifyOnSuccess: Boolean) {
        if (!remoteSyncRepository.hasConfig() || autoSyncRunning) return
        autoSyncRunning = true
        thread(name = "VaultBoxAutoSync") {
            val result = runCatching { remoteSyncRepository.uploadBackup() }
            runOnUiThread {
                autoSyncRunning = false
                result.onSuccess {
                    if (notifyOnSuccess) showToast(successMessage)
                }.onFailure { error ->
                    showToast("远程同步失败：${error.readableMessage()}")
                }
            }
        }
    }

    private fun chooseRemoteBackupToRestore() {
        runBusy("正在读取备份列表") {
            remoteSyncRepository.listBackups()
        }.onSuccess { backups ->
            if (backups.isEmpty()) {
                showSettings("没有找到远程备份")
            } else {
                showBackupPicker(backups)
            }
        }.onError { error ->
            showSettings(error.readableMessage())
        }
    }

    private fun showBackupPicker(backups: List<RemoteBackupPoint>) {
        val labels = backups.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择恢复时间")
            .setItems(labels) { _, index ->
                confirmRestoreRemoteBackup(backups[index])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRestoreRemoteBackup(point: RemoteBackupPoint) {
        AlertDialog.Builder(this)
            .setTitle("恢复备份")
            .setMessage("将恢复 ${point.label} 的备份，并覆盖当前本地数据。确定继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ -> downloadRemoteBackup(point) }
            .show()
    }

    private fun downloadRemoteBackup(point: RemoteBackupPoint) {
        runBusy("正在恢复备份") {
            repository.close()
            remoteSyncRepository.downloadBackup(point)
        }.onSuccess { message ->
            MasterPassword.wipe(dataKey)
            dataKey = null
            repository = VaultRepository(this)
            triedAutoBiometric = false
            showUnlock(message, autoPrompt = false)
        }.onError { error ->
            repository = VaultRepository(this)
            showSettings(error.readableMessage())
        }
    }

    private fun startBiometricUnlock() {
        if (!repository.hasDeviceUnlock()) {
            showUnlock("还没有开启指纹解锁")
            return
        }
        if (!canUseBiometric()) {
            requestLegacyDeviceAuth(DeviceAction.LegacyUnlock)
            return
        }
        val cipher = try {
            repository.createDeviceUnlockDecryptCipher()
        } catch (error: UserNotAuthenticatedException) {
            repository.disableDeviceUnlock()
            showUnlock("指纹解锁配置已更新，请用主密码解锁后重新开启")
            return
        } catch (error: KeyPermanentlyInvalidatedException) {
            repository.disableDeviceUnlock()
            showUnlock("指纹设置已变化，请用主密码重新开启指纹解锁")
            return
        } catch (error: Throwable) {
            showUnlock(error.readableMessage())
            return
        }
        authenticateWithBiometric(
            title = "指纹解锁",
            subtitle = "解锁密码箱",
            negative = "使用主密码",
            cipher = cipher,
            action = DeviceAction.Unlock
        )
    }

    private fun startBiometricEnable() {
        val key = dataKey ?: return lockSession()
        if (!canUseBiometric()) {
            showSettings("当前设备没有可用指纹")
            return
        }
        val cipher = try {
            repository.createDeviceUnlockEncryptCipher()
        } catch (error: Throwable) {
            showSettings(error.readableMessage())
            return
        }
        authenticateWithBiometric(
            title = "开启指纹解锁",
            subtitle = "确认后将用指纹保护本机快速解锁密钥",
            negative = "取消",
            cipher = cipher,
            action = DeviceAction.Enable(key)
        )
    }

    private fun authenticateWithBiometric(
        title: String,
        subtitle: String,
        negative: String,
        cipher: Cipher,
        action: DeviceAction
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showToast("当前系统版本不支持指纹弹窗")
            return
        }
        biometricCancellation?.cancel()
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        val builder = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negative, mainExecutor) { _, _ ->
                biometricCancellation = null
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        val prompt = builder.build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            cancellation,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricCancellation = null
                    val authedCipher = result.cryptoObject?.cipher ?: cipher
                    when (action) {
                        DeviceAction.Unlock -> unlockWithBiometricCipher(authedCipher)
                        is DeviceAction.Enable -> enableBiometricWithCipher(action.dataKey, authedCipher)
                        DeviceAction.LegacyUnlock -> Unit
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    biometricCancellation = null
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BIOMETRIC_ERROR_NEGATIVE_BUTTON
                    ) {
                        showToast(errString?.toString() ?: "指纹验证失败")
                    }
                }

                override fun onAuthenticationFailed() {
                    showToast("指纹不匹配")
                }
            }
        )
    }

    private fun unlockWithBiometricCipher(cipher: Cipher) {
        runBusy("正在解锁") {
            repository.unlockWithDevice(cipher)
        }.onSuccess { key ->
            dataKey = key
            repository.preferredUnlock = VaultRepository.UNLOCK_DEVICE
            showHome()
        }.onError { error ->
            if (error is UserNotAuthenticatedException) {
                repository.disableDeviceUnlock()
                showUnlock("指纹解锁配置已更新，请用主密码解锁后重新开启")
            } else {
                showUnlock(error.readableMessage())
            }
        }
    }

    private fun enableBiometricWithCipher(key: ByteArray, cipher: Cipher) {
        runBusy("正在开启") {
            repository.enableDeviceUnlock(key, cipher)
        }.onSuccess {
            showHome("已开启指纹解锁，并设为默认")
        }.onError { error -> showSettings(error.readableMessage()) }
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

    private fun requestLegacyDeviceAuth(action: DeviceAction) {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent("密码箱", "确认身份")
        if (intent == null) {
            showToast("系统未设置锁屏密码")
            return
        }
        pendingDeviceAction = action
        startActivityForResult(intent, REQUEST_DEVICE_AUTH)
    }

    private fun unlockWithLegacyDeviceAfterAuth() {
        runBusy("正在解锁") { repository.unlockWithDevice() }
            .onSuccess { key ->
                dataKey = key
                showHome()
            }
            .onError { error -> showUnlock(error.readableMessage()) }
    }

    private fun entryRow(entry: VaultEntrySummary): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = roundedBackground(COLOR_SURFACE, 14, COLOR_STROKE)
            layoutParams = spacedParams(bottom = 12)

            addView(horizontal {
                addView(faviconView(entry.website, entry.title))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = entry.title
                        setTextColor(COLOR_TEXT)
                        textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    })
                    addView(TextView(context).apply {
                        text = listOf(entry.username, displayHost(entry.website), entry.appPackage)
                            .filter { it.isNotBlank() }
                            .joinToString("  ")
                            .ifBlank { "无账号、网址或 App 关联" }
                        setTextColor(COLOR_MUTED)
                        textSize = 13f
                        maxLines = 1
                        setPadding(0, dp(3), 0, 0)
                    })
                })
                addView(categoryPill(entry.category))
            })
            addView(horizontal {
                addView(rowButton("复制") { copyEntryPassword(entry.id) })
                addView(rowButton("编辑") { openEntryEditor(entry.id) })
            })
        }

    private fun faviconView(website: String, fallback: String): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(12), 0)
            }
            background = roundedBackground(COLOR_ICON_BG, 12, 0)
            val bitmap = faviconRepository.cachedBitmap(website)
            if (bitmap != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
                })
            } else {
                addView(TextView(context).apply {
                    text = fallback.firstOrNull()?.uppercase() ?: "?"
                    setTextColor(COLOR_PRIMARY)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                })
            }
        }

    private fun prefetchFavicons(entries: List<VaultEntrySummary>) {
        entries.map { it.website }.filter { it.isNotBlank() }.distinct().forEach { website ->
            if (faviconRepository.cachedIconFile(website) == null && faviconLoads.add(website)) {
                thread(name = "VaultBoxFavicon") {
                    val bitmap = faviconRepository.fetchIfNeeded(website)
                    runOnUiThread {
                        faviconLoads.remove(website)
                        if (bitmap != null && activeScreen == Screen.Home) refreshHomeList()
                    }
                }
            }
        }
    }

    private fun fetchFavicon(website: String, onDone: (() -> Unit)? = null) {
        if (website.isBlank()) {
            onDone?.invoke()
            return
        }
        thread(name = "VaultBoxFavicon") {
            runCatching { faviconRepository.fetchIfNeeded(website) }
            onDone?.let { runOnUiThread(it) }
        }
    }

    private fun openEntryEditor(id: Long) {
        val key = dataKey ?: return lockSession()
        val entry = repository.getEntry(id, key)
        if (entry == null) {
            showToast("条目不存在")
            showHome()
        } else {
            showEditor(entry)
        }
    }

    private fun copyEntryPassword(id: Long) {
        val key = dataKey ?: return lockSession()
        val password = repository.getEntry(id, key)?.password
        if (password == null) {
            showToast("条目不存在")
            showHome()
        } else {
            copySecret("secret", password)
        }
    }

    private fun confirmDelete(entry: VaultEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除条目")
            .setMessage("确定删除「${entry.title}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val key = dataKey ?: return@setPositiveButton lockSession()
                repository.deleteEntry(entry.id, key)
                showHome("已删除")
                autoUploadBackup("已删除并同步", notifyOnSuccess = false)
            }
            .show()
    }

    private fun confirmDeleteCategory(category: String) {
        AlertDialog.Builder(this)
            .setTitle("删除分类")
            .setMessage("删除「$category」后，该分类下的条目会移动到「${VaultCategory.DEFAULT_PASSWORD}」。确定继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val key = dataKey ?: return@setPositiveButton lockSession()
                try {
                    val movedCount = repository.deleteCategory(category, key)
                    if (selectedCategory == category) selectedCategory = null
                    showSettings("已删除分类：$category，移动 $movedCount 个条目")
                    autoUploadBackup("已删除分类并同步", notifyOnSuccess = false)
                } catch (error: Exception) {
                    showSettings(error.readableMessage())
                }
            }
            .show()
    }

    private fun promptAddCategory(onAdded: (String) -> Unit) {
        val input = plainInput("分类名称")
        AlertDialog.Builder(this)
            .setTitle("新增分类")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                try {
                    repository.addCategory(input.text.toString())
                    onAdded(input.text.toString().trim())
                } catch (error: Exception) {
                    showToast(error.readableMessage())
                }
            }
            .show()
    }

    private fun lockSession() {
        clearClipboardTimer()
        MasterPassword.wipe(dataKey)
        dataKey = null
        lastBackgroundAt = 0L
        searchQuery = ""
        selectedCategory = null
        triedAutoBiometric = false
        if (repository.hasVault()) showUnlock(autoPrompt = true) else showCreateVault()
    }

    private fun copySecret(label: String, value: String) {
        if (value.isEmpty()) {
            showToast("没有可复制的内容")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        clearClipboardTimer()
        val runnable = Runnable {
            val current = clipboard.primaryClip
            if (current != null && current.itemCount > 0 && current.getItemAt(0).text == value) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        clipboardClearRunnable = runnable
        handler.postDelayed(runnable, CLIPBOARD_CLEAR_MILLIS)
        showToast("已复制，30 秒后清空")
    }

    private fun clearClipboardTimer() {
        clipboardClearRunnable?.let(handler::removeCallbacks)
        clipboardClearRunnable = null
    }

    private fun <T> runBusy(message: String, block: () -> T): PendingResult<T> {
        showBusy(message)
        val pending = PendingResult<T>()
        thread(name = "VaultBoxWorker") {
            try {
                val result = block()
                runOnUiThread { pending.deliverSuccess(result) }
            } catch (error: Throwable) {
                runOnUiThread { pending.deliverError(error) }
            }
        }
        return pending
    }

    private fun showBusy(message: String) {
        setContentView(page {
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = message
                textSize = 22f
                setTextColor(COLOR_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        })
    }

    private fun page(topPadding: Int = 28, build: LinearLayout.() -> Unit): ScrollView =
        ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), systemTopInset() + dp(topPadding), dp(18), dp(28))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                build()
            })
        }

    private fun heroHeader(title: String, subtitle: String, showIcon: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(18))
            addView(TextView(context).apply {
                text = title
                textSize = 34f
                setTextColor(COLOR_TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            if (showIcon) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.app_icon)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedBackground(COLOR_SURFACE, 18, COLOR_STROKE)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(dp(86), dp(86)).apply {
                        setMargins(0, dp(12), 0, dp(10))
                    }
                })
            }
            addView(TextView(context).apply {
                text = subtitle
                textSize = 17f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun toolbar(title: String, actions: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
            addView(TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT)
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            actions()
        }

    private fun statsBand(entries: List<VaultEntrySummary>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(COLOR_PANEL, 16, 0)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = spacedParams(bottom = 14)
            addView(statBlock("条目", entries.size.toString()))
            addView(statBlock("分类", repository.categories().size.toString()))
            addView(statBlock("默认", if (repository.preferredUnlock == VaultRepository.UNLOCK_DEVICE) "指纹" else "主密码"))
        }

    private fun statBlock(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = value
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
            })
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(COLOR_MUTED)
            })
        }

    private fun fingerprintPanel(action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(COLOR_PRIMARY_SOFT, 16, 0)
            layoutParams = spacedParams(bottom = 16)
            addView(TextView(context).apply {
                text = "指纹"
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_PRIMARY)
                background = roundedBackground(COLOR_SURFACE, 14, 0)
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                    setMargins(0, 0, dp(14), 0)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "点击后调用系统生物识别验证"
                    textSize = 13f
                    setTextColor(COLOR_MUTED)
                })
            })
            addView(iconButton("解锁", COLOR_PRIMARY, Color.WHITE, action))
            setOnClickListener { action() }
        }

    private fun infoPanel(text: String): View =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(COLOR_PANEL, 12, 0)
            layoutParams = spacedParams(bottom = 14)
        }

    private fun formCard(build: LinearLayout.() -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(4))
            background = roundedBackground(COLOR_SURFACE, 16, COLOR_STROKE)
            layoutParams = spacedParams(bottom = 14)
            build()
        }

    private fun settingsCard(title: String, build: LinearLayout.() -> Unit): View =
        formCard {
            addView(TextView(context).apply {
                text = title
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                setPadding(0, 0, 0, dp(8))
            })
            build()
        }

    private fun categoryStrip(categories: List<String>): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = spacedParams(bottom = 14)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip("全部", selectedCategory == null) {
                    selectedCategory = null
                    showHome()
                })
                categories.forEach { category ->
                    addView(chip(category, selectedCategory == category) {
                        selectedCategory = category
                        showHome()
                    })
                }
            })
        }

    private fun chip(text: String, selected: Boolean, action: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(if (selected) COLOR_PRIMARY else COLOR_SURFACE, 18, COLOR_STROKE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { setMargins(0, 0, dp(8), 0) }
            setOnClickListener { action() }
        }

    private fun settingsCategoryWrap(): View =
        horizontalWrap().apply {
            repository.categories().forEach { category ->
                addView(settingsCategoryChip(category))
            }
            addView(settingsAddCategoryChip())
        }

    private fun settingsCategoryChip(category: String): View {
        val removable = category !in VaultCategory.defaults
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply { setMargins(0, 0, dp(8), dp(8)) }

            addView(TextView(context).apply {
                text = category
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, if (removable) dp(24) else dp(16), 0)
                background = roundedBackground(COLOR_SURFACE, 18, COLOR_STROKE)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36),
                    Gravity.BOTTOM or Gravity.START
                )
            })

            if (removable) {
                addView(TextView(context).apply {
                    text = "x"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = roundedBackground(COLOR_DANGER, 9, 0)
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
                    setOnClickListener { confirmDeleteCategory(category) }
                })
            }
        }
    }

    private fun settingsAddCategoryChip(): View =
        TextView(this).apply {
            text = "+"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_PRIMARY)
            gravity = Gravity.CENTER
            background = roundedBackground(COLOR_PRIMARY_SOFT, 18, COLOR_STROKE)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(36)).apply {
                setMargins(0, 0, dp(8), dp(8))
            }
            setOnClickListener {
                promptAddCategory { showSettings("已新增分类：$it") }
            }
        }

    private fun categoryPill(category: String): View =
        TextView(this).apply {
            text = category
            textSize = 12f
            setTextColor(COLOR_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(COLOR_PRIMARY_SOFT, 14, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(28)
            ).apply { setMargins(dp(8), 0, 0, 0) }
        }

    private fun searchInput(value: String): EditText =
        plainInput("搜索标题、账号、网址、分类").apply {
            setText(value)
            background = roundedBackground(COLOR_SURFACE, 14, COLOR_STROKE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    handler.removeCallbacksAndMessages(SEARCH_REFRESH_TOKEN)
                    handler.postAtTime({ refreshHomeList() }, SEARCH_REFRESH_TOKEN, SystemClock.uptimeMillis() + 120L)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

    private fun emptyPanel(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_MUTED)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
            background = roundedBackground(COLOR_PANEL, 16, 0)
            layoutParams = spacedParams(bottom = 12)
        }

    private fun fieldLabel(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_MUTED)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(8), 0, dp(6))
        }

    private fun label(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_MUTED)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(10))
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

    private fun dividerLabel(text: String): View =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

    private fun plainInput(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_HINT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_INPUT, 12, COLOR_STROKE)
            layoutParams = spacedParams(height = dp(50), bottom = 10)
        }

    private fun passwordInput(hintText: String): EditText =
        plainInput(hintText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun spinner(options: List<String>, selected: String, onSelected: (String) -> Unit): Spinner =
        Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                options
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            this.adapter = adapter
            setSelection(options.indexOf(selected).coerceAtLeast(0), false)
            background = roundedBackground(COLOR_INPUT, 12, COLOR_STROKE)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = spacedParams(height = dp(50), bottom = 10)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                private var initialized = false

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val value = options[position]
                    if (!initialized) {
                        initialized = true
                        return
                    }
                    onSelected(value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

    private fun autoLockOptions(): List<Pair<String, Long>> =
        listOf(
            "30 秒" to 30_000L,
            "2 分钟" to 2 * 60_000L,
            "5 分钟" to 5 * 60_000L,
            "15 分钟" to 15 * 60_000L
        )

    private fun primaryButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_PRIMARY, Color.WHITE, action)

    private fun secondaryButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_SURFACE, COLOR_TEXT, action)

    private fun dangerButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_DANGER, Color.WHITE, action)

    private fun rowButton(text: String, action: () -> Unit): Button =
        button(text, COLOR_PANEL, COLOR_TEXT, action).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, dp(10), dp(8), 0)
            }
        }

    private fun iconButton(text: String, bgColor: Int, fgColor: Int, action: () -> Unit): Button =
        button(text, bgColor, fgColor, action).apply {
            minWidth = dp(52)
            minHeight = dp(42)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply { setMargins(dp(8), 0, 0, 0) }
        }

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

    private fun horizontal(build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            build()
        }

    private fun horizontalWrap(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun showAppPicker(
        currentPackages: String,
        onSelected: (String) -> Unit,
        onClear: () -> Unit
    ) {
        val apps = installedLaunchableApps()
        if (apps.isEmpty()) {
            showToast("没有找到可选择的应用")
            return
        }
        val searchInput = plainInput("搜索应用名或包名")
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(searchInput)
            addView(ScrollView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(420)
                )
                addView(listContainer)
            })
        }
        lateinit var dialog: AlertDialog
        fun render(query: String) {
            val needle = query.trim().lowercase()
            val filtered = if (needle.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.lowercase().contains(needle) ||
                        it.packageName.lowercase().contains(needle)
                }
            }
            listContainer.removeAllViews()
            if (filtered.isEmpty()) {
                listContainer.addView(label("没有匹配的应用"))
            } else {
                filtered.forEach { app ->
                    listContainer.addView(appChoiceRow(app) {
                        onSelected(app.packageName)
                        showToast("已关联 ${app.label}")
                        dialog.dismiss()
                    })
                }
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog = AlertDialog.Builder(this)
            .setTitle("选择关联 App")
            .setView(content)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除全部") { _, _ ->
                if (currentPackages.isNotBlank()) onClear()
            }
            .create()
        dialog.setOnShowListener { render("") }
        dialog.show()
    }

    private fun appChoiceRow(app: AppChoice, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(COLOR_SURFACE, 10, COLOR_STROKE)
            layoutParams = spacedParams(bottom = 8)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = app.label
                setTextColor(COLOR_TEXT)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            addView(TextView(context).apply {
                text = app.packageName
                setTextColor(COLOR_MUTED)
                textSize = 12f
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun installedLaunchableApps(): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolveInfos
            .mapNotNull { info -> info.toAppChoice() }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun ResolveInfo.toAppChoice(): AppChoice? {
        val packageName = activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return null
        val label = loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
        return AppChoice(label = label, packageName = packageName)
    }

    private fun appAssociationLabel(packages: String): String {
        val cleanPackages = splitPackageAssociation(packages)
        if (cleanPackages.isEmpty()) return "未关联 App"
        val labels = cleanPackages.map { packageName ->
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            "$label\n$packageName"
        }
        return labels.joinToString("\n\n")
    }

    private fun mergePackageAssociation(currentPackages: String, packageName: String): String {
        if (packageName.isBlank()) return ""
        val next = (splitPackageAssociation(currentPackages) + packageName.trim()).distinct()
        return next.joinToString(APP_PACKAGE_SEPARATOR)
    }

    private fun splitPackageAssociation(packages: String): List<String> =
        packages.split(APP_PACKAGE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun spacedParams(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        bottom: Int = 12
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(0, 0, 0, dp(bottom))
        }

    private fun roundedBackground(color: Int, radiusDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    private fun displayHost(website: String): String {
        val clean = website.removePrefix("https://").removePrefix("http://")
        return clean.substringBefore('/').trim()
    }

    private fun String.ifBlank(fallback: () -> String): String =
        if (isBlank()) fallback() else this

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun systemTopInset(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun Throwable.readableMessage(): String =
        message ?: cause?.message ?: "操作失败"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

    private sealed class DeviceAction {
        object Unlock : DeviceAction()
        data class Enable(val dataKey: ByteArray) : DeviceAction()
        object LegacyUnlock : DeviceAction()
    }

    private enum class Screen {
        Create,
        Locked,
        Home,
        Editor,
        Settings
    }

    private data class AppChoice(
        val label: String,
        val packageName: String
    )

    companion object {
        private const val REQUEST_DEVICE_AUTH = 42
        private const val REQUEST_AUTOFILL_SERVICE = 43
        private const val CLIPBOARD_CLEAR_MILLIS = 30_000L
        private const val BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13
        private const val APP_PACKAGE_SEPARATOR = ","
        private const val ACTION_AUTOFILL_SETTINGS = "android.settings.AUTOFILL_SETTINGS"
        private const val ACTION_AUTOFILL_SERVICE_SETTINGS = "android.settings.AUTOFILL_SERVICE_SETTINGS"
        private val SEARCH_REFRESH_TOKEN = Any()
        private const val COLOR_BACKGROUND = 0xFFF4F5F0.toInt()
        private const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private const val COLOR_PANEL = 0xFFE9EFEA.toInt()
        private const val COLOR_INPUT = 0xFFFAFBF8.toInt()
        private const val COLOR_TEXT = 0xFF1D2421.toInt()
        private const val COLOR_MUTED = 0xFF66736E.toInt()
        private const val COLOR_HINT = 0xFF96A19C.toInt()
        private const val COLOR_PRIMARY = 0xFF1F6B58.toInt()
        private const val COLOR_PRIMARY_SOFT = 0xFFDCEBE5.toInt()
        private const val COLOR_DANGER = 0xFFB5403A.toInt()
        private const val COLOR_DANGER_SOFT = 0xFFF4E1DF.toInt()
        private const val COLOR_STROKE = 0xFFD8DFDA.toInt()
        private const val COLOR_ICON_BG = 0xFFE5E9DE.toInt()
    }
}
