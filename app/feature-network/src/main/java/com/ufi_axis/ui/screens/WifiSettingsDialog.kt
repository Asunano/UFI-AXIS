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
import com.ufi_axis.viewmodel.module.WIFI_BAND_SWITCH_DISCONNECT_HINT
import com.ufi_axis.viewmodel.module.WIFI_RESTART_DISCONNECT_HINT
import kotlinx.coroutines.launch


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
 * 「最大连接数」的合法区间：**闭区间 `1..10`**。
 *
 * 依据：**用户对中兴 F50 的规格结论 —— 最大支持 10 个（2026-09-22）**。在此之前本页刻意不设
 * 上限（注释写的是「真机见过 7 与 10，本页不替固件设限」），那条依据已被上述结论取代。
 *
 * 与 core 同一份事实：`ZteGoformProfile.AP_MAX_STA_NUM_RANGE`（`validateApConfig` 拒 1..10 之外的值）。
 * 客户端仍要自己守一道，但 2026-09-22 用户裁决把守门方式从"校验"换成了**穷举**：
 * 本页把这个区间直接铺成下拉选项（见「最大连接数」一节），越界值在界面上根本选不出来，
 * 所以不再需要错误文案去解释「该填什么」。这个区间因此是**唯一**的值域来源 —— 改上限只改这里。
 */
private val WIFI_MAX_STA_RANGE = 1..10

/**
 * 频段选项：**界面文案 ↔ 传输取值**，一次对齐（2026-09-22）。
 *
 * 传输值只能是设备词汇 `chip1`（2.4G）/ `chip2`（5G）—— 这是
 * `goformId=switchWiFiChip&ChipEnum=…` 的取值域（真机抓包），core 侧 profile 的 validate
 * 只认这两个。**不要**拿 `"0"` / `"1"`（读侧 `chip_index` 的展示编码）或界面文案
 * `"2.4 GHz"` / `"5 GHz"` 去下发，那些会被 core 直接 400。
 *
 * 收成一份表是因为这两个字符串要出现在三处：下拉文案、确认弹窗正文、二维码区说明。
 * 各写一遍迟早漂成"下拉说 5 GHz、确认弹窗说 2.4 GHz"。列表顺序 = 下拉选项顺序。
 */
private val WIFI_BAND_OPTIONS = listOf("chip1" to "2.4 GHz", "chip2" to "5 GHz")

/** 频段传输值 → 界面文案。读到表外的值时按 2.4G 兜底（与 `WifiSettingsResponse.activeChip` 同口径）。 */
private fun wifiBandLabel(chip: String): String =
    WIFI_BAND_OPTIONS.firstOrNull { it.first == chip }?.second ?: WIFI_BAND_OPTIONS.first().second

/**
 * WiFi 热点设置弹窗 — 网络名称 / 加密方式 / 连接密码 / 最大连接数 / 隐藏 SSID / 频段 + 连接二维码。
 *
 * 全部使用 [UfiDialogParts] 中的统一快捷组件，零重复样式代码。
 *
 * **本弹窗是表单式**（草稿 + 「保存配置」才下发），不是即时生效式。
 * 2026-09-22 补进来的三项（加密方式 / 最大连接数 / 隐藏 SSID）刻意沿用同一语义 ——
 * 同一个表单里混"改完立刻下发"和"点保存才下发"，用户没法判断哪一项已经落地；
 * 而且这几项和 SSID/密码本来就该一起变更（改加密方式必然要连带确认密码）。
 *
 * **但频段是两条请求里的第二条**（2026-09-22）：它不属于 `POST /api/wifi/config`，
 * 换频段只能发 `POST /api/wifi/band`（设备命令 `switchWiFiChip`）。原来把它塞进
 * `/config` 的 `chip_index` 是**假入口** —— 那一项落到设备的 `setAccessPointInfo`，
 * 设备不认，用户实测「频段改了没反应」。点「保存配置」时的下发顺序与闸门见 `submit`。
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
    // 频段是草稿状态，点「保存配置」并二次确认后才走 POST /api/wifi/band 下发
    // （切换频段会重启 WiFi 模块顶掉连接，不能拨一下页签就立刻发）
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
    // `null` = 「保持不变」档 = **不下发这一项**。三种来源都落到 null：设备没报、设备报了个
    // [WIFI_MAX_STA_RANGE] 之外的值（不替固件猜该显示几，也不把越界值当草稿带回去）、用户主动选它。
    //
    // 2026-09-22 用户裁决改用下拉后，草稿状态**直接就是 Int?**，不再有字符串中间态 ——
    // 于是原来的「非法输入」状态（maxStaInvalid）、`maxLength = 2`、越界错误文案一并删除：
    // 十选一的值域由选项本身守，用户打不出 99，也就没有"打完再被拒"这条路径。
    var maxStaValue by remember(wifi?.maxStaNum) {
        mutableStateOf(wifi?.maxStaNum?.trim()?.toIntOrNull()?.takeIf { it in WIFI_MAX_STA_RANGE })
    }
    // 选项 = 「保持不变」(null) + 区间内每个整数。值域唯一来源仍是 [WIFI_MAX_STA_RANGE]：
    // 改上限只改那一处，这里跟着变，不存在第二份 1..10。
    val maxStaOptions = remember { listOf<Int?>(null) + WIFI_MAX_STA_RANGE.toList() }

    // ── 隐藏 SSID（2026-09-22）──
    // 读侧 `wifi_chip1_ssid1_broadcast_ssid` 与写侧 `broadcast_disabled` 语义一致：
    // **`1` = 隐藏**（不是"广播"）。两边同向，所以这里不需要取反。
    var hideSsid by remember(wifi?.broadcastSsid) {
        mutableStateOf(wifi?.broadcastSsid?.trim() == "1")
    }

    // 从「开放」切到 PSK 档时密码是空的（开放热点本来没有 passphrase）——
    // 此时放过提交，设备会拿空密码建一个连不上的加密热点。拦下来并写明原因。
    val pwdMissing = !isOpenAuth && selectedAuth.isNotEmpty() && pwd.isEmpty()

    // ── 会不会把当前连接踢下线（2026-09-22）──
    // 判据：草稿与**设备当前值**逐项比，只看真正会让设备重启热点的那几项。
    // 「最大连接数」刻意不算：改它不断连，把它也算进去就是无差别恐吓 ——
    // 每次保存都弹同一条警告，用户很快就不看了，真要断连的那次也一起被忽略。
    // 「保持不变」档（selectedAuth 为空 = 还没读到设备设置）同样不算：那一档连 auth_mode
    // 都不下发，没有任何改动可言。
    // **频段也不在这里**：它已经不走 `/config` 了（见 [bandChanged]），后果由它自己的确认弹窗
    // 与 WIFI_BAND_SWITCH_DISCONNECT_HINT 说明。留在这里会让"只改频段"时 /config 的成功
    // Toast 挂上一条它并不负责的断连提示。
    val willDisconnect =
        ssid != (wifi?.ssid ?: "") ||
            pwd != (wifi?.passphrase ?: "") ||
            (selectedAuth.isNotEmpty() && selectedAuth != wifi?.authMode?.trim().orEmpty()) ||
            hideSsid != (wifi?.broadcastSsid?.trim() == "1")

    // ── 频段是**独立动作**（2026-09-22）──
    // 只有草稿与设备生效频段真的不同才下发：这条命令会重启 WiFi 模块顶掉所有客户端，
    // 不能每次保存 SSID 都顺带来一次。相等时连请求都不发（见 submit 的 switchBand 参数）。
    val bandChanged = selectedChip != (wifi?.activeChip ?: "chip1")

    // 请求在飞时的闸门。成功才关窗，所以这个状态活得比原来的"发完就关"长。
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * 「切换 WiFi 频段」二次确认弹窗的开关。
     *
     * 以 [visible] 为 key：本弹窗关掉时 WifiSettingsDialog 并没有离开组合（只是 visible=false），
     * 不重置的话上一次没点完的确认会在下次打开时直接冒出来。
     */
    var bandConfirmVisible by remember(visible) { mutableStateOf(false) }

    /**
     * 真正下发。顺序固定「配置先落、频段最后」：换频段会重启 WiFi 模块并顶掉连接，
     * 放在前面的话 SSID / 密码就没机会落到设备上。
     *
     * `/config` 失败时**不发**频段：半套下发（频段换了但 SSID 没保存）会让用户下次打开弹窗
     * 看到一份和设备不一致的草稿。失败一律留在弹窗里让他接着改（原因已由全局错误通道给出）。
     */
    fun submit(switchBand: Boolean) {
        // 频段不在这份 payload 里：`chip_index` 落到设备的 setAccessPointInfo → ChipIndex，
        // **设备不认**（用户实测「频段改了没反应」的根因）。真正换频段走 POST /api/wifi/band。
        val preset = authPresets.firstOrNull { it.authMode == selectedAuth }
        val payload = buildMap<String, Any> {
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
            // `?.let` 这一层就是提交守门，**不许放宽**：maxStaValue 为 null（「保持不变」档）时
            // 整个键都不进 payload —— 不下发 ≠ 下发 0/空串，后者会被设备/core 当成一次真实改动。
            maxStaValue?.let { put("max_sta_num", it) }
            put("broadcast_disabled", if (hideSsid) 1 else 0)
        }
        saving = true
        scope.launch {
            // 等结果：原来这里发完请求就 onDismiss()，弹窗在请求还在飞的时候就关了，
            // 用户既看不到"在发"，也无从知道设备到底收没收。
            val configOk = viewModel.network.setWifiConfig(
                payload,
                willDisconnect = willDisconnect
            )
            val ok = if (configOk && switchBand) {
                viewModel.network.setWifiBand(selectedChip)
            } else {
                configOk
            }
            saving = false
            // 成功才关窗；失败**留在弹窗里**让用户接着改（原因已由全局 Toast 给出，
            // 关了窗再提示等于让他重新打开、重新填一遍）。
            if (ok) onDismiss()
        }
    }

    // 用 UfiScrollableDialog 而不是 UfiCustomDialog（2026-08-30）：
    // UfiCustomDialog 的 content 不滚动也不设高度上限，二维码展开后这一列高度直接超过弹窗，
    // Column 只能把各子项按比例压缩 —— 表现就是"弹窗被挤变形、底部按钮变扁"。
    // UfiScrollableDialog 会给内容区 clamp 出 maxScrollH 并让它滚动，
    // 同时把 actions 放到滚动区**之外**的固定位置（footer 预算 120dp）。
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
                    // 频段变了 → 先确认再下发（本仓硬约定：破坏性动作要用户点头）。
                    // 没变 → 直接保存配置，一个频段字段都不发。
                    if (bandChanged) bandConfirmVisible = true else submit(switchBand = false)
                },
                confirmText = "保存配置",
                // 会断连时按危险操作渲染（红），与其它"会把用户自己踢下线"的动作同一观感。
                // 换频段同样会断连，所以它也算进来。
                confirmDestructive = willDisconnect || bandChanged,
                // 校验不通过时禁用确认键，原因写在对应字段下面（警告块），
                // 不做"点了没反应"的静默失败。
                // 2026-09-22：原来这里还有 `!maxStaInvalid`。最大连接数改成下拉后**不存在非法值**
                // （选项只有 null 与 WIFI_MAX_STA_RANGE 内的整数），那个条件恒真，留着只会让人以为
                // 还有一条越界路径要防。真正的数值守门在 submit：null 时整个 max_sta_num 键都不下发。
                // saving 也要进 enabled：loading 只是视觉，单靠它挡不住重复提交。
                enabled = !saving && !pwdMissing,
                loading = saving
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
            // 2026-09-22 用户裁决：本弹窗三个选择控件（加密方式 / 最大连接数 / WiFi 频段）统一走
            // 公共下拉 [UfiDropdown]，弹窗内只留一种选择交互。原为 UfiDialogChipSelector 分段胶囊
            //（四档、含追加档时五档，靠 `wrap = true` 折行放下）。
            // 取值域仍是 [authPresets] 的 authMode（含「保持不变」档与透传档），显示文案由
            // optionLabel 从**同一份** authPresets 取：值与文案没有第二个来源。
            // modifier 不给 —— UfiDropdown 内部触发器已 fillMaxWidth，横向内距的唯一来源是弹窗壳
            //（UfiDateRangePickerDialog.kt:630 记过"自带一层 padding 导致左右缩进比别的字段多 18dp"）。
            UfiDialogField(label = "加密方式") {
                UfiDropdown(
                    selectedValue = selectedAuth,
                    options = authPresets.map { it.authMode },
                    onValueSelected = { selectedAuth = it },
                    optionLabel = { mode -> authPresets.firstOrNull { it.authMode == mode }?.label ?: mode }
                )
            }
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
            // 2026-09-22 用户裁决：十选一不该让用户手打再校验，改用公共下拉 [UfiDropdown]。
            // 原为 UfiDigitField（手打 + maxLength=2 + 越界红字），那套把一个硬性值域拆成了
            // "输入 → 校验 → 报错"三步，而值域本来就只有 10 个合法值。
            // 「保持不变」= 列表首项（null），选它就等于旧实现里的"留空"：提交时不带 max_sta_num。
            // 11 项超过 UfiDropdown 的默认 7 项可见高度，由它自己限高滚动并在打开时滚到选中项 ——
            // 不显式传 maxVisibleItems：让弹层长到 11 项高会在小屏上顶满内容区。
            UfiDialogField(label = "最大连接数") {
                UfiDropdown(
                    selectedValue = maxStaValue,
                    options = maxStaOptions,
                    onValueSelected = { maxStaValue = it },
                    optionLabel = { it?.toString() ?: "保持不变" }
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
            // ── 频段控件选型：一次**决策变更**，不是手滑（保留原判断以便追溯）──
            //
            // 原判断（2026-09-22，裁决之前）：
            //   「频段是严格二选一，用『胶囊内滑块』页签（UfiScrollableTabRow，与高级控制台的
            //   AT/Shell 切换同一组件），而不是两个独立 chip：一个轨道切两段更贴"二选一"语义，
            //   且全 App 切换器外观统一。」
            //
            // 被推翻：2026-09-22 用户裁决 —— 本弹窗的三个选择控件（频段 / 加密方式 / 最大连接数）
            //   一律改用公共下拉 [UfiDropdown]，弹窗内部只保留一种选择交互。
            //
            // 由此接受的取舍（明知而为，别再"优化"回去）：
            //   1. 二选一用下拉要多一次点击：滑块是一击切换，下拉得先展开弹层再点选项；
            //   2. 与全 App 其它切换器（AT/Shell 等页签）的外观不再统一 ——
            //      这里判定"同一个弹窗内三个选择控件长一样"优先于"跨页切换器长一样"。
            //
            // 文案与传输取值仍然**只**来自 [WIFI_BAND_OPTIONS]：options 取 `chip1`/`chip2`
            //（= 真正发出去的值），显示文案由 wifiBandLabel 从同一张表取，界面文案永远不当传输值。
            UfiDialogField(label = "WiFi 频段") {
                UfiDropdown(
                    selectedValue = selectedChip,
                    options = WIFI_BAND_OPTIONS.map { it.first },
                    onValueSelected = { selectedChip = it },
                    optionLabel = ::wifiBandLabel
                )
            }
            // 频段与本表单其余各项**不是同一次下发**：它走 POST /api/wifi/band，点「保存配置」时
            // 会先弹确认。先把这件事说清楚，用户才不会以为改完下拉就已经生效了
            // （这正是旧实现的问题：塞进 /config 的 chip_index 设备根本不认）。
            if (bandChanged) {
                UfiDialogNote("频段会在配置保存后单独切换，点「保存配置」时会先请你确认。")
            }
            WifiQrCodeSection(viewModel = viewModel, chip = wifi?.activeChip ?: "chip1")
            // 断连警告必须在**点击之前**给：用户很可能正通过这个热点连着设备，保存后热点重启，
            // 失败/成功提示物理上都送不到他眼前 —— 那时才提示等于没提示。
            // 放在内容最后（紧贴底部按钮）而不是顶部：本弹窗内容是可滚动的，
            // 顶部那条在用户滚到"保存配置"时已经划出屏幕了。
            if (willDisconnect) {
                UfiDialogWarning(WIFI_RESTART_DISCONNECT_HINT)
            }
        }
    }

    // ── 切换频段的二次确认（本仓硬约定：破坏性动作必须用户点头且写清后果）──
    // 用公共 UfiConfirmDialog(destructive = true)，与重启设备 / 恢复出厂那几处同一套观感，
    // 不另造布局。渲染在 UfiScrollableDialog **之外**：它是独立的一层弹窗，
    // 叠在热点设置弹窗上面（同 MediaVideoDownloadHistoryScreen 的 `if (flag) { … }` 写法）。
    if (bandConfirmVisible) {
        UfiConfirmDialog(
            title = "切换 WiFi 频段",
            text = "将把 WiFi 切换到 ${wifiBandLabel(selectedChip)}。$WIFI_BAND_SWITCH_DISCONNECT_HINT。" +
                "其余改动（网络名称 / 密码等）会在切换前先保存。",
            confirmText = "切换并保存",
            // 「暂不执行」而不是「取消」：这里要留的是"我不做这个动作"的出口，
            // 而不是"关掉整个设置"—— 点它只收起本确认框，热点设置弹窗还在，草稿都留着。
            dismissText = "暂不执行",
            destructive = true,
            onConfirm = {
                bandConfirmVisible = false
                submit(switchBand = true)
            },
            // 「暂不执行」= **一个字段都不发**（连 /config 都不发）。
            // 半套下发（存了 SSID 却没换频段）会让弹窗上的频段显示成设备并不在用的值 ——
            // 那就是假开关。要只存配置，用户可以把频段下拉选回原频段再点保存。
            onDismiss = { bandConfirmVisible = false }
        )
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
                    text = "扫码即可连接 ${wifiBandLabel(chip)} 热点",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
        }
    }
}
