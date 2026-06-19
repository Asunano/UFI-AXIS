package com.ufi_axis.data.model

data class AppListResponse(
    val apps: List<AppItem>,
    val count: Int,
    val root: Boolean
)

data class AppItem(
    val packageName: String,
    val apkPath: String = "",
    val isSystem: Boolean = false,
    val versionName: String = "",
    val isEnabled: Boolean = true,
    val isFrozen: Boolean = false
)

data class AppDetailResponse(
    val packageName: String,
    val versionName: String = "",
    val versionCode: String = "0",
    val firstInstallTime: String = "",
    val lastUpdateTime: String = "",
    val installer: String = "",
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true,
    val apkPath: String = ""
)

data class AppActionRequest(
    val packageName: String
)

data class AppActionResponse(
    val success: Boolean,
    val action: String? = null,
    val packageName: String? = null
)

data class AppInstallRequest(
    val path: String
)

data class AppInstallUrlRequest(
    val url: String
)

data class AppInstallResponse(
    val success: Boolean,
    val message: String
)

data class AppPermissionRequest(
    val packageName: String,
    val permission: String,
    val grant: Boolean = true
)

data class AppPermissionResponse(
    val success: Boolean,
    val grant: Boolean
)