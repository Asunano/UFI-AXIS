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

    /**
     * 应用列表入口：**先命中缓存再打 shell**（2026-10-12 应用列表缓存批次）。
     *
     * 慢的根因就在下面 [loadAllAppsUncached]：一次要串 4 条特权 shell
     * （`pm list packages -f` / `pm list packages -d` / `pm list packages --suspended` /
     * `dumpsys package packages | grep`），设备上是**秒级**开销。而「已安装哪些应用」是
     * 变化极慢的数据（人手动装/卸一次），于是改成：全量列表只拉一次并带时间戳缓存住，
     * `type` 过滤在内存里做（三个页签共用同一份全量，不必各拉一次）。
     *
     * 语义要点：
     * - **首次仍是同步拉取**：用户第一次进页面时该等的还是要等（没有旧值可给），
     *   但并发到达的多个请求由 [appListMutex] 合并，**只跑一次 shell**（不形成击穿风暴）。
     * - **空结果不入缓存**：`pm` 失败时本方法返回空列表（保持原行为），若把空列表也缓存住，
     *   一次瞬时失败会让页面在 TTL 内一直空着。设备上"一个包都没有"不可能发生
     *   （系统至少有几十个包），所以这个判据不会误伤真实的空设备。
     * - **写操作一律失效**：见 [invalidateAppListCache] 的调用点清单。
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
     * 全量拉取入口的**可替换缝**（测试用）。
     *
     * 默认指向 [loadAllAppsUncached]（真的 4 条特权 shell）。单测把它换成计数器 + 假数据，
     * 于是"第二次调用到底跑没跑 shell"可以被**数出来**，而不是靠 mock ShellExecutor
     * （那是 object + 静态方法，mock 起来比重写实现还脆）。
     *
     * 用 `var` 而不是构造参数：它是**测试缝**，不是依赖 —— 生产路径永远是默认值，
     * 不该让每个构造点都多传一个参数（`BootReceiver` 也 new 了一个只用来 installApk 的实例）。
     */
    @Volatile
    internal var listLoader: suspend () -> List<Map<String, Any>> = { loadAllAppsUncached() }

    /** 应用列表缓存条目：全量（**未**按 filter 过滤）+ 写入时刻（epoch 毫秒）。 */
    private data class AppListCacheEntry(
        val all: List<Map<String, Any>>,
        val timestampMs: Long
    ) {
        /**
         * 内存二次过滤：`system` / `user` / 其它（= 全量）。
         *
         * 判据与 [loadAllAppsUncached] 里原来那段 `when` 完全一致（先标 isSystem 再过滤），
         * 抽成函数是为了让"缓存命中路径"和"拉取路径"走**同一份**过滤逻辑 ——
         * 两处各写一遍的话，缓存命中的结果迟早会和首次拉取不一致。
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
     * 应用列表刷新的互斥锁 —— **刷新与请求共用同一把**。
     *
     * 场景：定时刷新正在跑（持锁、4 条 shell 飞着），此时用户进页面来读。
     * 共用一把锁意味着用户要等这一次跑完（几秒）而不是又并发开一轮 —— 后者会让设备
     * 同时跑 8 条 `pm`/`dumpsys`，在随身 WiFi 这种小内存设备上明显更慢甚至互相拖死。
     * 「后台刷新」的价值在于把用户这次等待**前置**到定时轮次里，而不是让它变快。
     */
    private val appListMutex = Mutex()

    /** 定时刷新协程句柄（见 [startPeriodicRefresh]）。`null` = 未启动或已停。 */
    @Volatile
    private var appListRefreshJob: Job? = null

    /** [startPeriodicRefresh] 记下的作用域，`setPeriodicRefreshEnabled(true)` 要靠它重新拉起。 */
    @Volatile
    private var appListRefreshScope: CoroutineScope? = null

    /**
     * 应用列表缓存 TTL（5 分钟）与定时刷新周期（2 分钟）。
     *
     * 为什么不用 `ResponseCache.CacheTTL`：那个常量住在 `:core:cache`，而 `AppManager`
     * 在 `:core:controller` —— `:core:controller` 现在**没有**对 `:core:cache` 的依赖
     * （缓存是 API 响应层的 JsonElement 缓存，语义也不同：它按 key 存 JSON，
     * 这里存的是已解析好的 `List<Map<String,Any>>`）。为复用一个数字引入模块依赖不划算，
     * 故就地定义并写清取值理由。
     *
     * - **TTL 5 分钟**：应用集合是人手动的变化（装/卸/冻结），系统自身不会自己增删包。
     *   5 分钟内最多让用户看到一次"刚装的应用没出现"，而页面上的刷新按钮、以及任何写操作
     *   都会立刻失效缓存，所以可感知窗口很短。
     * - **刷新周期 2 分钟 < TTL**：定时刷新每 2 分钟把 TTL 顶回去，于是进页面时**几乎总是命中**
     *   （最坏情况数据年龄 = TTL - 周期 = 3 分钟）。若周期 >= TTL，就会出现"进页面恰好
     *   卡在过期点"而现场跑 4 条 shell —— 那正是这次要消掉的体感。
     */
    /**
     * 让缓存失效 —— **所有会改变应用列表的写操作后都必须调用**。
     *
     * 取舍（保守失效）：`forceStop` / `clearAppData` / `grantPermission` /
     * `revokePermission` / `grantAllPermissionsViaAdb` 其实**不改变列表内容**
     * （列表字段只有 packageName/appName/apkPath/isSystem/versionName/enabled/isFrozen/icon），
     * 理论上可以不失效。但一律失效的代价只是"下一次进页面重新拉一次（core 侧还有 iconCache 兜底）"，
     * 而漏失效的代价是用户看到"我明明禁用了它，列表还显示启用"且**没有任何自愈路径**
     * （没有事件、没有推送）。省下的那点开销不值这个风险，故保守。
     *
     * 注意这里**不**顺带触发立即刷新：写操作刚跑完多条 shell，紧接着再串 4 条
     * 只会让写请求自己变慢（客户端正在等这个写的结果）。写后回读由客户端显式重发列表请求完成。
     */
    private fun invalidateAppListCache() {
        appListCache = null
    }

    /**
     * 包变化（安装 / 卸载 / 更新 / 启用停用）→ **立即失效缓存并后台重拉**（2026-10-19）。
     *
     * 用户反馈：在应用管理里卸载/安装后，列表要等 5 分钟 TTL 或手动刷新才变。
     * 根因是缓存只在 core 自己的写操作里失效；**通过其它途径**（系统设置、第三方商店、
     * `pm uninstall`）改包时，core 完全不知道。因此在定时刷新启动时顺带注册包变化广播。
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
     * 启动定时主动刷新（用户要的"core 主动获取缓存并定时更新"）。
     *
     * **刻意不在构造里起**：`AppManager` 还有一个只用来做自更新安装的短命实例
     * （`BootReceiver` 的开机恢复），构造即起协程等于给那种一次性实例也挂一条永不结束的循环。
     * 真正的持有者是 `ComponentFactory.build()` 里那个实例，它随 `ComponentGraph` 生灭，
     * 所以由 `BackendService` 在拿到图之后显式启动、`onDestroy` 路径显式停止。
     *
     * @param scope 必须来自 `BackendService.serviceScope`（它在 `onDestroy` 里被 cancel），
     *   这样即便漏掉显式 stop，协程也会随服务一起死 —— 双保险。
     */
    fun startPeriodicRefresh(scope: CoroutineScope) {
        if (appListRefreshJob?.isActive == true) return
        appListRefreshScope = scope
        appListRefreshJob = scope.launch(Dispatchers.IO) {
            // 启动后先等一个周期再刷：core 刚起来时设备上 pm/dumpsys 可能还在被别的初始化
            // 抢（AT 通道初始化、updateManager 恢复…），立刻刷只是给启动期添乱。
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
     * 失败/空结果一律**不写缓存**：保留上一份好数据比写空强（失败写空 = 让页面在 TTL 内全空）。
     * 只在"跑完真的变了"时不需要通知客户端 —— 本仓没有应用列表的 WS 推送，
     * 客户端进页面拿到的就是它，不需要额外广播（别为此新增频道）。
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
     * 不接这一层的话，用户点「停止服务」之后后台仍在每 2 分钟跑 4 条特权 shell，
     * 与该开关的既有语义（read-through 闸门 / 任务调度 / 隧道看护 / 下载轮询都停）自相矛盾。
     *
     * 暂停 = 取消协程（而不是置一个 flag 让循环空转）：设备上少 4 条 shell 比多一次唤醒便宜。
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
        // 恢复需要当初那个 scope；没有（从没启动过）就保持不启动 —— 宁可不刷，也别自己造一个
        // 与服务生命周期脱钩的 scope（那就是本批次要消灭的泄漏形态）。
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
        // 返回**全量**（不按 type 过滤）：过滤已上移到 [AppListCacheEntry.filterByType]，
        // 这样缓存只存一份全量、三个页签共用（原来每次调用都各自重拉一遍全量再过滤）。
        return all
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
        // 失效点⑩：安装成功会在包里多一个/多一个版本，列表（版本名/isSystem/图标）都要变。
        // 失败也失效：adb 主通道失败后 PackageInstaller 仍可能已经装上（结果判据不唯一），
        // 宁可多失效一次，也不能让用户看着"装成功了但列表里没有"。
        invalidateAppListCache()
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

    /**
     * 从 URL 下载 APK 并静默安装。
     *
     * 2026-10-05 P0-2 修复（对齐 UpdateManager 的防护水准）：
     * - `expectedSha256` **必填**：APK 内容哈希（小写 hex）。下载后流式计算比对，
     *   不匹配即删临时文件拒绝安装——此前任意 URL 下载后直接 root 安装，等于局域网内
     *   明文投毒通道（P0-1 的 body 签名缺口在修好前也压不住这条路径）。
     * - URL 强制 HTTPS（同 UpdateManager.requireSecureUrl 的豁免口径：localhost/内网
     *   调试地址允许 http——那是开发场景，生产-facing 的 URL 不豁免会被内网代理打穿）。
     * - 域名白名单**不在此层做**：白名单挡内容投毒挡不住镜像缓存污染，而哈希校验
     *   同时防两者且零维护成本；route 层与调用方（web UI）各自给出哈希来源即可。
     */
    suspend fun installApkFromUrl(url: String, expectedSha256: String): InstallResult {
        require(validateShellArg(url)) { "Invalid URL: $url" }
        // 2026-09-02：URL 会被插值进特权 curl/wget 命令。除了字符白名单（validateShellArg
        // 现已拦掉引号/换行），再加协议白名单——避免 file:// 之类把本地文件当"下载"读出来。
        require(url.startsWith("http://") || url.startsWith("https://")) { "Invalid URL scheme: $url" }
        // P0-2：HTTPS 强制（localhost/内网调试地址豁免，口径对齐 UpdateManager.requireSecureUrl）
        require(isSecureDownloadUrl(url)) { "URL 必须使用 HTTPS（内网调试地址除外）: $url" }
        // P0-2：sha256 必填，格式校验（64 位 hex，大小写归一在比较时做）
        val expectedSha = expectedSha256.trim().lowercase()
        require(expectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
            "sha256 必填且为 64 位十六进制（防任意 APK 静默安装）"
        }
        val tmpPath = "/data/local/tmp/install_${System.currentTimeMillis()}.apk"
        // 2026-10-05 R3-3 修复：任何失败路径都必须清掉已下载的临时 APK（/data 分区空间敏感，
        // 90MB 级 APK 反复失败会在 /data/local/tmp 堆积占满空间，后续下载全部 ENOSPC）。
        val download = ShellExecutor.executeAsRoot("curl -sL -o \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
        if (!download.stdout.contains("OK")) {
            val wgetResult = ShellExecutor.executeAsRoot("wget -q -O \"$tmpPath\" \"$url\" 2>/dev/null && echo OK || echo FAIL", 120_000L)
            if (!wgetResult.stdout.contains("OK")) {
                deleteTmpFile(tmpPath)
                return InstallResult(false, "下载失败: 不支持 curl/wget")
            }
        }
        // P0-2：下载内容哈希比对——不匹配即拒绝（同时拦内容投毒与镜像缓存污染）
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
        // 2026-08-28：同 installApk，删除安装后自动授权（adb install -r 覆盖安装本就继承权限）
        deleteTmpFile(tmpPath)
        // 失效点⑪：同 [installApk]
        invalidateAppListCache()
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
        // 失效点①：卸载直接改变包集合（且最容易被用户当场验证 —— 卸完刷新应当看不到它）
        invalidateAppListCache()
        // pm uninstall 成功时输出通常包含 "Success"
        return parseInstallResult(result)
    }

    suspend fun disableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm disable \"$packageName\" 2>/dev/null").isSuccess
        // 失效点②：enabled 字段直接由 `pm list packages -d` 决定
        invalidateAppListCache()
        return ok
    }

    suspend fun enableApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
        // 失效点③：同 disable，enabled 字段会变
        invalidateAppListCache()
        return ok
    }

    suspend fun clearAppData(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm clear \"$packageName\" 2>/dev/null").isSuccess
        // 失效点④（保守）：清数据不改列表内容，见 [invalidateAppListCache] 的取舍说明
        invalidateAppListCache()
        return ok
    }

    suspend fun forceStop(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("am force-stop \"$packageName\" 2>/dev/null").isSuccess
        // 失效点⑤（保守）：强停不动列表字段，但统一失效免得为它单开一条例外分支
        invalidateAppListCache()
        return ok
    }

    suspend fun grantPermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        val ok = ShellExecutor.executeAsRoot("pm grant \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
        // 失效点⑥（保守）：权限不进列表字段，见 [invalidateAppListCache] 的取舍说明
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

                // 2026-10-05 R3-2 修复：原来 readText() 在 waitFor 之前，adb 僵死不输出时
                // readText 永远阻塞（流不 EOF），30s 超时形同虚设 → 请求协程永久挂起。
                // 改为 waitFor 先行（bounded），再读已结束进程的输出。
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
            // 失效点⑫（保守）：它会顺带改 appops（影响可见性行为），且权限不进列表字段；
            // 与 [grantPermission] 同口径统一失效。放在 `also` 上是为了覆盖内部所有返回分支
            // （正常 / context 为空 / adb 找不到 / 超时），漏一条分支就等于漏一次失效。
            invalidateAppListCache()
        }
    }

    suspend fun revokePermission(pkg: String, perm: String): Boolean {
        require(validatePackageName(pkg)) { "Invalid package name: $pkg" }
        require(validateShellArg(perm)) { "Invalid permission name: $perm" }
        val ok = ShellExecutor.executeAsRoot("pm revoke \"$pkg\" \"$perm\" 2>/dev/null").isSuccess
        // 失效点⑦（保守）：同 [grantPermission]
        invalidateAppListCache()
        return ok
    }

    /** 冻结应用 (pm disable-user) */
    suspend fun freezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm disable-user --user 0 \"$packageName\" 2>/dev/null").isSuccess
        // 失效点⑧：冻结走 disable-user，同时改变 enabled 与 isFrozen 两个字段
        invalidateAppListCache()
        return ok
    }

    /** 解冻应用 (pm enable) */
    suspend fun unfreezeApp(packageName: String): Boolean {
        require(validatePackageName(packageName)) { "Invalid package name: $packageName" }
        val ok = ShellExecutor.executeAsRoot("pm enable \"$packageName\" 2>/dev/null").isSuccess
        // 失效点⑨：解冻改变 enabled 与 isFrozen
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
     * 校验 shell 参数：拒绝任何可能改变命令结构的字符。
     * 2026-08-23：允许空格，APK 文件名常含空格。
     * 2026-09-02 安全修复：原实现只拦 `$ ` ; | &`，**放过了引号与换行**。
     * 调用方普遍把参数包在单引号里（`'$arg'`），一个 `'` 就能闭合引号并追加任意命令；
     * 换行同样能在 `sh -c` 里起新语句。此处一并拦掉引号、换行、重定向与子 shell 括号。
     */
    /**
     * P0-2：下载 URL 安全协议检查。口径对齐 UpdateManager.requireSecureUrl（2026-10-05）：
     * 强制 HTTPS；localhost/127.0.0.1/内网段/.local 视为本地调试地址允许 http。
     * 这里不能用 java.net.URL 吗？可以，但签名保持 String 进 String 出更贴调用方——
     * 内部仍用 URL 解析，解析失败按不安全处理（fail-secure）。
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
     * P0-2：对下载落地的 APK 流式计算 SHA-256（90MB 级文件不能整读进内存）。
     * 文件不可读/为空返回 null（调用方 fail-secure 拒装）。root 写的文件本进程（shell uid）
     * 经 chmod 644 前可能读不了——所以先提权补一次 chmod 再算，失败仍返回 null。
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
         * 为什么不用 `ResponseCache.CacheTTL`：那个常量住在 `:core:cache`，而 `AppManager`
         * 在 `:core:controller` —— `:core:controller` 现在**没有**对 `:core:cache` 的依赖
         * （那里缓存的是 API 响应的 JsonElement，语义也不同：按 key 存 JSON，
         * 而这里存的是已解析好的 `List<Map<String,Any>>`）。为复用一个数字引入模块依赖不划算，
         * 故就地定义并写清取值理由。
         *
         * - **TTL 5 分钟**：应用集合是人手动的变化（装/卸/冻结），系统自身不会自己增删包。
         *   5 分钟内最多让用户看到一次"刚装的应用没出现"，而页面上的刷新按钮、以及任何写操作
         *   都会立刻失效缓存，所以可感知窗口很短。
         * - **刷新周期 2 分钟 < TTL**：定时刷新每 2 分钟把 TTL 顶回去，于是进页面时**几乎总是命中**
         *   （最坏情况数据年龄 = TTL − 周期 = 3 分钟）。若周期 >= TTL，就会出现"进页面恰好
         *   卡在过期点"而现场跑 4 条 shell —— 那正是这次要消掉的体感。
         *
         * 放 companion 而非类体：`const val` 在 Kotlin 里只允许出现在顶层 / 具名 object /
         * companion object 中（编译报 `Const 'val' is only allowed on top level...`）。
         */
        private const val APP_LIST_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val APP_LIST_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
    }
}