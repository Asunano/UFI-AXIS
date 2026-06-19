package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor

class AppManager {
    private val tag = "AppManager"

    suspend fun hasRoot(): Boolean = ShellExecutor.hasRootAccess()

    suspend fun listApps(type: String = "all"): List<Map<String, Any>> {
        val filter = when (type) {
            "system" -> "-s"
            "user" -> "-3"
            else -> ""
        }
        val result = ShellExecutor.executeAsRoot("pm list packages $filter -f 2>/dev/null")
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

        return result.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val clean = line.trim()
                val equalsIdx = clean.lastIndexOf('=')
                if (equalsIdx < 0) return@mapNotNull null
                val pkg = clean.substring(equalsIdx + 1).trim()
                val apkPath = clean.substring(clean.indexOf('/'), equalsIdx).trim()
                mapOf(
                    "packageName" to pkg,
                    "apkPath" to apkPath,
                    "isSystem" to apkPath.contains("/system/"),
                    "versionName" to (versionNames[pkg] ?: ""),
                    "isEnabled" to (pkg !in disabledPkgs),
                    "isFrozen" to (pkg in suspendedPkgs)
                )
            } catch (e: Exception) { null }
        }
    }

    private fun String.toIfNotEmpty(): String? = if (isNotBlank()) this else null

    suspend fun getAppInfo(packageName: String): Map<String, Any>? {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        // 快速路径：用 pm path + dumpsys 轻量字段
        val pathResult = ShellExecutor.executeAsRoot("pm path \"$packageName\" 2>/dev/null")
        if (!pathResult.isSuccess) return null
        val apkPath = pathResult.stdout.trim().removePrefix("package:")
        if (apkPath.isBlank()) return null

        val info = ShellExecutor.executeAsRoot("""
            dumpsys package "$packageName" 2>/dev/null | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|installerPackageName=' | head -5
        """.trimIndent())
        val out = info.stdout
        val versionName = Regex("versionName=(\\S+)").find(out)?.groupValues?.getOrNull(1) ?: ""
        val versionCode = Regex("versionCode=(\\d+)").find(out)?.groupValues?.getOrNull(1) ?: "0"
        val firstInstall = Regex("firstInstallTime=(.+)").find(out)?.groupValues?.getOrNull(1) ?: ""
        val lastUpdate = Regex("lastUpdateTime=(.+)").find(out)?.groupValues?.getOrNull(1) ?: ""
        val installer = Regex("installerPackageName=(\\S+)").find(out)?.groupValues?.getOrNull(1) ?: ""

        val flags = ShellExecutor.executeAsRoot("dumpsys package \"$packageName\" 2>/dev/null | grep -E 'FLAG_SYSTEM|FLAG_DISABLED|SystemApp' | head -3")
        val isSystem = flags.stdout.contains("SystemApp") || flags.stdout.contains("FLAG_SYSTEM")
        val isEnabled = !flags.stdout.contains("FLAG_DISABLED") && !out.contains("enabled=false")

        return mapOf(
            "packageName" to packageName,
            "versionName" to versionName,
            "versionCode" to versionCode,
            "firstInstallTime" to firstInstall,
            "lastUpdateTime" to lastUpdate,
            "installer" to installer,
            "isSystem" to isSystem,
            "isEnabled" to isEnabled,
            "apkPath" to apkPath
        )
    }

    suspend fun installApk(apkPath: String): InstallResult {
        require(validateShellArg(apkPath)) { "Invalid APK path: $apkPath" }
        val result = ShellExecutor.executeAsRoot("pm install -r -d \"$apkPath\" 2>/dev/null", 120_000L)
        return parseInstallResult(result)
    }

    suspend fun installApkFromUrl(url: String): InstallResult {
        require(validateShellArg(url)) { "Invalid URL: $url" }
        val tmpPath = "/data/local/tmp/install_${System.currentTimeMillis()}.apk"
        val download = ShellExecutor.executeAsRoot("curl -sL -o \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
        if (!download.stdout.contains("OK")) {
            val wgetResult = ShellExecutor.executeAsRoot("wget -q -O \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
            if (!wgetResult.stdout.contains("OK")) return InstallResult(false, "下载失败: 不支持 curl/wget")
        }
        val install = ShellExecutor.executeAsRoot("pm install -r -d \"$tmpPath\" 2>/dev/null", 120_000L)
        ShellExecutor.executeAsRoot("rm -f \"$tmpPath\"")
        return parseInstallResult(install)
    }

    suspend fun uninstallApp(packageName: String): InstallResult {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val result = ShellExecutor.executeAsRoot("pm uninstall -k \"$packageName\" 2>/dev/null", 30_000L)
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
     * 校验 shell 参数：不允许空格和危险 shell 字符（$ ` ; & |）
     */
    private fun validateShellArg(arg: String): Boolean {
        return arg.isNotBlank() && !Regex("[\\s$`;|&]").containsMatchIn(arg)
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