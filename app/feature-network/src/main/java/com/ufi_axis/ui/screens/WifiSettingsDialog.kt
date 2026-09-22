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
 * 一档「加密方式」= `auth_mode` + `encryp_type` 的**固定搭配**（2026-09-22 真机抓包口径）。
 *
 * 为什么把两个字段绑成一档、不让用户各选一个：设备只认成对的组合（`OPEN` 必须配 `NONE`，
 * 三档 PSK 一律配 `CCMP`）。拆成两个选择器只会让人选出设备拒收的组合，而
 * `POST /api/wifi/config` 对非法组合只回 `success:false`，说不出是哪一项错了。
 *
 * 字段名是 `encryp_type` 而**不是** `encrypt_type` —— 设备侧的拼写，core 原样透传，别"顺手修正"。
 */
private data class WifiAuthPreset(
    /** 空串 = 「保持不变」档：提交时连 `auth_mode` 都不下发（见 [WifiSettingsDialog] 的提交注释）。 */
    val authMode: String,
    /** 空串 = 不下发 `encryp_type`（只出现在透传档：设备报了个我们不认识的 auth_mode）。 */
    val encrypType: String,
    val label: String
)

/**
 * 四档权威取值（2026-09-22 真机抓包）。顺序 = 安全性递增，UI 上从左到右也是这个顺序。
 * 上限/别名不在这里猜：设备回了不在本表里的档位时走透传档，不做模糊匹配。
 */
private val WIFI_AUTH_PRESETS = listOf(
    WifiAuthPreset("OPEN", "NONE", "开放"),
    WifiAuthPreset("WPA2PSK", "CCMP", "WPA2"),
    WifiAuthPreset("WPA3PSK", "CCMP", "WPA3"),
    WifiAuthPreset("WPA2PSKWPA3PSK", "CCMP", "WPA2/WPA3")
)

/**
 * WiFi 热点设置弹窗 — 网络名称 / 加密方式 / 连接密码 / 最大连接数 / 隐藏 SSID / 频段 + 连接二维码。
 *
 * 全部使用 [UfiDialogParts] 中的统一快捷组件，零重复样式代码。
 *
 * **本弹窗是表单式**（草稿 + 「保存配置」一次性 POST），不是即时生效式。
 * 2026-09-22 补进来的三项（加密方式 / 最大连接数 / 隐藏 SSID）刻意沿用同一语义 ——
 * 同一个表单里混"改完立刻下发"和"点保存才下发"，用户没法判断哪一项已经落地；
 * 而且这几项和 SSID/密码本来就该一起变更（改加密方式必然要连带确认密码）。
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

    // ── 加密方式（2026-09-22）──
    // 设备回的档位不在 WIFI_AUTH_PRESETS 里时（老固件的 WPAPSKWPA2PSK 之类）追加一个透传档，
    // 没读到 WiFi 设置时追加一个「保持不变」档，两种情况都默认选中追加的那一档。
    // 不这么做的后果很具体：本表单每次保存都会带 auth_mode，用户只想改个 SSID，
    // 加密方式就被动变成了列表里的第一档 —— 等于静默降级。
    val authPresets = remember(wifi?.authMode, wifi?.encryptType) {
        val raw = wifi?.authMode?.trim().orEmpty()
        when {
            raw.isEmpty() -> listOf(WifiAuthPreset("", "", "保持不变")) + WIFI_AUTH_PRESETS
            WIFI_AUTH_PRESETS.any { it.authMode == raw } -> WIFI_AUTH_PRESETS
            else -> WIFI_AUTH_PRESETS +
                WifiAuthPreset(raw, wifi?.encryptType?.trim().orEmpty(), "设备当前（$raw）")
        }
    }
    var selectedAuth by remember(wifi?.authMode) {
        mutableStateOf(wifi?.authMode?.trim().orEmpty())
    }
    val isOpenAuth = selectedAuth == "OPEN"

    // ── 最大连接数（2026-09-22）──
    // 空串 = 不下发这一项（设备没报，或用户主动清空）。**上限刻意不校验**：
    // 真机见过 7 与 10，真实上限由固件决定，编一个数只会把合法值挡在门外。
    var maxSta by remember(wifi?.maxStaNum) { mutableStateOf(wifi?.maxStaNum?.trim().orEmpty()) }
    val maxStaValue = maxSta.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }
    val maxStaInvalid = maxSta.isNotEmpty() && maxStaValue == null

    // ── 隐藏 SSID（2026-09-22）──
    // 读侧 `wifi_chip1_ssid1_broadcast_ssid` 与写侧 `broadcast_disabled` 语义一致：
    // **`1` = 隐藏**（不是"广播"）。两边同向，所以这里不需要取反。
    var hideSsid by remember(wifi?.broadcastSsid) {
        mutableStateOf(wifi?.broadcastSsid?.trim() == "1")
    }

    // 从「开放」切到 PSK 档时密码是空的（开放热点本来没有 passphrase）——
    // 此时放过提交，设备会拿空密码建一个连不上的加密热点。拦下来并写明原因。
    val pwdMissing = !isOpenAuth && selectedAuth.isNotEmpty() && pwd.isEmpty()

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
                    val preset = authPresets.firstOrNull { it.authMode == selectedAuth }
                    viewModel.network.setWifiConfig(
                        buildMap<String, Any> {
                            put("ssid", ssid)
                            // auth_mode / encryp_type 是一对，要么都带要么都不带。
                            // 「保持不变」档（authMode 为空）出现在还没回读到 WiFi 设置时 ——
                            // 此刻发任何一档都是替用户瞎猜，所以两个键一起省掉。
                            preset?.takeIf { it.authMode.isNotEmpty() }?.let { p ->
                                put("auth_mode", p.authMode)
                                if (p.encrypType.isNotEmpty()) put("encryp_type", p.encrypType)
                            }
                            // 开放模式**不带 passphrase**（真机口径）：OPEN + 密码是矛盾组合，
                            // 带上会让整条请求失败，而失败原因设备不会告诉我们。
                            if (!isOpenAuth) put("passphrase", pwd)
                            // Int 而不是 String：这两个键在 WifiRoutes 里按 intOrNull 读，
                            // 契约类型就是数字；converter 会把 Number 写成 JSON 数字。
                            maxStaValue?.let { put("max_sta_num", it) }
                            put("broadcast_disabled", if (hideSsid) 1 else 0)
                            put("chip_index", if (selectedChip == "chip2") "1" else "0")
                        }
                    )
                    onDismiss()
                },
                confirmText = "保存配置",
                // 校验不通过时禁用确认键，原因写在对应字段下面（errorMessage / 警告块），
                // 不做"点了没反应"的静默失败。
                enabled = !maxStaInvalid && !pwdMissing
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
            UfiDialogChipSelector(
                label = "加密方式",
                options = authPresets.map { it.authMode to it.label },
                selectedValue = selectedAuth,
                onSelect = { selectedAuth = it },
                // 四档（含追加档时五档）一行放不下，弹窗宽度比设置行更窄
                wrap = true
            )
            // 选「开放」时密码框**直接不渲染**而不是置灰：置灰留着会让人以为"密码还在、
            // 只是暂时不能改"，而开放热点根本没有密码这一项，提交时也不带 passphrase。
            if (isOpenAuth) {
                UfiDialogWarning("开放热点不设密码，附近任何人都能直接连上。保存后不会下发密码。")
            } else {
                UfiDialogPasswordField(
                    label = "连接密码",
                    value = pwd,
                    onValueChange = { pwd = it },
                    placeholder = "请输入连接密码"
                )
                if (pwdMissing) {
                    UfiDialogWarning("加密热点必须设密码 —— 从「开放」切过来时密码是空的，请先填写。")
                }
            }
            UfiDialogField(label = "最大连接数") {
                UfiDigitField(
                    value = maxSta,
                    onValueChange = { maxSta = it },
                    label = "",
                    placeholder = "留空 = 不修改",
                    isError = maxStaInvalid,
                    errorMessage = if (maxStaInvalid) {
                        "必须是大于 0 的整数。上限由固件决定（真机见过 7 与 10），本页不替它设限。"
                    } else {
                        null
                    }
                )
            }
            UfiDialogSwitchField(
                label = "隐藏 SSID",
                checked = hideSsid,
                onCheckedChange = { hideSsid = it }
            )
            if (hideSsid) {
                UfiDialogNote("隐藏后手机搜不到这个热点，必须手动输入网络名称才能连接（二维码仍可用）。")
            }
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
