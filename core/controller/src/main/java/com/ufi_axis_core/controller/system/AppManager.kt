package com.ufi_axis_core.controller.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * 系统应用 APK 路径的权威判定前缀集合。
     * 覆盖 /system、/system_ext、/vendor、/product、/oem、/system_carrier 等常见系统分区。
     *
     * 采用前缀集合而非单一 `/system/` 判定：后者区分大小写且只覆盖一条路径，
     * 会把 /data/app 下的应用误判为系统应用，并漏判 /system_ext 等分区。
     */
    private val SYSTEM_PATH_PREFIXES = listOf(
        "/system/", "/system_ext/", "/vendor/", "/product/app/", "/product/priv-app/",
        "/oem/app/", "/oem/priv-app/", "/system_carrier/", "/system_ext/priv-app/",
        // APEX（Android 11+ 镜像）：/apex/ 下挂载的系统模块按系统应用处理，
        // 否则 devicelockcontroller 一类 APEX 模块会被误判为普通应用。
        "/apex/"
    )

    /** 依据 APK 路径前缀判定是否为系统应用（大小写敏感，匹配真实挂载路径）。 */
    private fun isSystemApp(apkPath: String): Boolean =
        apkPath.isNotBlank() && SYSTEM_PATH_PREFIXES.any { apkPath.startsWith(it, ignoreCase = false) }

    /** MATCH_UNINSTALLED_PACKAGES：包含对当前用户隐藏/已卸载残留的包，对齐 shell pm list 的可见范围。 */
    private val MATCH_UNINSTALLED_PKGS = android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES

    suspend fun hasRoot(): Boolean = ShellExecutor.hasRootAccess()

    /**
     * 应用列表入口：先命中缓存，未命中再执行 shell。
     *
     * 开销集中在 [loadAllAppsUncached]：一次需要串行执行 4 条特权 shell
     * （`pm list packages -f` / `pm list packages -d` / `pm list packages --suspended` /
     * `dumpsys package packages | grep`），在设备上为秒级开销。而已安装应用集合变化极慢
     * （仅在人工安装或卸载时变化），因此全量列表只拉取一次并带时间戳缓存，
     * `type` 过滤在内存中完成（三个页签共用同一份全量数据，不必各自拉取）。
     *
     * 语义要点：
     * - 首次请求仍是同步拉取：用户第一次进入页面时无旧值可用，必须等待；但并发到达的多个请求
     *   由 [appListMutex] 合并，只执行一次 shell，避免并发重复拉取。
     * - 空结果不入缓存：`pm` 失败时本方法返回空列表（保持原有行为）；若缓存空列表，一次瞬时
     *   失败会使页面在 TTL 内持续为空。设备上不存在「一个包都没有」的情形（系统至少有数十个
     *   包），因此该判据不会误伤真实的空设备。
     * - 写操作一律失效：调用点清单见 [invalidateAppListCache]。
     */
    suspend fun listApps(type: String = "all"): List<Map<String, Any>> {
        freshAppListCache()?.let { return it.filterByType(type) }
        // 未命中（或已过期）→ 进互斥区二次检查 + 真实拉取。
        // 二次检查必须放在锁内：并发的第二个请求可能在第一个已经把值放进缓存**之前**
        // 读到 null，只靠锁外那一次检查会让两个请求各跑一遍 4 条 shell。
        return appListMutex.withLock {
            freshAppListCache()?.let { return@withLock it.filterByType(type) }
            val all = listLoader()
            // 拉取路径也走**同一份**过滤逻辑：先把结果装进一个临时条目再过滤，
            // 而不是直接 `all.filter {…}`。两处各写一遍的话，缓存命中路径与首次拉取路径
            // 迟早会给出不一致的列表（而且这个不一致不会被任何编译错误发现）。
            if (all.isEmpty()) return@withLock all
            val entry = AppListCacheEntry(all, System.currentTimeMillis())
            appListCache = entry
            entry.filterByType(type)
        }
    }

    /**
     * 取**未过期**的缓存条目（过期视为未命中，交给调用方去拉）。
     *
     * TTL 在这里判、而不是靠"定时刷新把 TTL 顶住"：后者只在定时协程活着时成立，
     * 而它会被「停止服务」暂停 —— 一旦被暂停又恢复，缓存可能是几小时前的，
     * 那时若无条件命中，用户会看到一份过期的应用列表（且没有任何提示能刷新它）。
     * 定时刷新的作用是"让绝大多数请求不必走这条路"，TTL 是这条路的安全网。
     */
    private fun freshAppListCache(): AppListCacheEntry? =
        appListCache?.takeIf { !it.isExpired(System.currentTimeMillis(), APP_LIST_CACHE_TTL_MS) }

    /**
     * 全量拉取入口的可替换缝（仅用于测试）。
     *
     * 默认指向 [loadAllAppsUncached]（实际执行 4 条特权 shell）。单测将其替换为计数器与假数据，
     * 从而可通过计数判断「第二次调用是否真的执行了 shell」，避免 mock ShellExecutor
     * （object + 静态方法，mock 成本高于替换实现）。
     *
     * 使用 `var` 而非构造参数：它是测试缝，不是依赖 —— 生产路径始终使用默认值，
     * 不应要求每个构造点额外传参（`BootReceiver` 也创建了一个仅用于 installApk 的实例）。
     */
    @Volatile
    internal var listLoader: suspend () -> List<Map<String, Any>> = { loadAllAppsUncached() }

    /** 应用列表缓存条目：全量（**未**按 filter 过滤）+ 写入时刻（epoch 毫秒）。 */
    private data class AppListCacheEntry(
        val all: List<Map<String, Any>>,
        val timestampMs: Long
    ) {
        /**
         * 内存二次过滤：`system` / `user` / 其他（= 全量）。
         *
         * 判据与 [loadAllAppsUncached] 中的分类逻辑一致（先标记 isSystem 再过滤）。
         * 抽为函数是为了让缓存命中路径与拉取路径使用同一份过滤逻辑：两处各写一遍会使
         * 缓存命中的结果与首次拉取不一致。
         */
        fun filterByType(type: String): List<Map<String, Any>> = when (type) {
            "system" -> all.filter { it["isSystem"] == true }
            "user" -> all.filter { it["isSystem"] == false }
            else -> all
        }

        /** 距上次刷新是否已超过 TTL（按**写入时刻**算，不用 lastAccess）。 */
        fun isExpired(nowMs: Long, ttlMs: Long): Boolean = nowMs - timestampMs >= ttlMs
    }

    /** 缓存本体。`@Volatile` + 整体替换：读侧无锁，失效只需置 null，不需要加锁改内容。 */
    @Volatile
    private var appListCache: AppListCacheEntry? = null

    /**
     * 应用列表刷新的互斥锁 —— 刷新与请求共用同一把。
     *
     * 场景：定时刷新正在执行（持锁，4 条 shell 在途），此时用户进入页面读取列表。
     * 共用一把锁意味着用户需等待该轮完成（数秒），而不是并发再开一轮 —— 后者会使设备
     * 同时执行 8 条 `pm`/`dumpsys`，在小内存设备上明显更慢，甚至互相阻塞。
     * 后台刷新的作用是把该等待前置到定时轮次中，而不是缩短单次耗时。
     */
    private val appListMutex = Mutex()

    /** 定时刷新协程句柄（见 [startPeriodicRefresh]）。`null` = 未启动或已停。 */
    @Volatile
    private var appListRefreshJob: Job? = null

    /** [startPeriodicRefresh] 记下的作用域，`setPeriodicRefreshEnabled(true)` 要靠它重新拉起。 */
    @Volatile
    private var appListRefreshScope: CoroutineScope? = null

    /**
     * 让缓存失效 —— 所有会改变应用列表的写操作之后都必须调用。
     *
     * 取舍（保守失效）：`forceStop` / `clearAppData` / `grantPermission` /
     * `revokePermission` / `grantAllPermissionsViaAdb` 并不改变列表内容
     * （列表字段只有 packageName/appName/apkPath/isSystem/versionName/enabled/isFrozen/icon），
     * 理论上可以不失效。但一律失效的代价仅为「下次进入页面重新拉取一次（core 侧还有
     * iconCache 兜底）」，而漏失效的代价是用户看到「已禁用的应用仍显示为启用」且没有任何
     * 自愈路径（无事件、无推送）。该开销不值得承担此风险，故采用保守策略。
     *
     * 此处不触发立即刷新：写操作刚执行完多条 shell，紧接着再串行 4 条只会使写请求本身变慢
     * （客户端正在等待该写操作的结果）。写后回读由客户端显式重发列表请求完成。
     */
    private fun invalidateAppListCache() {
        appListCache = null
    }

    /**
     * 包变化（安装 / 卸载 / 更新 / 启用停用）→ 立即失效缓存并在后台重新拉取。
     *
     * 缓存原本只在 core 自身的写操作中失效；通过其他途径（系统设置、第三方商店、
     * `pm uninstall`）修改包时，core 无法感知，列表要等 TTL 到期或手动刷新才会更新。
     * 因此在定时刷新启动时同时注册包变化广播。
     */
    fun onPackagesChanged() {
        invalidateAppListCache()
        val scope = appListRefreshScope ?: return
        scope.launch(Dispatchers.IO) { refreshAppListInBackground() }
    }

    /** 包变化广播接收器（随定时刷新一起注册/注销）。 */
    private var packageReceiver: BroadcastReceiver? = null

    private fun registerPackageReceiver() {
        val ctx = context ?: return
        if (packageReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                AppLogger.i(tag, "包变化（${intent?.action}）→ 立即失效应用列表缓存并重拉")
                onPackagesChanged()
            }
        }
        runCatching { ctx.registerReceiver(receiver, filter) }
            .onSuccess { packageReceiver = receiver }
            .onFailure { AppLogger.w(tag, "注册包变化监听失败: ${it.message}") }
    }

    private fun unregisterPackageReceiver() {
        val ctx = context ?: return
        packageReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        packageReceiver = null
    }

    /**
     * 启动定时主动刷新（core 主动获取并周期性更新缓存）。
     *
     * 刻意不在构造时启动：`AppManager` 另有一个仅用于自更新安装的短生命周期实例
     * （`BootReceiver` 的开机恢复），构造即启动协程会为该一次性实例挂上永不结束的循环。
     * 真正的持有者是 `ComponentFactory.build()` 中的实例，它随 `ComponentGraph` 生灭，
     * 因此由 `BackendService` 在取得组件图后显式启动，并在 `onDestroy` 路径显式停止。
     *
     * @param scope 必须来自 `BackendService.serviceScope`（在 `onDestroy` 中被 cancel），
     *   这样即使遗漏显式 stop，协程也会随服务结束 —— 双保险。
     */
    fun startPeriodicRefresh(scope: CoroutineScope) {
        if (appListRefreshJob?.isActive == true) return
        appListRefreshScope = scope
        appListRefreshJob = scope.launch(Dispatchers.IO) {
            // 启动后先等待一个周期再刷新：core 刚启动时设备上的 pm/dumpsys 可能仍被其他初始化占用
            // （AT 通道初始化、updateManager 恢复等），立即刷新只会加重启动期负载。
            while (isActive) {
                delay(APP_LIST_REFRESH_INTERVAL_MS)
                refreshAppListInBackground()
            }
        }
        AppLogger.i(tag, "App list periodic refresh started (interval=${APP_LIST_REFRESH_INTERVAL_MS}ms)")
        // 顺带监听包变化：外部安装/卸载时立刻失效缓存，不用等 TTL。
        registerPackageReceiver()
    }

    /**
     * 后台刷新一轮（定时器与「启动/恢复后台」两个入口共用）。
     *
     * 失败或空结果一律不写缓存：保留上一份有效数据优于写入空数据（写入空数据会使页面在 TTL 内
     * 持续为空）。刷新完成后无需通知客户端 —— 本仓没有应用列表的 WS 推送，客户端进入页面时
     * 读取的即为缓存结果，不需要额外广播，也不应为该需求新增推送频道。
     */
    private suspend fun refreshAppListInBackground() {
        try {
            appListMutex.withLock {
                val all = listLoader()
                if (all.isEmpty()) {
                    AppLogger.w(tag, "后台刷新应用列表返回空，保留旧缓存")
                    return@withLock
                }
                appListCache = AppListCacheEntry(all, System.currentTimeMillis())
                AppLogger.i(tag, "后台刷新应用列表完成，共 ${all.size} 个包")
            }
        } catch (e: Exception) {
            // 定时循环里绝不能把异常放出去（SupervisorJob 下会静默终止这条循环且无日志）
            AppLogger.w(tag, "后台刷新应用列表失败: ${e.message}")
        }
    }

    /**
     * 暂停/恢复定时刷新 —— 对齐 [com.ufi_axis_core.service.BackendService] 的
     * 「停止服务 = 除 HTTP 外全停」语义（`pauseComponentBackground` / `resumeComponentBackground`）。
     *
     * 若不接入该层，用户点「停止服务」之后后台仍会每 2 分钟执行 4 条特权 shell，
     * 与该开关的既有语义（read-through 闸门、任务调度、隧道看护、下载轮询均停止）矛盾。
     *
     * 暂停即取消协程（而不是置标志让循环空转）：设备上少执行 4 条 shell 的收益高于多一次唤醒。
     */
    fun setPeriodicRefreshEnabled(enabled: Boolean) {
        val scope = appListRefreshScope
        if (!enabled) {
            appListRefreshJob?.cancel()
            appListRefreshJob = null
            unregisterPackageReceiver()
            AppLogger.i(tag, "App list periodic refresh paused")
            return
        }
        // 恢复需要启动时记录的作用域；不存在（从未启动过）则保持不启动 ——
        // 不自行创建与服务生命周期脱钩的作用域，该形态会导致协程泄漏。
        if (scope != null) startPeriodicRefresh(scope)
    }

    private suspend fun loadAllAppsUncached(): List<Map<String, Any>> {
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
                    // dumpsys 解析不到 versionName 时回退 PackageManager，避免该字段为空。
                    "versionName" to (versionNames[pkg] ?: getVersionFromPackageManager(pkg).first),
                    "enabled" to (pkg !in disabledPkgs),
                    "isFrozen" to (pkg in suspendedPkgs),
                    "iconBase64" to getAppIconBase64(pkg)
                )
            } catch (e: Exception) {
                null
            }
        }
        // 返回**全量**（不按 type 过滤）：过滤已上移到 [AppListCacheEntry.filterByType]，
        // 缓存因此只存一份全量，三个页签共用，不需要每次调用各自拉取后过滤。
        return all
    }

    private fun String.toIfNotEmpty(): String? = if (isNotBlank()) this else null

    suspend fun getAppInfo(packageName: String): Map<String, Any>? {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        // 详情路径全部使用 PackageManager（binder 调用通常 <100ms），不使用 pm path / dumpsys 的
        // root shell（单次 200-800ms，多条累计 0.6-2.4s）。
        val ctx = context ?: return null
        val pm = ctx.packageManager
        // 列表来自 shell pm（可见全部包，含对当前用户隐藏或已卸载残留的包），
        // 而应用进程的 PackageManager 默认视图查不到这些包，会抛 NameNotFoundException 导致 404。
        // 带上 MATCH_UNINSTALLED_PACKAGES 以对齐两种视图。
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
        // 应用名（label）：详情弹窗头部展示应用名（如「Google Play 服务」）而非包名段。
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
            // iconBase64 不在详情接口返回：4-5KB 的 base64 占响应体主要部分，前端从列表缓存复用。
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
        // 先清扫历史残留：自更新（安装 core 自身）时进程会被安装器直接终止，
        // 函数末尾的 rm 无法执行。临时文件名原先带时间戳，每次自更新都会在
        // /data/local/tmp 留下一个约 60MB 的 APK，累积可达数十个、数 GB。
        sweepStaleInstallApks()
        // SELinux/fuse：/sdcard 上的文件对 shell(uid 2000) 与 adb client(uid=app) 不可读。
        // 先复制到 /data/local/tmp 并 chmod 644 使其 world-readable，供 adb install 读取推送。
        // 固定文件名（不带时间戳）：天然覆盖，最多只占一份。
        val tmpApk = "/data/local/tmp/appmgr_install.apk"
        ShellExecutor.executeAsRoot("cp -f \"$apkPath\" \"$tmpApk\" && chmod 644 \"$tmpApk\"", 10_000L)
        // 主通道：adb connect localhost:5555 && adb install -r（uid 2000 shell 天然具备安装特权，
        // 无需 appops 放行、无需 PackageInstaller API，避免旧通道下本地推送更新无法安装的问题）。
        val adbResult = installApkViaAdb(tmpApk)
        val result = if (adbResult.success) {
            adbResult
        } else {
            // adb 不可用或失败：回退 InstallService → PackageInstaller API（备用通道，需 appops 放行）
            AppLogger.w(tag, "adb install 主通道失败/不可用，回退多策略安装: ${adbResult.message}")
            installApkMultiStrategy(tmpApk, isSelfUpdate)
        }
        // 安装后不自动授予运行时权限：主通道 adb install -r 为覆盖安装，运行时权限由系统继承，
        // 该步骤是多余的。需要手动授权时仍可通过 POST /api/apps/grant-all-permissions
        // （web 应用页的按钮）完成。
        deleteTmpFile(tmpApk)
        // 安装成功会改变包集合（版本名 / isSystem / 图标均可能变化）。
        // 失败时同样失效：adb 主通道失败后 PackageInstaller 仍可能已安装成功（结果判据不唯一），
        // 多失效一次的代价低于让用户看到「安装成功但列表中没有」。
        invalidateAppListCache()
        return result
    }

    /**
     * 清扫 `/data/local/tmp` 下遗留的安装临时 APK（含历史带时间戳的 `appmgr_install_*.apk`）。
     *
     * 自更新会在删除前终止进程，因此残留只能在下一次安装开始时兜底清理。
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

    /** 删除临时文件：先以普通权限删除，失败后回退 root。 */
    private suspend fun deleteTmpFile(path: String) {
        if (runCatching { java.io.File(path).delete() }.getOrDefault(false)) return
        runCatching { ShellExecutor.execute("rm -f \"$path\"", timeoutMs = 5_000L) }
        runCatching { ShellExecutor.executeAsRoot("rm -f \"$path\"", 5_000L) }
    }

    /**
     * 从 URL 下载 APK 并静默安装。
     *
     * 安全约束（与 UpdateManager 的防护口径一致）：
     * - `expectedSha256` 必填：APK 内容哈希（小写 hex）。下载后流式计算并比对，不匹配即删除
     *   临时文件并拒绝安装。缺少该校验时，任意 URL 下载后直接以 root 安装，等同于局域网内的
     *   明文投毒通道。
     * - URL 强制 HTTPS（豁免口径与 UpdateManager.requireSecureUrl 一致：localhost 与内网调试
     *   地址允许 http，属于开发场景；生产 URL 不豁免会被内网代理绕过）。
     * - 域名白名单不在此层实现：白名单无法防御镜像缓存污染，而哈希校验同时覆盖两者且无维护
     *   成本；route 层与调用方（web UI）各自提供哈希来源即可。
     */
    suspend fun installApkFromUrl(url: String, expectedSha256: String): InstallResult {
        require(validateShellArg(url)) { "Invalid URL: $url" }
        // URL 会被插值进特权 curl/wget 命令。除字符白名单（validateShellArg 已拦截引号与换行）外，
        // 再加协议白名单，避免 file:// 一类协议把本地文件当作下载内容读取。
        require(url.startsWith("http://") || url.startsWith("https://")) { "Invalid URL scheme: $url" }
        // HTTPS 强制（localhost / 内网调试地址豁免，口径与 UpdateManager.requireSecureUrl 一致）。
        require(isSecureDownloadUrl(url)) { "URL 必须使用 HTTPS（内网调试地址除外）: $url" }
        // sha256 必填并校验格式（64 位 hex，大小写归一在比较时处理）。
        val expectedSha = expectedSha256.trim().lowercase()
        require(expectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
            "sha256 必填且为 64 位十六进制（防任意 APK 静默安装）"
        }
        val tmpPath = "/data/local/tmp/install_${System.currentTimeMillis()}.apk"
        // 任何失败路径都必须清除已下载的临时 APK：/data 分区空间敏感，
        // 90MB 级 APK 反复失败会在 /data/local/tmp 堆积并占满空间，导致后续下载全部 ENOSPC。
        val download = ShellExecutor.executeAsRoot("curl -sL -o \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
        if (!download.stdout.contains("OK")) {
            val wgetResult = ShellExecutor.executeAsRoot("wget -q -O \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
            if (!wgetResult.stdout.contains("OK")) {
                deleteTmpFile(tmpPath)
                return InstallResult(false, "下载失败: 不支持 curl/wget")
            }
        }
        // 下载内容哈希比对：不匹配即拒绝安装（同时拦截内容投毒与镜像缓存污染）。
        val actualSha = fileSha256Hex(tmpPath)
        if (actualSha == null) {
            deleteTmpFile(tmpPath)
            return InstallResult(false, "SHA-256 计算失败：下载文件不可读")
        }
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            deleteTmpFile(tmpPath)
            AppLogger.e(tag, "[P0-2] install-url 哈希不匹配，已拒绝安装: expected=$expectedSha actual=$actualSha url=$url")
            return InstallResult(false, "SHA-256 不匹配（预期 $expectedSha，实际 $actualSha），已拒绝安装")
        }
        // 确保 APK 对 adb client(uid=app)/adbd(uid 2000) 可读（adbd/shell 不可读 /data/local/tmp 下 root 写的文件时回退）
        ShellExecutor.executeAsRoot("chmod 644 \"$tmpPath\"", 5_000L)
        // 主通道：adb install -r（回退多策略 InstallService → PackageInstaller API）
        val result = installApkViaAdb(tmpPath).let { adbRes ->
            if (adbRes.success) adbRes else installApkMultiStrategy(tmpPath)
        }
        // 与 installApk 一致：不自动授予权限（adb install -r 覆盖安装本就会继承权限）。
        deleteTmpFile(tmpPath)
        // 与 [installApk] 相同，安装后失效应用列表缓存。
        invalidateAppListCache()
        return result
    }

    // ── ADB 安装通道（安装主通道：adb connect localhost:5555 && adb install -r）──
    // uid 2000 的 shell 经 adb 安装天然具备特权，无需 appops 放行、无需 PackageInstaller API，
    // 避免旧通道下本地推送更新无法安装的问题（app uid 缺少 REQUEST_INSTALL_PACKAGES，
    // PackageInstaller 前置检查直接 abort）。

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
     * 确保本应用具备 PackageInstaller 静默安装权限。
     *
     * 本设备的 sh 脚本安装（pm / cmd package install）不可用，安装仅通过 InstallService 的
     * PackageInstaller API 完成，sh 脚本只作为 watchdog。普通侧载应用默认既无特权
     * INSTALL_PACKAGES，也未获得「安装未知应用」授权，PackageInstaller 前置检查会以
     * "no install permission" 中止 —— 这是旧通道下本地推送更新始终无法安装的根因。
     * adb shell (uid 2000) 可通过 appops 放行（幂等）。
     */
    private suspend fun ensurePackageInstallerPermission(): Boolean {
        val pkg = context?.packageName ?: "com.ufi_axis_core"
        // 先检查是否已具备权限，避免不必要的 root shell 调用。
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

    /**
     * adb 主通道不可用时的回退安装：通过 InstallService / InstallReceiver 调用
     * PackageInstaller API，在应用进程内执行（UID 为应用自身），
     * 绕过 shell `pm install` 在部分 ROM 上触发的 AppOpsService.checkPackage NPE。
     * 安装前先确保 appops 安装权限，否则 InstallService 会因权限不足中止。
     */
    private suspend fun installApkMultiStrategy(apkPath: String, isSelfUpdate: Boolean = false): InstallResult {
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

        // 轮询结果文件：PI 失败路径在 1~2s 内写入；
        // 自更新成功路径中进程会被 PackageInstaller 终止，无法写入文件，因此超时按已提交处理。
        // 非自更新：InstallReceiver 正常写入 SUCCESS/FAILED，等待满 120s。
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
        // 不带 -k 标志以完成干净卸载，并显式指定 --user 0。
        val result = ShellExecutor.executeAsRoot("pm uninstall --user 0 \"$packageName\" 2>/dev/null", 30_000L)
        // 卸载直接改变包集合，必须失效缓存（用户卸载后刷新应立即看不到该应用）。
        invalidateAppListCache()
        // pm uninstall 成功时输出通常包含 "Success"
        return parseInstallResult(result)
    }

    suspend fun disableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm disable \"$packageName\" 2>/dev/null").isSuccess
        // enabled 字段由 `pm list packages -d` 决定，必须失效缓存。
        invalidateAppListCache()
        return ok
    }

    suspend fun enableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
        // 同 disableApp：enabled 字段会变化。
        invalidateAppListCache()
        return ok
    }

    suspend fun clearAppData(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm clear \"$packageName\" 2>/dev/null").isSuccess
        // 保守失效：清数据不改变列表内容，理由见 [invalidateAppListCache]。
        invalidateAppListCache()
        return ok
    }

    suspend fun forceStop(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("am force-stop \"$packageName\" 2>/dev/null").isSuccess
        // 保守失效：强停不改变列表字段，但统一失效，避免为此增加例外分支。
        invalidateAppListCache()
        return ok
    }

    suspend fun grantPermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        val ok = ShellExecutor.executeAsRoot("pm grant \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
        // 保守失效：权限不属于列表字段，理由见 [invalidateAppListCache]。
        invalidateAppListCache()
        return ok
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

                // 必须先 waitFor（有界）再读取输出：原先在 waitFor 之前调用 readText，
                // adb 僵死且不输出时流不会 EOF，readText 永久阻塞，30s 超时形同虚设，
                // 请求协程会永久挂起。
                val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                val output = if (finished) {
                    proc.inputStream.bufferedReader().readText()
                } else {
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
        }.also {
            // 该调用同时修改 appops（影响应用可见性行为），且权限不属于列表字段；
            // 与 [grantPermission] 采用同一口径，统一失效。
            // 放在 `also` 上是为了覆盖内部所有返回分支（正常 / context 为空 / adb 不存在 / 超时），
            // 遗漏任一分支即遗漏一次失效。
            invalidateAppListCache()
        }
    }

    suspend fun revokePermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        val ok = ShellExecutor.executeAsRoot("pm revoke \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
        // 保守失效：同 [grantPermission]。
        invalidateAppListCache()
        return ok
    }

    /** 冻结应用 (pm disable-user) */
    suspend fun freezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm disable-user --user 0 \"$packageName\" 2>/dev/null").isSuccess
        // 冻结通过 disable-user 实现，同时改变 enabled 与 isFrozen 两个字段。
        invalidateAppListCache()
        return ok
    }

    /** 解冻应用 (pm enable) */
    suspend fun unfreezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
        // 解冻改变 enabled 与 isFrozen 两个字段。
        invalidateAppListCache()
        return ok
    }

    /**
     * 校验包名：只允许字母、数字、点、下划线和短横线
     */
    private fun validatePackageName(name: String): Boolean {
        return name.isNotBlank() && name.matches(Regex("^[a-zA-Z0-9._-]+$"))
    }

    /**
     * 下载 URL 的安全协议检查。口径与 UpdateManager.requireSecureUrl 一致：
     * 强制 HTTPS；localhost / 127.0.0.1 / 内网地址段 / .local 视为本地调试地址，允许 http。
     * 内部使用 URL 解析，解析失败按不安全处理（fail-secure）。
     */
    private fun isSecureDownloadUrl(urlStr: String): Boolean {
        val u = try {
            java.net.URL(urlStr)
        } catch (e: Exception) {
            return false
        }
        if (u.protocol.equals("https", ignoreCase = true)) return true
        val host = u.host ?: return false
        val isLocalDebug = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.3") ||
            host.endsWith(".local")
        return isLocalDebug
    }

    /**
     * 对下载落地的 APK 流式计算 SHA-256（90MB 级文件不能整读进内存）。
     * 文件不可读或为空时返回 null（调用方按 fail-secure 拒绝安装）。
     * root 写入的文件在本进程（shell uid）执行 chmod 644 之前可能不可读，
     * 因此先提权补一次 chmod 再计算，仍失败则返回 null。
     */
    private suspend fun fileSha256Hex(path: String): String? = try {
        ShellExecutor.executeAsRoot("chmod 644 \"$path\"", 5_000L)
        val f = java.io.File(path)
        if (!f.exists() || f.length() == 0L) null else {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    } catch (e: Exception) {
        AppLogger.e(tag, "fileSha256Hex failed: ${e.message}")
        null
    }

    /**
     * 校验 shell 参数：拒绝任何可能改变命令结构的字符。
     *
     * 允许空格（APK 文件名常含空格）。调用方普遍把参数包在单引号中（`'$arg'`），
     * 单个 `'` 即可闭合引号并追加任意命令；换行同样能在 `sh -c` 中开始新语句。
     * 因此一并拦截引号、换行、重定向与子 shell 括号。
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

    companion object {
        /**
         * 应用列表缓存 TTL（5 分钟）与定时刷新周期（2 分钟）。
         *
         * 不复用 `ResponseCache.CacheTTL`：该常量位于 `:core:cache`，而 `AppManager` 位于
         * `:core:controller`，后者当前不依赖 `:core:cache`（其中缓存的是 API 响应的 JsonElement，
         * 语义也不同：按 key 存 JSON，而此处存的是已解析的 `List<Map<String,Any>>`）。
         * 为复用一个数值引入模块依赖不划算，故就地定义并说明取值理由。
         *
         * - TTL 5 分钟：应用集合由人工操作改变（安装 / 卸载 / 冻结），系统不会自行增删包。
         *   5 分钟内最多出现一次「刚安装的应用未显示」，而页面刷新按钮及任何写操作都会立即
         *   失效缓存，可感知窗口很短。
         * - 刷新周期 2 分钟 < TTL：定时刷新每 2 分钟重新写入缓存时间戳，进入页面时几乎总是命中
         *   （最坏数据年龄 = TTL − 周期 = 3 分钟）。若周期 >= TTL，会出现进入页面时恰好命中
         *   过期点、需现场执行 4 条 shell 的情形。
         *
         * 放在 companion 而非类体：`const val` 在 Kotlin 中只允许出现在顶层、具名 object 或
         * companion object 中（编译报 `Const 'val' is only allowed on top level...`）。
         */
        private const val APP_LIST_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val APP_LIST_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
    }
}