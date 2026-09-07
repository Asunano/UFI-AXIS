package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogInfoRow
import com.ufi_axis.ui.components.common.UfiDialogSectionTitle
import com.ufi_axis.ui.components.common.UfiDialogWarning
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 双域存储权限引导对话框（ticket T12）。
 *
 * 文件传输（下载到本机 / 上传到设备）需要**两个独立域**都授予「所有文件访问权限」：
 *  - 设备端（Core，跑在随身 WiFi 设备上）：授权在设备上的 Core App 内完成，
 *    手机**不能**跨设备代为跳转系统设置 —— 仅说明文案 + 「重试」重新查询状态。
 *  - 手机端（本应用，跑在手机上）：授权在手机自身设置页完成 —— 提供「前往手机设置」按钮，
 *    经 [android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION] 打开**本机**
 *    设置页（合法，同一台设备）。
 *
 * ⚠️ 跨设备红线：本对话框**绝不**用 startActivity 去打开 Core 设备端的系统设置页；
 * 设备端授权只能由用户在设备上的 Core App 内手动完成。任何「去设备端设置」的跳转都是错误的。
 *
 * 设计系统：走 [com.ufi_axis.ui.components.common.UfiCustomDialog]（showCloseButton=false），
 * content 内 [com.ufi_axis.ui.components.common.UfiDialogBody] 作兄弟节点接
 * [com.ufi_axis.ui.components.common.UfiDialogActions]（双按钮）；颜色走 LocalResolvedPalette，
 * 禁 tertiary；不裸用 AlertDialog。
 *
 * @param visible 是否显示
 * @param coreGranted 设备端（Core）是否已授权
 * @param phoneGranted 手机端（本应用）是否已授权
 * @param onRetry 点击「重试」——重新查询双域授权状态（checkStorageAccess）
 * @param onOpenPhoneSettings 点击「前往手机设置」——打开手机自身存储权限设置页
 * @param onDismiss 关闭（稍后）
 */
@Composable
fun StoragePermissionDialog(
    visible: Boolean,
    coreGranted: Boolean,
    phoneGranted: Boolean,
    onRetry: () -> Unit,
    onOpenPhoneSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val phoneMissing = !phoneGranted

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "存储权限",
        icon = rememberVectorPainter(Icons.Filled.Storage),
        showCloseButton = false
    ) {
        UfiDialogBody {
            Text(
                text = "文件传输（下载到本机 / 上传到设备）需要设备端与手机端都授予「所有文件访问权限」。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            UfiDialogSectionTitle(title = "设备端（随身 WiFi 设备）")
            UfiDialogInfoRow(label = "授权状态", value = if (coreGranted) "已授权" else "未授权")
            Text(
                text = "需在设备上的 Core App 内授予。手机无法跨设备代开其系统设置，请到设备上手动授权后点「重试」。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogSectionTitle(title = "手机端（本应用）")
            UfiDialogInfoRow(label = "授权状态", value = if (phoneGranted) "已授权" else "未授权")
            Text(
                text = "下载到本机需要手机自身的「所有文件访问权限」。可点下方「前往手机设置」在本机设置页授权。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            if (!coreGranted || !phoneGranted) {
                UfiDialogWarning(
                    "两端均授权后才能传输文件。手机端可在本机设置页授权；设备端需在随身 WiFi 设备上的 Core App 内授权。"
                )
            }
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = if (phoneMissing) onOpenPhoneSettings else onRetry,
            confirmText = if (phoneMissing) "前往手机设置" else "重试",
            dismissText = "稍后"
        )
    }
}
