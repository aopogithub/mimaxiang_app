# 密码箱

密码箱 is a local-first Android password vault MVP written in Kotlin with platform UI and cryptography APIs, and JSch for SSH/SFTP.

## Current Features

- Create and unlock a local vault with a master password.
- Encrypt every stored entry field before writing to SQLite.
- Use a random vault data key wrapped by a key derived from the master password.
- Default biometric unlock through Android Keystore and platform `BiometricPrompt` when the device supports strong biometrics.
- Custom categories, with default `密码` and `API 密钥` categories.
- Website URLs on entries, with best-effort favicon caching from the site's own `/favicon.ico`.
- SSH/SFTP remote backup upload and restore with selectable restore points. The remote server password is stored locally with Android Keystore.
- Add, edit, delete, search, and copy password entries.
- Generate strong passwords locally with `SecureRandom`.
- Auto-lock after a configurable background timeout.
- Block screenshots with `FLAG_SECURE`.
- Clear copied passwords from the clipboard after 30 seconds.
- Exclude app files, preferences, and databases from Android backup/transfer by default.

## Security Model

The vault uses a random 256-bit data key to encrypt entry fields with AES-GCM. The data key is wrapped by a key derived from the master password using PBKDF2-HMAC-SHA256 and a per-vault salt. Android Keystore protects local biometric unlock and device-local secrets/matching metadata; it is not the only vault recovery path.

The app does not implement plaintext export, analytics, or crash upload. Network access is used to fetch favicons for websites the user enters and to upload/download encrypted backup bundles over SSH/SFTP. Remote backups keep one backup per day for the last 30 days, then one backup per month for older backups.

## Autofill

- 密码箱只在当前网站或 App 有匹配的非空密码时提供自动填充候选；没有匹配项时不显示，也不会解锁后回退到全部条目。
- 网页按条目“网址”的域名精确匹配，忽略大小写、`www.`、路径和端口，不自动匹配其他父域或子域。请填写实际登录页面的域名。
- 原生 App 按条目中的“关联 App”精确匹配。网页不会退回浏览器或 WebView 宿主 App 的包名匹配；不会根据标题或包名片段猜测。
- 升级或恢复备份后，先手动打开并解锁一次密码箱以建立匹配索引。之后新增、编辑、删除和成功解锁会自动更新索引。
- 锁定状态下只读取设备本地的最小匹配索引（规范化域名和关联包名），通过现有 Android Keystore `LocalSecretStore` 加密保存，不包含账号、密码或保险箱密钥，也不进入远程备份。索引缺失、读取失败或损坏时不提供候选，成功解锁后重建。
- 填充仍须主密码或生物识别认证；选择条目和返回密码前均重新检查匹配。

## Build

The project expects the Android SDK at `/opt/android-sdk` and a full JDK at `/usr/lib/jvm/java-21-openjdk-amd64`, configured in `local.properties`.

```bash
GRADLE=/root/.gradle/wrapper/dists/gradle-8.14-all/c2qonpi39x1mddn7hk5gh9iqj/gradle-8.14/bin/gradle
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$GRADLE" assembleDebug --offline
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Tests

Use JDK 21 and the `GRADLE` path above. The first build needs network access if dependencies are not cached (omit `--offline`).

```bash
"$GRADLE" testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
# With an Android device or emulator connected:
"$GRADLE" connectedDebugAndroidTest --offline
```

JVM tests cover host normalization, exact app matching, misleading domains and missing targets. Platform instrumentation tests use isolated databases/preferences and cover encrypted index storage, locked lookups, create/unlock/update/delete, corruption and restore invalidation. Biometric prompts and actual browser/keyboard integration still require device testing.

Manual check: save an entry for a test website and/or associated App, lock the vault, and focus its password field. A matching target should offer 密码箱 and require authentication; an unrelated target should offer nothing from 密码箱. Edit or delete the last matching entry and repeat; its old target must no longer offer 密码箱. After restoring a backup, unlock the vault once before repeating.

## Next Work

- Add encrypted backup/import using the same master-password recovery path.
- Add instrumentation tests for create/unlock/save/delete flows.
- Replace the platform View UI with Compose if AndroidX dependencies are available.
- Migrate SQLiteOpenHelper to Room if dependencies are available.
- Consider Argon2id or scrypt through a vetted library when dependency policy allows it.
