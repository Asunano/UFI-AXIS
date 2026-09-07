package com.ufi_axis_core.controller.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppManager(
    private val context: Context? = null
) {
    private val tag = "AppManager"

    /** adb install 输出读取上限（1MB，防 OOM） */
    private val MAX_ADB_OUTPUT_SIZE = 1_048_576

    /** 应用图标 base64 缓存（packageName → PNG base64），APK 更新后 apkPath 变化由调用方负责失效。 */
    private val iconCache = ConcurrentHashMap<String, String>()

    /**
     * 系统应用 APK 路径权威判定前缀集合。
     * 覆盖 /system、/system_ext、/vendor、/product、/oem、/system_carrier 等常见系统分区，
     * 解决原 `apkPath.contains("/system/")` 大小写敏感且仅覆盖单一路径导致的分类错误
     * （如 /data/app/ 下的应用被错误归到系统、/system_ext 等路径漏判）。
     */
    private val SYSTEM_PATH_PREFIXES = listOf(
        "/system/", "/system_ext/", "/vendor/", "/product/app/", "/product/priv-app/",
        "/oem/app/", "/oem/priv-app/", "/system_carrier/", "/system_ext/priv-app/",
        // APEX（Android 11+ 镜像）：/apex/ 下挂载的系统模块视为系统应用（2026-08-19 修复 devicelockcontroller 误判为 user）。
        "/apex/"
    )

    /** 依据 APK 路径前缀判定是否为系统应用（大小写敏感，匹配真实挂载路径）。 */
    private fun isSystemApp(apkPath: String): Boolean =
        apkPath.isNotBlank() && SYSTEM_PATH_PREFIXES.any { apkPath.startsWith(it, ignoreCase = false) }

    /** MATCH_UNINSTALLED_PACKAGES：包含对当前用户隐藏/已卸载残留的包，对齐 shell pm list 的可见范围。 */
    private val MATCH_UNINSTALLED_PKGS = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES

    suspend fun hasRoot(): Boolean = ShellExecutor.hasRootAccess()

    suspend fun listApps(type: String = "all"): List<Map<String, Any>> {
        // 统一拉取全部包：不再依赖 pm 的 -s/-3 标记（root 模拟器/部分 ROM 上不可靠，会导致分类错乱）。
        val result = ShellExecutor.executeAsRoot("pm list packages -f 2>/dev/null")
        if (!result.isSuccess || result.stdout.isBlank()) return emptyList()

        val disabledPkgs = ShellExecutor.executeAsRoot("pm list packages -d 2>/dev/null").stdout.lines()
            .mapNotNull { it.removePrefix("package:").trim().toIfNotEmpty() }.toSet()
        val suspendedPkgs = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ShellExecutor.executeAsRoot("pm list packages --suspended 2>/dev/null").stdout.lines()
                .mapNotNull { it.removePrefix("package:").trim().toIfNotEmpty() }.toSet()
        } else emptySet()

        // 批量获取版本名: dumpsys package packages 输出所有包的 versionName
        val versionNames = mutableMapOf<String, String>()
        val dumpsys = ShellExecutor.executeAsRoot("dumpsys package packages 2>/dev/null | grep -E '^  Package |versionName='")
        if (dumpsys.isSuccess) {
            var currentPkg = ""
            dumpsys.stdout.lines().forEach { line ->
                val pkgMatch = Regex("^  Package \\[([^\\]]+)\\]").find(line) // "  Package [com.example.app]"
                // 有些设备输出 "  Package [pkg]" (无空格)，有些是 "  Package  [pkg]" (双空格)
                val altPkgMatch = if (pkgMatch == null) Regex("^  Package\\[([^\\]]+)\\]").find(line) else null
                if (pkgMatch != null) {
                    currentPkg = pkgMatch.groupValues[1]
                } else {
                    val verMatch = Regex("versionName=([^\\s]+)").find(line)
                    if (verMatch != null && currentPkg.isNotEmpty()) {
                        versionNames[currentPkg] = verMatch.groupValues[1]
                    }
                }
            }
        }

        val all = result.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val clean = line.trim()
                val equalsIdx = clean.lastIndexOf('=')
                if (equalsIdx < 0) return@mapNotNull null
                val pkg = clean.substring(equalsIdx + 1).trim()
                val apkPath = clean.substring(clean.indexOf('/'), equalsIdx).trim()
                mapOf(
                    "packageName" to pkg,
                    "appName" to (getApplicationLabel(pkg).takeIf { it.isNotBlank() } ?: pkg),
                    "apkPath" to apkPath,
                    "isSystem" to isSystemApp(apkPath),
                    // v17：dumpsys 解析不到时 fallback PackageManager（避免列表 v0 显示）
                    "versionName" to (versionNames[pkg] ?: getVersionFromPackageManager(pkg).first),
                    "enabled" to (pkg !in disabledPkgs),
                    "isFrozen" to (pkg in suspendedPkgs),
                    "iconBase64" to getAppIconBase64(pkg)
                )
            } catch (e: Exception) {
                null
            }
        }
        // 内存里按 type 二次过滤（先标 isSystem 再过滤），彻底摆脱 pm 标记不可靠问题。
        return when (type) {
            "system" -> all.filter { it["isSystem"] == true }
            "user" -> all.filter { it["isSystem"] == false }
            else -> all
        }
    }

    private fun String.toIfNotEmpty(): String? = if (isNotBlank()) this else null

    suspend fun getAppInfo(packageName: String): Map<String, Any>? {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        // v18 性能优化：全部改 PackageManager（binder <100ms），不再用 pm path / dumpsys root shell（雷电上每次 200-800ms，累计 0.6-2.4s）
        val ctx = context ?: return null
        val pm = ctx.packageManager
        // 2026-08-22 修复详情 404：列表来自 shell pm（可见全部包，含对当前用户
        // 隐藏/已卸载残留的包），而 App 进程 PackageManager 默认视图查不到这些包 →
        // NameNotFoundException → 404。带上 MATCH_UNINSTALLED_PACKAGES 对齐两种视图。
        val appInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(MATCH_UNINSTALLED_PKGS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, MATCH_UNINSTALLED_PKGS)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "getApplicationInfo failed for $packageName: ${e.message}")
            return null
        }
        val apkPath = appInfo.sourceDir
        val pkgInfo = getPackageInfoCompat(pm, packageName, MATCH_UNINSTALLED_PKGS)
        val versionName = pkgInfo?.versionName ?: ""
        val versionCode = pkgInfo?.longVersionCode?.toString() ?: "0"
        val firstInstall = formatEpochMillis(pkgInfo?.firstInstallTime ?: 0L)
        val lastUpdate = formatEpochMillis(pkgInfo?.lastUpdateTime ?: 0L)
        val installer = try { pm.getInstallerPackageName(packageName) ?: "" } catch (e: Exception) { "" }
        // isEnabled：ApplicationInfo.enabled（新 SDK 已移除 FLAG_DISABLED，官方推荐用 enabled 属性）
        val isEnabled = appInfo.enabled
        // isSystem 判定：与 listApps 对齐，统一用 APK 路径前缀权威判定
        val isSystem = isSystemApp(apkPath)
        // 应用名（label）：v16 需求 — 弹窗头部展示"应用名"（如"Google Play 服务"）而非包名段
        val applicationLabel = getApplicationLabel(packageName)

        return mapOf(
            "packageName" to packageName,
            "appName" to applicationLabel,
            "applicationLabel" to applicationLabel,
            "versionName" to versionName,
            "versionCode" to versionCode,
            "firstInstallTime" to firstInstall,
            "lastUpdateTime" to lastUpdate,
            "installer" to installer,
            "isSystem" to isSystem,
            "enabled" to isEnabled,
            "isEnabled" to isEnabled,
            "apkPath" to apkPath
            // v18：iconBase64 不再在详情接口返回（4-5KB base64 是响应体大头），前端从 list 缓存复用
        )
    }

    /** PackageInfo 兼容获取（API 33+ 用 PackageInfoFlags）。flags 默认 0，详情路径传 MATCH_UNINSTALLED_PACKAGES。 */
    private fun getPackageInfoCompat(
        pm: android.content.pm.PackageManager,
        pkg: String,
        flags: Int = 0
    ): android.content.pm.PackageInfo? = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }
    } catch (e: Exception) {
        AppLogger.w(tag, "getPackageInfoCompat failed for $pkg: ${e.message}")
        null
    }

    /** epoch 毫秒 → "yyyy-MM-dd HH:mm:ss"；<=0 返回空串。 */
    private fun formatEpochMillis(ms: Long): String {
        if (ms <= 0) return ""
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))
        } catch (e: Exception) {
            ms.toString()
        }
    }

    /** PackageManager.getApplicationLabel → "Google Play 服务" 形式（用户可读名）。空时返回包名段。 */
    private fun getApplicationLabel(packageName: String): String {
        val ctx = context ?: return ""
        return try {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(MATCH_UNINSTALLED_PKGS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(packageName, MATCH_UNINSTALLED_PKGS)
            }
            ctx.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            AppLogger.w(tag, "getApplicationLabel failed for $packageName: ${e.message}")
            ""
        }
    }

    /** PackageManager 读取 versionName/versionCode（Pair）。API 33+ 用 PackageInfoFlags.of(0)。 */
    private fun getVersionFromPackageManager(packageName: String): Pair<String, String> {
        val ctx = context ?: return "" to "0"
        return try {
            val pm = ctx.packageManager
            val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            (pkgInfo.versionName ?: "") to pkgInfo.longVersionCode.toString()
        } catch (e: Exception) {
            AppLogger.w(tag, "getVersionFromPackageManager failed for $packageName: ${e.message}")
            "" to "0"
        }
    }

    /**
     * 通过 PackageManager 读取应用启动图标 → 缩放 64dp → PNG → base64。
     * 带 packageName 级缓存（iconCache），APK 更新后请调用 [invalidateIconCache] 失效。
     * 无需 root；对 disabled/系统应用同样可读（可能返回默认图标）。
     */
    private fun getAppIconBase64(packageName: String): String {
        iconCache[packageName]?.let { return it }
        val ctx = context ?: return ""
        val b64 = try {
            val drawable = ctx.packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, 64)
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "getAppIcon failed for $packageName: ${e.message}")
            ""
        }
        if (b64.isNotBlank()) iconCache[packageName] = b64
        return b64
    }

    /** Drawable → 等比缩放的 Bitmap（支持 BitmapDrawable / VectorDrawable）。 */
    private fun drawableToBitmap(drawable: Drawable, targetSize: Int): Bitmap {
        val src = (drawable as? BitmapDrawable)?.bitmap
        if (src != null && src.width == targetSize && src.height == targetSize) return src
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetSize
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetSize
        val scale = targetSize.toFloat() / maxOf(width, height)
        val outW = (width * scale).toInt().coerceAtLeast(1)
        val outH = (height * scale).toInt().coerceAtLeast(1)
        if (src != null) return Bitmap.createScaledBitmap(src, outW, outH, true)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, outW, outH)
        drawable.draw(canvas)
        return bitmap
    }

    suspend fun installApk(apkPath: String, isSelfUpdate: Boolean = false): InstallResult {
        require(validateShellArg(apkPath)) { "Invalid APK path: $apkPath" }
        // 先清扫历史残留：自更新（安装 core 自己）时进程会被安装器直接杀掉，
        // 函数末尾的 rm 永远执行不到。原来临时文件名带时间戳，于是每次自更新都在
        // /data/local/tmp 留下一个 ~60MB 的 APK —— 实测累积 40 个、2.1GB。
        sweepStaleInstallApks()
        // SELinux/fuse：/sdcard 文件对 shell(uid 2000) 与 adb client(uid=app) 不可读，
        // 复制到 /data/local/tmp 并 chmod 644 使其 world-readable，供 adb install 读取推送。
        // 固定文件名（不带时间戳）：天然覆盖，最多只占一份。
        val tmpApk = "/data/local/tmp/appmgr_install.apk"
        ShellExecutor.executeAsRoot("cp -f \"$apkPath\" \"$tmpApk\" && chmod 644 \"$tmpApk\"", 10_000L)
        // 主通道：adb connect localhost:5555 && adb install -r（uid 2000 shell，天然安装特权，
        // 无需 appops 放行、无需 PackageInstaller API，彻底规避旧通道"本地推送更新从未安装"）。
        val adbResult = installApkViaAdb(tmpApk)
        val result = if (adbResult.success) {
            adbResult
        } else {
            // adb 不可用/失败：回退 InstallService → PackageInstaller API（老通道，需 appops 放行）
            AppLogger.w(tag, "adb install 主通道失败/不可用，回退多策略安装: ${adbResult.message}")
            installApkMultiStrategy(tmpApk, isSelfUpdate)
        }
        // 2026-08-28：删除「安装后自动授予运行时权限」。
        // 主通道 adb install -r 是覆盖安装，运行时权限本身会被系统继承，这一步是多余的；
        // 需要手动授权仍可走 POST /api/apps/grant-all-permissions（web 应用页的按钮）。
        deleteTmpFile(tmpApk)
        return result
    }

    /**
     * 清扫 `/data/local/tmp` 下遗留的安装临时 APK（含历史带时间戳的 `appmgr_install_*.apk`）。
     *
     * 自更新会在删除前杀掉进程，所以残留只能靠「下一次安装开始时」兜底清理。
     */
    private suspend fun sweepStaleInstallApks() {
        runCatching {
            java.io.File("/data/local/tmp").listFiles { f ->
                f.isFile && f.name.startsWith("appmgr_install") && f.name.endsWith(".apk")
            }?.forEach { f ->
                val size = f.length()
                if (f.delete()) {
                    AppLogger.i(tag, "清理残留安装包 ${f.name} (${size / 1024}KB)")
                } else {
                    deleteTmpFile(f.absolutePath)
                }
            }
        }
    }

    /** 删除临时文件：先普通权限，再退回 root（这台设备无 root，普通删除才是主路径）。 */
    private suspend fun deleteTmpFile(path: String) {
        if (runCatching { java.io.File(path).delete() }.getOrDefault(false)) return
        runCatching { ShellExecutor.execute("rm -f \"$path\"", timeoutMs = 5_000L) }
        runCatching { ShellExecutor.executeAsRoot("rm -f \"$path\"", 5_000L) }
    }

    suspend fun installApkFromUrl(url: String): InstallResult {
        require(validateShellArg(url)) { "Invalid URL: $url" }
        // 2026-09-02：URL 会被插值进特权 curl/wget 命令。除了字符白名单（validateShellArg
        // 现已拦掉引号/换行），再加协议白名单——避免 file:// 之类把本地文件当"下载"读出来。
        require(url.startsWith("http://") || url.startsWith("https://")) { "Invalid URL scheme: $url" }
        val tmpPath = "/data/local/tmp/install_${System.currentTimeMillis()}.apk"
        val download = ShellExecutor.executeAsRoot("curl -sL -o \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
        if (!download.stdout.contains("OK")) {
            val wgetResult = ShellExecutor.executeAsRoot("wget -q -O \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
            if (!wgetResult.stdout.contains("OK")) return InstallResult(false, "下载失败: 不支持 curl/wget")
        }
        // 确保 APK 对 adb client(uid=app)/adbd(uid 2000) 可读（adbd/shell 不可读 /data/local/tmp 下 root 写的文件时回退）
        ShellExecutor.executeAsRoot("chmod 644 \"$tmpPath\"", 5_000L)
        // 主通道：adb install -r（回退多策略 InstallService → PackageInstaller API）
        val result = installApkViaAdb(tmpPath).let { adbRes ->
            if (adbRes.success) adbRes else installApkMultiStrategy(tmpPath)
        }
        // 2026-08-28：同 installApk，删除安装后自动授权（adb install -r 覆盖安装本就继承权限）
        deleteTmpFile(tmpPath)
        return result
    }

    // ── ADB 安装通道（2026-08-23：安装主通道改为 adb connect localhost:5555 && adb install -r）──
    // uid 2000 shell 经 adb 安装天然具备特权，无需 appops 放行、无需 PackageInstaller API，
    // 彻底规避旧通道"本地推送更新从未安装"（app uid 10101 缺 REQUEST_INSTALL_PACKAGES → PI 前置检查 abort）。

    /** 定位内置 adb 二进制（与 [grantAllPermissionsViaAdb] 同候选列表）。 */
    private fun resolveAdbBinary(): String? {
        val ctx = context ?: return null
        return listOf(
            File(ctx.filesDir, "shell/adb").absolutePath,
            "/data/local/tmp/ufi_axis/adb"
        ).firstOrNull { File(it).exists() && File(it).canExecute() }
    }

    /** 运行一条 adb 命令（带 ANDROID_* env 避免 ~/.android 权限问题），返回 ShellResult。 */
    private suspend fun runAdb(adbPath: String, args: List<String>, timeoutMs: Long): ShellExecutor.ShellResult =
        withContext(Dispatchers.IO) {
            try {
                val homeDir = context?.cacheDir?.absolutePath ?: "/data/local/tmp"
                val process = ProcessBuilder(listOf(adbPath) + args).apply {
                    environment()["ANDROID_ADB_HOME"] = homeDir
                    environment()["ANDROID_USER_HOME"] = homeDir
                    environment()["HOME"] = homeDir
                    environment()["ANDROID_SDK_HOME"] = homeDir
                    redirectErrorStream(true)
                }.start()
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                val output = readAdbLimitedOutput(process, MAX_ADB_OUTPUT_SIZE)
                if (!finished) {
                    process.destroyForcibly()
                    ShellExecutor.ShellResult(-1, output.trim(), "adb timed out after ${timeoutMs}ms")
                } else {
                    ShellExecutor.ShellResult(process.exitValue(), output.trim(), "")
                }
            } catch (e: Exception) {
                ShellExecutor.ShellResult(-1, "", e.message ?: "unknown")
            }
        }

    private fun readAdbLimitedOutput(process: Process, maxSize: Int): String {
        val sb = StringBuilder()
        val buffer = CharArray(8192)
        process.inputStream.bufferedReader().use { reader ->
            var totalRead = 0
            var n: Int
            while (reader.read(buffer).also { n = it } != -1) {
                val remaining = maxSize - totalRead
                if (remaining <= 0) break
                val toRead = minOf(n, remaining)
                sb.append(buffer, 0, toRead)
                totalRead += toRead
            }
        }
        return sb.toString()
    }

    /**
     * 通过 adb 自连接 localhost:5555 安装 APK（安装主通道）。
     * 命令序列：adb connect localhost:5555 → adb -s localhost:5555 install -r <apk>
     * -r 保留数据/覆盖安装（自更新不丢配置）；-s localhost:5555 锁定目标设备，避免多设备歧义。
     * @param apkPath 已复制到 /data/local/tmp 且 chmod 644 的 APK 绝对路径（对 uid=app / uid 2000 可读）
     * @return 成功/失败；adb 不可用或 connect 失败 → success=false，由调用方回退多策略安装
     */
    private suspend fun installApkViaAdb(apkPath: String): InstallResult {
        val adbPath = resolveAdbBinary() ?: return InstallResult(false, "adb binary not found（回退多策略安装）")
        // ① adb connect localhost:5555：拉起/确认本机 adbd TCP 监听
        val conn = runAdb(adbPath, listOf("connect", "localhost:5555"), 10_000L)
        AppLogger.i(tag, "adb connect localhost:5555 → exit=${conn.exitCode} out=${conn.stdout.take(200)}")
        val connected = conn.isSuccess ||
            conn.stdout.contains("connected", ignoreCase = true) ||
            conn.stdout.contains("already", ignoreCase = true)
        if (!connected) {
            AppLogger.w(tag, "adb connect 失败: ${conn.stdout.take(120)}（回退多策略安装）")
            return InstallResult(false, "adb connect 失败: ${conn.stdout.take(120)}")
        }
        // ② adb install -r：覆盖安装（shell uid 2000 天然具备安装特权）
        val res = runAdb(adbPath, listOf("-s", "localhost:5555", "install", "-r", apkPath), 120_000L)
        AppLogger.i(tag, "adb install -r → exit=${res.exitCode} out=${res.stdout.take(400)}")
        return when {
            res.exitCode == 0 && res.stdout.contains("Success", ignoreCase = true) ->
                InstallResult(true, "安装成功 (adb install -r)")
            res.stdout.contains("INSTALL_FAILED", ignoreCase = true) ->
                InstallResult(false, parseAdbInstallError(res.stdout))
            res.isSuccess ->
                InstallResult(true, "安装成功 (adb install -r)")
            else ->
                InstallResult(false, "adb install 失败: ${res.stdout.take(200)}")
        }
    }

    /** 解析 adb install 输出中的失败原因（INSTALL_FAILED_* → 中文可读）。 */
    private fun parseAdbInstallError(output: String): String {
        return when {
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) -> "版本降级"
            output.contains("INSTALL_FAILED_ALREADY_EXISTS", ignoreCase = true) -> "应用已存在"
            output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) -> "存储空间不足"
            output.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) -> "权限冲突"
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) -> "签名不一致"
            output.contains("INSTALL_FAILED_NO_MATCHING_ABIS", ignoreCase = true) -> "设备 ABI 不兼容"
            output.contains("INSTALL_FAILED_USER_RESTRICTED", ignoreCase = true) -> "用户限制安装（需允许安装未知来源）"
            output.contains("Failure", ignoreCase = true) -> output.substringAfter("Failure").trim().ifBlank { "未知错误" }
            else -> output.take(200).ifBlank { "未知错误" }
        }
    }

    /**
     * 多策略 APK 安装（adb 主通道不可用时的回退）：
     * 策略1 (首选): am startservice → InstallService → Java PackageInstaller API，
     *   在 app 进程内执行，UID 为 app 自身，绕过 shell pm install 的 AppOpsService.checkPackage NPE。
     *   （am broadcast 在某些 ROM 上 exit=255，改用 am startservice 更可靠）
     * 策略2-4 (fallback): cmd package install / pm install --full / pm install 标准方式。
     */
    /**
     * 确保本应用具备 PackageInstaller 静默安装权限。
     *
     * 2026-08-22 设计定案（用户澄清）：本设备 sh 脚本安装（pm/cmd package install）不可用，
     * 安装唯一走 InstallService 的 PackageInstaller API；sh 脚本仅作 watchdog。
     * 普通侧载 App 默认既无特权 INSTALL_PACKAGES、也未获用户"安装未知应用"授权，
     * PackageInstaller 前置检查会 abort "no install permission"——这正是此前本地推送
     * 更新从未安装的根因。adb shell (uid 2000) 可通过 appops 放行（幂等）。
     */
    private suspend fun ensurePackageInstallerPermission(): Boolean {
        val pkg = context?.packageName ?: "com.ufi_axis_core"
        // 2026-08-23 性能优化：优先检查是否已具有权限，避免不必要的 root shell 调用
        try {
            val pm = context?.packageManager
            if (pm != null && pm.canRequestPackageInstalls()) return true
        } catch (_: Exception) {}

        return try {
            val result = ShellExecutor.executeAsRoot(
                "appops set $pkg REQUEST_INSTALL_PACKAGES allow", 10_000L
            )
            AppLogger.i(tag, "appops REQUEST_INSTALL_PACKAGES allow for $pkg: exit=${result.exitCode} out=${result.stdout.take(100)}")
            result.isSuccess
        } catch (e: Exception) {
            AppLogger.w(tag, "appops grant failed: ${e.message}")
            false
        }
    }

    private suspend fun installApkMultiStrategy(apkPath: String, isSelfUpdate: Boolean = false): InstallResult {
        // 唯一安装通道：InstallService/InstallReceiver → PackageInstaller API。
        // 安装前先确保 appops 安装权限，否则 InstallService 会权限 abort。
        ensurePackageInstallerPermission()

        val resultFile = "/data/local/tmp/ufi_install_result.txt"
        val pkg = context?.packageName ?: "com.ufi_axis_core"
        val action = "com.ufi_axis_core.action.INSTALL_APK"
        
        ShellExecutor.executeAsRoot("rm -f $resultFile", 5_000L)
        
        // 策略1: am startservice (IntentService 路径)
        // 增加 --user 0 解决多用户/特权域启动失败问题
        val serviceResult = ShellExecutor.executeAsRoot(
            "am startservice --user 0 -a $action -n $pkg/.service.InstallService --es \"apk_path\" \"$apkPath\"",
            10_000L
        )
        AppLogger.i(tag, "am startservice exit=${serviceResult.exitCode}, out=${serviceResult.stdout}")
        
        // 如果 startservice 明确失败（如 ROM 限制），切换到策略2: am broadcast
        if (!serviceResult.isSuccess || serviceResult.stdout.contains("Error")) {
            AppLogger.w(tag, "am startservice failed, trying am broadcast fallback...")
            val brResult = ShellExecutor.executeAsRoot(
                "am broadcast --user 0 -a $action -n $pkg/.service.InstallReceiver --es \"apk_path\" \"$apkPath\"",
                10_000L
            )
            if (!brResult.isSuccess && !isSelfUpdate) {
                return InstallResult(false, "无法下达安装请求 (am failed): ${brResult.stderr.take(100)}")
            }
        }

        // 轮询结果文件：PI 失败路径 1~2s 内写入；
        // 自更新成功路径进程会被 PackageInstaller 杀掉、写不了文件 → 超时视为已提交。
        // 非自更新：InstallReceiver 正常写入 SUCCESS/FAILED，等满 120s。
        val waitSecs = if (isSelfUpdate) 25 else 120
        var waited = 0
        while (waited < waitSecs) {
            val check = ShellExecutor.executeAsRoot("cat $resultFile 2>/dev/null", 5_000L)
            val content = check.stdout.trim()
            if (content.isNotBlank()) {
                ShellExecutor.executeAsRoot("rm -f $resultFile", 5_000L)
                return if (content.startsWith("SUCCESS")) {
                    InstallResult(true, "安装成功 (PackageInstaller)")
                } else {
                    AppLogger.w(tag, "PackageInstaller failed: $content")
                    InstallResult(false, "PackageInstaller 安装失败: $content")
                }
            }
            delay(1000)
            waited++
        }
        ShellExecutor.executeAsRoot("rm -f $resultFile", 5_000L)
        return if (isSelfUpdate) {
            // 自更新无失败结果 = commit 已发出、进程即将被杀；完成/失败由独立 watchdog
            // （sh 脚本 mode=2：版本/lastUpdateTime 变化 → 重启 Core → 写 RESULT）裁决
            AppLogger.i(tag, "self-update 已提交（无失败结果），交由 watchdog 判定完成")
            InstallResult(true, "self-update 已提交 (watchdog 判定完成)")
        } else {
            InstallResult(false, "安装超时（PackageInstaller 未响应）")
        }
    }

    suspend fun uninstallApp(packageName: String): InstallResult {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        // 2026-08-23 修复：移除 -k 标志以进行干净卸载，并显式指定 --user 0
        val result = ShellExecutor.executeAsRoot("pm uninstall --user 0 \"$packageName\" 2>/dev/null", 30_000L)
        // pm uninstall 成功时输出通常包含 "Success"
        return parseInstallResult(result)
    }

    suspend fun disableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("pm disable \"$packageName\" 2>/dev/null").isSuccess
    }

    suspend fun enableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
    }

    suspend fun clearAppData(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("pm clear \"$packageName\" 2>/dev/null").isSuccess
    }

    suspend fun forceStop(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("am force-stop \"$packageName\" 2>/dev/null").isSuccess
    }

    suspend fun grantPermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        return ShellExecutor.executeAsRoot("pm grant \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
    }

    /**
     * 通过 ADB shell (uid 2000) 一次性授予所有运行时权限。
     * 绕过 ShellExecutor.executeAsRoot 的 uid 10101 fallback，直接用 adb 二进制执行。
     * pm grant 需要 uid 0 或 uid 2000，uid 10101 静默失败。
     */
    suspend fun grantAllPermissionsViaAdb(pkg: String): Pair<Boolean, String> {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ctx = context ?: return@withContext false to "context is null"
                val adbCandidates = listOf(
                    File(ctx.filesDir, "shell/adb").absolutePath,
                    "/data/local/tmp/ufi_axis/adb"
                )
                val adbPath = adbCandidates.firstOrNull { File(it).exists() && File(it).canExecute() }
                    ?: return@withContext false to "adb binary not found"

                val homeDir = ctx.cacheDir.absolutePath

                // 构建一条 shell 命令授予所有权限 + appops
                val perms = listOf(
                    "android.permission.READ_SMS",
                    "android.permission.RECEIVE_SMS",
                    "android.permission.SEND_SMS",
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.READ_PHONE_NUMBERS",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.POST_NOTIFICATIONS"
                )
                val shellCmd = buildString {
                    perms.forEach { append("pm grant $pkg $it 2>/dev/null; ") }
                    append("appops set $pkg MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; ")
                    append("appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>/dev/null; ")
                    append("appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null; ")
                    append("echo DONE")
                }

                val proc = ProcessBuilder(adbPath, "-s", "localhost:5555", "shell", shellCmd).apply {
                    environment()["ANDROID_ADB_HOME"] = homeDir
                    environment()["ANDROID_USER_HOME"] = homeDir
                    environment()["HOME"] = homeDir
                    environment()["ANDROID_SDK_HOME"] = homeDir
                    redirectErrorStream(true)
                }.start()

                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext false to "adb shell timeout (30s)"
                }
                val success = output.trim().endsWith("DONE")
                AppLogger.i(tag, "grantAllPermissionsViaAdb($pkg): exit=${proc.exitValue()} output=${output.trim().takeLast(100)}")
                success to output.trim()
            } catch (e: Exception) {
                AppLogger.e(tag, "grantAllPermissionsViaAdb exception: ${e.message}")
                false to "exception: ${e.message}"
            }
        }
    }

    suspend fun revokePermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        return ShellExecutor.executeAsRoot("pm revoke \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
    }

    /** 冻结应用 (pm disable-user) */
    suspend fun freezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("pm disable-user --user 0 \"$packageName\" 2>/dev/null").isSuccess
    }

    /** 解冻应用 (pm enable) */
    suspend fun unfreezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        return ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
    }

    /**
     * 校验包名：只允许字母、数字、点、下划线和短横线
     */
    private fun validatePackageName(name: String): Boolean {
        return name.isNotBlank() && name.matches(Regex("^[a-zA-Z0-9._-]+$"))
    }

    /**
     * 校验 shell 参数：拒绝任何可能改变命令结构的字符。
     * 2026-08-23：允许空格，APK 文件名常含空格。
     * 2026-09-02 安全修复：原实现只拦 `$ ` ; | &`，**放过了引号与换行**。
     * 调用方普遍把参数包在单引号里（`'$arg'`），一个 `'` 就能闭合引号并追加任意命令；
     * 换行同样能在 `sh -c` 里起新语句。此处一并拦掉引号、换行、重定向与子 shell 括号。
     */
    private fun validateShellArg(arg: String): Boolean {
        if (arg.isBlank()) return false
        if (arg.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        return !Regex("""["'$`;|&<>(){}\\]""").containsMatchIn(arg)
    }

    private fun parseInstallResult(result: ShellExecutor.ShellResult): InstallResult {
        val output = result.stdout + result.stderr
        return when {
            result.isSuccess && output.contains("Success", ignoreCase = true) ->
                InstallResult(true, "安装成功")
            output.contains("INSTALL_FAILED_ALREADY_EXISTS", ignoreCase = true) ->
                InstallResult(false, "应用已存在")
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                InstallResult(false, "版本降级")
            output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ->
                InstallResult(false, "存储空间不足")
            output.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) ->
                InstallResult(false, "权限冲突")
            else -> InstallResult(false, output.take(200).ifBlank { "未知错误" })
        }
    }

    data class InstallResult(val success: Boolean, val message: String)
}