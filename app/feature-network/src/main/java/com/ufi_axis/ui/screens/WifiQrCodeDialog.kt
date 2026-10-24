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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiLoadingBox
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 连接二维码弹窗（`GET /api/wifi/qrcode`）。
 *
 * ## 2026-10-14：从 WiFi 设置弹窗里拆出来，成为独立入口
 * 用户要求："显示二维码功能单独放出来，入口放在 WiFi 设置弹窗的下面"。
 * 拆出来的理由不只是入口位置：二维码是**只读**的一件事（看一眼、扫一下），
 * 而 WiFi 设置弹窗里全是"改完要保存"的草稿字段 —— 混在一起时用户会以为
 * "二维码也要点保存才会更新"。现在它是一个独立弹窗，打开即取即显。
 *
 * ## 按需加载
 * 二维码由设备**现生成**，所以只在弹窗打开时才发请求；关掉再打开会重新取一张
 *（`bytes` 随组合销毁，不做跨次缓存）—— 用户改了 WiFi 配置后重开就能拿到新图。
 *
 * @param chip 用**设备当前生效频段**（`wifi.activeChip`）而不是任何草稿值：
 *   二维码由设备按已生效的配置生成，拿草稿去请求只会得到旧频段的图，反而误导。
 */
@Composable
fun WifiQrCodeDialog(
    viewModel: MainViewModel,
    chip: String,
    visible: Boolean = true,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    var bytes by remember(chip) { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(visible, chip) {
        if (!visible || bytes != null) return@LaunchedEffect
        loading = true
        failed = false
        val result = viewModel.network.fetchWifiQrCode(chip)
        bytes = result
        failed = result == null
        loading = false
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "连接二维码",
        showCloseButton = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        // 二维码永远画在白底上：设备生成的是黑白 PNG，深色主题下直接贴会糊成一片。
                        .background(Color.White)
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "WiFi 连接二维码",
                        // fillMaxSize + Fit：设备返回的图不保证是正方形，
                        // 只给 fillMaxWidth 会让高度按原始比例撑出白底方框。
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
                text = "扫码即可连接 ${wifiBandLabel(chip)} 热点",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
    }
}
