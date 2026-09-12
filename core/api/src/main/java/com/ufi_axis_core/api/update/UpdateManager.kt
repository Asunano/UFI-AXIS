@file:OptIn(ExperimentalSerializationApi::class)

package com.ufi_axis_core.api.update

import com.ufi_axis_core.lib_api.BuildConfig
import com.ufi_axis_core.util.AdbShellExecutor
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.LogPaths
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.api.routes.statusToMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后端更新管理器（2026-08-10，利用 ADB 静默安装；2026-08-10 P1 改走 v4 守护脚本）。
 *
 * 职责（前端只触发检查，后端主导下载安装）：
 * - [checkAndUpdate]：GET [AppSettings.updateUrl]（版本清单 JSON）→ 比对当前版本 →
 *   有新版则自动下载 APK + SHA-256 校验 → 启动 mode=2 watchdog 脚本 `/data/local/tmp/ufi_update.sh`
 *   （脚本负责：环境自检 → SHA-256 比对 → 轮询检测安装完成 → 重启新 Core →
 *   写 RESULT 机器可读行）；安装由 UpdateManager 后台线程直接调 AppManager.installApk
 *   （唯一安装执行者，传 /sdcard 原路径，内部复制/多策略/权限授予）；
 * - [installLocalApk]：前端推送兜底，同样由 AppManager.installApk 执行安装 + watchdog 脚本兜底；
 * - [status]：更新状态流（idle/downloading/verifying/installing/uploading/done/failed +
 *   need_push + 进度）；
 * - 结果双通道：prefs（[AppSettings.pendingUpdate] 启动恢复）+ 脚本日志
 *   `Download/UFI-AXIS/log/watchdog/watchdog.log` 的 RESULT 行（[recoverResultFromLog] / [refreshResultFromLogIfInstalling]）；
 * - 更新源不可用（URL 不可达 / 非清单 JSON / APK 下载失败）→ state=NEED_PUSH，
 *   前端据此切换「推送 APK」兜底引导。
 *
 * 版本清单 JSON 格式约定（sha256 必填，缺失即拒绝更新防旧格式）：
 * `{"version":"0.2","apkUrl":"https://.../ufi-core-0.2.apk","apkSha256":"<64位hex>"}`
 * 兼容旧 snake_case 命名（apk_url / sha256，经 @JsonNames 双名解析）。
 *
 * 2026-08-10 C5 升级为前后端双对象后，后端解析取 `backend` 对象：
 * `{"frontend":{...},"backend":{"version":"0.2","apkUrl":"...","apkSha256":"..."}}`
 * 兼容旧 flat 格式（无 backend 字段时回退整份对象解析，向后兼容）。
 */
class UpdateManager(
    private val settings: AppSettings,
    private val scriptTemplate: String = "",
    /** WS 广播回调：更新状态变化 / 恢复终态时下发 "update" 事件（由 ComponentFactory 接入 WebSocketManager）。 */
    private val broadcaster: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
    /** app 上下文（PackageInstaller API 安装需要） */
    private val appContext: android.content.Context? = null,
    /** 文件管理器的安装管理器（复用其已验证的安装策略 + 自动权限授予） */
    private val appManager: com.ufi_axis_core.controller.system.AppManager? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // ── 2026-08-20：WS 广播 + Download/UFI-AXIS 文件日志（issue #2）──
    /** 将当前更新状态作为 "update" 事件广播给已订阅客户端（如手动推送 APK 成功/失败）。 */
    private suspend fun broadcastUpdate() {
        runCatching { broadcaster("update", statusToMap()) }
    }

    /** 将更新关键事件写入 Download/UFI-AXIS/log/core/update.log（便于手机端/ADB 取回排查）。 */
    private fun logUpdateFile(msg: String) {
        runCatching {
            val dir = coreLogDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, CORE_UPDATE_LOG)
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        }
    }

    /** 分类目录：Core 侧更新日志统一写到 Download/UFI-AXIS/log/core/update.log（RESULT 备份也在此）。 */
    private fun coreLogDir(): File {
        // 路径取自 LogPaths（唯一真源）；探测顺序与回退语义保持原样
        val candidates = LogPaths.dirCandidates(LogPaths.Component.CORE)
        for (c in candidates) {
            val d = File(c)
            if (d.exists() || runCatching { d.mkdirs() }.getOrDefault(false)) return d
        }
        return File(LogPaths.dir(LogPaths.Component.CORE))
    }
    private val CORE_UPDATE_LOG = "update.log"

    /** 启动/拉起调试日志：写入 Download/UFI-AXIS/log/core/launcher.log（与 ufi_update.sh 的 watchdog 日志同属分类目录）。 */
    private fun debugLogToFile(msg: String) {
        runCatching {
            val candidates = LogPaths.dirCandidates(LogPaths.Component.CORE)
            var dir: File? = null
            for (c in candidates) {
                val d = File(c)
                if (d.exists() || runCatching { d.mkdirs() }.getOrDefault(false)) { dir = d; break }
            }
            val f = File(dir ?: File(LogPaths.dir(LogPaths.Component.CORE)), "launcher.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        }
    }

    /** P0-5 更新互斥：checkAndUpdate / installLocalApk 统一入口 CAS，防并发/重入/跨链路并发 install */
    private val updateMutex = AtomicBoolean(false)

    /** P1 E25 上传互斥：upload 期间禁止 check/install，且 check/install 期间 upload 返回 409 */
    private val uploadMutex = AtomicBoolean(false)

    @Serializable
    private data class VersionManifest(
        // C6：新格式 versionName（对齐旧项目 UFITOOLS-Widget），兼容旧 version
        @JsonNames("versionName") val version: String? = null,
        // 双名解析：新 camelCase（apkUrl/apkSha256/apkSize）与旧 snake_case（apk_url/sha256）均兼容
        @JsonNames("apkUrl") val apk_url: String? = null,
        @JsonNames("apkSha256") val sha256: String? = null,
        @JsonNames("apkSize") val apk_size: Long? = null
    )

    /**
     * 前端 App 更新信息（2026-08-10 C5：仓库根 version.json 的 frontend 对象）。
     * 供 Core 转发给前端 App（GET /api/update/frontend-info）与 frontend-apk 代理同源校验。
     * 双名解析：新 camelCase（apkUrl/apkSha256）与旧 snake_case（apk_url/sha256）均兼容。
     */
    @Serializable
    data class FrontendUpdateInfo(
        val version: String? = null,
        val versionCode: Int? = null,
        val changelog: String? = null,
        @JsonNames("apkUrl") val apk_url: String? = null,
        @JsonNames("apkSha256") val sha256: String? = null
    )

    enum class State { IDLE, DOWNLOADING, VERIFYING, INSTALLING, UPLOADING, DONE, FAILED, NEED_PUSH }

    data class UpdateStatus(
        val state: State = State.IDLE,
        val progress: Int = 0,           // 下载百分比
        val message: String = "",
        val currentVersion: String = "",
        val latestVersion: String? = null,
        val apkPath: String? = null
    )

    @Volatile
    var status: UpdateStatus = UpdateStatus(currentVersion = currentVersionName())
        private set

    /** 最近一次版本清单解析到的 backend APK 体积（字节；未解析到为 null）——供上传接口动态放开请求体上限 */
    @Volatile
    var backendApkSizeBytes: Long? = null
        private set

    /**
     * /api/update/upload 动态请求体上限（2026-08-10）：
     * 优先取清单 apkSize × 1.2 + 10MB 缓冲（比实际 APK 略大，兼容签名后体积微增）；
     * 清单未知时回落 100MB 兜底；下限 20MB（防小文件时上限过小）。
     */
    fun uploadLimitBytes(): Long {
        val size = backendApkSizeBytes ?: return 100L * 1024 * 1024
        val buffered = (size * 1.2).toLong() + 10L * 1024 * 1024
        return buffered.coerceAtLeast(20L * 1024 * 1024)
    }

    private val UPDATE_DIR = "/sdcard/Download/UFI-AXIS/update"
    private val APK_FILE = "$UPDATE_DIR/ufi-core.apk"
    private val APK_PART_FILE = "$UPDATE_DIR/ufi-core.apk.part"
    private val CORE_PACKAGE = "com.ufi_axis_core"
    private val MAX_APK_BYTES = 50L * 1024 * 1024
    /** P1 E24：守护脚本固定执行副本 + 结果日志（shell 可写、普通 App 不可写） */
    private val SCRIPT_PATH = "/data/local/tmp/ufi_update.sh"
    /**
     * 脚本运行日志（分类目录 log/watchdog/watchdog.log）；双候选路径兼容 /sdcard 与 /storage/emulated/0 布局。
     *
     * 这个文件的**生产者是 `ufi_update.sh`**（它自己持有一份路径，见 [LogPaths] 的说明），
     * 这里只是读它的 RESULT 行，所以路径必须与脚本逐字一致 —— 用真源拼接就是为了这个。
     */
    private val WATCHDOG_LOG_CANDIDATES =
        LogPaths.fileCandidates(LogPaths.Component.WATCHDOG, "watchdog.log")
    private fun watchdogLogPath(): String {
        for (c in WATCHDOG_LOG_CANDIDATES) if (File(c).exists()) return c
        return WATCHDOG_LOG_CANDIDATES.last()
    }
    private val BUSY_STATES = setOf(State.DOWNLOADING, State.VERIFYING, State.INSTALLING, State.UPLOADING)

    /** GitHub 相关域名（命中即走镜像；gh-proxy 风格前缀拼接在完整 URL 前） */
    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com"
    )

    /**
     * 基础镜像支持：GitHub 域名的 URL 前拼接 [AppSettings.updateMirrorBase] 后返回；
     * - 镜像前缀为空串（update_mirror_base=""）→ 直连，不拼接；
     * - 非 GitHub 域名原样返回（自建 CDN / 本地地址不受影响）。
     * 2026-08-12：镜像前缀由写死常量改为读 settings（前端「同步到设备」可下发）。
     */
    private fun applyMirrorToUrl(urlStr: String): String {
        if (urlStr.isBlank()) return urlStr
        val base = settings.updateMirrorBase.trim().trimEnd('/')
        if (base.isEmpty()) return urlStr   // 空串 = 直连
        val host = try { URL(urlStr).host?.lowercase() } catch (e: Exception) { return urlStr }
        if (host.isNullOrBlank()) return urlStr
        if (host in GITHUB_HOSTS || host.endsWith(".githubusercontent.com")) {
            return "$base/$urlStr"
        }
        return urlStr
    }

    /** 触发检查 + 自动更新（异步；立即返回当前状态，进度经 [status] 轮询） */
    fun checkAndUpdate() {
        scope.launch {
            // P0-5 互斥：进行中（DOWNLOADING/VERIFYING/INSTALLING/UPLOADING）或已被其他入口占用 → 忽略重复触发
            if (status.state in BUSY_STATES || !tryAcquireUpdate()) {
                AppLogger.i(TAG, "checkAndUpdate 忽略重复触发 (state=${status.state})")
                return@launch
            }
            try {
                val current = currentVersionName()
                status = UpdateStatus(State.DOWNLOADING, 0, "正在检查更新源...", current)
                logUpdateFile("checkAndUpdate start (current=$current)")

                // ① 拉取版本清单（update_url → version.json）；强制 HTTPS（本地调试 IP 例外）
                // 2026-08-10：GitHub 域名自动拼镜像前缀（设备在国内访问 GitHub 被墙）
                val manifestUrl = applyMirrorToUrl(settings.updateUrl)
                requireSecureUrl(manifestUrl, "更新源")
                val manifestJson = fetchUrl(manifestUrl, timeoutMs = 15_000)
                    ?: throw NeedPushException("更新源不可达: $manifestUrl")
                val manifest = try {
                    // C5 双对象兼容：优先取 backend 对象；无 backend 字段回退整份（旧 flat 格式）
                    val root = json.parseToJsonElement(manifestJson).jsonObject
                    val obj = root["backend"]?.jsonObject ?: root
                    json.decodeFromJsonElement(VersionManifest.serializer(), obj)
                } catch (e: Exception) {
                    throw NeedPushException("更新源不是版本清单 JSON（需要推送 APK 兜底）: ${e.message}")
                }
                // 缓存 backend APK 体积（供上传接口动态放开请求体上限；清单无该字段则保持未知）
                manifest.apk_size?.let { backendApkSizeBytes = it }
                val latest = manifest.version ?: throw NeedPushException("版本清单缺少 version 字段")
                val apkUrl = manifest.apk_url ?: throw NeedPushException("版本清单缺少 apk_url 字段")
                // P0-2：sha256 必填——缺失即拒绝更新（防旧格式/防篡改）
                val expectedSha = manifest.sha256 ?: throw NeedPushException("版本清单缺少 sha256 字段（旧格式已拒绝更新）")

                // ② 版本比对（禁止降级）
                if (compareVersions(latest, current) <= 0) {
                    status = UpdateStatus(State.IDLE, 0, "已是最新版本", current, latest)
                    return@launch
                }
                status = UpdateStatus(State.DOWNLOADING, 0, "发现新版 v$latest，开始下载...", current, latest)

                // ③ 下载 APK（.part 原子写 + 磁盘预检 + SHA-256 流式计算比对）
                requireSecureUrl(apkUrl, "APK 下载地址")
                val partFile = File(APK_PART_FILE)
                partFile.delete()
                val apkHash = try {
                    // 2026-08-10：GitHub 域名 APK 下载同样走镜像（设备在国内拉 GitHub 被墙）
                    downloadApk(applyMirrorToUrl(apkUrl), partFile)
                } catch (e: Exception) {
                    partFile.delete()
                    throw NeedPushException("APK 下载失败: ${e.message}")
                }
                if (!apkHash.equals(expectedSha.trim().lowercase(Locale.ROOT), ignoreCase = true)) {
                    partFile.delete()
                    throw Exception("APK SHA-256 校验失败（VERIFY_FAIL）：期望 $expectedSha，实际 $apkHash")
                }
                // 原子提交：.part → 最终文件名
                val apkFile = File(APK_FILE)
                if (apkFile.exists()) apkFile.delete()
                if (!partFile.renameTo(apkFile)) {
                    partFile.delete()
                    throw Exception("APK 文件提交失败")
                }

                // ④ 安装前置位（供断电/被杀恢复）+ fire-and-forget 执行
                // 不再同步等待 pm install：装后校验/主动重启由脚本独立完成，结果经日志 RESULT 行双通道确认
                status = UpdateStatus(State.INSTALLING, 100, "校验通过，正在准备安装...", current, latest, apkFile.absolutePath)
                settings.pendingUpdate = latest

                // ── 安装（fire-and-forget，不阻塞协程；自更新时进程会被杀）──
                // 2026-08-21 最终简化（用户澄清）：安装包位于 Download/UFI-AXIS/update 目录，
                // 直接传原路径给 AppManager.installApk（唯一安装执行者，内部负责复制到
                // /data/local/tmp → installApkMultiStrategy → 成功后权限授予），
                // UpdateManager 不再重复实现复制/安装细节。
                // 安装 core 会结束进程、无法同步获取结果 → 后台线程 fire-and-forget；
                // watchdog 脚本（独立进程）负责「安装后进程被杀 → 自动重启 core → 写 RESULT」兜底。
                val installApkPath = apkFile.absolutePath

                val targetSha = expectedSha.trim().lowercase(Locale.ROOT)
                val pendingInstallFile = File("/data/local/tmp/ufi_pending_install.txt")
                runCatching {
                    // MED1：第4行 mode 统一为 2（与实时启动脚本语义一致），BootReceiver 恢复时同走脚本接管安装
                    pendingInstallFile.writeText("${apkFile.absolutePath}\n$latest\n$targetSha\n2")
                }

                // 清除旧 RESULT 行（防止上次残留误判）
                clearResultFromLog()

                // ── 先启动 watchdog 脚本（独立于 Core 进程运行，安装完成后兜底）──
                // 时序要求（QA 回归修复）：watchdog 必须先进入轮询，再触发安装——否则安装线程的
                // PI commit 若先杀掉 core 进程，watchdog 尚未就绪则无重启兜底；且 BootReceiver
                // 恢复链路只启动 mode=2 纯 watchdog、无人再触发安装 → 600s 超时更新失败。
                // 职责：检测安装完成（InstallService 结果文件 / version:versionCode / lastUpdateTime）→
                // 重启 core → 写 RESULT，新 core 启动读 RESULT 回报前端。
                // pending 文件由 BootReceiver 开机恢复时消费后删除，Kotlin 侧不删（recoverResultFromLog 依赖 pendingUpdate 判定）。
                val baselineVer = getBaselineVersion()
                // 根因2c修复：baseline 不可用（"0"/blank）时 watchdog 必然 ENV_FAIL:no_baseline，
                // 在此提前判定置 FAILED，避免静默启动一个注定失败的 watchdog
                if (baselineVer.isBlank() || baselineVer == "0") {
                    AppLogger.w(TAG, "checkAndUpdate: baseline 版本不可用 ($baselineVer)，终止更新")
                    writeResultToLog("ENV_FAIL:no_baseline")
                    status = UpdateStatus(State.FAILED, 100, "无法获取当前版本基线，终止更新", current, latest, apkFile.absolutePath)
                    logUpdateFile("checkAndUpdate FAILED: baseline 不可用 ($baselineVer)")
                    broadcastUpdate()
                    return@launch
                }
                val launched = launchUpdateScript(installApkPath, latest, targetSha, mode = 2, baselineVersion = baselineVer)
                if (!launched) {
                    // 根因3修复：launchUpdateScript 失败不再静默——置 FAILED 并广播，前端可立即感知
                    AppLogger.w(TAG, "checkAndUpdate: 守护脚本启动失败")
                    writeResultToLog("INSTALL_FAILED:script_launch_failed")
                    status = UpdateStatus(State.FAILED, 100, "守护脚本启动失败，请检查 adb/脚本模板", current, latest, apkFile.absolutePath)
                    logUpdateFile("checkAndUpdate FAILED: 守护脚本启动失败")
                    broadcastUpdate()
                    return@launch
                }
                logUpdateFile("watchdog script launched OK (baseline=$baselineVer)")

                // ── 后台线程调用 AppManager.installApk（唯一安装执行者）──
                // watchdog 已先启动进入轮询，此处安装线程 fire-and-forget（安装 core 会结束进程，
                // 结果经 watchdog 检测后写 RESULT，新 core 启动读 RESULT 回报前端）。
                val manager = appManager
                if (manager == null) {
                    AppLogger.w(TAG, "checkAndUpdate: appManager 为 null，无法调用文件管理器安装接口")
                    writeResultToLog("INSTALL_FAILED:app_manager_unavailable")
                    status = UpdateStatus(State.FAILED, 100, "安装组件不可用，请重启 Core 后重试", current, latest, apkFile.absolutePath)
                    logUpdateFile("checkAndUpdate FAILED: appManager 为 null")
                    broadcastUpdate()
                    return@launch
                }
                Thread {
                    kotlinx.coroutines.runBlocking {
                        // 这是脱离 scope 的裸线程：里面抛出的异常没人接，会直接触发
                        // 全局 CrashHandler 把 core 打挂（installApk 的 require(validateShellArg)
                        // 就会抛 IllegalArgumentException）。必须自己兜住并落成 INSTALL_FAILED。
                        try {
                            val (success, message) = manager.installApk(installApkPath, isSelfUpdate = true)
                            AppLogger.i(TAG, "checkAndUpdate installApk result: success=$success ($message)")
                            // 2026-08-23 修复：自更新成功路径不可由此处写 OK（盲报），
                            // 因为 installApk 成功可能只是 commit 提交（超时返回），进程随后才会被杀。
                            // 结果由 watchdog 脚本（ufi_update.sh）在检测到新版本启动后独立写入。
                            // 仅当明确失败时才在此记录。
                            if (!success) {
                                writeResultToLog("INSTALL_FAILED:$message")
                            }
                        } catch (e: Throwable) {
                            AppLogger.e(TAG, "checkAndUpdate installApk 异常", e)
                            writeResultToLog("INSTALL_FAILED:exception:${e.message}")
                        }
                    }
                }.start()

                status = UpdateStatus(State.INSTALLING, 100, "更新执行中，设备将重启生效", current, latest, apkFile.absolutePath)
                logUpdateFile("checkAndUpdate: 安装已后台启动 (target=$latest)")
                broadcastUpdate()
            } catch (e: NeedPushException) {
                // P1 A4：更新源不可用（不可达/非 JSON/下载失败）→ NEED_PUSH，引导前端走推送兜底
                AppLogger.w(TAG, "checkAndUpdate 更新源不可用: ${e.message}")
                settings.lastUpdateResult = "NEED_PUSH:${e.message}"
                status = status.copy(state = State.NEED_PUSH, message = "更新源不可用，可推送 APK 手动更新：${e.message}")
                logUpdateFile("checkAndUpdate NEED_PUSH: ${e.message}")
                broadcastUpdate()
            } catch (e: Exception) {
                AppLogger.w(TAG, "checkAndUpdate 失败: ${e.message}")
                status = status.copy(state = State.FAILED, message = e.message ?: "更新失败")
                logUpdateFile("checkAndUpdate FAILED: ${e.message}")
                broadcastUpdate()
            } finally {
                releaseUpdate()
            }
        }
    }

    /** 安装已上传的本地 APK（前端推送兜底路径）。由 AppManager.installApk 执行安装（唯一安装执行者）+ watchdog 脚本兜底重启 */
    suspend fun installLocalApk(apkPath: String): Boolean {
        if (status.state in BUSY_STATES || !tryAcquireUpdate()) {
            AppLogger.i(TAG, "installLocalApk 忽略重复触发 (state=${status.state})")
            return false
        }
        try {
            val f = File(apkPath)
            if (!f.exists()) {
                status = status.copy(state = State.FAILED, message = "APK 文件不存在: $apkPath")
                logUpdateFile("installLocalApk FAILED: APK 不存在 $apkPath")
                broadcastUpdate()
                return false
            }
            val current = currentVersionName()
            val apkSha = sha256OfFile(f)
                ?: run {
                    status = status.copy(state = State.FAILED, message = "APK SHA-256 计算失败")
                    logUpdateFile("installLocalApk FAILED: SHA-256 计算失败")
                    broadcastUpdate()
                    return false
                }
            status = UpdateStatus(State.INSTALLING, 100, "正在静默安装 $apkPath ...", current, null, apkPath)
            settings.pendingUpdate = "local:$apkPath"
            // 2026-08-22：记录安装前 lastUpdateTime 基线——恢复判定时若未变化则推送从未
            // 生效（同版本重装也会更新 lastUpdateTime），杜绝旧版"结果日志缺失即 DONE"的误报
            settings.pendingLocalInstallLut = try {
                val ctx = appContext
                if (ctx != null) ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime else 0L
            } catch (_: Exception) { 0L }
            logUpdateFile("installLocalApk start (apk=$apkPath)")

            // 安装包直接使用上传路径（AppManager.installApk 内部自行复制到 /data/local/tmp）

            // 保存安装参数（BootReceiver 恢复用）
            val pendingInstallFile = File("/data/local/tmp/ufi_pending_install.txt")
            runCatching {
                // MED1：第4行 mode 统一为 2（与实时启动脚本语义一致），BootReceiver 恢复时同走脚本接管安装
                pendingInstallFile.writeText("${f.absolutePath}\nlocal\n$apkSha\n2")
            }

            // 清除旧 RESULT 行（防止上次残留误判）
            clearResultFromLog()

            // ── 先启动 watchdog 脚本（独立于 Core 进程运行，安装完成后兜底）──
            // 时序要求（QA 回归修复）：watchdog 必须先进入轮询，再触发安装——否则安装线程的
            // PI commit 若先杀掉 core 进程，watchdog 尚未就绪则无重启兜底；且 BootReceiver
            // 恢复链路只启动 mode=2 纯 watchdog、无人再触发安装 → 600s 超时更新失败。
            // 职责：检测安装完成（InstallService 结果文件 / version:versionCode / lastUpdateTime）→
            // 重启 core → 写 RESULT，新 core 启动读 RESULT 回报前端。
            // pending 文件由 BootReceiver 开机恢复时消费后删除，Kotlin 侧不删（recoverResultFromLog 依赖 pendingUpdate 判定）。
            val baselineVer = getBaselineVersion()
            // 根因2c修复：baseline 不可用（"0"/blank）时 watchdog 必然 ENV_FAIL:no_baseline，
            // 在此提前判定置 FAILED，避免静默启动一个注定失败的 watchdog
            if (baselineVer.isBlank() || baselineVer == "0") {
                AppLogger.w(TAG, "installLocalApk: baseline 版本不可用 ($baselineVer)，终止更新")
                writeResultToLog("ENV_FAIL:no_baseline")
                status = status.copy(state = State.FAILED, message = "无法获取当前版本基线，终止更新")
                logUpdateFile("installLocalApk FAILED: baseline 不可用 ($baselineVer)")
                broadcastUpdate()
                return false
            }
            val launched = launchUpdateScript(apkPath, "local", apkSha, mode = 2, baselineVersion = baselineVer)
            if (!launched) {
                // 根因3修复：launchUpdateScript 失败不再静默——置 FAILED 并广播，前端可立即感知
                AppLogger.w(TAG, "installLocalApk: 守护脚本启动失败")
                writeResultToLog("INSTALL_FAILED:script_launch_failed")
                status = status.copy(state = State.FAILED, message = "守护脚本启动失败，请检查 adb/脚本模板")
                logUpdateFile("installLocalApk FAILED: 守护脚本启动失败")
                broadcastUpdate()
                return false
            }
            logUpdateFile("watchdog script launched OK (baseline=$baselineVer)")

            // ── 后台线程调用 AppManager.installApk（唯一安装执行者）──
            // 与 checkAndUpdate 同语义：由 UpdateManager 后台线程调 appManager.installApk 完成安装
            // （不在文件管理器路径里做更新逻辑），watchdog 脚本（已先启动）只做「安装后进程被杀 →
            // 自动重启 core → 写 RESULT」兜底。
            val manager = appManager
            if (manager == null) {
                AppLogger.w(TAG, "installLocalApk: appManager 为 null，无法调用文件管理器安装接口")
                writeResultToLog("INSTALL_FAILED:app_manager_unavailable")
                status = status.copy(state = State.FAILED, message = "安装组件不可用，请重启 Core 后重试")
                logUpdateFile("installLocalApk FAILED: appManager 为 null")
                broadcastUpdate()
                return false
            }
            Thread {
                kotlinx.coroutines.runBlocking {
                    // 同 checkAndUpdate：裸线程里的异常没人接，会直接把 core 打挂
                    try {
                        val (success, message) = manager.installApk(apkPath, isSelfUpdate = true)
                        AppLogger.i(TAG, "installLocalApk installApk result: success=$success ($message)")
                        // 2026-08-23 修复：本地推送自更新同样严禁盲报 OK。
                        // 仅在明确失败时写入。成功结果由 watchdog 完成判定。
                        if (!success) {
                            writeResultToLog("INSTALL_FAILED:$message")
                        }
                    } catch (e: Throwable) {
                        AppLogger.e(TAG, "installLocalApk installApk 异常", e)
                        writeResultToLog("INSTALL_FAILED:exception:${e.message}")
                    }
                }
            }.start()

            status = UpdateStatus(State.INSTALLING, 100, "安装执行中，设备将重启生效", current, null, apkPath)
            logUpdateFile("installLocalApk: 安装已后台启动")
            broadcastUpdate()
            return true
        } finally {
            releaseUpdate()
        }
    }

    /** 将 RESULT 行写入 update log（供 recoverResultFromLog 读取） */
    private fun writeResultToLog(result: String) {
        runCatching {
            val dir = coreLogDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, CORE_UPDATE_LOG)
            f.appendText("RESULT=$result\n")
        }
    }

    /** 清除 core 日志中旧的 RESULT 行（防止上次残留误判） */
    private fun clearResultFromLog() {
        runCatching {
            val f = File(coreLogDir(), CORE_UPDATE_LOG)
            if (f.exists()) {
                val lines = f.readLines().filter { !it.trim().startsWith("RESULT=") }
                f.writeText(lines.joinToString("\n") + "\n")
            }
        }
    }

    /**
     * 启动恢复（P0-4，RESULT 机制最小版）：由 ComponentFactory 在服务启动时调用。
     * 若上次更新安装前被杀/断电，prefs 留有 pendingUpdate → 校验当前版本：
     * - 当前进程编译版本 == pending（新 Core 已接管）→ 写回 DONE + 清 pending；
     * - 或设备已安装版本 == pending（装成功但未重启，best-effort dumpsys）→ 写回 DONE + 清 pending；
     * - 否则 → 写回 FAILED（上次更新未生效，可重试）。
     */
    suspend fun recoverFromPending() {
        val pending = settings.pendingUpdate ?: return
        if (pending.isBlank()) return
        val current = currentVersionName()
        // 本地推送占位（local:<path>）：目标版本未知，无法与当前版本比对——结果由 RESULT 日志通道
        // （recoverResultFromLog 已先执行）裁决；若日志也被清空，则按 WARN 处理而非误报 FAILED。
        if (pending.startsWith("local:")) {
            // 本地推送占位（local:<path>）：结果由 RESULT 日志通道裁决；日志缺失时不再
            // 盲报 DONE（旧版误报掩盖了"推送从未安装"），改用 lastUpdateTime 基线校验：
            // 安装真正生效（含同版本重装）必然改变 lastUpdateTime；未变 = 从未安装 → FAILED。
            settings.pendingUpdate = null
            val baselineLut = settings.pendingLocalInstallLut
            settings.pendingLocalInstallLut = 0L
            val curLut = try {
                val ctx = appContext
                if (ctx != null) ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime else -1L
            } catch (_: Exception) { -1L }
            if (baselineLut > 0 && curLut > 0) {
                if (curLut != baselineLut) {
                    settings.lastUpdateResult = "OK:local"
                    status = status.copy(state = State.DONE, message = "本地推送更新已完成（APK 已重新安装生效）")
                    AppLogger.i(TAG, "recoverFromPending: 本地推送已生效 (lastUpdateTime $baselineLut → $curLut)")
                } else {
                    settings.lastUpdateResult = "FAILED:LOCAL_NOT_INSTALLED"
                    status = status.copy(state = State.FAILED, message = "本地推送未生效（APK 未被安装，lastUpdateTime 未变化），请重试推送")
                    AppLogger.w(TAG, "recoverFromPending: 本地推送未安装 (lastUpdateTime 未变: $curLut)")
                }
            } else {
                // 基线不可用（旧版本升级场景）：保守按 WARN 处理
                settings.lastUpdateResult = "WARN:VERIFY_SKIPPED:$pending"
                status = status.copy(state = State.DONE, message = "本地推送更新已执行（基线缺失，装后校验被跳过）")
            }
            // 2026-09-06：补一次清理。[cleanupInstallArtifacts] 的 startup 触发点在
            // [recoverResultFromLog] 里，条件是「pending 已为空」—— 而 pending 恰恰由本函数清，
            // 且本函数排在它之后（ComponentFactory 先 recoverResultFromLog 再 recoverFromPending）。
            // 于是一次推送安装后的那次启动必然漏掉清理，安装包要多躺一整个启动周期。
            // 与 result-ok 通道对称：仅在判定成功时清，FAILED 保留安装包供重试。
            if (status.state == State.DONE) cleanupInstallArtifacts("pending-recovered-done")
            broadcastUpdate()
            return
        }
        val installed = queryInstalledVersionName()
        val matched = pending == current || (installed != null && pending == installed)
        if (matched) {
            settings.pendingUpdate = null
            settings.lastUpdateResult = "OK:$pending"
            status = UpdateStatus(State.DONE, 100, "本次更新已完成（v$pending），设备运行正常", current, pending)
            AppLogger.i(TAG, "recoverFromPending: 本次更新成功 (v$pending)")
        } else if (installed == null) {
            // P1 A1/E13：dumpsys 解析失败（空输出/格式异常）→ 不误报 FAILED，置 WARN 记录
            AppLogger.w(TAG, "recoverFromPending: 装后版本解析失败（dumpsys 空），跳过装后校验 (pending=$pending)")
            settings.pendingUpdate = null
            settings.lastUpdateResult = "WARN:VERIFY_SKIPPED:$pending"
            status = status.copy(state = State.DONE, message = "上次更新已执行（装后版本校验被跳过，设备运行正常）")
        } else {
            settings.pendingUpdate = null
            settings.lastUpdateResult = "FAILED:NOT_ACTIVE:${current}"
            status = status.copy(state = State.FAILED, message = "上次更新未生效（目标 $pending，当前 $current），可重试")
            AppLogger.w(TAG, "recoverFromPending: 上次更新未生效 (pending=$pending, current=$current, installed=$installed)")
        }
        // 同上：非 local 通道也补一次，仅成功时清
        if (status.state == State.DONE) cleanupInstallArtifacts("pending-recovered-done")
    }

    // ── P1 A3/E25：上传互斥（UpdateRoutes.upload 调用）──

    /** 尝试进入上传态：check/install 进行中或已有上传占用 → false（路由返回 409） */
    fun tryBeginUpload(): Boolean {
        if (status.state in BUSY_STATES) {
            AppLogger.i(TAG, "tryBeginUpload 拒绝：更新进行中 (state=${status.state})")
            return false
        }
        if (!uploadMutex.compareAndSet(false, true)) {
            AppLogger.i(TAG, "tryBeginUpload 拒绝：已有上传占用")
            return false
        }
        status = status.copy(state = State.UPLOADING, message = "正在接收 APK 上传...")
        return true
    }

    /** 上传结束释放互斥；成功时把落地路径写进 status，失败置 FAILED 展示原因 */
    fun finishUpload(success: Boolean, errorMessage: String? = null, apkPath: String? = null) {
        uploadMutex.set(false)
        if (success) {
            // 2026-09-06：原来只改 state/message 没带 apkPath —— GET /api/update/status 于是恒回
            // apk_path=null，Web 端「安装已上传的包」按钮（v-if="backendStatus.apkPath"）在上传完
            // 立刻回读状态时被抹掉，表现为"提示点安装但没有安装按钮"。
            status = status.copy(state = State.IDLE, message = "APK 上传完成，可执行安装", apkPath = apkPath)
        } else {
            status = status.copy(state = State.FAILED, message = errorMessage ?: "上传失败")
        }
    }

    /** 重置状态（UI 关闭/重试前） */
    fun reset() {
        status = UpdateStatus(currentVersion = currentVersionName())
    }

    // ── 前端 App 更新信息（2026-08-10 C5：Core 转发给前端 App）──

    /** frontend 信息内存缓存（避免每次点检查都拉外网） */
    @Volatile
    private var frontendInfoCache: FrontendUpdateInfo? = null

    @Volatile
    private var frontendInfoCacheAt: Long = 0L

    private val FRONTEND_INFO_CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟

    /**
     * 拉取仓库根 version.json 的 frontend 对象（前端 App 更新信息）。
     * - 复用 [fetchUrl] 外网拉取逻辑（update_url 同一清单源）；
     * - 5 分钟内存缓存，避免前端每次点检查都触发外网请求；
     * - 拉取/解析失败返回 null（路由据此回 502「更新源不可用」）。
     */
    suspend fun fetchFrontendUpdateInfo(): FrontendUpdateInfo? {
        val now = System.currentTimeMillis()
        val cached = frontendInfoCache
        if (cached != null && now - frontendInfoCacheAt < FRONTEND_INFO_CACHE_TTL_MS) {
            return cached
        }
        // 外网阻塞 IO 切到 IO 线程池，避免占用 Ktor 事件循环线程
        return withContext(Dispatchers.IO) {
            try {
                // 2026-08-10：GitHub 域名自动拼镜像前缀（设备在国内访问 GitHub 被墙）
                val manifestUrl = applyMirrorToUrl(settings.updateUrl)
                requireSecureUrl(manifestUrl, "更新源")
                val manifestJson = fetchUrl(manifestUrl, timeoutMs = 15_000)
                    ?: throw Exception("更新源不可达: $manifestUrl")
                val root = json.parseToJsonElement(manifestJson).jsonObject
                val obj = root["frontend"]?.jsonObject ?: root
                val info = json.decodeFromJsonElement(FrontendUpdateInfo.serializer(), obj)
                frontendInfoCache = info
                frontendInfoCacheAt = now
                AppLogger.i(TAG, "fetchFrontendUpdateInfo: v${info.version}")
                info
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetchFrontendUpdateInfo 失败: ${e.message}")
                null
            }
        }
    }

    /** 最近一次成功缓存的 frontend apk_url（frontend-apk 代理同源校验用） */
    fun cachedFrontendApkUrl(): String? = frontendInfoCache?.apk_url

    // ── 后端 core 更新信息（2026-09-06：只检查、不下载、不安装）──
    //
    // 为什么单独开一个函数而不复用 [checkAndUpdate]：后者的语义是「检查+下载+校验+安装+重启」
    // 一条龙，Web 端点一次「检查更新」就直接开装、没有任何确认环节。这里只拉清单做版本比对，
    // **不碰 [status] 状态机**（不会把 state 置为 DOWNLOADING），供前端「先展示再二次确认」。

    /** 清单 backend 对象的完整解析形态（[VersionManifest] 没有 changelog，故单独一份）。 */
    @Serializable
    private data class BackendManifest(
        @JsonNames("versionName") val version: String? = null,
        val changelog: String? = null,
        @JsonNames("apkUrl") val apk_url: String? = null,
        @JsonNames("apkSha256") val sha256: String? = null,
        @JsonNames("apkSize") val apk_size: Long? = null
    )

    /** 后端 core 更新信息（GET /api/update/backend-info 的响应载荷）。 */
    data class BackendUpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val hasUpdate: Boolean,
        val changelog: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String
    )

    /** backend 清单内存缓存（与 frontend 侧同一份 TTL，避免每次点检查都拉外网） */
    @Volatile
    private var backendManifestCache: BackendManifest? = null

    @Volatile
    private var backendManifestCacheAt: Long = 0L

    /**
     * 拉取仓库根 version.json 的 backend 对象并与当前版本比对（**只读**，不触发任何下载/安装）。
     * - 解析口径与 [checkAndUpdate] 完全一致：优先取 backend 对象，无该字段回退整份（旧 flat 格式）；
     * - 复用 [fetchUrl] / [requireSecureUrl] / [applyMirrorToUrl] / [compareVersions]；
     * - 5 分钟内存缓存（清单本体），currentVersion / hasUpdate 每次现算；
     * - 拉取或解析失败返回 null（路由据此回 502「更新源不可用」）。
     */
    suspend fun fetchBackendUpdateInfo(): BackendUpdateInfo? {
        val now = System.currentTimeMillis()
        val cached = backendManifestCache
        val manifest = if (cached != null && now - backendManifestCacheAt < FRONTEND_INFO_CACHE_TTL_MS) {
            cached
        } else {
            // 外网阻塞 IO 切到 IO 线程池，避免占用 Ktor 事件循环线程
            withContext(Dispatchers.IO) {
                try {
                    val manifestUrl = applyMirrorToUrl(settings.updateUrl)
                    requireSecureUrl(manifestUrl, "更新源")
                    val manifestJson = fetchUrl(manifestUrl, timeoutMs = 15_000)
                        ?: throw Exception("更新源不可达: $manifestUrl")
                    val root = json.parseToJsonElement(manifestJson).jsonObject
                    val obj = root["backend"]?.jsonObject ?: root
                    val parsed = json.decodeFromJsonElement(BackendManifest.serializer(), obj)
                    backendManifestCache = parsed
                    backendManifestCacheAt = now
                    // 顺带刷新上传接口的动态请求体上限依据（与 checkAndUpdate 同口径）
                    parsed.apk_size?.let { backendApkSizeBytes = it }
                    AppLogger.i(TAG, "fetchBackendUpdateInfo: v${parsed.version}")
                    parsed
                } catch (e: Exception) {
                    AppLogger.w(TAG, "fetchBackendUpdateInfo 失败: ${e.message}")
                    null
                }
            }
        } ?: return null
        val current = currentVersionName()
        val latest = manifest.version?.trim().orEmpty()
        return BackendUpdateInfo(
            currentVersion = current,
            latestVersion = latest,
            // 与 checkAndUpdate 的「禁止降级」判定同一套 compareVersions
            hasUpdate = latest.isNotBlank() && compareVersions(latest, current) > 0,
            changelog = manifest.changelog?.trim().orEmpty(),
            apkUrl = manifest.apk_url?.trim().orEmpty(),
            apkSize = manifest.apk_size ?: 0L,
            sha256 = manifest.sha256?.trim().orEmpty()
        )
    }

    // ── P1 B2：v4 守护脚本执行通道 ──

    /**
     * 写 v4 守护脚本到 /data/local/tmp/ufi_update.sh（覆盖写，保证每次最新）并 fire-and-forget 执行。
     * 脚本（mode=2 watchdog）负责：环境自检 → APK SHA-256 比对 → 轮询检测安装完成
     * （InstallService 结果文件 / version:versionCode / lastUpdateTime）→
     * am kill + start-foreground-service 重启新 Core；结果写 log/watchdog/watchdog.log 的 RESULT 行。
     * 安装本身由 AppManager.installApk 执行（UpdateManager 后台线程调用，脚本不触发安装）。
     *
     * @param apkPath APK 绝对路径（checkAndUpdate 为 /sdcard/Download/UFI-AXIS/update/ufi-core.apk；installLocalApk 为上传路径）
     * @param targetVer 目标版本号（装后校验比对；installLocalApk 传 "local" 表示跳过版本比对）
     * @param apkSha APK 的 SHA-256 hex（脚本第 3 参，装前完整性校验）
     * @param mode 脚本第 4 参：恒为 2=等待外部安装（UpdateManager→AppManager.installApk 唯一安装执行者，脚本仅 watchdog）
     * @param baselineVersion mode=2 时的 versionName:versionCode 基线（脚本据此检测 pm install 完成）
     * @return 脚本是否成功写入并启动（不等安装结果）
     */
    private suspend fun launchUpdateScript(apkPath: String, targetVer: String, apkSha: String, mode: Int = 0, baselineVersion: String = "0"): Boolean {
        return try {
            if (scriptTemplate.isBlank()) {
                AppLogger.w(TAG, "launchUpdateScript: 脚本模板为空（assets/shell/ufi_update.sh 未提取）")
                logUpdateFile("launchUpdateScript FAILED: 脚本模板为空")
                return false
            }
            // 2b 可观测性：每一步都写 core 日志，真机失败时能精确定位卡在哪一步
            logUpdateFile("launchUpdateScript start (apk=$apkPath target=$targetVer mode=$mode baseline=$baselineVersion adbAvailable=${AdbShellExecutor.isAvailable})")
            // ── 清理 stale lock（上次脚本异常退出残留）──
            // 进入 launchUpdateScript() 意味着全新更新，旧锁一定是 stale 的
            val cleanResult = ShellExecutor.executeAsRoot(
                "rm -rf /data/local/tmp/ufi_update.lock 2>/dev/null; " +
                    "rm -f /data/local/tmp/ufi_install_result.txt 2>/dev/null",
                timeoutMs = 5_000L
            )
            logUpdateFile("launchUpdateScript: stale lock cleaned (exit=${cleanResult.exitCode})")
            // 注意：此处【不再】执行 `appops reset`。
            // 旧逻辑在安装前对 Core 包执行 appops reset，会将 MANAGE_EXTERNAL_STORAGE /
            // REQUEST_INSTALL_PACKAGES 等特殊权限重置为默认值（MANAGE_EXTERNAL_STORAGE 默认即拒绝），
            // 导致推送更新后 Core 重启即丢失关键权限、表现为「无法正常安装/功能异常」，而文件管理器
            // 直接安装路径不经过此步骤故正常。Core 作为特权系统应用，其权限由 privapp-permissions
            // 白名单在安装时持久授予，无需运行时 appops 操作。安装与结果判定已由 PackageInstaller
            // （app 进程内，uid=app 自身，天然规避 shell pm 的 AppOpsService.checkPackage NPE）承担。
            // 直接搬运已提取的真实脚本文件：assets/shell/ufi_update.sh（APK 内置静态文件）
            // 已由 AssetExtractor.extractAll 提取到 filesDir/shell/ufi_update.sh（并设可执行）。
            // 此处仅用 Java Files.copy 把该真实文件 cp 到 /data/local/tmp/ufi_update.sh，
            // 让 adb shell(uid 2000) 能读/执行（Core 私有 filesDir 对 shell 不可见）。
            // 不 runtime 用 cat/echo/base64 重新生成内容——脚本本就是文件，直接搬文件即可。
            val src = appContext
                ?.let { com.ufi_axis_core.util.AssetExtractor.getPath(it, "ufi_update.sh") }
                ?.let { java.io.File(it) }
            val copyOk = if (src != null && src.exists() && src.length() > 0) {
                runCatching {
                    val dest = java.io.File(SCRIPT_PATH)
                    java.nio.file.Files.copy(
                        src.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    // 属主是 Core 的 uid；adb shell(uid 2000) 以 `sh 脚本` 方式【读取】该文件，
                    // 必须放开 other 的 r+x，否则报 "Permission denied"（执行位只放开仍打不开文件）。
                    dest.setReadable(true, false)   // 所有人可读（sh 读取脚本所需）
                    dest.setExecutable(true, false)  // 所有人可执行（兜底）
                    dest.exists() && dest.length() > 0
                }.getOrElse { e ->
                    AppLogger.w(TAG, "launchUpdateScript: 拷贝脚本失败: ${e.message}")
                    false
                }
            } else {
                AppLogger.w(TAG, "launchUpdateScript: 源脚本文件不存在 (${src?.absolutePath})")
                false
            }
            logUpdateFile("launchUpdateScript: script copied (success=$copyOk)")
            debugLogToFile("script copied success=$copyOk src=${src?.absolutePath} dest=$SCRIPT_PATH")
            if (!copyOk) {
                AppLogger.w(TAG, "launchUpdateScript: 写脚本失败")
                return false
            }
            // 清掉旧 RESULT 日志，避免误读上一次结果
            ShellExecutor.executeAsRoot("rm -f ${WATCHDOG_LOG_CANDIDATES.joinToString(" ")}", timeoutMs = 10_000)
            // fire-and-forget 后台启动 watchdog
            // 2026-08-21 修复：旧写法 `sh -c "sh ... & echo WDPID=\$!"` 把 `&` 包在双引号内、且多嵌套一层，
            // 交互式 adb shell 下 `$!` 取不到后台 PID、子进程实际未 spawn（launcher.log 显示 WDPID= 空、.launch.out 未生成）。
            // 改直启模式：`nohup sh <脚本> ... &` 由外层 shell 直接解析 `&`，nohup 忽略 SIGHUP 使进程在 adb 会话退出后存活；
            // 输出重定向到 world-writable 的 /data/local/tmp/ufi_watchdog.out（不受 /sdcard 存储权限影响）；PID 写入文件便于 ps 验证。
            // 第4参数 mode: 0=正常 1=跳过安装 2=等待外部安装；第5参数 baselineVersion: mode=2 时传 versionName:versionCode 基线
            val wdOut = "/data/local/tmp/ufi_watchdog.out"
            // setsid 不能省（此前只有 nohup）：AdbShellExecutor 不可用时 executeAsRoot 会回退到
            // app uid 的 sh，watchdog 就成了 Core 进程的子进程 —— 安装 commit 杀进程 / 脚本自己
            // `am force-stop` 时会连它一起带走（nohup 只挡 SIGHUP，挡不住 SIGKILL），
            // 结果就是"装上了但没人重启 Core"。BootReceiver 那条路径一直用的就是 nohup setsid。
            val runCmd = "nohup setsid sh $SCRIPT_PATH '$apkPath' '$targetVer' '$apkSha' '$mode' '$baselineVersion' </dev/null >$wdOut 2>&1 & echo WDPID=\$! >/data/local/tmp/ufi_watchdog.pid"
            val runResult = ShellExecutor.executeAsRoot(runCmd, timeoutMs = 10_000)
            AppLogger.i(TAG, "launchUpdateScript: 已后台启动 (apk=$apkPath target=$targetVer mode=$mode baseline=$baselineVersion) exit=${runResult.exitCode}")
            logUpdateFile("launchUpdateScript: watchdog launched (exit=${runResult.exitCode} stderr=${runResult.stderr})")
            debugLogToFile("watchdog launched exit=${runResult.exitCode} stderr='${runResult.stderr}' adbAvailable=${AdbShellExecutor.isAvailable} cmd=$runCmd")
            // 写“安装已触发”标志（world-readable），供 watchdog 早退判断：
            // 若 60s 无任何变化且本标志不存在，说明安装根本未发起，watchdog 直接早退而非傻等。
            runCatching {
                val trigger = java.io.File("/data/local/tmp/ufi_install_triggered")
                trigger.writeText("triggered=1 apk=$apkPath ver=$targetVer ts=${System.currentTimeMillis()}\n")
                trigger.setReadable(true, false) // adb shell(uid 2000) 需读
            }
            // 2s 后检查启动哨兵（/data/local/tmp，world-writable，不受 /sdcard 权限影响）
            ShellExecutor.executeAsRoot(
                "sleep 2; echo '---SENTINEL---'; cat /data/local/tmp/.watchdog_started 2>/dev/null; echo '---PID---'; cat /data/local/tmp/ufi_watchdog.pid 2>/dev/null; echo '---OUT---'; head -c 600 $wdOut 2>/dev/null",
                timeoutMs = 8_000
            ).also { sr ->
                if (sr.stdout.contains("[STARTED]")) {
                    debugLogToFile("sentinel OK: ${sr.stdout.lines().firstOrNull { it.contains("[STARTED]") }}")
                } else {
                    val wdOutText = runCatching { java.io.File(wdOut).readText().take(600) }.getOrDefault("<unreadable>")
                    debugLogToFile("sentinel MISSING. runResult.stdout='${runResult.stdout}' watchdog.out=$wdOutText")
                    logUpdateFile("launchUpdateScript: WARN sentinel_missing (script 未真正启动)")
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "launchUpdateScript 异常: ${e.message}")
            logUpdateFile("launchUpdateScript EXCEPTION: ${e.message}")
            false
        }
    }

    /**
     * 启动恢复（P1 B2，RESULT 双通道之一）：新 Core 启动时读 `log/watchdog/watchdog.log`
     * 的 RESULT 行更新 [settings.lastUpdateResult] / [status]。
     * - RESULT=OK:<ver>：安装成功 + 装后校验通过（ver=unknown 表示装后版本解析失败但按 WARN 放行）→ DONE；
     * - RESULT=INSTALL_FAILED:<原因> / VERIFY_FAIL:<原因> / ENV_FAIL:<原因> → FAILED + 中文原因。
     */
    suspend fun recoverResultFromLog() {
        val pending = settings.pendingUpdate
        if (pending == null || pending.isBlank()) {
            // 无 pending = 没有安装在途，那么 update 目录里剩下的 APK 就是上一轮的垃圾。
            // 自更新会杀掉进程，安装成功后的清理只能落到「下次启动」这一步。
            cleanupInstallArtifacts("startup-no-pending")
            return
        }
        val current = currentVersionName()
        val result = readResultFromLog()


        // 无 RESULT 行：进程可能在安装过程中被杀（自更新），检查版本是否已变化
        if (result == null) {
            if (!pending.startsWith("local:")) {
                // checkAndUpdate 路径：pending = 目标版本号
                if (current != pending) {
                    // 版本已变 → 安装成功（新 Core 已启动）
                    settings.pendingUpdate = null
                    settings.lastUpdateResult = recordSignatureFingerprint(current)
                    cleanupInstallArtifacts("version-changed")
                    status = UpdateStatus(State.DONE, 100, "上次更新已完成（v$current），设备运行正常", current, pending)
                    AppLogger.i(TAG, "recoverResultFromLog: 无 RESULT 行但版本已变 ($pending → $current)，判定成功")
                    logUpdateFile("recoverResultFromLog: 版本变化检测成功 ($pending → $current)")
                    broadcastUpdate()
                }
                // 版本未变 → 不处理，等下次 installLocalApk 重试
            } else {
                // installLocalApk 路径：pending = "local:path"，无法比版本号
                // 检查编译版本是否变化（新 Core 编译版本 > 旧 Core）
                val compiledVer = try {
                    val ctx = appContext ?: return
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
                } catch (_: Exception) { "unknown" }
                // 如果编译版本与当前运行版本一致，无法判断 → 留 pending 等重试
                AppLogger.w(TAG, "recoverResultFromLog: 无 RESULT 行 (pending=$pending, current=$current, compiled=$compiledVer)")
            }
            return
        }

        when {
            result.startsWith("OK:") -> {
                val ver = result.removePrefix("OK:")
                // 规范：RESULT=OK:ver 且 pendingUpdate 匹配才置 DONE（防陈旧日志误判）
                // 匹配条件：ver==pending（checkAndUpdate）｜pending 为 local 占位（installLocalApk）｜ver=unknown（WARN 放行）
                val pendingMatches = pending == ver ||
                    pending.startsWith("local:") ||
                    ver.isBlank() || ver == "unknown"
                if (!pendingMatches) {
                    AppLogger.w(TAG, "recoverResultFromLog: RESULT=OK 但 pending 不匹配，忽略 (pending=$pending, ver=$ver)")
                    return
                }
                settings.pendingUpdate = null
                settings.lastUpdateResult = recordSignatureFingerprint(if (ver.isBlank() || ver == "unknown") current else ver)
                val display = when {
                    ver.isBlank() || ver == "unknown" -> "（装后版本解析失败，按成功放行）"
                    ver == "local" -> "v本地推送"  // installLocalApk 路径：脚本传字面量 "local"
                    else -> "v$ver"
                }
                status = UpdateStatus(State.DONE, 100, "本次更新已完成（$display），设备运行正常", current, ver.takeIf { it.isNotBlank() && it != "unknown" })
                AppLogger.i(TAG, "recoverResultFromLog: 本次更新成功 (RESULT=$result)")
                logUpdateFile("recoverResultFromLog: 更新成功 (RESULT=$result)")
                // MED2：更新确认成功后清理 pending 文件（防下次开机 BootReceiver 重复安装 core）
                runCatching { File("/data/local/tmp/ufi_pending_install.txt").delete() }
                cleanupInstallArtifacts("result-ok")
                // 安装后的运行时权限授予由 AppManager.installApk 内部 + samba_exec 承担，
                // UpdateManager 不再主动授予权限（ufi_update.sh 已去除权限逻辑）
                broadcastUpdate()
            }
            result.startsWith("INSTALL_FAILED") -> {
                val reason = result.removePrefix("INSTALL_FAILED").removePrefix(":")
                settings.pendingUpdate = null
                settings.lastUpdateResult = "FAILED:INSTALL_FAILED:$reason"
                status = status.copy(state = State.FAILED, message = "安装失败：${classifyInstallError(reason)}")
                AppLogger.w(TAG, "recoverResultFromLog: 安装失败 (RESULT=$result)")
                logUpdateFile("recoverResultFromLog: 安装失败 (RESULT=$result)")
                broadcastUpdate()
            }
            result.startsWith("VERIFY_FAIL") -> {
                val reason = result.removePrefix("VERIFY_FAIL").removePrefix(":")
                settings.pendingUpdate = null
                settings.lastUpdateResult = "FAILED:VERIFY_FAIL:$reason"
                status = status.copy(state = State.FAILED, message = "校验失败：$reason")
                AppLogger.w(TAG, "recoverResultFromLog: 校验失败 (RESULT=$result)")
                logUpdateFile("recoverResultFromLog: 校验失败 (RESULT=$result)")
                broadcastUpdate()
            }
            result.startsWith("ENV_FAIL") -> {
                val reason = result.removePrefix("ENV_FAIL").removePrefix(":")
                settings.pendingUpdate = null
                settings.lastUpdateResult = "FAILED:ENV_FAIL:$reason"
                status = status.copy(state = State.FAILED, message = "环境自检失败：$reason")
                AppLogger.w(TAG, "recoverResultFromLog: 环境自检失败 (RESULT=$result)")
                logUpdateFile("recoverResultFromLog: 环境自检失败 (RESULT=$result)")
                broadcastUpdate()
            }
            else -> AppLogger.w(TAG, "recoverResultFromLog: 未知 RESULT 行: $result")
        }
    }

    /**
     * 运行中轮询刷新（P1 B2）：status 处于 INSTALLING 时，前端 GET /status 顺带读日志 RESULT 行，
     * 使「脚本已失败但 Core 未重启」的场景也能及时反映（旧版完好，可直接重试）。
     */
    suspend fun refreshResultFromLogIfInstalling() {
        if (status.state != State.INSTALLING) return
        recoverResultFromLog()
    }

    /**
     * 清理安装产物（2026-08-28 新增）。
     *
     * 之前**完全没有安装后清理**：`ufi-core.apk`/`ufi-core-uploaded.apk` 装完就一直躺在
     * `/sdcard/Download/UFI-AXIS/update/`（各约 60MB），加上 `AppManager` 遗留在
     * `/data/local/tmp` 的临时包，实测占了 2.2GB。自更新时进程会被安装器杀掉，
     * 所以清理点必须放在「确认成功之后」和「下次启动发现无 pending」这两处。
     *
     * 失败时**不清理**：用户可能还要重试 install-local，删了包就得重新上传 60MB。
     * `ufi-core-stage.apk` 也不动 —— 那是 keepalive 脚本的暂存位（固定名、自然覆盖），
     * 删它可能打断分阶段安装。
     *
     * 2026-09-06：**扫描结果不再静默**。原来只在 `freed > 0` 时记日志，于是"扫到文件但一个都没
     * 删掉"和"目录本来就是空的"在日志里完全同形 —— 实测 update 目录躺着一个 9 小时前的 15MB
     * `ufi-core-uploaded.apk`，翻遍 update.log 找不到任何与它相关的记录，定位成本极高。
     * 现在只要扫到文件就必落一行 `scanned=N, freed=NB`，删不掉的另出一条 WARN 带文件名。
     */
    private fun cleanupInstallArtifacts(reason: String) {
        if (status.state in BUSY_STATES) return
        val targets = mutableListOf<File>()
        runCatching {
            File(UPDATE_DIR)
                .listFiles { f -> f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part")) }
                ?.let { targets.addAll(it) }
        }
        runCatching {
            File("/data/local/tmp")
                .listFiles { f -> f.isFile && f.name.startsWith("appmgr_install") && f.name.endsWith(".apk") }
                ?.let { targets.addAll(it) }
        }
        // 目录为空是常态（多数启动都没有残留），不产生日志噪音
        if (targets.isEmpty()) return
        var freed = 0L
        val stubborn = mutableListOf<String>()
        targets.forEach { f ->
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) freed += size
            else stubborn += "${f.name}(${size / 1024}KB)"
        }
        AppLogger.i(TAG, "cleanupInstallArtifacts($reason): 扫到 ${targets.size} 个，释放 ${freed / 1024 / 1024}MB")
        logUpdateFile("cleanupInstallArtifacts($reason): scanned=${targets.size}, freed=${freed}B")
        if (stubborn.isNotEmpty()) {
            AppLogger.w(TAG, "cleanupInstallArtifacts($reason): ${stubborn.size} 个删不掉 —— ${stubborn.joinToString(", ")}")
            logUpdateFile("cleanupInstallArtifacts($reason): 删除失败 files=[${stubborn.joinToString(", ")}]")
        }
    }


    /** 读日志并返回 RESULT= 行内容（先查脚本日志，再查 core 日志；无 RESULT 行返回 null） */
    private suspend fun readResultFromLog(): String? {
        return try {
            // 先查脚本运行日志（分类目录 log/watchdog/watchdog.log）。
                    // 2026-08-21 修复：append 模式下 firstOrNull 会命中「第一行」（=上次会话的 RESULT），导致误报成功。
                    // 必须反序读，取「最后一行」RESULT（=本轮会话的新结果）。
                    val fromScript = runCatching {
                        val r = ShellExecutor.executeAsRoot("cat ${watchdogLogPath()} 2>/dev/null", timeoutMs = 10_000)
                        r.stdout.lineSequence()
                            .map { it.trim() }
                            .toList()
                            .asReversed()
                            .firstOrNull { it.startsWith("RESULT=") }
                            ?.removePrefix("RESULT=")
                    }.getOrNull()
            if (fromScript != null) return fromScript

            // 再查 core 日志（writeResultToLog 写入的位置，log/core/update.log）
            val coreLogPath = File(coreLogDir(), CORE_UPDATE_LOG)
            if (coreLogPath.exists()) {
                coreLogPath.readLines().asReversed()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("RESULT=") }
                    ?.removePrefix("RESULT=")
            } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "readResultFromLog 异常: ${e.message}")
            null
        }
    }

    /** 计算本地文件 SHA-256 hex（installLocalApk 装前完整性校验） */
    private fun sha256OfFile(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "sha256OfFile 失败: ${e.message}")
            null
        }
    }

    /**
     * P1 A2/E17：pm install 失败原因分类（INSTALL_FAILED_* → 中文可读原因）。
     * 复用 AppManager 已有解析模式并扩展常见错误码。
     */
    private fun classifyInstallError(raw: String): String {
        val trimmed = raw.trim()
        val code = trimmed
            .substringAfter("INSTALL_FAILED_", trimmed)
            .uppercase(Locale.ROOT)
        return when (code) {
            "UPDATE_INCOMPATIBLE" -> "签名不一致（与已安装版本签名不同，请使用同一签名 APK）"
            "VERSION_DOWNGRADE" -> "版本降级（目标版本低于已安装版本）"
            "INSUFFICIENT_STORAGE" -> "存储空间不足"
            "ALREADY_EXISTS" -> "应用已存在"
            "DUPLICATE_PERMISSION" -> "权限冲突"
            "INVALID_APK" -> "APK 无效或损坏"
            "PACKAGE_INVALID" -> "包名无效"
            "NO_MATCHING_ABIS" -> "设备 ABI 不兼容（架构不匹配）"
            "USER_RESTRICTED" -> "用户限制安装（需允许安装未知来源）"
            "TEST_ONLY" -> "仅测试 APK，不允许安装"
            "VERIFICATION_FAILURE" -> "安装验证失败（系统安全校验未通过）"
            "OLDER_SDK" -> "目标系统版本过低"
            "NEWER_SDK" -> "目标系统版本过高"
            "MISSING_SHARED_LIBRARY" -> "缺少共享库"
            "CONTAINER_ERROR" -> "容器/存储错误"
            "INTERNAL_ERROR" -> "系统内部错误"
            "ABORTED" -> "安装被中止"
            "UNKNOWN", "" -> "系统拒绝安装（详见设备日志）"
            else -> "安装被拒绝：$code"
        }
    }

    /** P1 A4：更新源不可用异常——catch 后置 NEED_PUSH（区别于一般失败 FAILED） */
    private class NeedPushException(message: String) : Exception(message)

    /** 当前 Core 版本名（P0-1：读 core/api buildConfigField 注入的 VERSION_NAME，与 build.gradle versionName 同步） */
    private fun currentVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    // ── 互斥 ──

    private fun tryAcquireUpdate(): Boolean = updateMutex.compareAndSet(false, true)

    private fun releaseUpdate() {
        updateMutex.set(false)
    }

    // ── 工具 ──

    /** GET URL 返回文本（版本清单） */
    private fun fetchUrl(urlStr: String, timeoutMs: Int): String? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
            instanceFollowRedirects = true
        }
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    } catch (e: Exception) {
        AppLogger.w(TAG, "fetchUrl 失败: ${e.message}")
        null
    }

    /**
     * 更新源/APK 地址强制 HTTPS（P0-8）。本地调试 IP（localhost/内网）允许 http。
     * @throws Exception 非 HTTPS 且非本地调试地址时抛出
     */
    private fun requireSecureUrl(urlStr: String, what: String) {
        val u = try {
            URL(urlStr)
        } catch (e: Exception) {
            throw Exception("$what 不是合法 URL: $urlStr")
        }
        if (u.protocol.equals("https", ignoreCase = true)) return
        val host = u.host ?: ""
        val isLocalDebug = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.3") ||
            host.endsWith(".local")
        if (!isLocalDebug) {
            throw Exception("$what 必须使用 HTTPS（当前协议: ${u.protocol}）")
        }
    }

    /**
     * 流式下载 APK 到文件（P0-6：.part 原子写 + 磁盘预检），返回文件 SHA-256 hex。
     * 下载前删除旧 .part；失败抛异常（由调用方清理 .part）。
     */
    private fun downloadApk(urlStr: String, target: File, timeoutMs: Int = 60_000): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw Exception("APK 下载失败: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            // 磁盘预检：剩余空间 < APK 大小×2 提前失败（E8）
            if (total > 0) {
                val dir = File(UPDATE_DIR)
                dir.mkdirs()
                val usable = dir.usableSpace
                if (usable < total * 2) {
                    throw Exception("磁盘空间不足（可用 ${usable / 1024 / 1024}MB，需约 ${total * 2 / 1024 / 1024}MB）")
                }
            }
            if (total > MAX_APK_BYTES) {
                throw Exception("APK 超过 ${MAX_APK_BYTES / 1024 / 1024}MB 限制")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val input = conn.inputStream
            val output = target.outputStream()
            val buf = ByteArray(64 * 1024)
            var written = 0L
            var lastReport = 0
            input.use { ins ->
                output.use { outs ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        val pct = if (total > 0) ((written * 100) / total).toInt() else 0
                        if (pct - lastReport >= 5) {
                            lastReport = pct
                            status = status.copy(state = State.DOWNLOADING, progress = pct.coerceIn(0, 100))
                        }
                    }
                }
            }
            conn.disconnect()
            conn = null
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "downloadApk 失败: ${e.message}")
            throw e
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 查询已安装 Core 的 versionName（dumpsys package；失败/无 ADB 返回 null） */
    private suspend fun queryInstalledVersionName(): String? {
        return try {
            val r = ShellExecutor.executeAsRoot(
                "dumpsys package $CORE_PACKAGE 2>/dev/null | grep -m1 'versionName'",
                timeoutMs = 15_000
            )
            if (r.stdout.isBlank()) null else extractVersionName(r.stdout)
        } catch (e: Exception) {
            AppLogger.w(TAG, "queryInstalledVersionName 异常: ${e.message}")
            null
        }
    }

    /**
     * 多模式解析 dumpsys 输出的 versionName（P1 A1/E13：兼容 versionName=x / versionName="x" /
     * versionName: x / 前后空格等格式；解析失败返回 null 由调用方决定跳过而非误报 FAILED）
     */
    private fun extractVersionName(output: String): String? {
        if (output.isBlank()) return null
        val m1 = Regex("versionName[=:\\s]*[\"']?([^\\s,\"']+)").find(output)
        if (m1 != null) return m1.groupValues[1].trim().trim('"', '\'')
        val m2 = Regex("versionName[\"':=]+([^\\s,}]+)").find(output)
        return m2?.groupValues?.get(1)?.trim()?.trim('"', '\'')
    }

    /** 查询已安装 Core 的签名指纹（pm dump signatures 段；失败返回 null） */
    private suspend fun querySignatureFingerprint(): String? {
        return try {
            val r = ShellExecutor.executeAsRoot(
                "pm dump $CORE_PACKAGE 2>/dev/null | grep -iA3 'signatures'",
                timeoutMs = 15_000
            )
            val lines = r.stdout.lineSequence()
                .filter { it.contains("signature", ignoreCase = true) }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) null else lines.joinToString("|").take(512)
        } catch (e: Exception) {
            AppLogger.w(TAG, "querySignatureFingerprint 异常: ${e.message}")
            null
        }
    }

    /**
     * 装后签名指纹记录 + 变更检测（P0-2，不阻断更新）。
     * 首次安装：记录基线；后续安装：与历史指纹对比，不一致报警（写入 lastUpdateResult WARN）。
     * @return 最终更新结果串（OK:<ver> 或 WARN:SIG_CHANGED:<ver>）
     */
    private suspend fun recordSignatureFingerprint(installedVersion: String): String {
        val fp = querySignatureFingerprint()
        if (fp == null) {
            AppLogger.w(TAG, "recordSignatureFingerprint: 无法读取签名指纹，跳过变更检测")
            return "OK:$installedVersion"
        }
        val prev = settings.lastSigFingerprint
        settings.lastSigFingerprint = fp
        if (prev != null && prev.isNotBlank() && prev != fp) {
            AppLogger.w(TAG, "签名指纹变化！上次=$prev 本次=$fp")
            return "WARN:SIG_CHANGED:$installedVersion"
        }
        return "OK:$installedVersion"
    }

    private fun compareVersions(a: String, b: String): Int {
        // 2026-08-28 修复：原实现是 `split(".").mapNotNull { it.toIntOrNull() }`，
        // 非纯数字段会被**丢弃**而不是当 0 —— GitHub 上极常见的 "v0.4" 会变成 [4]，
        // 与本机 "0.4"→[0,4] 比较时 4>0 判定"有新版"，装完 versionName 仍是 0.4，
        // 于是每次检查都重新下载 60MB 并再装一次，无限循环（也正是 /data/local/tmp
        // 堆了 40 个安装包的推手之一）。"1.0.0-rc1" 同理错位。
        fun parse(v: String): List<Int> = v.trim()
            .removePrefix("v").removePrefix("V")
            .split('.', '-', '_', '+')
            .map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parse(a)
        val pb = parse(b)
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 获取当前 versionName:versionCode 作为 watchdog 基线 */
    private fun getBaselineVersion(): String {
        return try {
            val ctx = appContext ?: return "0"
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName ?: "unknown"}:${info.versionCode}"
        } catch (e: Exception) {
            AppLogger.w(TAG, "getBaselineVersion failed: ${e.message}")
            "0"
        }
    }

    private companion object {
        const val TAG = "UpdateManager"
    }
}
