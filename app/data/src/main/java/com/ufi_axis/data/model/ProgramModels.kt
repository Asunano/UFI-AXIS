package com.ufi_axis.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 第三方程序（应用商店）模型，对应 core `ProgramRoutes` 的 `/api/programs`。
 *
 * 与「应用管理」的 APK 模型是两件事：这里是带 data/conf/run/state 的 Linux 进程，
 * 由 core 的 ProgramRuntime 拉起；字段与 web `ProgramItem` 保持同名 snake_case。
 */
@Serializable
data class ProgramItemDto(
    val id: String = "",
    val name: String = "",
    val summary: String = "",
    val homepage: String = "",
    val license: String = "",
    val available: Boolean = false,
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("download_size") val downloadSize: Long = 0,
    val installed: Boolean = false,
    @SerialName("installed_version") val installedVersion: String = "",
    @SerialName("installed_size") val installedSize: Long = 0,
    val source: String = "",
    val phase: String = "",
    val pid: Int = -1,
    @SerialName("rss_mb") val rssMb: Long = 0,
    @SerialName("uptime_ms") val uptimeMs: Long = 0,
    @SerialName("last_error") val lastError: String = "",
    @SerialName("last_exit_code") val lastExitCode: Int? = null,
    val desired: Boolean = false,
    @SerialName("circuit_open") val circuitOpen: Boolean = false,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("external_port") val externalPort: Int = 0,
    @SerialName("internal_port") val internalPort: Int = 0,
    @SerialName("requires_root") val requiresRoot: Boolean = false,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("has_secret") val hasSecret: Boolean = false,
    val storage: String = "internal",
    @SerialName("storage_ready") val storageReady: Boolean = false,
    @SerialName("access_mode") val accessMode: String = "direct",
    /** Core 启动后自动拉起（与当前是否在跑无关） */
    val autostart: Boolean = false,
    @SerialName("ui_path") val uiPath: String = "/",
    /** 数据目录含凭据（token / 私钥 / 口令哈希）→ 只能装内部存储。 */
    @SerialName("private_data") val privateData: Boolean = false,
    /** 是否有可配置的「内容目录」（大文件、可放公共存储/SD 卡）。 */
    @SerialName("content_enabled") val contentEnabled: Boolean = false,
    @SerialName("content_dir") val contentDir: String = "",
    @SerialName("content_bytes") val contentBytes: Long = 0,
)

/** 内容目录的一个可选落点（内部默认 / 公共存储 / SD 卡），由 core 枚举。 */
@Serializable
data class ProgramVolumeDto(
    val id: String = "",
    val label: String = "",
    /** null = 内部默认（`programs/<id>/content`） */
    val path: String? = null,
    val removable: Boolean = false,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long = 0,
)

@Serializable
data class ProgramVolumesResponse(
    val volumes: List<ProgramVolumeDto> = emptyList(),
)

@Serializable
data class ProgramListResponse(
    val programs: List<ProgramItemDto> = emptyList(),
    val count: Int = 0,
    @SerialName("native_exec") val nativeExec: Boolean = true,
    @SerialName("total_bytes") val totalBytes: Long = 0,
)

@Serializable
data class ProgramStatusDto(
    val id: String = "",
    val phase: String = "idle",
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("candidate_index") val candidateIndex: Int = 0,
    val message: String = "",
) {
    val busy: Boolean
        get() = phase in setOf("downloading", "verifying", "extracting", "installing", "initializing")
}

/** `PATCH /api/programs/{id}/settings` 的请求体（2026-11-10，与 Web 设置弹窗同源）。 */
@Serializable
data class ProgramSettingsRequest(
    @SerialName("access_mode") val accessMode: String,
    @SerialName("internal_port") val internalPort: Int,
    @SerialName("external_port") val externalPort: Int,
    val autostart: Boolean,
)

@Serializable
data class ProgramOpenDto(
    val direct: Boolean = false,
    val scheme: String = "http",
    val port: Int = 0,
    val path: String = "/",
    @SerialName("expires_in") val expiresIn: Int = 0,
    /**
     * 经后台转发时的一次性入口（`/apps/{id}/enter?t=...`，60 秒有效）。
     * App 用系统浏览器打开它：core 种下 Gateway cookie 后 302 到程序端口。
     */
    @SerialName("enter_path") val enterPath: String = "",
)

@Serializable
data class ProgramLogsDto(
    val id: String = "",
    val stdout: String = "",
    val stderr: String = "",
)

@Serializable
data class ProgramSecretDto(
    val id: String = "",
    val secret: String = "",
)
