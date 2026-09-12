package com.ufi_axis.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.BackupInfoResponse
import com.ufi_axis.data.model.BackupPreviewResponse
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「备份与恢复」设置二级页。入口在「关于设备」。
 *
 * 备份包的组装、加解密与段的取舍全部由 core 完成（`/api/backup`）。本页只负责三件事：
 * 提交手机端自身的偏好、把备份包写入本地存储或从本地读取、恢复完成后把回传的手机端偏好落地。
 *
 * ## 恢复前必须先预览
 * 恢复是覆盖式写入，两个方向的失败代价不对等：导出失败只是没有拿到文件，恢复出错则是配置被改。
 * 因此流程固定为「选择文件 → 预览清单 → 确认 → 写入」。`/api/backup/preview` 只读不写，
 * 加密包在预览阶段即可发现口令错误，不会在写入时才失败。
 *
 * ## 明文导出
 * 加密为默认值，但允许关闭，便于在其他工具中查看备份内容。关闭时弹窗给出的是风险提示块
 * （[UfiDialogWarning]）而非普通说明：明文包中包含通知渠道密码等凭据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // observeExternal = false：本页仅在恢复完成后写入一次，无需监听外部变更（同 AppearanceSettingsScreen）
    val themeManager = remember { ThemeManager(context, observeExternal = false) }
    val prefs = remember { AppPreferences(context) }

    var info by remember { mutableStateOf<BackupInfoResponse?>(null) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // ── 导出 ──
    var exportDialogOpen by remember { mutableStateOf(false) }
    var encryptExport by remember { mutableStateOf(true) }
    var exportPass by remember { mutableStateOf("") }
    var exportPassAgain by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }

    // ── 恢复 ──
    var importDialogOpen by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPass by remember { mutableStateOf("") }
    var importReplace by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupPreviewResponse?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { info = viewModel.backup.loadInfo() }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 上限取设备端上报值；拿不到时用与 core 一致的兜底值，避免"读不到 /info 就不设限"。
            val limit = (info?.max_upload_bytes ?: 0L).takeIf { it > 0 } ?: DEFAULT_IMPORT_MAX_BYTES
            // 先查大小、再读内容。**不能先 readBytes() 再判大小** —— 那样在判断之前整个文件
            // 就已经进了内存：用户误选一个 4GB 的视频，判大小那行根本来不及执行就 OOM 了。
            // 不按扩展名拦截（文件管理器给的 MIME/名字都不可信），只靠大小与后续解析把关。
            val declaredSize = withContext(Dispatchers.IO) { queryOpenableSize(context, uri) }
            if (declaredSize != null && declaredSize > limit) {
                toastMessage = ToastMessage("文件超过 ${limit / 1024 / 1024}MB 上限", ToastType.ERROR)
                return@launch
            }
            // 读取本身也带计数兜底：SIZE 可能缺失，也可能被 provider 谎报。
            val bytes = withContext(Dispatchers.IO) {
                runCatching { readBounded(context, uri, limit) }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                toastMessage = ToastMessage(
                    "无法读取所选文件，或文件超过 ${limit / 1024 / 1024}MB 上限",
                    ToastType.ERROR
                )
                return@launch
            }
            importBytes = bytes
            importPass = ""
            importReplace = false
            preview = null
            importError = null
            importDialogOpen = true
        }
    }

    UfiScreenScaffold(title = "备份与恢复", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // 仅隧道来源提示，局域网不提示：局域网虽然同为明文 HTTP，范围限于用户自己的网段。
            // 这里只提示、不拦截 —— 远程恢复是正当用法。
            if (info?.origin == "tunnel") {
                UfiNoticeCard(
                    severity = UfiNoticeSeverity.WARNING,
                    title = "当前是远程连接",
                    message = "备份内容与口令会经隧道传输。若隧道未启用 HTTPS，" +
                        "这些内容在链路上是明文的，建议改用局域网操作。"
                )
            }

            if (info == null) {
                UfiNoticeCard(
                    message = "读不到设备端备份信息：服务未运行或版本过旧，导出与恢复暂不可用。"
                )
            }

            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.CloudUpload,
                    title = "导出备份包",
                    description = "设备端配置、通知渠道、定时任务与本机外观偏好，导出到 $BACKUP_DIR_LABEL",
                    trailing = {
                        UfiButton(
                            size = UfiButtonSize.Small,
                            text = "导出",
                            enabled = info != null,
                            onClick = {
                                exportPass = ""
                                exportPassAgain = ""
                                exportDialogOpen = true
                            }
                        )
                    }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Restore,
                    title = "从备份恢复",
                    description = "选择 .ufibak 文件，先查看清单再决定是否写入",
                    trailing = {
                        UfiButton(
                            size = UfiButtonSize.Small,
                            variant = UfiButtonVariant.Subtle,
                            text = "选择文件",
                            enabled = info != null,
                            onClick = { pickLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        // ── 导出弹窗 ──
        val minLen = info?.min_passphrase_length ?: 12
        val passTooShort = encryptExport && exportPass.length < minLen
        val passMismatch = encryptExport && exportPassAgain != exportPass
        UfiCustomDialog(
            visible = exportDialogOpen,
            onDismiss = { if (!exporting) exportDialogOpen = false },
            title = "导出备份包",
            confirmButton = {
                UfiButton(
                    text = "导出",
                    loading = exporting,
                    enabled = !exporting && !passTooShort && !passMismatch,
                    onClick = {
                        exporting = true
                        scope.launch {
                            val section = collectAppSection(themeManager, prefs)
                            val result = viewModel.backup.export(
                                encrypted = encryptExport,
                                passphrase = exportPass,
                                clientApp = section
                            )
                            result.onSuccess { bytes ->
                                val name = backupFileName()
                                val saved = withContext(Dispatchers.IO) {
                                    saveToPublicBackupDir(context, name, bytes)
                                }
                                exporting = false
                                exportDialogOpen = false
                                toastMessage = if (saved) {
                                    ToastMessage(
                                        "已导出到 $BACKUP_DIR_LABEL/$name",
                                        ToastType.SUCCESS,
                                        durationMs = 5000L
                                    )
                                } else {
                                    ToastMessage("写入存储失败，请检查存储空间", ToastType.ERROR)
                                }
                            }.onFailure {
                                exporting = false
                                toastMessage = ToastMessage(it.message ?: "导出失败", ToastType.ERROR)
                            }
                        }
                    }
                )
            },
            dismissButton = {
                UfiButton(
                    text = "取消",
                    variant = UfiButtonVariant.Secondary,
                    enabled = !exporting,
                    onClick = { exportDialogOpen = false }
                )
            }
        ) {
            UfiDialogBody {
                UfiDialogSwitchField(
                    label = "加密备份内容",
                    checked = encryptExport,
                    enabled = !exporting,
                    onCheckedChange = { encryptExport = it }
                )
                if (encryptExport) {
                    UfiDialogPasswordField(
                        label = "口令（至少 $minLen 位）",
                        value = exportPass,
                        onValueChange = { exportPass = it },
                        enabled = !exporting
                    )
                    UfiDialogPasswordField(
                        label = "确认口令",
                        value = exportPassAgain,
                        onValueChange = { exportPassAgain = it },
                        enabled = !exporting
                    )
                    if (passMismatch && exportPassAgain.isNotEmpty()) {
                        UfiDialogWarning("两次输入的口令不一致")
                    }
                    UfiDialogNote("口令不会被保存，遗忘后无法恢复备份内容。")
                } else {
                    UfiDialogWarning(
                        "不加密时，包内的通知渠道密码、隧道凭据等均为明文，" +
                            "获得该文件的任何人都可直接读取。"
                    )
                }
                UfiDialogNote("导出位置：$BACKUP_DIR_LABEL")
            }
        }

        // ── 恢复弹窗 ──
        // 必须用 UfiScrollableDialog：内容是「段清单」（随包变化，可达十余行）+ 恢复方式，
        // 高度会超过屏幕 82%。UfiCustomDialog 不带滚动，超出部分会被直接裁掉 ——
        // 用户既看不到完整清单，也可能够不到底部的「开始恢复」，属于会把功能卡死的裁切。
        val pv = preview
        UfiScrollableDialog(
            visible = importDialogOpen,
            onDismiss = { if (!importBusy) importDialogOpen = false },
            title = "从备份恢复",
            confirmButton = {
                UfiButton(
                    // 预览通过前只能执行检查，写入必然发生在清单确认之后
                    text = if (pv == null) "检查备份包" else "开始恢复",
                    loading = importBusy,
                    enabled = !importBusy,
                    onClick = {
                        val bytes = importBytes ?: return@UfiButton
                        importBusy = true
                        importError = null
                        scope.launch {
                            if (pv == null) {
                                viewModel.backup.preview(bytes, importPass)
                                    .onSuccess { preview = it }
                                    .onFailure { importError = it.message }
                                importBusy = false
                                return@launch
                            }
                            viewModel.backup.import(bytes, importReplace, importPass)
                                .onSuccess { report ->
                                    report.client_app?.let { applyAppSection(it, themeManager, prefs) }
                                    importDialogOpen = false
                                    importBytes = null
                                    preview = null
                                    toastMessage = ToastMessage(
                                        buildString {
                                            append("已恢复 ${report.applied.size} 段")
                                            if (report.failed.isNotEmpty()) {
                                                append("，${report.failed.size} 段失败")
                                            }
                                            if (report.needs_restart) append("；部分设置需重启服务后生效")
                                        },
                                        if (report.failed.isEmpty()) ToastType.SUCCESS else ToastType.WARNING,
                                        durationMs = 5000L
                                    )
                                }
                                .onFailure { importError = it.message }
                            importBusy = false
                        }
                    }
                )
            },
            dismissButton = {
                UfiButton(
                    text = "取消",
                    variant = UfiButtonVariant.Secondary,
                    enabled = !importBusy,
                    onClick = { importDialogOpen = false }
                )
            }
        ) {
            UfiDialogBody {
                if (pv == null) {
                    UfiDialogNote("先校验备份包是否可读并列出其中内容，此步骤不会修改任何配置。")
                    UfiDialogPasswordField(
                        label = "口令（未加密的备份留空）",
                        value = importPass,
                        onValueChange = { importPass = it },
                        enabled = !importBusy
                    )
                } else {
                    UfiDialogInfoRow("导出时间", formatBackupTime(pv.created_at))
                    UfiDialogInfoRow("加密", if (pv.encrypted) "是" else "否")
                    UfiDialogSectionTitle("包含内容")
                    pv.sections.forEach { s ->
                        UfiDialogInfoRow(
                            label = s.label,
                            value = "${s.items} 项" + if (s.sensitive_items > 0) " · 含敏感信息" else ""
                        )
                    }
                    // chip 只承载档位名，说明另起一行：整句文案会把 chip 轨道撑满并折行，
                    // 与全站 chip（15/30/60、自动/浅色/深色）的形态不一致。
                    UfiDialogChipSelector(
                        label = "恢复方式",
                        options = listOf("merge" to "合并", "replace" to "替换"),
                        selectedValue = if (importReplace) "replace" else "merge",
                        onSelect = { importReplace = it == "replace" }
                    )
                    if (importReplace) {
                        UfiDialogWarning("替换会清除本机上备份中不存在的项，例如新增的通知渠道或定时任务。")
                    } else {
                        UfiDialogNote("合并只覆盖备份中存在的项，其余配置保持不变。")
                    }
                    if (!pv.same_device) {
                        UfiDialogNote("该备份来自另一台设备，与硬件相关的配置恢复后可能需要重新调整。")
                    }
                }
                importError?.let { UfiDialogWarning(it) }
            }
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

// ══════════════════════════════════════════════════════════════
// 导出位置
// ══════════════════════════════════════════════════════════════

/**
 * 备份包的固定导出目录（MediaStore 相对路径）。
 *
 * 一级目录必须是 Android 标准的 `Download`（单数）—— MediaStore 的 `RELATIVE_PATH` 只认这个名字；
 * 二级收敛到 `UFI-AXIS/`，与下载、日志、更新包同一品牌根目录（见 core 侧
 * `DownloadManager.PUBLIC_DOWNLOAD_DIR`）。
 */
private const val BACKUP_RELATIVE_PATH = "Download/UFI-AXIS/Backup/"

/** 展示给用户的目录文案，与 [BACKUP_RELATIVE_PATH] 保持一致。 */
private const val BACKUP_DIR_LABEL = "Download/UFI-AXIS/Backup"

/**
 * 读不到设备端上报上限时的兜底值。
 *
 * 与 core 的 `BackupRoutes.MAX_UPLOAD_BYTES`（8MB）保持一致：宁可本地先按同一口径拦住，
 * 也不要读不到 `/info` 时就完全放开。
 */
private const val DEFAULT_IMPORT_MAX_BYTES = 8L * 1024 * 1024

/**
 * 查询所选文档的大小。
 *
 * @return 字节数；provider 不提供 `OpenableColumns.SIZE` 时返回 null（此时只能靠读取计数兜底）
 */
private fun queryOpenableSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
}.getOrNull()

/**
 * 带计数上限地读取所选文件，超过 [limit] 返回 null。
 *
 * 与「先读完整文件再判大小」的区别是关键：后者的判断发生在内存已经被占用之后，
 * 对误选的大文件毫无防护作用（Android 上直接表现为 OOM 闪退）。
 */
private fun readBounded(context: Context, uri: Uri, limit: Long): ByteArray? {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    return input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            if (out.size() + read > limit) return null
            out.write(chunk, 0, read)
        }
        out.toByteArray()
    }
}

/**
 * 写入公共备份目录。
 *
 * 用 MediaStore 而不是 SAF 保存对话框：导出位置是固定的，弹一次目录选择器只是多一步操作；
 * 也不用 `File` 直写 —— minSdk 31 下应用没有公共目录的直接写权限。
 *
 * 同名文件由 MediaStore 自动改名（追加序号），不会静默覆盖既有备份。
 */
private fun saveToPublicBackupDir(context: Context, fileName: String, bytes: ByteArray): Boolean =
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, BACKUP_RELATIVE_PATH)
            // 写入期间对其他应用不可见，避免读到半个文件
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在备份目录创建文件")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入备份文件")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

/** 备份文件名。带秒级时间戳，连续导出不会相互覆盖。 */
private fun backupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    return "ufi-axis-backup-$stamp.ufibak"
}

private fun formatBackupTime(millis: Long): String {
    if (millis <= 0L) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}

// ══════════════════════════════════════════════════════════════
// 手机端偏好段
// ══════════════════════════════════════════════════════════════
//
// core 不解析这一段：原样转存、原样回传。键名与取值语义仅由本文件定义，增删字段无需改动 core。
// 收录范围限于「更换手机后应当延续」的偏好：外观、转场、更新源与几个显示开关。
//
// 明确排除：token / device_hwid（凭据，且绑定本机）、server_ip / gateway_ip（每台手机各自的
// 接入点）、setup_complete（恢复后新手机会跳过引导却没有凭据）、各类 seen_id 与时间戳
// （进度标记，恢复后会造成通知重复或漏报）。

private const val K_THEME_ID = "theme_id"
private const val K_THEME_MODE = "theme_mode"
private const val K_DYNAMIC = "dynamic_enabled"
private const val K_SEED = "custom_seed_color"
private const val K_TRANSITION = "page_transition"
private const val K_TRANSITION_MS = "transition_duration_ms"
private const val K_BLUR = "blur_enabled"
private const val K_UI_SCALE = "ui_scale_percent"
private const val K_AUTO_REFRESH = "auto_refresh"
private const val K_FILE_VIEW_MODE = "file_view_mode"
private const val K_AUTO_CHECK_UPDATE = "auto_check_update"
private const val K_UPDATE_SOURCE = "update_source_mode"
private const val K_UPDATE_MIRROR_INDEX = "update_mirror_index"
private const val K_UPDATE_MIRROR_CUSTOM = "update_mirror_custom"

private fun collectAppSection(themeManager: ThemeManager, prefs: AppPreferences): JsonObject =
    buildJsonObject {
        put(K_THEME_ID, themeManager.selectedThemeId.value)
        put(K_THEME_MODE, themeManager.themeMode.value.name)
        put(K_DYNAMIC, themeManager.dynamicEnabled.value)
        put(K_SEED, themeManager.customSeedColor.value)
        put(K_TRANSITION, themeManager.pageTransition.value)
        put(K_TRANSITION_MS, themeManager.transitionDurationMs.value)
        put(K_BLUR, themeManager.blurEnabled.value)
        put(K_UI_SCALE, themeManager.uiScalePercent.value)
        put(K_AUTO_REFRESH, prefs.autoRefresh)
        put(K_FILE_VIEW_MODE, prefs.fileViewMode)
        put(K_AUTO_CHECK_UPDATE, prefs.autoCheckUpdate)
        put(K_UPDATE_SOURCE, prefs.updateSourceMode)
        put(K_UPDATE_MIRROR_INDEX, prefs.updateMirrorIndex)
        put(K_UPDATE_MIRROR_CUSTOM, prefs.updateMirrorCustom)
    }

/**
 * 把 core 回传的手机端偏好段写回本机。
 *
 * 逐字段判空而非整段反序列化为 data class：备份包可能来自更早或更新的版本，缺少一个字段
 * 不应导致整段丢弃。字段缺失即保持当前值，取值非法则由各 setter 自身的值域校验拦下。
 */
private fun applyAppSection(raw: String, themeManager: ThemeManager, prefs: AppPreferences) {
    val obj = runCatching { LENIENT.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
    fun int(key: String) = obj[key]?.jsonPrimitive?.intOrNull
    fun bool(key: String) = obj[key]?.jsonPrimitive?.booleanOrNull

    str(K_THEME_ID)?.takeIf { it.isNotBlank() }?.let { themeManager.setTheme(it) }
    str(K_THEME_MODE)?.let { name ->
        ThemeMode.entries.firstOrNull { it.name == name }?.let { themeManager.setThemeMode(it) }
    }
    bool(K_DYNAMIC)?.let { themeManager.setDynamicEnabled(it) }
    int(K_SEED)?.takeIf { it != ThemeManager.CUSTOM_SEED_UNSET }
        ?.let { themeManager.setCustomSeedColor(it) }
    str(K_TRANSITION)?.takeIf { it.isNotBlank() }?.let { themeManager.setPageTransition(it) }
    int(K_TRANSITION_MS)?.let { themeManager.setTransitionDurationMs(it) }
    bool(K_BLUR)?.let { themeManager.setBlurEnabled(it) }
    int(K_UI_SCALE)?.let { themeManager.setUiScalePercent(it) }

    bool(K_AUTO_REFRESH)?.let { prefs.autoRefresh = it }
    str(K_FILE_VIEW_MODE)?.takeIf { it.isNotBlank() }?.let { prefs.fileViewMode = it }
    bool(K_AUTO_CHECK_UPDATE)?.let { prefs.autoCheckUpdate = it }
    str(K_UPDATE_SOURCE)?.takeIf { it.isNotBlank() }?.let { prefs.updateSourceMode = it }
    int(K_UPDATE_MIRROR_INDEX)?.let { prefs.updateMirrorIndex = it }
    str(K_UPDATE_MIRROR_CUSTOM)?.let { prefs.updateMirrorCustom = it }
}

private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
