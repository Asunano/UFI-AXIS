package com.ufi_axis.ui.resources

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogNote
import com.ufi_axis.ui.components.common.UfiDialogWarning
import com.ufi_axis.ui.components.common.UfiDialogSectionTitle
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiInfoRow
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 资源中心 —— **通用行卡**（2026-10-14）。
 *
 * 只读 [ResourceDescriptor] 的字段与可选回调，**一个资源 id 都不认识**。
 * 本文件里不存在（也不允许存在）`if (descriptor.id == "frpc")` 这类分支：
 *  · 画不画「检查更新」→ 看 [ResourceDescriptor.refresh] 是否为 null；
 *  · 画不画「下载安装 / 更新 / 在线更新」→ 看 [ResourceDescriptor.install] 与
 *    [ResourceItem.actionLabel]（**按钮存不存在、叫什么**由描述符决定，卡片不组合
 *    `available` / `installed` / `updateAvailable`）；
 *  · 画不画「卸载」→ 看 [ResourceDescriptor.uninstall] 与 [ResourceItem.uninstallable]；
 *  · 画不画「本地上传」→ 看 [ResourceDescriptor.supportsUpload]（accept 与 upload 都齐才画）；
 *  · 画不画「回滚 / 恢复内置」→ 看 [ResourceDescriptor.rollback] / [ResourceDescriptor.clear]；
 *  · 额外只读信息行 → [ResourceDescriptor.extraRows]；
 *  · 状态中文与色调 → [ResourceDescriptor.stateMetaOf]。
 * 所以"加一个新资源"不需要动本文件一个字。
 *
 * ## 版式
 * 一张 [UfiSettingsRowCard] 里：标题行 → 只读信息行 → 条目行 → 动作区 → 进度区。
 * 复用 `UfiSettingsItem` / `UfiInfoRow` / `UfiButton` / `UfiCompactProgressBar` / `UfiDivider`，
 * **不手搓**任何几何与配色。
 *
 * ## 破坏性动作一律二次确认
 * 卸载 / 回滚 / 恢复内置都走公共 [UfiConfirmDialog]（destructive）：它们删的是设备上的文件，
 * 而且回滚的备份只有一份（用掉就没了），必须先说清楚。
 */
@Composable
fun ResourceCard(
    descriptor: ResourceDescriptor,
    snapshot: ResourceSnapshot?,
    task: ResourceTaskController.Task?,
    onRefresh: () -> Unit,
    onInstall: (ResourceItem) -> Unit,
    onUpload: (File) -> Unit,
    onUninstall: (String) -> Unit,
    onRollback: () -> Unit,
    onClear: () -> Unit,
    onDismissTask: () -> Unit,
    /**
     * 是否在卡内渲染任务进度区（默认 true）。
     *
     * 资源中心的「检查更新」是只读、约 6 秒的动作，页面改用 Toast 反馈：传 false 可避免
     * 弹窗内容被临时撑高/收起。安装 / 上传 / 卸载 / 回滚 / 恢复内置仍走卡内进度。
     */
    showTaskProgress: Boolean = true,
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current

    // 上传前的"拷贝到 cacheDir"阶段：拷的是几十 MB，期间要把按钮禁掉，否则用户会连点。
    var preparing by remember { mutableStateOf(false) }
    // 本地上传通道自己的错误（读不出文件 / 超过端点下发的上限）。放卡内而不是 Toast：
    // 它是"这次选择失败了，请再选一次"，与页面级的操作结果不是一类信息。
    var pickError by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<Confirm?>(null) }

    val busy = task?.running == true || preparing
    val items = snapshot?.items.orEmpty()
    // 单例资源的状态位挂在它唯一的那条 item 上（含义见 ResourceItem 的 KDoc）
    val primary = items.firstOrNull()

    // SAF 选择器：MIME 白名单来自描述符的 accept。空 accept 时本 lambda 不会被触发
    // （按钮压根不画），所以这里不需要再判一次。
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickError = null
        preparing = true
        // 走**任务 scope**而不是 rememberCoroutineScope：准备与上传必须活在同一条时间线上，
        // 否则用户按返回键会把拷贝掐断、后面的上传根本不会发生（见 ResourceTaskController）。
        ResourceTaskController.shared.launchOnTaskScope {
            val result = copyToCache(context, uri, descriptor.uploadLimitOf(snapshot))
            preparing = false
            result.fold(
                onSuccess = { file -> onUpload(file) },
                onFailure = { e -> pickError = e.message ?: "无法读取所选文件" }
            )
        }
    }

    UfiDialogBody {
        UfiDialogNote(descriptor.desc)

        // ── 读取失败 / 清单不可用 ──
        snapshot?.error?.let { message ->
            UfiDivider()
            UfiDialogWarning(message)
        }
        if (snapshot != null && snapshot.manifestError.isNotBlank()) {
            UfiDivider()
            UfiDialogWarning("更新源不可用：${snapshot.manifestError}")
        }

        // ── 只读信息行（版本 / 来源 / 占用…），文案由描述符给 ──
        val rows = descriptor.extraRows?.invoke(snapshot ?: ResourceSnapshot()).orEmpty()
        if (rows.isNotEmpty()) {
            UfiDivider()
            rows.forEach { (label, value) -> UfiInfoRow(label, value) }
        }

        // ── 条目行：安装 / 更新 / 卸载 ──
        if (items.isNotEmpty()) {
        UfiDialogSectionTitle("可下载条目")
            UfiDivider()
            items.forEach { item ->
                UfiSettingsItem(
                    title = item.title,
                    description = item.detail,
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 按钮存不存在、叫什么，全由描述符给的 actionLabel 决定 ——
                            // 卡片不组合 available/installed/updateAvailable（那正是"分支长在 UI 里"）
                            if (descriptor.install != null && item.actionLabel != null) {
                                UfiButton(
                                    size = UfiButtonSize.Small,
                                    text = item.actionLabel,
                                    onClick = { onInstall(item) },
                                    // busy = core 同时只跑一个安装任务
                                    enabled = !busy
                                )
                            }
                            if (descriptor.uninstall != null && item.uninstallable) {
                                UfiButton(
                                    size = UfiButtonSize.Small,
                                    text = "卸载",
                                    onClick = { pendingConfirm = Confirm.Uninstall(item.id, item.title) },
                                    enabled = !busy
                                )
                            }
                        }
                    }
                )
            }
        } else if (snapshot != null && snapshot.error == null) {
            // 清单为空且没报错：可能是 core 还在后台拉。给一句可操作的提示，而不是空白卡片。
            UfiDivider()
            NoticeText(
                if (snapshot.manifestLoading) "正在获取资源清单…" else "没读到条目信息，可点「检查更新」重试。",
                palette.textSecondary
            )
        }

        // ── 动作区：本地上传 / 回滚 / 恢复内置 ──
        val hasActions = descriptor.supportsUpload ||
            (descriptor.rollback != null && primary != null) ||
            (descriptor.clear != null && primary != null)
        if (hasActions) {
            UfiDivider()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                if (descriptor.supportsUpload) {
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = if (preparing) "正在准备文件…" else "本地上传安装",
                        onClick = { uploadLauncher.launch(descriptor.accept.toTypedArray()) },
                        enabled = !busy
                    )
                    // 上限来自端点下发的 max_upload_bytes，**不写死**：core 改常量时这里自动跟上。
                    // 拿不到时（老 core 没这个字段）不显示数字，也不做前置拦截。
                    val limit = descriptor.uploadLimitOf(snapshot)
                    if (limit != null) {
                        NoticeText("单个文件不超过 ${FormatUtils.formatBytes(limit)}（上限由设备端下发）", palette.textSecondary)
                    }
                }
                if (descriptor.rollback != null && primary != null) {
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "回滚上一版",
                        onClick = { pendingConfirm = Confirm.Rollback },
                        // 单例语义：updateAvailable = 有可切换的另一版（Web 的回滚备份）
                        enabled = primary.updateAvailable && !busy
                    )
                }
                if (descriptor.clear != null && primary != null) {
                    UfiButton(
                        variant = UfiButtonVariant.Danger,
                        text = "恢复内置版本",
                        onClick = { pendingConfirm = Confirm.Clear },
                        // 单例语义：installed = 自定义资源正在生效
                        enabled = primary.installed && !busy
                    )
                }
            }
        }

        pickError?.let { message ->
            UfiDivider()
            UfiDialogWarning(message)
        }

        // ── 进度区：只在有任务时出现（空闲态不留占位行，行高才和别的卡对齐）──
        // 「检查更新」由页面用 Toast 反馈，调用方传 showTaskProgress = false 时整段不画。
        if (showTaskProgress) {
            task?.let { t -> TaskProgress(descriptor = descriptor, task = t, onDismiss = onDismissTask) }
        }
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is Confirm.Uninstall -> UfiConfirmDialog(
                visible = true,
                title = "卸载 ${confirm.title}",
                text = "会删除设备上「${descriptor.name}」的这一份文件与元数据。" +
                    "已有配置不受影响，但在重新安装前对应功能无法启动。",
                confirmText = "卸载",
                destructive = true,
                onConfirm = {
                    pendingConfirm = null
                    onUninstall(confirm.itemId)
                },
                onDismiss = { pendingConfirm = null }
            )

            Confirm.Rollback -> UfiConfirmDialog(
                visible = true,
                title = "回滚「${descriptor.name}」",
                text = "将把设备上的「${descriptor.name}」换回上一个版本。\n\n" +
                    "备份只有一份，回滚后即被消耗，无法再回滚。",
                confirmText = "回滚",
                destructive = true,
                onConfirm = {
                    pendingConfirm = null
                    onRollback()
                },
                onDismiss = { pendingConfirm = null }
            )

            Confirm.Clear -> UfiConfirmDialog(
                visible = true,
                title = "恢复内置「${descriptor.name}」",
                text = "将删除设备上已上传的「${descriptor.name}」及其备份，改用随包分发的版本。\n\n" +
                    "删除后无法回滚到已上传版本，需要时请重新上传。",
                confirmText = "恢复内置",
                destructive = true,
                onConfirm = {
                    pendingConfirm = null
                    onClear()
                },
                onDismiss = { pendingConfirm = null }
            )
        }
    }
}

/**
 * 进度区：`(state, percent, message)` 三元组 + 由 [ResourceDescriptor.stateMetaOf] 给出的色调。
 *
 * ## 为什么进度条不是"一个百分比"
 * [ResourceTaskController.Task.percent] 在不同阶段含义不同（下发阶段 = 客户端上传百分比、
 * 轮询阶段 = core 自报百分比），所以这里：
 *  · 中文标签取 **core 状态**的翻译（`stateMetaOf`），说明"现在这一步是什么"；
 *  · 百分比数字只在**该阶段确实有字节进度**时才显示 —— 终态不显示"100%"，因为
 *    "100%"与"已完成"不是一回事（core 的 progress 在 installing 阶段就常驻 100）。
 */
@Composable
private fun TaskProgress(
    descriptor: ResourceDescriptor,
    task: ResourceTaskController.Task,
    onDismiss: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    val meta = if (task.coreState.isNotBlank()) descriptor.stateMetaOf(task.coreState) else null
    val tone = when (task.phase) {
        ResourceTaskController.Task.Phase.SUCCESS -> StateTone.SUCCESS
        ResourceTaskController.Task.Phase.FAILED -> StateTone.FAILURE
        ResourceTaskController.Task.Phase.RUNNING -> meta?.tone ?: StateTone.PROGRESS
        ResourceTaskController.Task.Phase.IDLE -> StateTone.NEUTRAL
    }
    val label = meta?.label ?: task.label
    val color = when (tone) {
        StateTone.SUCCESS -> palette.success
        StateTone.FAILURE -> palette.error
        StateTone.PROGRESS -> palette.accent
        StateTone.NEUTRAL -> palette.textSecondary
    }

    UfiDivider()
    Text(
        text = if (task.phase == ResourceTaskController.Task.Phase.RUNNING) label else "$label：${task.message}",
        style = UfiTextStyles.note,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    if (task.message.isNotBlank() && task.phase == ResourceTaskController.Task.Phase.RUNNING) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = task.message,
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (tone == StateTone.PROGRESS) {
        Spacer(Modifier.height(6.dp))
        UfiCompactProgressBar(progress = task.percent / 100f, modifier = Modifier.fillMaxWidth())
    }
    // 失败态必须能被用户收掉：它会一直挂在这张卡上（成功态 1.5s 后自己消失）。
    // 不收掉的话，卡片会长期停在一个"上一次失败"的样子，看起来像当前状态。
    if (task.phase == ResourceTaskController.Task.Phase.FAILED) {
        Spacer(Modifier.height(Spacing.Small))
        UfiButton(
            size = UfiButtonSize.Small,
            variant = UfiButtonVariant.Subtle,
            text = "知道了",
            onClick = onDismiss
        )
    }
}

/** 卡片内的一行说明 / 错误文案。 */
@Composable
private fun NoticeText(text: String, color: Color) {
    Text(
        text = text,
        style = UfiTextStyles.note,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

/** 需要二次确认的动作。 */
private sealed interface Confirm {
    data class Uninstall(val itemId: String, val title: String) : Confirm
    data object Rollback : Confirm
    data object Clear : Confirm
}

/**
 * 把 SAF 选中的文件拷进 cacheDir，**边拷边核端点下发的上限**。
 *
 * 为什么要先落盘：core 的 multipart 需要一个可重复读的 `File`（ContentResolver 给的是一次性流），
 * 而 OkHttp 至少要读两遍（先算 Content-Length 再写体）。
 *
 * 为什么在上限这件事上要"边拷边判"而不是先看 `length`：`OpenableFileDescriptor.length()` 对
 * 部分 provider（云盘 / 文档 provider）返回 -1，靠它做前置判断会漏放超限文件；边读边数
 * 是唯一可靠的写法，而且发现超限时立刻停手，不把几十 MB 白读一遍。
 *
 * 文件名固定：core 只认 multipart 的**字段名** `file`（`WebUpdateRoutes` 里判的是
 * `part.name == "file"`），从不看文件名 —— 所以这里不需要再去查 DISPLAY_NAME。
 */
private suspend fun copyToCache(context: Context, uri: Uri, limit: Long?): Result<File> =
    withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "ufi-resource-upload.bin")
            target.delete()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (limit != null && total > limit) {
                            throw IllegalStateException("文件超过 ${FormatUtils.formatBytes(limit)} 上限，已中止")
                        }
                        out.write(buf, 0, n)
                    }
                    if (total == 0L) throw IllegalStateException("所选文件为空")
                }
            }
            target
        }
    }
