package com.ufi_axis.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.viewmodel.MainViewModel


/**
 * WiFi 热点设置弹窗 — 精简配置项（网络名称 / 连接密码 / WiFi 频段）+ 连接二维码。
 *
 * 全部使用 [UfiDialogParts] 中的统一快捷组件，零重复样式代码。
 */
@Composable
fun WifiSettingsDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()

    val wifi = state.wifiSettings
    var ssid by remember(wifi?.ssid) { mutableStateOf(wifi?.ssid ?: "") }
    var pwd by remember(wifi?.passphrase) { mutableStateOf(wifi?.passphrase ?: "") }
    // 频段选择改为本地状态，仅在确认时一起提交（避免切换频段立即发请求导致 WiFi 断联）
    var selectedChip by remember(wifi?.activeChip) { mutableStateOf(wifi?.activeChip ?: "chip1") }

    // 用 UfiScrollableDialog 而不是 UfiCustomDialog（2026-08-30）：
    // UfiCustomDialog 的 content 不滚动也不设高度上限，二维码展开后这一列高度直接超过弹窗，
    // Column 只能把各子项按比例压缩 —— 表现就是"弹窗被挤变形、底部按钮变扁"。
    // UfiScrollableDialog 会给内容区 clamp 出 maxScrollH 并让它滚动，
    // 同时把 actions 放到滚动区**之外**的固定位置（footer 预算 120dp），按钮高度不再被内容抢走。
    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "WiFi 热点设置",
        icon = rememberVectorPainter(Icons.Default.Wifi),
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    viewModel.network.setWifiConfig(
                        mapOf(
                            "ssid" to ssid,
                            "passphrase" to pwd,
                            "chip_index" to if (selectedChip == "chip2") "1" else "0"
                        )
                    )
                    onDismiss()
                },
                confirmText = "保存配置"
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "网络名称 (SSID)",
                value = ssid,
                onValueChange = { ssid = it },
                placeholder = "请输入网络名称"
            )
            UfiDialogPasswordField(
                label = "连接密码",
                value = pwd,
                onValueChange = { pwd = it },
                placeholder = "请输入连接密码"
            )
            // 频段是严格二选一，用「胶囊内滑块」页签（与高级控制台的 AT/Shell 切换同一组件），
            // 而不是两个独立 chip：一个轨道切两段更贴"二选一"语义，且全 App 切换器外观统一。
            // 下标映射固定：0 → chip1(2.4G)，1 → chip2(5G)。
            UfiDialogField(label = "WiFi 频段") {
                UfiScrollableTabRow(
                    selectedTabIndex = if (selectedChip == "chip2") 1 else 0,
                    onTabSelected = { selectedChip = if (it == 1) "chip2" else "chip1" },
                    tabs = listOf("2.4 GHz", "5 GHz")
                )
            }
            WifiQrCodeSection(viewModel = viewModel, chip = wifi?.activeChip ?: "chip1")
        }
    }
}

/**
 * 连接二维码区（`GET /api/wifi/qrcode`）。
 *
 * 按需加载：默认只显示一个按钮，点开才发请求 —— 二维码是设备现生成的图片，
 * 每次打开 WiFi 设置都预拉会让弹窗多等一个来回。
 *
 * [chip] 用的是**设备当前生效频段**（`wifi.activeChip`）而不是弹窗里的草稿选择：
 * 二维码由设备按已生效的配置生成，草稿还没保存时拿它去请求只会得到旧频段的图，反而误导。
 * 同理，改完配置保存后需要重新打开本区才会拿到新图。
 */
@Composable
private fun WifiQrCodeSection(viewModel: MainViewModel, chip: String) {
    val palette = LocalResolvedPalette.current
    var expanded by remember { mutableStateOf(false) }
    var bytes by remember(chip) { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(expanded, chip) {
        if (!expanded || bytes != null) return@LaunchedEffect
        loading = true
        failed = false
        val result = viewModel.network.fetchWifiQrCode(chip)
        bytes = result
        failed = result == null
        loading = false
    }

    UfiDialogField(label = "连接二维码") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!expanded) {
                UfiButton(variant = UfiButtonVariant.Secondary, text = "显示二维码", onClick = { expanded = true })
            } else {
                // 二维码永远画在白底上：设备生成的是黑白 PNG，深色主题下直接贴会糊成一片。
                val bitmap = remember(bytes) {
                    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                }
                when {
                    bitmap != null -> Box(
                        modifier = Modifier
                            // 168dp 而非 196dp：弹窗内容区高度有限，二维码不该独占大半屏；
                            // 168dp 在手机上扫码距离仍然充裕。
                            .size(168.dp)
                            .clip(UfiCardDefaults.shape)
                            .background(Color.White)
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "WiFi 连接二维码",
                            // fillMaxSize + Fit：设备返回的图不保证是正方形，
                            // 原来只给 fillMaxWidth 会让高度按原始比例撑出白底方框。
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    loading -> UfiLoadingBox(isLoading = true) {}
                    failed -> Text(
                        text = "设备未提供二维码",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
                Text(
                    text = "扫码即可连接 ${if (chip == "chip2") "5 GHz" else "2.4 GHz"} 热点",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
        }
    }
}
