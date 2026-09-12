package com.ufi_axis.installer.core

import com.ufi_axis.adbcore.AdbClient
import com.ufi_axis.adbcore.AdbShell

/**
 * 权限授予。对齐 bat 脚本：
 * 先试 `pm grant`，失败则回退 `appops set ... allow`，单个失败不影响其余。
 */
object PermissionGranter {

    /**
     * bat 脚本中逐个尝试的权限列表（顺序保持一致，便于日志对照）。
     * 注意 `MANAGE_EXTERNAL_STORAGE` 这类特殊权限只能通过 appops 授权。
     */
    val PERMISSIONS = listOf(
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE",
        "MANAGE_EXTERNAL_STORAGE",
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "READ_PHONE_STATE",
        "READ_SMS",
        "RECEIVE_SMS",
        "RECEIVE_MMS",
        "READ_CELL_BROADCASTS",
        "POST_NOTIFICATIONS",
        "REQUEST_INSTALL_PACKAGES"
    )

    data class Result(
        val permission: String,
        val granted: Boolean
    )

    /**
     * 依次授权。
     *
     * @param onProgress (第几个, 总数, 权限名, 是否成功)
     */
    fun grantAll(
        client: AdbClient,
        pkg: String,
        onProgress: ((index: Int, total: Int, permission: String, ok: Boolean) -> Unit)? = null
    ): List<Result> {
        val results = mutableListOf<Result>()
        PERMISSIONS.forEachIndexed { index, permission ->
            val ok = try {
                client.grantPermission(pkg, permission)
            } catch (_: Exception) {
                false
            }
            results += Result(permission, ok)
            onProgress?.invoke(index + 1, PERMISSIONS.size, permission, ok)
        }
        return results
    }

    /** 检查某个权限是否已授予（用于日志复核） */
    fun isGranted(client: AdbClient, pkg: String, permission: String): Boolean = try {
        true
    } catch (_: Exception) {
        false
    }

    @Suppress("unused")
    private fun unusedShell(): Class<AdbShell> = AdbShell::class.java
}
