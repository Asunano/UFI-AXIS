package com.ufi_axis.viewmodel.repository

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.AlertConfig
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

/**
 * 告警配置多端同步仓库（P2 核心）。
 *
 * ════════════ 多端同步铁律（防 ab 设备回弹） ════════════
 * 1. core 端 SharedPreferences 是唯一真源；本仓库只是「镜像」。
 * 2. **连接即拉取，绝不推送默认**：仅在用户「显式切换」开关/阈值时才 pushUpdate；
 *    严禁在初始化/连接时把本地默认 enabled=true 写回 core（那是 B 设备回弹的根因）。
 * 3. 版本守门：pushUpdate 携带 configVersion；core 拒绝陈旧写（409），
 *    调用方须 GET 最新后重试。
 * 4. WS config_changed：A 设备改完后 core 广播，在线 B 设备 applyRemote 即时覆盖本地镜像。
 *
 * 用例（ab 设备连同一 core）：
 * - A 关总开关 → pushUpdate(enabled=false, v=N) → core 存 v=N+1 并广播 config_changed。
 * - B 在线 → applyRemote(enabled=false) 即时 OFF。
 * - B 后连接 → refreshFromCore() GET 即 OFF，**不写默认**，不回弹。
 */
class AlertPrefsRepository {

    private val _config = MutableStateFlow<AlertConfig?>(null)
    val configFlow: StateFlow<AlertConfig?> = _config.asStateFlow()

    /** 当前镜像（只读快照） */
    val current: AlertConfig? get() = _config.value

    /**
     * 连接 core 时调用：拉取最新配置覆盖本地镜像。
     * 绝不在此写入任何默认值——core 返回什么就用什么。
     */
    suspend fun refreshFromCore(api: UfiAxisApi) {
        runCatching { api.getAlertConfig() }
            .onSuccess { _config.value = it }
            .onFailure { e -> DebugLog.w("AlertPrefs", "refreshFromCore failed: ${e.message}") }
    }

    /**
     * 接收 WS config_changed 推送：直接覆盖本地镜像（在线设备即时生效）。
     * 不触发任何 GET/PUT，避免回环。
     */
    fun applyRemote(config: AlertConfig) {
        _config.value = config
        DebugLog.i("AlertPrefs", "applyRemote config_changed v=${config.configVersion} enabled=${config.enabled}")
    }

    /**
     * 用户显式切换后推送：PUT 带 configVersion；遇 409（版本冲突）自动拉取最新后带新 version 重试一次。
     * 重试仍失败则保留本地镜像不变（不写默认），由 UI 提示「配置已变更，请重试」。
     *
     * @return true=保存成功 false=冲突重试后仍失败（UI 应提示用户重新操作）
     */
    suspend fun pushUpdate(api: UfiAxisApi, config: AlertConfig): Boolean {
        // 用本地镜像的 version 作为基准；若本地为空则用入参 version（首次）
        val base = _config.value
        val baseVersion = base?.configVersion ?: config.configVersion
        val payload = config.copy(configVersion = baseVersion)

        val first = runCatching { api.updateAlertConfig(payload) }
        if (first.isSuccess) {
            _config.value = first.getOrNull()?.config
            return true
        }
        // 409 或其他 HTTP 错误 → 拉取最新后重试一次
        val ex = first.exceptionOrNull()
        if (ex is HttpException && ex.code() == 409) {
            DebugLog.w("AlertPrefs", "pushUpdate 409, refetch and retry once")
            val latest = runCatching { api.getAlertConfig() }.getOrNull() ?: return false
            _config.value = latest
            // T13 复核：重试基准必须是**服务端最新配置**，只把"用户这次真正改动的字段"叠上去。
            // 旧写法从 config（用户本地基线）出发，会把别端并发改动的阈值一起覆盖回旧值。
            val retryPayload = applyUserDiff(base, config, latest)
            val second = runCatching { api.updateAlertConfig(retryPayload) }
            if (second.isSuccess) {
                _config.value = second.getOrNull()?.config
                return true
            }
            DebugLog.w("AlertPrefs", "pushUpdate retry failed: ${second.exceptionOrNull()?.message}")
            return false
        }
        DebugLog.w("AlertPrefs", "pushUpdate failed: ${ex?.message}")
        return false
    }

    /**
     * 计算"用户本次改动"并叠加到服务端最新配置上（409 重试用）。
     *
     * 用 JSON 逐键比对而不是手写 14 个字段的 copy()：新增字段时不会漏，
     * 也不会像逐字段写法那样把"没改的字段"当成用户意图覆盖回去。
     * [base] 为空（镜像未加载）时退化为直接用用户对象 —— 这种情况已由
     * `ToolsModule.updateAlertConfig` 的 T13 守卫拦在门外，此处仅作兜底。
     */
    private fun applyUserDiff(base: AlertConfig?, intent: AlertConfig, latest: AlertConfig): AlertConfig {
        if (base == null) return intent.copy(configVersion = latest.configVersion)
        val ser = AlertConfig.serializer()
        val baseJson = AppJson.encodeToJsonElement(ser, base).jsonObject
        val intentJson = AppJson.encodeToJsonElement(ser, intent).jsonObject
        val latestJson = AppJson.encodeToJsonElement(ser, latest).jsonObject
        val changed = intentJson.filter { (k, v) -> k != "configVersion" && baseJson[k] != v }
        return AppJson.decodeFromJsonElement(ser, JsonObject(latestJson + changed))
    }
}
