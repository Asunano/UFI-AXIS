package com.ufi_axis.viewmodel.module

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.*
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.AppLogBuffer
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.LogEntry
import com.ufi_axis.util.OkHttpClientProvider
import com.ufi_axis.util.UpdateSource
import com.ufi_axis.viewmodel.state.*
import com.ufi_axis.viewmodel.repository.AlertPrefsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.security.MessageDigest

class ToolsModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val crossModuleEventSink: MutableSharedFlow<UiEvent>,
    private val alertPrefs: AlertPrefsRepository
) {
    // ── State ──
    private val _toolsState = MutableStateFlow(ToolsState())
    val toolsState: StateFlow<ToolsState> = _toolsState.asStateFlow()

    private val _alertsState = MutableStateFlow(AlertsState())
    val alertsState: StateFlow<AlertsState> = _alertsState.asStateFlow()

    private val _tasksState = MutableStateFlow(TasksState())
    val tasksState: StateFlow<TasksState> = _tasksState.asStateFlow()

    private val _smsForwardState = MutableStateFlow(SmsForwardState())
    val smsForwardState: StateFlow<SmsForwardState> = _smsForwardState.asStateFlow()

    private val _debugLogState = MutableStateFlow(DebugLogState())
    val debugLogState: StateFlow<DebugLogState> = _debugLogState.asStateFlow()

    private val _coreLogFilesState = MutableStateFlow(CoreLogFilesState())
    val coreLogFilesState: StateFlow<CoreLogFilesState> = _coreLogFilesState.asStateFlow()

    // ── 日志四层开关（2026-09-04）：唯一真源在 core，这里只是镜像 ──
    // 初值用本地缓存填，仅为首帧不闪；loaded=false 期间 UI 禁用开关，
    // 等 loadLogSwitches() 从 core 回读成功才允许修改。
    private val _logSwitchState = MutableStateFlow(
        AppPreferences(appContext).let {
            LogSwitchState(
                logEnabled = it.logEnabled,
                coreLogEnabled = it.coreLogEnabled,
                appLogEnabled = it.appLogEnabled,
                debugMode = it.debugMode
            )
        }
    )
    val logSwitchState: StateFlow<LogSwitchState> = _logSwitchState.asStateFlow()

    // ── core 崩溃提醒（2026-09-04）──
    // core 崩溃后由 keepalive / START_STICKY 自动拉起，app 此前完全无感（只表现为数据断一下）。
    // 非空 = 有一次**尚未向用户提示过**的 core 崩溃，由 MainActivity 弹窗。
    private val _coreCrashNotice = MutableStateFlow<CoreCrashNotice?>(null)
    val coreCrashNotice: StateFlow<CoreCrashNotice?> = _coreCrashNotice.asStateFlow()

    // ── 运行诊断（2026-08-30）：diagnose / qos / cache / root-check / shell-root 聚合 ──
    private val _diagnoseState = MutableStateFlow(DiagnoseState())
    val diagnoseState: StateFlow<DiagnoseState> = _diagnoseState.asStateFlow()

    private val consoleHistoryFile = File(appContext.filesDir, "console_history.json")
    private val MAX_PERSISTED_PER_TAB = 500

    // ── 设备更新状态（2026-08-10：后端自拉取 + 前端兜底推送） ──
    private val _updateDeviceState = MutableStateFlow<UpdateStatusResponse?>(null)
    val updateDeviceState: StateFlow<UpdateStatusResponse?> = _updateDeviceState.asStateFlow()

    /** P0-7：轮询失败连续次数（Core 重启窗口 8088 不可达时累计；恢复后清零） */
    private var deviceUpdatePollFailures = 0

    /** 触发后端检查更新（后端自动拉取+安装；状态经 [pollDeviceUpdateStatus] 轮询） */
    fun triggerDeviceUpdate() {
        scope.launch {
            runCatching { api.triggerDeviceUpdate() }
                .onSuccess {
                    _updateDeviceState.value = it
                    startDeviceUpdatePolling()
                }
                .onFailure { e ->
                    _updateDeviceState.value = UpdateStatusResponse(state = "failed", message = "触发更新失败: ${e.message}")
                }
        }
    }

    /**
     * 轮询设备更新状态（P0-7：失败连续 3 次 → 过渡态「设备重启中…」，恢复后清除；
     * P1 A5/E21：连续失败超过 150 次（约 2s×150 ≈ 5 分钟）→ 失联红字「设备更新可能失败」）。
     * Core 主动重启（force-stop + startservice）期间 8088 不可达，轮询会短暂失败——
     * 此时不显示错误红字，改为过渡态；恢复后读回后端真实 status。
     */
    private var updatePollJob: Job? = null

    /** 单次轮询设备更新状态（suspend，供轮询循环复用）。 */
    private suspend fun doPollDeviceUpdateStatus() {
        runCatching { api.getDeviceUpdateStatus() }
            .onSuccess {
                deviceUpdatePollFailures = 0
                _updateDeviceState.value = it.copy(reconnecting = false)
            }
            .onFailure {
                deviceUpdatePollFailures++
                val prev = _updateDeviceState.value
                when {
                    // P1 A5/E21：超过 5 分钟持续失联 → 明确提示更新可能失败（不再无限等重启）
                    deviceUpdatePollFailures >= DEVICE_UPDATE_POLL_LOST_THRESHOLD -> {
                        _updateDeviceState.value = (prev ?: UpdateStatusResponse())
                            .copy(
                                state = "failed",
                                message = "设备更新可能失败，请检查设备/ADB 恢复",
                                reconnecting = false
                            )
                    }
                    // P0-7 断连过渡态：保持 installing 语义，busy 轮询不中断
                    deviceUpdatePollFailures >= DEVICE_UPDATE_POLL_FAILURE_THRESHOLD -> {
                        _updateDeviceState.value = (prev ?: UpdateStatusResponse())
                            .copy(
                                state = "installing",
                                message = "设备重启中，等待恢复...",
                                reconnecting = true
                            )
                    }
                    else -> {
                        _updateDeviceState.value = prev?.copy(reconnecting = false)
                    }
                }
            }
    }

    /** 单次触发轮询（保留供手动刷新）。 */
    fun pollDeviceUpdateStatus() {
        scope.launch { doPollDeviceUpdateStatus() }
    }

    /**
     * 启动设备更新轮询循环。
     *
     * 2026-08-20 修复（issue #2）：此前 [pollDeviceUpdateStatus] 从未被任何调用方触发，
     * 导致手动推送 APK 后 Core 重启（force-stop + startservice）期间 8088 不可达，
     * 前端永远停留在 installing，无法收到更新成功/失败结果。
     * 现在 [pushApkAndInstall] / [triggerDeviceUpdate] 触发本循环，每 2s 轮询一次，
     * 直到终态（done/failed）或外部 [stopDeviceUpdatePolling] 停止。
     */
    fun startDeviceUpdatePolling() {
        updatePollJob?.cancel()
        updatePollJob = scope.launch {
            while (isActive) {
                doPollDeviceUpdateStatus()
                val st = _updateDeviceState.value?.state
                if (st == "done" || st == "failed") {
                    updatePollJob = null
                    break
                }
                delay(2000)
            }
        }
    }

    /** 停止设备更新轮询循环（弹窗关闭 / 重置状态时调用）。 */
    fun stopDeviceUpdatePolling() {
        updatePollJob?.cancel()
        updatePollJob = null
    }

    /**
     * 处理后端 WS "update" 推送（含新连接订阅时服务端下发的状态快照）。
     * 将后端 UpdateManager.statusToMap() 映射为 [UpdateStatusResponse]；终态时停止轮询。
     */
    fun onUpdateWsEvent(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        runCatching {
            val resp = AppJson.decodeFromJsonElement<UpdateStatusResponse>(obj)
            _updateDeviceState.value = resp
            if (resp.state == "done" || resp.state == "failed") stopDeviceUpdatePolling()
        }
    }

    /** 兜底：推送本地 APK 到设备并安装（后端无法自下载时）。
     *  @param onResult (ok: Boolean, message: String) 推送/安装结果回调（成功/失败均有返回） */
    fun pushApkAndInstall(apkFile: java.io.File, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                _updateDeviceState.value = UpdateStatusResponse(state = "uploading", progress = 0, message = "正在上传 APK...")
                val totalSize = apkFile.length()
                // 自定义 RequestBody：写入时通过 OkHttp sink 回调报告进度
                val requestBody = object : RequestBody() {
                    override fun contentType() = "application/vnd.android.package-archive".toMediaType()
                    override fun contentLength() = totalSize
                    override fun writeTo(sink: okio.BufferedSink) {
                        apkFile.source().use { source ->
                            var written = 0L
                            val buf = okio.Buffer()
                            while (true) {
                                val read = source.read(buf, 8192)
                                if (read == -1L) break
                                sink.write(buf, read)
                                written += read
                                if (totalSize > 0) {
                                    val pct = ((written * 100) / totalSize).toInt().coerceIn(0, 99)
                                    _updateDeviceState.value = UpdateStatusResponse(
                                        state = "uploading", progress = pct,
                                        message = "正在上传 APK… $pct%"
                                    )
                                }
                            }
                        }
                    }
                }
                val part = MultipartBody.Part.createFormData("file", apkFile.name, requestBody)
                val upload = api.pushUpdateApk(part)
                if (!upload.ok || upload.apk_path == null) {
                    val msg = upload.error ?: "上传失败"
                    _updateDeviceState.value = UpdateStatusResponse(state = "failed", message = msg)
                    onResult(false, msg)
                    return@launch
                }
                // ── 本地安装 ──
                // 注意：自更新会杀掉 Core 进程（PackageInstaller.commit 终止 Core），
                // 因此 installLocalApk 的 HTTP 响应常常因连接被重置/超时而中断并抛异常。
                // 这是预期行为，并非安装失败。故安装调用异常时不直接判失败，而是进入
                // 「设备重启中」过渡态并启动轮询，由重连后的 getDeviceUpdateStatus / WS 快照
                // （statusToMap 终态）来判定真实结果，避免把「进程被杀重启」误报为「推送失败」。
                _updateDeviceState.value = UpdateStatusResponse(
                    state = "installing",
                    message = "正在安装，设备即将重启…"
                )
                try {
                    val install = api.installLocalApk(mapOf("apk_path" to (upload.apk_path ?: "")))
                    if (install.ok) {
                        _updateDeviceState.value = install.status
                            ?: UpdateStatusResponse(state = "installing", message = "正在安装，等待设备重启...")
                        onResult(true, "APK 推送安装成功，设备将自动重启生效")
                    } else {
                        // 后端在进程被杀前已明确返回失败（如 APK 不存在 / 安装被拒）→ 真实失败
                        val msg = "安装失败: ${install.status?.message ?: "未知错误"}"
                        _updateDeviceState.value = UpdateStatusResponse(state = "failed", message = msg)
                        onResult(false, msg)
                        return@launch
                    }
                } catch (e: Exception) {
                    // installLocalApk 因 Core 被杀而中断（连接重置/超时），属预期，继续等待重启后终态
                    DebugLog.d("Tools", "installLocalApk 调用异常（设备重启中，属预期）：${e.message}")
                    _updateDeviceState.value = UpdateStatusResponse(
                        state = "installing",
                        message = "正在安装，设备重启中，等待恢复…"
                    )
                }
                // 无论 installLocalApk 成功返回还是因重启抛异常，都启动轮询等待真实终态（done/failed）
                startDeviceUpdatePolling()
            } catch (e: Exception) {
                val msg = "推送安装失败: ${e.message}"
                _updateDeviceState.value = UpdateStatusResponse(state = "failed", message = msg)
                onResult(false, msg)
            }
        }
    }

    /** 重置设备更新状态 */
    fun resetDeviceUpdate() {
        stopDeviceUpdatePolling()
        scope.launch {
            runCatching { api.resetDeviceUpdate() }
                .onSuccess { _updateDeviceState.value = it }
            _updateDeviceState.value = null
        }
    }

    /** 仅重置本地推送状态（不请求后端），防止残留 uploading/installing 导致弹窗误显示 */
    fun resetUpdateDeviceState() {
        stopDeviceUpdatePolling()
        _updateDeviceState.value = null
    }

    /**
     * 同步更新源/镜像前缀到设备端 Core（2026-08-12：config PUT update_url + update_mirror_base）。
     * @param updateUrl 后端版本清单 URL（前端写 ToolsModule.RAW_VERSION_URL）
     * @param mirrorBase 镜像前缀；空串 = 直连（Core UpdateManager 据此决定是否拼前缀）
     * @param onResult 设备在线且写入成功 → true；网络失败/设备未连接 → false
     */
    fun syncUpdateSourceToDevice(updateUrl: String, mirrorBase: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            runCatching {
                api.updateUpdateSource(mapOf(
                    "update_url" to updateUrl,
                    "update_mirror_base" to mirrorBase
                ))
            }.onSuccess { onResult(true) }
                .onFailure { e ->
                    DebugLog.w("Tools", "同步更新源到设备失败: ${e.message}")
                    onResult(false)
                }
        }
    }

    // ── 前端 App 更新（2026-08-10 C6：前端直连 GitHub 清单 + 镜像源，去 Core 化） ──
    private val _frontendUpdateState = MutableStateFlow(FrontendUpdateState())
    val frontendUpdateState: StateFlow<FrontendUpdateState> = _frontendUpdateState.asStateFlow()

    /**
     * 检查前端 App 更新：直连 GitHub 仓库根 version.json（raw，经镜像源拼接，
     * 不再经 Core /api/update/frontend-info 代理）→ 取 frontend 对象 → 与当前版本比对。
     * 当前版本从 PackageManager 读取（禁止硬编码）。
     * 结果缓存到 [FrontendUpdateState]（latestApkUrl/latestSha256 供下载阶段直接使用）。
     */
    fun checkFrontendUpdate() {
        scope.launch {
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "checking", errorMessage = null
            )
            try {
                val prefs = AppPreferences(appContext)
                // 按优先级逐级尝试候选 URL（自定义前缀 → 内置镜像 1/2/3 → 直连兜底）
                val body = withContext(Dispatchers.IO) {
                    fetchWithFallback(prefs, RAW_VERSION_URL)
                }
                // 清单双对象：取 frontend 对象；无 frontend 字段回退整份（兼容 flat 旧格式）
                val root = AppJson.parseToJsonElement(body).jsonObject
                val frontendObj = root["frontend"]?.jsonObject ?: root
                val info = AppJson.decodeFromJsonElement<FrontendUpdateInfo>(frontendObj)
                val current = currentAppVersionName()
                val latest = info.version
                // current 为空 = PackageManager 读取失败，此时任何比较都不可信
                // （空串解析成 [] → 全按 0 → 恒判定"有新版"），宁可不提示也不能误导用户重复安装
                val hasUpdate = latest.isNotBlank() && current.isNotBlank() &&
                    compareVersions(latest, current) > 0
                _frontendUpdateState.value = FrontendUpdateState(
                    state = if (hasUpdate) "available" else "no_update",
                    currentVersion = current,
                    latestVersion = latest,
                    changelog = info.changelog,
                    latestApkUrl = info.apkUrl,
                    latestSha256 = info.apkSha256.trim().lowercase()
                )
            } catch (e: Exception) {
                _frontendUpdateState.value = _frontendUpdateState.value.copy(
                    state = "error",
                    errorMessage = "检查更新失败（更新源不可用）: ${e.message}"
                )
            }
        }
    }

    /**
     * 下载前端 App APK：直接从 [FrontendUpdateState.latestApkUrl]（清单 frontend.apkUrl）
     * 经镜像源拼接后直连下载（不再经 Core /api/update/frontend-apk 代理）→ 写
     * filesDir/update/app-update.apk（.part 原子提交）→ SHA-256 校验（与清单 apkSha256 比对，不符置 error）。
     * 缓存复用：目标文件已存在且 SHA-256 与清单一致 → 跳过下载直接进入 downloaded 状态。
     */
    fun downloadFrontendApk() {
        val current = _frontendUpdateState.value
        if (current.latestVersion.isBlank()) return
        scope.launch {
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "downloading", downloadProgress = 0, errorMessage = null
            )
            try {
                val apkUrl = current.latestApkUrl
                if (apkUrl.isBlank()) throw Exception("清单缺少 apkUrl（请先重新检查更新）")
                val expectedSha = current.latestSha256.lowercase()

                val dir = java.io.File(appContext.filesDir, "update")
                dir.mkdirs()
                val partFile = java.io.File(dir, "app-update.apk.part")
                val targetFile = java.io.File(dir, "app-update.apk")

                // 缓存复用（参考旧项目 UpdateChecker）：已有 APK 且 SHA-256 与清单匹配 → 跳过下载
                if (targetFile.exists() && targetFile.length() > 0 && expectedSha.isNotBlank()) {
                    val cachedSha = withContext(Dispatchers.IO) { sha256OfFile(targetFile) }
                    if (cachedSha == expectedSha) {
                        _frontendUpdateState.value = _frontendUpdateState.value.copy(
                            state = "downloaded", downloadProgress = 100,
                            downloadedApkPath = targetFile.absolutePath
                        )
                        return@launch
                    }
                }
                // 缓存不匹配 / 无缓存 → 清理旧文件后重新下载
                partFile.delete()
                targetFile.delete()

                val prefs = AppPreferences(appContext)
                // 逐级尝试候选 URL（自定义前缀 → 内置镜像 1/2/3 → 直连兜底），全部失败抛"网络异常"
                val ok = downloadWithFallback(prefs, apkUrl, partFile, targetFile, expectedSha)
                if (!ok) throw Exception("所有更新源均不可用（网络异常或镜像全部失效）")
                _frontendUpdateState.value = _frontendUpdateState.value.copy(
                    state = "downloaded", downloadProgress = 100,
                    downloadedApkPath = targetFile.absolutePath
                )
            } catch (e: Exception) {
                _frontendUpdateState.value = _frontendUpdateState.value.copy(
                    state = "error", errorMessage = "下载失败: ${e.message}"
                )
            }
        }
    }

    /** 计算本地文件 SHA-256 hex（小写；失败返回 null） */
    private fun sha256OfFile(file: java.io.File): String? {
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
            DebugLog.w("Tools", "计算 APK SHA-256 失败: ${e.message}")
            null
        }
    }

    /**
     * 安装已下载的前端 App APK：FileProvider content:// URI + ACTION_VIEW
     * （标准系统安装器，用户确认；authorities 与 AndroidManifest 中 provider 一致）。
     */
    fun installFrontendApk() {
        val path = _frontendUpdateState.value.downloadedApkPath ?: run {
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "error", errorMessage = "APK 未下载，无法安装"
            )
            return
        }
        val file = java.io.File(path)
        if (!file.exists()) {
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "error", errorMessage = "APK 文件不存在: $path"
            )
            return
        }
        try {
            val authority = "${appContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "installing", errorMessage = null
            )
        } catch (e: Exception) {
            _frontendUpdateState.value = _frontendUpdateState.value.copy(
                state = "error", errorMessage = "启动安装器失败: ${e.message}"
            )
        }
    }

    /** 当前 App 版本名（PackageManager 读取，禁止硬编码） */
    private fun currentAppVersionName(): String = try {
        val pm = appContext.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(appContext.packageName, 0)
        }
        info.versionName ?: ""
    } catch (e: Exception) {
        DebugLog.w("Tools", "读取 App 版本失败: ${e.message}")
        ""
    }

    /**
     * 版本号比较（返回 >0 表示 v1 新）。
     *
     * 2026-08-28 修复：原实现 `split(".").mapNotNull { it.toIntOrNull() }` 会把非纯数字段
     * **丢弃**而不是当 0 —— 清单里写 "v1.2" 就变成 [2]，与本机 "1.2"→[1,2] 比较时
     * 2>1 判定"有新版"，装完版本名没变，于是每次检查都提示更新、重复下载。
     * 与 core 的 `UpdateManager.compareVersions` 保持同一套解析规则。
     */
    private fun compareVersions(v1: String, v2: String): Int {
        fun parse(v: String): List<Int> = v.trim()
            .removePrefix("v").removePrefix("V")
            .split('.', '-', '_', '+')
            .map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val p1 = parse(v1)
        val p2 = parse(v2)
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    // ══════════════ 运行诊断（2026-08-30） ══════════════
    // 五个只读端点一次并发拉齐。任一失败只打日志、保留上一次的值，不写 errorMessage ——
    // 这是排障页，比「整页红条」更重要的是把还能读到的信息显示出来。

    /**
     * 刷新诊断快照。
     *
     * @param withFieldCoverage 传 true 才给 `/api/diagnose` 带 `fields=1`，而那会**逐分组向设备
     *   发查询**（最多 10 组，明显变慢）。只在用户显式点「检测字段覆盖率」时传 true；
     *   传过一次后由 [DiagnoseState.fieldCoverageRequested] 记住，后续刷新沿用同一档。
     */
    fun loadDiagnostics(withFieldCoverage: Boolean? = null) {
        val wantFields = withFieldCoverage ?: _diagnoseState.value.fieldCoverageRequested
        scope.launch {
            _diagnoseState.value = _diagnoseState.value.copy(
                isLoading = true, fieldCoverageRequested = wantFields)
            val diag = async { runCatching { api.getDiagnose(if (wantFields) "1" else null) } }
            val qos = async { runCatching { api.getQosStatus() } }
            val cache = async { runCatching { api.getCacheStats() } }
            val rootCheck = async { runCatching { api.getRootCheck() } }
            val shellRoot = async { runCatching { api.getShellRoot() } }
            val rDiag = diag.await(); val rQos = qos.await(); val rCache = cache.await()
            val rRoot = rootCheck.await(); val rShell = shellRoot.await()
            listOf(
                "diagnose" to rDiag.exceptionOrNull(), "qos" to rQos.exceptionOrNull(),
                "cache" to rCache.exceptionOrNull(), "root-check" to rRoot.exceptionOrNull(),
                "shell/root" to rShell.exceptionOrNull()
            ).forEach { (name, e) -> if (e != null) DebugLog.w("Tools", "诊断项 $name 读取失败", e) }
            _diagnoseState.value = _diagnoseState.value.copy(
                diagnose = rDiag.getOrNull() ?: _diagnoseState.value.diagnose,
                qos = rQos.getOrNull() ?: _diagnoseState.value.qos,
                cache = rCache.getOrNull() ?: _diagnoseState.value.cache,
                rootCheck = rRoot.getOrNull() ?: _diagnoseState.value.rootCheck,
                shellRoot = rShell.getOrNull() ?: _diagnoseState.value.shellRoot,
                isLoading = false
            )
        }
    }

    /** 只重读缓存统计（清缓存后对账用，不必把五个端点全拉一遍）。 */
    fun loadCacheStats() {
        scope.launch {
            try { _diagnoseState.value = _diagnoseState.value.copy(cache = api.getCacheStats()) }
            catch (e: Exception) { DebugLog.w("Tools", "缓存统计读取失败", e) }
        }
    }

    /**
     * 清空 core 的全部响应缓存。
     *
     * 影响：下一次各页面请求都会真打设备，短时间内会慢一些；不会丢配置或历史数据。
     * @return 成功 = true to 提示语；失败 = false to 原因
     */
    suspend fun clearResponseCache(): Pair<Boolean, String> {
        _diagnoseState.value = _diagnoseState.value.copy(isBusy = true, errorMessage = null)
        return try {
            val resp = api.clearCache()
            if (resp.success) true to "缓存已清空" else false to (resp.message ?: "清空失败")
        } catch (e: Exception) {
            false to (e.message ?: "清空失败")
        } finally {
            _diagnoseState.value = _diagnoseState.value.copy(isBusy = false)
            loadCacheStats()
        }
    }

    /**
     * 按 glob 失效部分缓存（如 `device:*`）。
     *
     * **空 pattern 直接拒掉**：core 读不到 `pattern` 时会回落 `"*"`，那等同于清空全部缓存 ——
     * 用户以为只清了一类，实际全清了。这一层挡在客户端，避免误操作依赖 core 的默认行为。
     */
    suspend fun invalidateResponseCache(pattern: String): Pair<Boolean, String> {
        val p = pattern.trim()
        if (p.isEmpty()) return false to "请填写要失效的 key 规则（留空会清空全部缓存）"
        _diagnoseState.value = _diagnoseState.value.copy(isBusy = true, errorMessage = null)
        return try {
            val resp = api.invalidateCache(mapOf("pattern" to p))
            // core 回显生效的 pattern，但不回删除条数 —— 想知道少了多少条只能前后各读一次 stats
            if (resp.success) true to "已失效 ${resp.pattern ?: p}" else false to "失效失败"
        } catch (e: Exception) {
            false to (e.message ?: "失效失败")
        } finally {
            _diagnoseState.value = _diagnoseState.value.copy(isBusy = false)
            loadCacheStats()
        }
    }

    private val _trafficManagementState = MutableStateFlow(TrafficManagementState())
    val trafficManagementState: StateFlow<TrafficManagementState> = _trafficManagementState.asStateFlow()

    // 通知唯一出口（data 层，懒创建；仅 loadAlerts / 短信差异检测路径使用）
    private val notificationCenter by lazy { NotificationCenter(appContext) }

    // ── Cross-module Events ──
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // 跨模块事件转发：模块内 _events 统一汇聚到 MainViewModel 持有的 sink，
    // 避免 collectCrossModuleEvents 在构建期触发本模块的 by lazy 求值（冷启动优化 #6）。
    // viewModelScope 随 ViewModel 销毁而取消，本 forward 协程一并结束，无泄漏。
    init {
        scope.launch {
            _events.collect { event -> crossModuleEventSink.tryEmit(event) }
        }
        // 恢复持久化的控制台对话（AT / Shell）—— IO 线程读取，避免主线程阻塞
        scope.launch(Dispatchers.IO) {
            val history = loadConsoleHistory() ?: return@launch
            val maxId = maxOf(
                history.at.maxOfOrNull { it.id } ?: 0L,
                history.shell.maxOfOrNull { it.id } ?: 0L
            )
            ConsoleMessage.seedIdAbove(maxId)
            _toolsState.value = _toolsState.value.copy(
                atMessages = history.at,
                shellMessages = history.shell
            )
        }
    }

    private fun emitNetworkError(msg: String?) {
        _events.tryEmit(UiEvent.ShowNetworkError(msg))
    }

    /**
     * 逐级尝试候选 URL 拉取文本（清单 version.json）。
     * 候选顺序：自定义前缀 → 内置镜像 1/2/3 → 直连兜底；全部失败抛网络异常（含各候选原因）。
     */
    private suspend fun fetchWithFallback(prefs: AppPreferences, url: String): String {
        val failures = mutableListOf<String>()
        for (candidate in UpdateSource.candidateUrls(prefs, url)) {
            try {
                val request = Request.Builder().url(candidate).build()
                return OkHttpClientProvider.shared.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    resp.body?.string() ?: throw Exception("响应体为空")
                }
            } catch (e: Exception) {
                failures += "${candidate.removePrefix("https://").take(28)}: ${e.message}"
            }
        }
        throw Exception("所有更新源均不可用（网络异常或镜像全部失效）: ${failures.joinToString("; ")}")
    }

    /**
     * 逐级尝试候选 URL 下载 APK 到 partFile（校验 SHA-256 → rename 提交）。
     * 候选顺序：自定义前缀 → 内置镜像 1/2/3 → 直连兜底；全部失败返回 false。
     */
    private suspend fun downloadWithFallback(
        prefs: AppPreferences,
        apkUrl: String,
        partFile: java.io.File,
        targetFile: java.io.File,
        expectedSha: String
    ): Boolean {
        for (candidate in UpdateSource.candidateUrls(prefs, apkUrl)) {
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                partFile.delete()
                val request = Request.Builder().url(candidate).build()
                withContext(Dispatchers.IO) {
                    OkHttpClientProvider.shared.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val body = resp.body ?: throw Exception("响应体为空")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            partFile.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    written += n
                                    val pct = if (total > 0) ((written * 100) / total).toInt() else 0
                                    _frontendUpdateState.update { it.copy(downloadProgress = pct.coerceIn(0, 100)) }
                                }
                            }
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha.isNotBlank() && actualSha != expectedSha) throw Exception("SHA-256 校验失败")
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) throw Exception("文件提交失败")
                return true
            } catch (e: Exception) {
                partFile.delete()
                // 继续尝试下一候选
            }
        }
        return false
    }

    /** 持久化控制台对话（AT + Shell）到 filesDir/console_history.json */
    private fun persistConsoleHistory() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val at = _toolsState.value.atMessages.takeLast(MAX_PERSISTED_PER_TAB)
                val shell = _toolsState.value.shellMessages.takeLast(MAX_PERSISTED_PER_TAB)
                consoleHistoryFile.writeText(AppJson.encodeToString(ConsoleHistoryPayload(at, shell)))
            }.onFailure { e -> DebugLog.w("Tools", "持久化控制台历史失败: ${e.message}") }
        }
    }

    /** 读取持久化控制台历史；文件缺失/损坏返回 null（损坏时删除避免反复失败） */
    private fun loadConsoleHistory(): ConsoleHistoryPayload? {
        if (!consoleHistoryFile.exists()) return null
        return runCatching {
            val text = consoleHistoryFile.readText()
            if (text.isBlank()) return null
            AppJson.decodeFromString<ConsoleHistoryPayload>(text)
        }.onFailure { consoleHistoryFile.delete() }.getOrNull()
    }

    // ── AT Command ──
    fun sendAtCommand(command: String) {
        scope.launch {
            val userMsg = ConsoleMessage(role = ConsoleRole.USER, text = command)
            // 先上用户气泡（立即可见 + 入场动画），再进入加载态等待基带返回
            _toolsState.value = _toolsState.value.copy(
                atMessages = _toolsState.value.atMessages + userMsg,
                isLoading = true,
                errorMessage = null
            )
            try {
                val response = api.sendAtCommand(AtCommandRequest(command))
                val assistantMsg = ConsoleMessage(role = ConsoleRole.ASSISTANT, text = response.response)
                _toolsState.value = _toolsState.value.copy(
                    atMessages = _toolsState.value.atMessages + assistantMsg,
                    isLoading = false
                )
                persistConsoleHistory()
            } catch (e: Exception) {
                val errorMsg = ConsoleMessage(role = ConsoleRole.ERROR, text = e.message ?: "AT 指令失败")
                _toolsState.value = _toolsState.value.copy(
                    atMessages = _toolsState.value.atMessages + errorMsg,
                    isLoading = false,
                    errorMessage = null
                )
                persistConsoleHistory()
            }
        }
    }

    // ── SMS ──
    fun sendSms(phone: String, message: String) {
        scope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val conversationTotalBefore = _toolsState.value.conversationTotal
                val response = api.sendSms(SmsSendRequest(phone, message))
                _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = if (!response.success) "短信发送失败" else null)
                if (response.success) {
                    loadSmsList()
                    loadSmsContacts()
                    // 如果正在对话中，刷新对话
                    if (_toolsState.value.conversationPhone.isNotEmpty()) {
                        openConversation(_toolsState.value.conversationPhone)
                    }
                    // 设备把刚发出的那条写进信箱 / 同步到短信库有延迟，紧跟着的这次拉取常常还是发送前的
                    // 快照 —— 表现就是"发完了列表和对话都没变化"。隔一拍再静默补一次兜底。
                    delay(SEND_REFRESH_DELAY_MS)
                    loadSmsContacts(silent = true)
                    // 对话里确实还没出现新内容才重开：否则会让刚刷好的对话再闪一次
                    if (_toolsState.value.conversationPhone == phone &&
                        _toolsState.value.conversationTotal <= conversationTotalBefore) {
                        openConversation(phone)
                    }
                }
            } catch (e: Exception) { _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = "发送失败: ${e.message}") }
        }
    }

    /**
     * 加载联系人列表（后端已按号码聚合，含总数、未读数、最新消息）。
     *
     * @param silent 静默刷新：不翻 [ToolsState.isLoading]（否则下拉刷新指示器会被自动轮询点亮），
     *        失败也只记日志、不写 [ToolsState.errorMessage] —— 一次轮询失败不该在页面顶部挂错误横幅。
     */
    fun loadSmsContacts(silent: Boolean = false) {
        scope.launch {
            if (!silent) _toolsState.value = _toolsState.value.copy(isLoading = true)
            try {
                val resp = api.getSmsContacts()
                _toolsState.value = _toolsState.value.copy(smsContacts = resp.contacts, isLoading = false)
                // T04 N9：新短信差异检测（last_sms_total 对比未读数）
                notificationCenter.maybeNotifyNewSms(resp.contacts)
            } catch (e: Exception) {
                if (silent) {
                    DebugLog.w("Tools", "短信联系人静默刷新失败", e)
                } else {
                    _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = "短信联系人加载失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 设备短信总数 / 未读数（`GET /api/sms/count`）。
     *
     * 只读诊断信息，**失败不写 [ToolsState.errorMessage]** —— 容量行读不到就不画，
     * 不该在短信页顶部挂一条常驻错误横幅。由短信设置弹窗打开时触发，不进轮询。
     */
    fun loadSmsCount() {
        scope.launch {
            try {
                _toolsState.value = _toolsState.value.copy(smsCount = api.getSmsCount())
            } catch (e: Exception) {
                DebugLog.w("Tools", "短信条数读取失败", e)
            }
        }
    }

    /**
     * 全部标为已读（`POST /api/sms/mark-all-read`）。
     *
     * **不做乐观更新**：未读数的真源在设备，先本地清零再失败会与设备长期不一致，
     * 而这是用户显式点的一次性操作，等一个来回可以接受。成功后重拉联系人列表刷新角标。
     *
     * 返回值给 UI 决定提示文案（成功/失败 Toast），因此不写 [ToolsState.errorMessage] ——
     * 免得一次失败在页面顶部挂一条常驻错误横幅。
     */
    suspend fun markAllSmsRead(): Boolean = try {
        val ok = api.markAllSmsRead().success
        if (ok) {
            loadSmsContacts()
            // 容量行的未读数也出自 /count，不重读的话弹窗里会留着旧值
            loadSmsCount()
        }
        ok
    } catch (e: Exception) {
        DebugLog.w("Tools", "全部标为已读失败", e)
        false
    }

    /** WS 推送更新联系人列表（免 HTTP 请求，实时性高） */
    fun updateSmsContactsFromWs(contacts: List<SmsContact>) {
        _toolsState.value = _toolsState.value.copy(smsContacts = contacts)
        // T04 N9：WS sms_contacts 实时推送也做未读数差异检测
        notificationCenter.maybeNotifyNewSms(contacts)
    }

    fun loadSmsList() {
        scope.launch {
            try { _toolsState.value = _toolsState.value.copy(smsList = api.getSmsList().messages) }
            catch (e: Exception) { _toolsState.value = _toolsState.value.copy(errorMessage = "短信列表加载失败: ${e.message}") }
        }
    }

    /**
     * 打开联系人对话：加载最近一页消息（按号码过滤），自动标记已读。
     *
     * @param targetMsgId 打开后要定位并高亮的消息 id（0 = 不定位，停在最新一条）。
     *        由「查看原对话」带过来；从联系人列表进来时是 0，会顺手把上一次的目标清掉。
     */
    fun openConversation(phone: String, targetMsgId: Long = 0L) {
        scope.launch {
            _toolsState.update {
                it.copy(conversationPhone = phone, conversationMessages = emptyList(),
                    conversationOffset = 0, conversationHasMore = true,
                    conversationLoading = true, conversationTotal = 0,
                    conversationTargetMsgId = targetMsgId)
            }
            try {
                val resp = api.getSmsList(limit = CONVERSATION_PAGE_SIZE, offset = 0, phone = phone)
                // 后端返回 DESC（最新在前），转为 ASC 用于对话展示
                val msgs = resp.messages.sortedBy { it.timestamp }
                val total = resp.total.coerceAtLeast(msgs.size.coerceAtLeast(resp.count))
                _toolsState.update {
                    it.copy(conversationMessages = msgs,
                        conversationOffset = msgs.size,
                        conversationHasMore = msgs.size < total,
                        conversationLoading = false,
                        conversationTotal = total)
                }
                // 打开对话 → 自动标记该对话所有消息为已读。
                // 标记已读是「尽力而为」的副作用：即便后端动作接口抖动也不要污染对话展示，
                // 否则会因 SmsActionResponse 解析/网络异常把已成功加载的对话打成「对话加载失败」。
                try {
                    api.markConversationRead(mapOf("phone" to phone))
                } catch (_: Exception) {
                    // 忽略：本地已乐观标记已读
                }
                // 乐观更新本地状态：所有消息标记为已读
                _toolsState.update {
                    it.copy(conversationMessages = it.conversationMessages.map { m -> m.copy(read = true) })
                }
                // 刷新联系人列表（更新未读角标）——静默：这只是副作用，不该让列表出现刷新态
                loadSmsContacts(silent = true)
            } catch (e: Exception) {
                _toolsState.update { it.copy(conversationLoading = false, errorMessage = "对话加载失败: ${e.message}") }
            }
        }
    }

    /** 加载更早的消息（向上滚动触发），按号码过滤 */
    fun loadMoreConversation() {
        val phone = _toolsState.value.conversationPhone
        if (phone.isEmpty() || _toolsState.value.conversationLoading || !_toolsState.value.conversationHasMore) return
        scope.launch {
            val offset = _toolsState.value.conversationOffset
            _toolsState.update { it.copy(conversationLoading = true) }
            try {
                val resp = api.getSmsList(limit = CONVERSATION_PAGE_SIZE, offset = offset, phone = phone)
                val older = resp.messages.sortedBy { it.timestamp }
                val total = resp.total.coerceAtLeast(
                    (older.size + _toolsState.value.conversationMessages.size).coerceAtLeast(resp.count))
                val newOffset = offset + older.size
                _toolsState.update {
                    it.copy(
                        conversationMessages = older + it.conversationMessages,
                        conversationOffset = newOffset,
                        conversationHasMore = newOffset < total,
                        conversationLoading = false,
                        conversationTotal = total
                    )
                }
            } catch (e: Exception) {
                _toolsState.update { it.copy(conversationLoading = false, errorMessage = "加载更多消息失败: ${e.message}") }
            }
        }
    }

    /** 关闭对话，清理分页状态 */
    fun closeConversation() {
        _toolsState.update {
            it.copy(conversationPhone = "", conversationMessages = emptyList(),
                conversationOffset = 0, conversationHasMore = true,
                conversationLoading = false, conversationTotal = 0,
                conversationTargetMsgId = 0L)
        }
    }

    // ── SMS Tab + 验证码 ──

    fun switchSmsTab(index: Int) {
        _toolsState.update { it.copy(smsTab = index) }
        if (index == 1) {
            // 先按当前已知列表清角标（点进去立刻灭，不等网络回来），再静默刷新
            markVerificationCodesSeen()
            loadVerificationCodes()
        }
    }

    /**
     * 把「通知」页签角标的已读水位推到当前列表的最大 msgId，并把角标数归 0。
     *
     * 只前推不回退（`maxOf`）：并发的静默刷新若拿到旧快照，不会把水位往回拨、
     * 导致已经看过的验证码重新点亮角标。
     */
    fun markVerificationCodesSeen() {
        val codes = _toolsState.value.verificationCodes
        if (codes.isNotEmpty()) {
            val prefs = AppPreferences(appContext)
            prefs.smsCodeSeenMsgId = maxOf(prefs.smsCodeSeenMsgId, codes.maxOf { it.msgId })
        }
        _toolsState.update { it.copy(verificationCodesUnread = 0) }
    }

    /**
     * 拉取验证码缓存。
     *
     * 2026-08-29：改为**无感刷新** —— 不再翻 `verificationCodesLoading` 让页面出现加载态，
     * 数据静默替换。该字段仍保留给需要显式 loading 的调用方（当前 UI 已不读它）。
     * 同时角标改成「未读水位」语义：`msgId > smsCodeSeenMsgId` 的条数；若用户此刻正停在
     * 通知页签，拉到新数据的同时直接标记为已看，避免角标闪一下又灭。
     */
    fun loadVerificationCodes() {
        scope.launch {
            try {
                val resp = api.getVerificationCodes()
                val prefs = AppPreferences(appContext)
                val onNotifyTab = _toolsState.value.smsTab == 1
                if (onNotifyTab && resp.codes.isNotEmpty()) {
                    prefs.smsCodeSeenMsgId = maxOf(prefs.smsCodeSeenMsgId, resp.codes.maxOf { it.msgId })
                }
                val seen = prefs.smsCodeSeenMsgId
                _toolsState.update {
                    it.copy(
                        verificationCodes = resp.codes,
                        verificationCodesLoading = false,
                        verificationCodesUnread = resp.codes.count { c -> c.msgId > seen }
                    )
                }
                // T04 N10：验证码差异检测（游标 + 新鲜度窗口，必须整批传入）
                notificationCenter.maybeNotifyVerificationCodes(resp.codes)
            } catch (e: Exception) {
                // 无感刷新：失败也不弹错误横幅打断阅读，只在日志留痕（列表仍显示上一次的数据）
                DebugLog.w("Sms", "验证码加载失败: ${e.message}")
                _toolsState.update { it.copy(verificationCodesLoading = false) }
            }
        }
    }

    fun setSmsCodeEnabled(enabled: Boolean) {
        _toolsState.update { it.copy(smsCodeEnabled = enabled, smsCodeOptInShown = true) }
        scope.launch {
            try {
                api.updateConfig(mapOf("sms_code_enabled" to enabled))
                if (enabled) loadVerificationCodes()
            } catch (_: Exception) {}
        }
    }

    fun setSmsCodeCleanupHours(hours: Int) {
        _toolsState.update { it.copy(smsCodeCleanupHours = hours) }
        scope.launch {
            try {
                api.updateConfig(mapOf("sms_code_cleanup_hours" to hours))
            } catch (_: Exception) {}
        }
    }

    /**
     * 以 core 为唯一真源回读设备配置，并覆盖本地缓存。
     *
     * 2026-08-26（T40-4）：原来这里叫 `loadSmsConfig()`，拿到整个 `GET /api/config` 却**只取
     * `sms_code_enabled` / `sms_code_cleanup_hours` 两个字段**，其余全部丢弃 —— 于是 core 那份
     * GET 对 app 等于不存在，`debug_mode` / `goform_port` / `update_mirror_base` 全靠本地
     * SharedPreferences 各存一份、只写不读。web 在另一端改过之后，app 这边显示的还是旧值，
     * 甚至会用本地默认值把 core 覆盖回去（「app 设为 A、web 显示 B」的直接来源）。
     *
     * 现在：一次 GET 覆盖所有能回读的字段；**回读失败静默沿用本地缓存**（不提示、不阻断页面）。
     * 注意 `goform_password` 在 GET 里是脱敏值，不能回写本地，
     * 只能得到「是否已设置」——所以 goform 密码本地不再存明文，只存 [AppPreferences.goformPasswordSet]。
     */
    fun refreshDeviceConfig() {        scope.launch {
            val cfg = try {
                api.getConfig()
            } catch (e: Exception) {
                DebugLog.w("Config", "回读 /api/config 失败，沿用本地缓存: ${e.message}")
                return@launch
            }
            _toolsState.update {
                it.copy(
                    smsCodeEnabled = cfg.sms_code_enabled,
                    smsCodeCleanupHours = cfg.sms_code_cleanup_hours
                )
            }
            // core 为真源：直接覆盖本地缓存。
            // 2026-09-04：日志四层开关改成「只有 core 明确给了值才覆盖」。
            // 这四个字段在 AppConfig 里已改为可空（null = 响应里没有这个 key）——原来它们非空 +
            // 默认 true，「core 没返回」与「core 说 true」不可区分，于是老 core / 裁剪过的响应
            // 会把用户刚关掉的开关重新打开。其余字段沿用「有默认值也照写」的旧行为，
            // 因为它们不是开关、写错了不会产生持续的副作用（日志会一直写盘）。
            val prefs = AppPreferences(appContext)
            applyLogSwitchesFromCore(cfg)
            prefs.goformPort = cfg.goform_port
            prefs.updateMirrorCustom = cfg.update_mirror_base
            prefs.goformPasswordSet = cfg.goform_password.isNotBlank()
        }
    }

    /**
     * 把 `GET /api/config` 里的日志开关落到本地缓存 + [logSwitchState] 镜像。
     *
     * 只覆盖 core 明确给了值的字段（非 null）。全都为 null 时（老 core）保持 `loaded = false`，
     * UI 会把开关置灰 —— 宁可让用户点不动，也不能让他在一个假状态上做决定。
     */
    private fun applyLogSwitchesFromCore(cfg: AppConfig) {
        val prefs = AppPreferences(appContext)
        cfg.log_enabled?.let { prefs.logEnabled = it }
        cfg.app_log_enabled?.let { prefs.appLogEnabled = it }
        cfg.core_log_enabled?.let { prefs.coreLogEnabled = it }
        cfg.debug_mode?.let { prefs.debugMode = it }
        val anyPresent = cfg.log_enabled != null || cfg.app_log_enabled != null ||
            cfg.core_log_enabled != null || cfg.debug_mode != null
        _logSwitchState.value = _logSwitchState.value.copy(
            logEnabled = cfg.log_enabled ?: prefs.logEnabled,
            coreLogEnabled = cfg.core_log_enabled ?: prefs.coreLogEnabled,
            appLogEnabled = cfg.app_log_enabled ?: prefs.appLogEnabled,
            debugMode = cfg.debug_mode ?: prefs.debugMode,
            loaded = _logSwitchState.value.loaded || anyPresent,
            isSaving = false,
            errorMessage = null
        )
    }

    /**
     * 打开某个号码的对话，并定位到指定消息（验证码详情弹窗的「查看原对话」）。
     *
     * 刻意**不动 smsTab**：对话是覆盖整屏的一层，底下停在哪个页签不影响显示；
     * 旧实现顺手 `smsTab = 0`，结果用户从「通知」跳进对话、返回时落在消息列表，
     * 丢掉了原来的浏览位置。
     *
     * @param msgId 验证码来源短信的 id。只带号码的话对话只会停在最新一条，
     *        用户还得自己往上翻找那条验证码 —— 这个 id 让对话直接滚到并高亮它。
     */
    fun openVerificationCodeSource(phone: String, msgId: Long = 0L) {
        _toolsState.update { it.copy(navigateToPhone = phone, navigateToMsgId = msgId) }
    }

    /**
     * 删除一条验证码 —— 实际删的是它的**来源短信**。
     *
     * core 没有"只删验证码缓存"的接口，但也不需要：`SmsController` 在删短信的两条路径里
     * 都跟着 `vcDao?.deleteByMsgId(id)`，所以 `deleteSms(msgId)` 会把原短信和验证码缓存行
     * 一起清掉。这里额外做的是**本地列表同步移除**，否则卡片要等下一次轮询才消失。
     * 未读数按剩余条目重算：被删的那条如果本来未读，角标得跟着减。
     */
    fun deleteVerificationCode(msgId: Long) {
        val seen = AppPreferences(appContext).smsCodeSeenMsgId
        _toolsState.update { state ->
            val remaining = state.verificationCodes.filterNot { it.msgId == msgId }
            state.copy(
                verificationCodes = remaining,
                verificationCodesUnread = remaining.count { it.msgId > seen }
            )
        }
        deleteSms(msgId.toString())
    }

    /**
     * 回读通知配置真源并落地生效（T40-6）。
     *
     * 这 10 项原先只有「UI 直接写 SharedPreferences」一条路径，web / 另一台设备改了配置
     * app 侧什么都不会发生。现在 core 是唯一真源，本地降级为缓存。
     * 回读失败静默沿用本地缓存（core_truth_silent）。
     *
     * @param guard 由持有实例的页面传入（`guard_*` 三项需要 WorkManager 重调度）；
     *              传 null 时那三项只写缓存，等下一次 `GuardScheduler.refresh()` 补齐。
     */
    fun refreshNotificationConfig(guard: com.ufi_axis.data.notification.GuardScheduler? = null) {
        scope.launch {
            val remote = try {
                api.getNotificationConfig()
            } catch (e: Exception) {
                DebugLog.w("NotifyConfig", "回读 /api/notifications/config 失败，沿用本地缓存: ${e.message}")
                return@launch
            }
            com.ufi_axis.data.notification.NotificationConfigSync.applyRemote(appContext, remote, guard)
        }
    }

    /**
     * 下发通知配置改动（字段级 patch），并以服务端回显为准落地。
     *
     * 只传改动的键：core 做字段级合并，整体回传会把别端刚改的项用本地值覆盖掉。
     * 失败写 `DebugLog.w` 而不弹 UI —— 但必须留痕，否则本地已改、真源未改，
     * 下次回读又变回去，用户只会看到「我改的设置自己弹回来了」。
     */
    fun updateNotificationConfig(
        patch: Map<String, Any>,
        guard: com.ufi_axis.data.notification.GuardScheduler? = null
    ) {
        if (patch.isEmpty()) return
        scope.launch {
            val result = runCatching { api.updateNotificationConfig(patch) }
            val echoed = result.getOrNull()?.takeIf { it.success }?.config
            if (echoed == null) {
                DebugLog.w(
                    "NotifyConfig",
                    "通知配置下发失败，本地已改但 core 未更新: ${result.exceptionOrNull()?.message}"
                )
                return@launch
            }
            com.ufi_axis.data.notification.NotificationConfigSync.applyRemote(appContext, echoed, guard)
        }
    }

    fun clearNavigateToPhone() {
        _toolsState.update { it.copy(navigateToPhone = "", navigateToMsgId = 0L) }
    }

    companion object {
        private const val CONVERSATION_PAGE_SIZE = 100

        /** 发送成功后延迟多久再静默补一次刷新（设备写信箱 / 同步短信库有延迟，立刻拉是发送前的快照） */
        private const val SEND_REFRESH_DELAY_MS = 2_500L

        /** 前端更新信息源：GitHub 仓库根 version.json（raw；release.yml 发布时自动回写 apkUrl/apkSha256，也可手动填写） */
        const val RAW_VERSION_URL = "https://raw.githubusercontent.com/Asunano/UFI-AXIS/main/version.json"

        /** P0-7：轮询失败连续 N 次判定为「设备重启中」过渡态（Core 重启窗口 8088 不可达） */
        private const val DEVICE_UPDATE_POLL_FAILURE_THRESHOLD = 3

        /** P1 A5/E21：轮询失败连续 N 次（约 2s×150 = 5 分钟）判定为失联，提示「设备更新可能失败」 */
        private const val DEVICE_UPDATE_POLL_LOST_THRESHOLD = 150
    }

    // ── Shell Exec ──
    fun executeShell(command: String, asRoot: Boolean = true) {
        scope.launch {
            val displayCommand = "${if (asRoot) "# " else "$ "}$command"
            val userMsg = ConsoleMessage(role = ConsoleRole.USER, text = displayCommand)
            // 先上用户气泡（立即可见 + 入场动画），再进入加载态等待 Shell 返回
            _toolsState.value = _toolsState.value.copy(
                shellMessages = _toolsState.value.shellMessages + userMsg,
                isLoading = true,
                errorMessage = null
            )
            try {
                val result = api.shellExec(ShellExecRequest(command, asRoot, 30))
                val output = buildString {
                    if (result.stdout.isNotBlank()) append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("[stderr]\n").append(result.stderr)
                    }
                    append("\n[exit: ${result.exit_code}]")
                }
                val assistantMsg = ConsoleMessage(role = ConsoleRole.ASSISTANT, text = output)
                _toolsState.value = _toolsState.value.copy(
                    shellMessages = _toolsState.value.shellMessages + assistantMsg,
                    isLoading = false
                )
                persistConsoleHistory()
            } catch (e: Exception) {
                val errorMsg = ConsoleMessage(role = ConsoleRole.ERROR, text = e.message ?: "Shell 执行失败")
                _toolsState.value = _toolsState.value.copy(
                    shellMessages = _toolsState.value.shellMessages + errorMsg,
                    isLoading = false,
                    errorMessage = null
                )
                persistConsoleHistory()
            }
        }
    }

    fun deleteSms(id: String) {
        val idLong = id.toLongOrNull() ?: return
        scope.launch {
            try {
                // 1. 立即标记为"删除中"，触发 UI 渐出动画
                _toolsState.update { it.copy(deletingMessageIds = it.deletingMessageIds + idLong) }

                // 2. 调用后端删除（goform + ContentResolver）
                api.deleteSms(mapOf("id" to id))

                // 3. 等待动画播放完成（350ms fadeOut）
                delay(350L)

                // 4. 从对话消息列表 + 全局短信列表移除，更新 offset/total
                _toolsState.update { state ->
                    state.copy(
                        smsList = state.smsList.filter { it.id != idLong },
                        conversationMessages = state.conversationMessages.filter { m -> m.id != idLong },
                        conversationTotal = (state.conversationTotal - 1).coerceAtLeast(0),
                        conversationOffset = (state.conversationOffset - 1).coerceAtLeast(0),
                        deletingMessageIds = state.deletingMessageIds - idLong
                    )
                }
                // 5. WS 短信缓存（3s 轮询 + 5条快速 + 500条保底）会自动推送最新联系人列表
            }
            catch (e: Exception) {
                // 失败：撤销动画状态
                _toolsState.update { it.copy(deletingMessageIds = it.deletingMessageIds - idLong, errorMessage = "删除失败: ${e.message}") }
            }
        }
    }

    /**
     * 切换 SIM 卡槽（当前无 UI 调用点，暂不启用）。
     *
     * 停用原因：① 当前实测设备本身没有多卡槽功能；② core 也没有「当前卡槽」的稳定查询接口
     * （sim_slot 只在 goform 全量状态里，出口仅有默认关闭 + 脱敏的 GET /api/device/goform）。
     * 后续适配多卡槽设备时再接 UI。
     *
     * 注意：core 契约入参是 slot，值域为 1/2/3（第 N 个物理槽）或字符串 "external"；
     * 现在 UfiAxisApi.switchSimSlot 声明成 Map<String, Int> 带不了 "external"，启用前要一起改。
     * 不要退回旧入参 goformSlot——它的值域是运营商编码（"1" = 电信），和 slot 的槽位编号重叠，会静默切错卡。
     */
    fun switchSimSlot(slot: Int) {
        scope.launch {
            try { api.switchSimSlot(mapOf("slot" to slot)) }
            catch (e: Exception) { emitNetworkError("切换卡槽失败: ${e.message}") }
        }
    }

    // ── Alerts ──
    fun loadAlerts() {
        scope.launch {
            _alertsState.value = _alertsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val config = async { runCatching { api.getAlertConfig() } }
                val list = async { runCatching { api.getAlertList(50) } }
                val rConfig = config.await(); val rList = list.await()

                val failures = listOfNotNull(
                    "配置" to rConfig, "列表" to rList
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                _alertsState.value = AlertsState(
                    config = rConfig.getOrNull(),
                    alerts = rList.getOrNull()?.alerts ?: emptyList(),
                    isLoading = false,
                    errorMessage = failures
                )
                // P2 多端同步：拉取成功后同步镜像（连接即拉取，绝不推送默认）
                rConfig.getOrNull()?.let { alertPrefs.applyRemote(it) }
                // 2026-08-09 19:03 系统通知推送（alert-notification-plan 阶段 B3）：
                // 拉取成功后差异检测新告警——统一委托 NotificationCenter（开关关→零开销 return）
                rList.getOrNull()?.alerts?.let { notificationCenter.maybeNotifyNewAlerts(it) }
            } catch (e: Exception) { _alertsState.value = _alertsState.value.copy(isLoading = false, errorMessage = "加载告警失败: ${e.message}") }
        }
    }

    /**
     * 更新告警配置。
     *
     * T13 守卫：**镜像未加载完成时一律不 PUT**。此前 UI 在 `config == null` 时用
     * `AlertConfig()`（本地默认值 + configVersion=1）提交，若设备当前 version 恰为 1 就会守门通过，
     * 把别端设置的 `perType` / `minIntervalSec` / `notifyEnabled` 一并写成默认值 —— 正是
     * `AlertEngine` 注释里点名禁止的"连接即写默认"回弹。C02 的合并语义只能防"漏传键"，
     * 防不住"显式带默认值提交"，所以这道守卫不可省。
     */
    fun updateAlertConfig(config: AlertConfig) {
        if (alertPrefs.current == null) {
            _alertsState.value = _alertsState.value.copy(errorMessage = "告警配置尚未加载完成，请稍后重试")
            DebugLog.w("Tools", "updateAlertConfig 被拒：本地镜像为空（防止把默认值写回设备）")
            loadAlerts()
            return
        }
        scope.launch {
            try {
                // P2 多端同步：经 AlertPrefsRepository 推送（带 configVersion 守门 + 409 自动重试）
                val ok = alertPrefs.pushUpdate(api, config)
                if (!ok) {
                    _alertsState.value = _alertsState.value.copy(errorMessage = "更新告警配置冲突，请重试")
                }
                loadAlerts()
            } catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "更新告警配置失败: ${e.message}") }
        }
    }

    fun ackAlert(id: Long) {
        scope.launch {
            try { api.ackAlert(AckRequest(id)); loadAlerts() }
            catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "确认告警失败: ${e.message}") }
        }
    }

    // ── SMS Forward ──
    fun loadSmsForwardConfig() {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true)
            // 用 copy 而不是新建 SmsForwardState：diagnose 是另一个端点的产物，
            // 整体替换会在每次重载配置（含保存后的自动重载）时把它清空，UI 上是诊断区一闪就没。
            try {
                _smsForwardState.value = _smsForwardState.value.copy(
                    config = api.getSmsForwardConfig(), isLoading = false, errorMessage = null
                )
            } catch (e: Exception) {
                _smsForwardState.value = _smsForwardState.value.copy(
                    isLoading = false, errorMessage = "加载失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 邮件通知诊断（`GET /api/sms-forward/diagnose`）。
     *
     * 失败**不写 errorMessage**：诊断只是辅助信息，读不到时页面不显示诊断区即可；
     * 占掉错误位会让用户误以为配置保存失败。
     */
    fun loadSmsForwardDiagnose() {
        scope.launch {
            try {
                _smsForwardState.value = _smsForwardState.value.copy(diagnose = api.diagnoseSmsForward())
            } catch (e: Exception) {
                DebugLog.w("Tools", "邮件通知诊断读取失败", e)
            }
        }
    }

    fun saveSmsForwardConfig(config: SmsForwardConfig) {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.saveSmsForwardConfig(config)
                if (result.success) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false); loadSmsForwardConfig() }
                else _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败：服务器返回失败")
            } catch (e: Exception) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败: ${e.message}") }
        }
    }

    fun testSmsForward() {
        scope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = api.testSmsForward()
                val success = result.success
                val error = result.error
                _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = if (success) null else (error ?: "测试发送失败"))
            } catch (e: Exception) { _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "测试失败: ${e.message}") }
        }
    }

    // ── Scheduled Tasks ──
    fun loadTaskList() {
        scope.launch {
            _tasksState.value = _tasksState.value.copy(isLoading = true)
            try { _tasksState.value = TasksState(tasks = api.getTaskList().tasks) }
            catch (e: Exception) { _tasksState.value = TasksState(errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun createTask(task: ScheduledTask) {
        scope.launch {
            try { api.createTask(task); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "创建失败: ${e.message}") }
        }
    }

    /**
     * 更新任务（含 Switch 开关、编辑对话框保存）。
     *
     * 2026-08-18 修复 P1 定时任务失效：
     *  - `AppJson.encodeDefaults = true` 已保证 body 包含所有字段（含 `enabled=true` 默认值），
     *    后端 PUT 路由 `p["xxx"] ?: existing.xxx` 才能拿到新值，开关才不回弹。
     *  - 加乐观更新：立刻把本地 `state.tasks` 里同 id 的 task 替换成新 task，UI 即时反馈；
     *    后端返回后再 `loadTaskList()` 校准；失败时再回拉一次（兜底）+ 错误信息。
     *  - id 为空直接 return（防御：上层传错也不发空请求）。
     */
    fun updateTask(id: String, task: ScheduledTask) {
        if (id.isBlank()) {
            _tasksState.value = _tasksState.value.copy(errorMessage = "更新失败: 任务 id 为空")
            return
        }
        // 乐观更新：立即替换本地状态，UI 立即反馈
        _tasksState.value = _tasksState.value.copy(
            tasks = _tasksState.value.tasks.map { if (it.id == id) task else it }
        )
        scope.launch {
            try {
                api.updateTask(id, task)
                loadTaskList()
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "更新失败: ${e.message}")
                // 失败回拉一次以恢复正确状态
                runCatching { loadTaskList() }
            }
        }
    }

    fun deleteTask(id: String) {
        if (id.isBlank()) {
            _tasksState.value = _tasksState.value.copy(errorMessage = "删除失败: 任务 id 为空")
            return
        }
        // 乐观更新：先从列表里移除，UI 立即消失
        _tasksState.value = _tasksState.value.copy(
            tasks = _tasksState.value.tasks.filter { it.id != id }
        )
        scope.launch {
            try {
                api.deleteTask(id)
                loadTaskList()
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "删除失败: ${e.message}")
                runCatching { loadTaskList() }
            }
        }
    }

    fun clearTasks() {
        scope.launch {
            try { api.clearTasks(); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "清除失败: ${e.message}") }
        }
    }

    fun loadTaskLogs(taskId: String) {
        scope.launch {
            try {
                val response = api.getTaskLogs(taskId)
                _tasksState.value = _tasksState.value.copy(
                    taskLogs = _tasksState.value.taskLogs + (taskId to response.logs)
                )
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "加载日志失败: ${e.message}")
            }
        }
    }

    // ── Automation Rules (条件触发 · 当…就…) ──
    // 与 Scheduled Tasks 同模式：镜像 load/create/update/delete/clear/loadLogs。
    // 后端 ConditionEngine 持久化在 SharedPreferences "automation_rules"，与 TaskScheduler 解耦。

    fun loadRuleList() {
        scope.launch {
            _tasksState.value = _tasksState.value.copy(isLoading = true)
            try { _tasksState.value = _tasksState.value.copy(rules = api.getRuleList().rules, isLoading = false) }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(rules = emptyList(), isLoading = false, errorMessage = "加载规则失败: ${e.message}") }
        }
    }

    fun createRule(rule: AutomationRule) {
        scope.launch {
            try { api.createRule(rule); loadRuleList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "创建规则失败: ${e.message}") }
        }
    }

    /**
     * 更新规则（含 Switch 开关、编辑对话框保存）。
     * 乐观更新 + 失败回拉，与 updateTask 同策略（AppJson.encodeDefaults=true 保证 body 完整，开关不回弹）。
     */
    fun updateRule(id: String, rule: AutomationRule) {
        if (id.isBlank()) {
            _tasksState.value = _tasksState.value.copy(errorMessage = "更新失败: 规则 id 为空")
            return
        }
        _tasksState.value = _tasksState.value.copy(
            rules = _tasksState.value.rules.map { if (it.id == id) rule else it }
        )
        scope.launch {
            try {
                api.updateRule(id, rule)
                loadRuleList()
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "更新规则失败: ${e.message}")
                runCatching { loadRuleList() }
            }
        }
    }

    fun deleteRule(id: String) {
        if (id.isBlank()) {
            _tasksState.value = _tasksState.value.copy(errorMessage = "删除失败: 规则 id 为空")
            return
        }
        _tasksState.value = _tasksState.value.copy(
            rules = _tasksState.value.rules.filter { it.id != id }
        )
        scope.launch {
            try {
                api.deleteRule(id)
                loadRuleList()
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "删除规则失败: ${e.message}")
                runCatching { loadRuleList() }
            }
        }
    }

    fun clearRules() {
        scope.launch {
            try { api.clearRules(); loadRuleList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "清除规则失败: ${e.message}") }
        }
    }

    fun loadRuleLogs(ruleId: String) {
        scope.launch {
            try {
                val response = api.getRuleLogs(ruleId)
                _tasksState.value = _tasksState.value.copy(
                    ruleLogs = _tasksState.value.ruleLogs + (ruleId to response.logs)
                )
            } catch (e: Exception) {
                _tasksState.value = _tasksState.value.copy(errorMessage = "加载规则日志失败: ${e.message}")
            }
        }
    }

    // ── Debug Logs（core 侧；APP 侧由 AppLogBuffer 直供 UI）──

    /** core 内存缓冲上限就是 500（`AppLogger.MAX_BUFFER_SIZE`），一次拉满即可。 */
    private val CORE_LOG_LIMIT = 500

    /**
     * 拉取 core 的日志缓冲并解析为 [LogEntry]。
     *
     * **不带 level 参数**：core 的级别过滤是朴素的整行 `contains`，正文里出现 "ERROR" 字样的
     * INFO 行也会被误命中；而且切一次级别就得重新发一次 HTTP。现在一次拉全量，
     * 级别/类型/关键字全在 UI 本地按解析后的字段精确筛。
     */
    fun loadDebugLogs() {
        scope.launch {
            _debugLogState.value = _debugLogState.value.copy(isLoading = true, errorMessage = null)
            try {
                val logs = api.getDebugLogs(null, CORE_LOG_LIMIT).logs
                _debugLogState.value = DebugLogState(
                    coreEntries = logs.mapIndexed { i, line -> LogEntry.parseCoreLine(line, i.toLong()) },
                    isLoading = false
                )
            } catch (e: Exception) {
                _debugLogState.value = _debugLogState.value.copy(
                    isLoading = false,
                    errorMessage = "加载失败: ${e.message}"
                )
            }
        }
    }

    /** 清空 core 的内存缓冲（DELETE /api/debug-logs）。APP 侧清空由 UI 直接调 `AppLogBuffer.clear()`。 */
    fun clearDebugLogs() {
        scope.launch {
            try {
                api.clearDebugLogs()
                _debugLogState.value = DebugLogState()
            } catch (e: Exception) {
                _debugLogState.value = _debugLogState.value.copy(errorMessage = "清除失败: ${e.message}")
            }
        }
    }

    /** 拉取 core 落盘日志文件列表（`GET /api/debug-logs/files`）。 */
    fun loadCoreLogFiles() {
        scope.launch {
            _coreLogFilesState.value = _coreLogFilesState.value.copy(isLoading = true, errorMessage = null)
            try {
                val resp = api.getDebugLogFiles()
                _coreLogFilesState.value = CoreLogFilesState(
                    files = resp.files,
                    totalBytes = resp.total_bytes,
                    dir = resp.dir
                )
            } catch (e: Exception) {
                _coreLogFilesState.value = _coreLogFilesState.value.copy(
                    isLoading = false,
                    errorMessage = "加载文件列表失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 读取某个落盘日志文件的尾部正文。core 侧只返回最后 [maxBytes] 字节 ——
     * 单文件可达几十 MB，整读会 OOM。
     */
    fun loadCoreLogFileTail(name: String, maxBytes: Int = 256 * 1024) {
        scope.launch {
            _coreLogFilesState.value = _coreLogFilesState.value.copy(
                viewingName = name,
                viewingText = null,
                isLoading = true,
                errorMessage = null
            )
            try {
                val text = api.getDebugLogFileTail(name, maxBytes).use { it.string() }
                _coreLogFilesState.value = _coreLogFilesState.value.copy(
                    viewingText = text,
                    isLoading = false
                )
            } catch (e: Exception) {
                _coreLogFilesState.value = _coreLogFilesState.value.copy(
                    isLoading = false,
                    errorMessage = "读取 $name 失败: ${e.message}"
                )
            }
        }
    }

    /** 关闭正文查看，回到文件列表。 */
    fun closeCoreLogFile() {
        _coreLogFilesState.value = _coreLogFilesState.value.copy(viewingName = null, viewingText = null)
    }

    /** 删除 core 全部落盘日志文件，成功后重新拉一次列表。 */
    fun deleteCoreLogFiles() {
        scope.launch {
            try {
                api.deleteDebugLogFiles()
                _coreLogFilesState.value = CoreLogFilesState()
                loadCoreLogFiles()
            } catch (e: Exception) {
                _coreLogFilesState.value = _coreLogFilesState.value.copy(errorMessage = "删除失败: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  日志四层开关（2026-09-04 重写）
    //
    //  修的缺陷：原来这四个方法都是「先写本地 AppPreferences，再 fire-and-forget 地
    //  PUT /api/config，失败只 DebugLog.w 一行」。后果是**两端永久分叉**：
    //  手机端 UI 与本地缓存显示"已关闭"，core 从没收到过这条指令、仍然是开，
    //  web 读 core 于是显示"开启"，core 也继续写日志文件。用户看到的
    //  「我在手机端关闭了，但 web 端经常显示开启 / 日志总是自动开启」就是这个。
    //
    //  现在的顺序是**下发成功才落地**：
    //  1. PUT /api/config（唯一真源在 core 的 AppSettings）；
    //  2. 校验 updated_fields 真的含这个字段（core 的 boolField 会把非法值放进 rejected_fields
    //     却仍回 success:true，只看 HTTP 200 会把"被拒"当成"成功"）；
    //  3. 成功后才写本地缓存（其 setter 顺带同步 DebugLog 的进程内开关）+ 更新镜像；
    //  4. 失败：本地与镜像都不动（开关自然停在旧位置）+ 把原因抛给 UI。
    // ══════════════════════════════════════════════════════════════

    /**
     * 从 core 回读四层开关（进日志页时调用）。失败只置 [LogSwitchState.errorMessage]，
     * 不动本地缓存 —— 连不上设备时把开关改掉毫无道理。
     */
    fun loadLogSwitches() {
        scope.launch {
            val cfg = try {
                api.getConfig()
            } catch (e: Exception) {
                _logSwitchState.value = _logSwitchState.value.copy(
                    isSaving = false,
                    errorMessage = "读取设备日志开关失败：${e.message}"
                )
                return@launch
            }
            applyLogSwitchesFromCore(cfg)
        }
    }

    /** 用户已读错误提示后清掉，避免 toast 反复弹。 */
    fun clearLogSwitchError() {
        _logSwitchState.value = _logSwitchState.value.copy(errorMessage = null)
    }

    // ── core 崩溃提醒（2026-09-04）──

    /**
     * 拉一次 `GET /api/service/crash`，若时间戳与本地"已提示过"的不同就抛给 UI 弹窗。
     *
     * 调用时机：MainActivity 首帧 + 每次重新连上设备。**失败一律静默** —— 这只是个提醒，
     * 老 core 没有这个端点（404）也走这条路，不该给用户报错。
     */
    fun loadCoreCrashNotice() {
        scope.launch {
            val report = try {
                api.getCoreCrashReport()
            } catch (e: Exception) {
                DebugLog.d("ToolsModule") { "读取 core 崩溃信息失败（忽略）：${e.message}" }
                return@launch
            }
            if (!report.crashed || report.timestamp <= 0L) return@launch
            val prefs = AppPreferences(appContext)
            if (prefs.coreCrashShownAt == report.timestamp) return@launch
            _coreCrashNotice.value = CoreCrashNotice(
                timestamp = report.timestamp,
                summary = report.summary,
                file = report.file
            )
        }
    }

    /** 弹窗已展示（或用户关闭）→ 记下时间戳，同一次崩溃不再提示。 */
    fun markCoreCrashNoticeShown() {
        val notice = _coreCrashNotice.value ?: return
        AppPreferences(appContext).coreCrashShownAt = notice.timestamp
        _coreCrashNotice.value = null
    }

    /**
     * 通用下发：PUT → 校验被采纳 → 落本地 + 更新镜像。
     *
     * [isSaving] 期间直接忽略新的点击：连点会产出多个并发 PUT，返回顺序不保证，
     * 最后一个到达的响应可能把镜像写成较早那次的值。
     */
    private fun pushLogSwitch(
        field: String,
        value: Boolean,
        applyLocal: (AppPreferences, Boolean) -> Unit,
        mirror: (LogSwitchState, Boolean) -> LogSwitchState
    ) = pushLogSwitches(
        fields = mapOf(field to value),
        applyLocal = { prefs -> applyLocal(prefs, value) },
        mirror = { s -> mirror(s, value) }
    )

    /**
     * 多字段版：一个 PUT 带多个开关。
     *
     * 需要它是因为「打开总开关时连带打开详细」不能拆成两次调用 —— [isSaving] 会把第二次直接吞掉。
     * 只要有任一字段未被 core 采纳就整体算失败，不做部分成功（本地与 core 半同步比整体失败更难查）。
     */
    private fun pushLogSwitches(
        fields: Map<String, Boolean>,
        applyLocal: (AppPreferences) -> Unit,
        mirror: (LogSwitchState) -> LogSwitchState
    ) {
        if (_logSwitchState.value.isSaving) return
        _logSwitchState.value = _logSwitchState.value.copy(isSaving = true, errorMessage = null)
        scope.launch {
            try {
                val resp = api.updateConfig(fields)
                val missing = fields.keys.firstOrNull { it !in resp.updated_fields }
                if (missing != null) {
                    val reason = resp.rejected_fields.firstOrNull { it.field == missing }?.reason
                    error(reason ?: "设备未接受该设置（$missing）")
                }
                applyLocal(AppPreferences(appContext))
                _logSwitchState.value = mirror(_logSwitchState.value)
                    .copy(loaded = true, isSaving = false, errorMessage = null)
            } catch (e: Exception) {
                DebugLog.w("Tools", "下发日志开关 $fields 失败: ${e.message}")
                _logSwitchState.value = _logSwitchState.value.copy(
                    isSaving = false,
                    errorMessage = "设置未生效（设备未收到）：${e.message}"
                )
            }
        }
    }

    /**
     * 日志总开关 `log_enabled`：关闭后 core 连缓冲/文件/logcat 都不写。
     *
     * **打开时连带打开「详细」（`debug_mode`）**：总闸只放开 WARN/ERROR，DEBUG/INFO 还要过
     * `DebugLog.verbose` / `AppLogger` 的详细闸门。一次正常会话里可能一条 WARN/ERROR 都不产生，
     * 于是用户「开了总开关，日志页还是空的」—— 表现和开关坏了一样。
     * 打开即记全量，用户嫌吵可以再单独关掉「详细」。关闭总开关时不动 `debug_mode`，
     * 免得下次打开把用户手动关掉的详细又翻回来。
     */
    fun syncLogEnabled(enabled: Boolean) {
        val alsoVerbose = enabled && !_logSwitchState.value.debugMode
        pushLogSwitches(
            fields = buildMap {
                put("log_enabled", enabled)
                if (alsoVerbose) put("debug_mode", true)
            },
            applyLocal = { prefs ->
                prefs.logEnabled = enabled
                if (alsoVerbose) prefs.debugMode = true
                // 关闸后清掉 app 侧内存缓冲：留着只会让日志页显示一批"已经不再更新"的旧行
                if (!enabled) AppLogBuffer.clear()
            },
            mirror = { s ->
                s.copy(logEnabled = enabled, debugMode = if (alsoVerbose) true else s.debugMode)
            }
        )
    }

    /** app 端子开关 `app_log_enabled`：真源放 core 便于 web 一起控制、多机一致。 */
    fun syncAppLogEnabled(enabled: Boolean) = pushLogSwitch(
        field = "app_log_enabled",
        value = enabled,
        applyLocal = { prefs, v ->
            prefs.appLogEnabled = v
            if (!v) AppLogBuffer.clear()
        },
        mirror = { s, v -> s.copy(appLogEnabled = v) }
    )

    /** core 端子开关 `core_log_enabled`：core 常驻写盘，这是控制后端日志体积的那一个。 */
    fun syncCoreLogEnabled(enabled: Boolean) = pushLogSwitch(
        field = "core_log_enabled",
        value = enabled,
        applyLocal = { prefs, v -> prefs.coreLogEnabled = v },
        mirror = { s, v -> s.copy(coreLogEnabled = v) }
    )

    /** 详细级别 `debug_mode`：控制两端 DEBUG/INFO 是否进缓冲与磁盘（WARN/ERROR 不受影响）。 */
    fun syncDebugMode(enabled: Boolean) = pushLogSwitch(
        field = "debug_mode",
        value = enabled,
        applyLocal = { prefs, v -> prefs.debugMode = v },
        mirror = { s, v -> s.copy(debugMode = v) }
    )

    // ── Traffic Management ──

    /**
     * 最近一次流量限额**成功读取**的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 单调时钟的理由见 `DataFreshness.kt`（`currentTimeMillis` 可被用户改）。
     */
    @Volatile private var trafficLimitSuccessElapsed: Long = 0L

    /**
     * 读流量限额配置（`getTrafficLimit`）。首页 hero 卡的「本月流量」与「工具-流量管理」同源。
     *
     * ## 为什么要有 [force]（2026-09-05 掉帧治理）
     * 本方法的调用点包含 `DashboardScreen` 那条以 `pageForeground` 为 key 的 `LaunchedEffect`
     * —— 每次横滑回到首页都会再进来一次，于是 settle 后必然多一次 REST 回包整页写，
     * 落在胶囊归位的可见运动窗口里（就是"顿一下"）。而限额是**用户设定的配置**，
     * 除了本 App 自己改、或 core 推 `device:traffic-limit`，它根本不会变 ——
     * 前台化重拉纯属白付重组。
     *
     * 所以默认走新鲜度闸门（[isForegroundDataFresh]，窗口取
     * [FOREGROUND_REFRESH_INTERVAL_MS]，与首页轮询周期同源；对一个配置项来说这已经很保守）。
     * **写后回读**（`saveDataLimit` / `calibrateFlow`）和 **core 推送**（`smartRefresh`）
     * 必须传 `force = true`：那三条路径的前提就是"值刚变了"，吃缓存会显示旧值。
     *
     * ## loading 态也只在没数据时写
     * 骨架屏判据是 `state.isLoading && cfg == null`（`TrafficManagementScreen`）——
     * 有配置时把 `isLoading` 翻成 true 没有任何 UI 效果，只是白付一次重组。
     * 首次加载（`limitConfig == null`）的骨架屏反馈完整保留。
     */
    fun loadTrafficLimit(force: Boolean = false) {
        if (!force &&
            _trafficManagementState.value.limitConfig != null &&
            isForegroundDataFresh(trafficLimitSuccessElapsed, SystemClock.elapsedRealtime())
        ) {
            return
        }
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.let { s ->
                if (s.limitConfig == null) {
                    s.copy(isLoading = true, errorMessage = null, successMessage = null)
                } else {
                    // 结构相等时 MutableStateFlow 不发射（TrafficManagementState 是 data class），
                    // 所以"已有数据 + 无错误/提示"这条常见路径在这里是零重组。
                    s.copy(errorMessage = null, successMessage = null)
                }
            }
            try {
                val result = api.getTrafficLimit()
                // 检查响应中是否包含错误字段
                if (result.error != null) {
                    _trafficManagementState.value = _trafficManagementState.value.copy(
                        isLoading = false,
                        errorMessage = result.error ?: "查询失败"
                    )
                    return@launch
                }
                val config = result
                _trafficManagementState.value = _trafficManagementState.value.copy(limitConfig = config, isLoading = false)
                // 只在成功落地后记新鲜度基准，失败不记（否则一次失败会把后续重拉全跳过）
                trafficLimitSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                _trafficManagementState.value = _trafficManagementState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    /**
     * 保存流量限额。
     *
     * 发结构化参数（数值 + 单位）；设备侧的 `"470_1024"` 复合串由 core 拼，
     * 客户端不再拼串也不再解析（core 2.8）。
     *
     * @param limitUnit `"MB"` / `"GB"` / `"TB"`
     * @param autoOffEnabled 到达告警阈值自动关闭移动数据；null = 不改（core 保留原值）
     * @param autoOffRestore 流量清零后自动重新打开；null = 不改
     */
    fun saveDataLimit(enabled: Boolean, limitValue: String, limitUnit: String,
                      alertPercent: String, autoClear: Boolean, clearDate: String,
                      autoOffEnabled: Boolean? = null, autoOffRestore: Boolean? = null) {
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            try {
                val body = mutableMapOf<String, Any>(
                    "enabled" to enabled, "limit_value" to limitValue, "limit_unit" to limitUnit,
                    "alert_percent" to alertPercent, "auto_clear" to autoClear, "clear_date" to clearDate)
                // 只在用户确实改过时才发这两个键 —— core 收不到就保持原值，于是
                // 「启用流量限额」这类只带设备参数的调用不会顺手把自动关网关掉。
                autoOffEnabled?.let { body["auto_off_enabled"] = it }
                autoOffRestore?.let { body["auto_off_restore"] = it }
                val resp = api.setDataLimit(body)
                _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false,
                    successMessage = if (resp.success) "设置已保存" else null,
                    errorMessage = if (!resp.success) "保存失败" else null)
                if (resp.success) {
                    // 写后回读必须绕过新鲜度闸门：值刚被本 App 改过，吃缓存会显示旧值
                    loadTrafficLimit(force = true)
                }
            } catch (e: Exception) { _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false, errorMessage = "保存失败: ${e.message}") }
        }
    }

    /**
     * 流量校准。core 契约入参为 target（校准对象，如 "data"）+ value（校准值），
     * 旧入参 way/data/time core 仍兼容但已打 WARN，下一版会删。
     */
    fun calibrateFlow(target: String, value: String) {
        scope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            try {
                val resp = api.calibrateFlow(mapOf("target" to target, "value" to value))
                _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false,
                    successMessage = if (resp.success) "校准成功" else null,
                    errorMessage = if (!resp.success) "校准失败" else null)
                if (resp.success) loadTrafficLimit(force = true)
            } catch (e: Exception) { _trafficManagementState.value = _trafficManagementState.value.copy(isSaving = false, errorMessage = "校准失败: ${e.message}") }
        }
    }

    fun clearTrafficMessage() {
        _trafficManagementState.value = _trafficManagementState.value.copy(errorMessage = null, successMessage = null)
    }

    // ── Config Sync ──
    /**
     * 把 goform 网关配置推给 core。
     *
     * [password] 为 null/空白时**不发送该字段** —— core 的 `PUT /api/config` 对缺失键是
     * `?: return` 跳过，所以不传就等于「保持 core 现有密码不变」。这样本地就不需要留明文副本。
     */
    fun syncGatewayConfig(ip: String, port: Int = 8080, password: String? = null) {
        scope.launch {
            try {
                DebugLog.d("Config", "syncing goform: ip=$ip port=$port pw=${if (password.isNullOrBlank()) "(unchanged)" else "(new)"}")
                val body = buildMap<String, Any> {
                    put("goform_ip", ip)
                    put("goform_port", port)
                    if (!password.isNullOrBlank()) put("goform_password", password)
                }
                val res = api.updateConfig(body)
                // C03：core 不再静默丢弃被拒字段，落日志便于定位"看着保存成功但没生效"
                if (res.rejected_fields.isNotEmpty()) {
                    DebugLog.w("Config", "goform 配置部分未生效: " +
                        res.rejected_fields.joinToString("; ") { it.describe() })
                }
                if (!password.isNullOrBlank() && res.rejected_fields.none { it.field == "goform_password" }) {
                    AppPreferences(appContext).goformPasswordSet = true
                }
            } catch (e: Exception) { DebugLog.w("Config", "syncGatewayConfig failed", e) }
        }
    }

    /** 清空指定 Tab 的控制台对话（"at" 或 "shell"），保留另一 Tab，并持久化 */
    fun clearConsole(tab: String) {
        _toolsState.value = _toolsState.value.copy(
            atMessages = if (tab == "at") emptyList() else _toolsState.value.atMessages,
            shellMessages = if (tab == "shell") emptyList() else _toolsState.value.shellMessages
        )
        persistConsoleHistory()
    }

    fun clearError() { _toolsState.value = _toolsState.value.copy(errorMessage = null) }

    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        when {
            // core 明确说了"这个值变了"，必须绕过新鲜度闸门
            changedType == "device:traffic-limit" -> loadTrafficLimit(force = true)
        }
    }
}

@Serializable
private data class ConsoleHistoryPayload(
    val at: List<ConsoleMessage> = emptyList(),
    val shell: List<ConsoleMessage> = emptyList()
)