package com.ufi_axis.ui.diagnose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiSectionGroupTitle
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlinx.coroutines.launch

/**
 * 诊断包导出卡（2026-10-24，从分组页移到诊断中心入口页）。
 *
 * 为什么只放在入口页：导出是**全局动作**（全量快照 + 最近日志 + 检查项清单），
 * 而各分组页的 `results` 是各自 section 实例的 state —— 三个分组页各放一份，
 * 导出的"本次结果"会各不相同，标题却都写"全量快照 + 本次结果"。
 *
 * 入口页没有运行结果，`results` 传空即可：包里会写"已跑 0 项"，
 * 与"刚进页面、什么都还没跑"的事实一致。
 */
@Composable
fun DiagnosticExportCard(
    api: DiagnosticApi,
    items: List<DiagnosticItem>,
    results: Map<String, DiagnosticResult>,
    onToast: (ToastMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPending by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // SAF：让用户选落点。文本在点按钮时就拼好，因为选择器返回时协程已经结束。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = exportPending
        exportPending = null
        if (uri == null) {
            onToast(ToastMessage("已取消导出", ToastType.INFO))
            return@rememberLauncherForActivityResult
        }
        if (text == null) return@rememberLauncherForActivityResult
        val failure = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开所选位置")
        }.exceptionOrNull()
        onToast(
            if (failure == null) ToastMessage("诊断包已导出", ToastType.SUCCESS)
            else ToastMessage("写入失败：${failure.message ?: "未知原因"}", ToastType.ERROR)
        )
    }

    fun startExport() {
        scope.launch {
            exporting = true
            // 日志读不到也要导出，失败原因写进包里。
            val logs = runCatching { api.service.getDebugLogs(limit = 200).logs }
                .getOrElse { listOf("（读取失败：${it.message ?: "未知原因"}）") }
            exportPending = buildDiagnosticBundle(
                context = context,
                api = api,
                items = items,
                results = results,
                finishedAt = emptyMap(),
                logs = logs,
            )
            exporting = false
            exportLauncher.launch(defaultExportFileName())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
    ) {
        UfiSectionGroupTitle("导出诊断包", "全量快照 + 检查项清单 + 最近日志")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .ufiStandardCard(elevation = 2.dp)
                .padding(Spacing.XLarge)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                Text(
                    text = "把 /api/diagnose 全量原文、检查项清单与 core 最近的日志拼成一个文本文件。" +
                        "导出位置由你选（不写死目录、不要权限）。",
                    style = UfiTextStyles.note,
                    color = LocalResolvedPalette.current.textSecondary,
                )
                UfiButton(
                    text = "导出诊断包",
                    variant = UfiButtonVariant.Secondary,
                    loading = exporting,
                    onClick = { startExport() },
                )
            }
        }
    }
}
