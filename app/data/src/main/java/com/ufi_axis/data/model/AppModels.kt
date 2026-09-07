package com.ufi_axis.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppListResponse(
    val apps: List<AppItem>,
    val count: Int,
    val root: Boolean
)

@Serializable
data class AppItem(
    val packageName: String,
    val apkPath: String = "",
    val isSystem: Boolean = false,
    val versionName: String = "",
    val isEnabled: Boolean = true,
    val isFrozen: Boolean = false,
    val iconBase64: String = ""
)

@Serializable
data class AppDetailResponse(
    val packageName: String,
    val applicationLabel: String = "",
    val versionName: String = "",
    val versionCode: String = "0",
    val firstInstallTime: String = "",
    val lastUpdateTime: String = "",
    val installer: String = "",
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true,
    val apkPath: String = "",
    val iconBase64: String = ""
)

@Serializable
data class AppActionRequest(
    val packageName: String
)

@Serializable
data class AppActionResponse(
    val success: Boolean,
    val action: String? = null,
    val packageName: String? = null
)

@Serializable
data class AppInstallRequest(
    val path: String
)

@Serializable
data class AppInstallUrlRequest(
    val url: String
)

@Serializable
data class AppInstallResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class AppPermissionRequest(
    val packageName: String,
    val permission: String,
    val grant: Boolean = true
)

@Serializable
data class AppPermissionResponse(
    val success: Boolean,
    val grant: Boolean
)

/**
 * `POST /api/apps/grant-all-permissions` 的响应。
 *
 * 成功：200 + `{"success": true, "message": "已授予 12 项权限"}`（message 是给用户看的摘要）。
 * 失败：**HTTP 500**（Retrofit 抛 HttpException），message 是 **shell 原始报错**，
 * 不适合直接当提示文案 —— UI 要自己兜一句人话。
 * `packageName` 为空串时 core 回 400。
 */
@Serializable
data class AppGrantAllResponse(
    val success: Boolean = false,
    val message: String = ""
)