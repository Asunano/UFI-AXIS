package com.ufi_axis.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DeviceKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 初次连接引导（Onboarding）重构后的 SetupScreen。
 *
 * 探测链（所有路径收敛到同一配对端点 /pairing/info + /pairing/confirm）：
 *   ① 读本机网关直连探测（设备即网关，:PAIRING_PORT）→ ② 失败则手动输入 IP:端口。
 *   不再使用 mDNS，简化发现逻辑、消除跨模块依赖。
 * 发现候选（可能多台）后展示列表含 device_id 供用户确认连哪台（边缘情况 #2）；
 * 选定后拉取配对信息并点"配对"完成握手，凭据落盘后进入正常连接。
 *
 * 边缘情况落点：
 *   - #12 已 isSetupComplete 时不进入本屏（由 MainActivity 控制）。
 *   - #1/#3 凭据失效（401）由 RetrofitClient.onUnauthorized 触发回到本屏。
 *   - #7 错误文案区分"设备不可达 / 已配对 / 配对码无效"。
 *   - #8 并发配对第二台 confirm 返回 409 → 提示。
 *   - #15 配对中被杀 → 回前台重新拉取 /pairing/info，若 409 则给出解除配对引导。
 *
 * 2026-08-11 阶段 3（配对密码认证）：
 *   - /pairing/info 增 device_name / has_default_password 解析；CONFIRM 文案"设备标识"→"设备名"。
 *   - 密码输入 UI：has_default_password=true → "设置配对密码"（新密码+确认+使用默认 admin）；
 *     false → "输入配对密码"（单框）。
 *   - doPair 携带 password + device_name（本机 Settings.Global.DEVICE_NAME，读不到可空）。
 *   - ConfirmOutcome 增 InvalidPassword / PasswordLocked 分支。
 *
 * 2026-08-11 阶段 4（UI 异常修复 + 动画增强）：
 *   - AnimatedContent 内 when 分支外层包 Column(verticalArrangement = spacedBy)，修复按钮/输入框/提示文字重叠
 *   - DISCOVER 阶段进度指示+文字改为 Row(Icon + Text) 水平排列 + 小间距
 *   - 路由器呼吸脉冲：周期 1200ms → 900ms，scale 0.92-1.08 → 0.88-1.12，幅度更明显
 *   - 标题淡入 800ms → 600ms 加快节奏；外圈光环 alpha 上限 0.18 → 0.24
 */

/** 后端默认监听端口（来自 AppSettings.DEFAULT_PORT / HttpServer 默认 8088），用于网关探测与手动兜底。 */
private const val PAIRING_PORT = 8088

/** IPv4 地址正则（每段 1–3 位数字），配合分段数值校验（每段 0–255）。用于手动配对输入校验（UID-011, Wave 2）。 */
private val IPV4_PATTERN = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

/** 校验字符串是否为合法 IPv4 地址（每段 0–255）。 */
private fun isValidIpv4(ip: String): Boolean {
    if (!IPV4_PATTERN.matches(ip)) return false
    return ip.split('.').all { it.toIntOrNull() in 0..255 }
}

private enum class SetupPhase { DISCOVER, CANDIDATES, CONFIRM, MANUAL }

private data class DiscoveredDevice(val deviceId: String, val host: String, val port: Int, val cachedInfo: PairingInfo? = null)

private data class PairingInfo(
    val deviceId: String,
    val pairingCode: String,
    val hasRoot: Boolean,
    val isExternalStorageManager: Boolean,
    val deviceName: String = "",
    val hasDefaultPassword: Boolean = true
)

private sealed class InfoOutcome {
    data class Success(val info: PairingInfo) : InfoOutcome()
    object AlreadyPaired : InfoOutcome()
    data class Error(val msg: String) : InfoOutcome()
    data class Unreachable(val msg: String) : InfoOutcome()
}

private sealed class ConfirmOutcome {
    data class Success(val token: String, val fingerprint: String) : ConfirmOutcome()
    object AlreadyPaired : ConfirmOutcome()
    object Invalid : ConfirmOutcome()
    object InvalidPassword : ConfirmOutcome()
    object PasswordLocked : ConfirmOutcome()
    /** 设备身份验签失败 / 挑战过期：重新走一遍 challenge→confirm 即可，不是用户输入问题 */
    object DeviceKeyRejected : ConfirmOutcome()
    data class Unreachable(val msg: String) : ConfirmOutcome()
    data class Error(val msg: String) : ConfirmOutcome()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: (ip: String, port: Int, token: String) -> Unit) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(SetupPhase.DISCOVER) }
    var candidates by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pairingInfo by remember { mutableStateOf<PairingInfo?>(null) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("$PAIRING_PORT") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 配对密码（阶段3）：首次配对设置（新+确认）/ 再次配对校验（单框）
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    // CONFIRM 子步骤：仅当 [needsGoformSetup]（后端从未被首次设置）时使用：
    //   0=设置配对密码，1=GoForm 后台设置。
    // 当后端已被设置过（hasDefaultPassword=false，即 devicePasswordSet=true），
    // CONFIRM 阶段仅显示密码步，用户输完密码直接配对（不展示也不允许走 GoForm 步）。
    var confirmStep by remember { mutableStateOf(0) }

    // needsGoformSetup=true  → 初始化状态：后端从未被任何客户端首次设置，需要引导走两步。
    // needsGoformSetup=false → 正常状态：后端已被某客户端配置过（密码+GoForm），仅验证密码即可完成配对。
    // 派生自 pairingInfo.hasDefaultPassword，这是后端 GET /pairing/info 的权威字段：
    //   后端 AppSettings.hasDefaultPassword = !devicePasswordSet。
    val needsGoformSetup: Boolean = pairingInfo?.hasDefaultPassword ?: true

    // GoForm 后台连接配置（首次配对一并提交给后端 persistGoformSettings 落库）
    val setupPrefs = AppPreferences(context)
    var goformAddress by remember { mutableStateOf("${setupPrefs.gatewayIp}:${setupPrefs.goformPort}") }
    // 密码不从本地读回（本地不再存明文，见 AppPreferences.goformPasswordSet）：留空即沿用 core 现值
    var goformPassword by remember { mutableStateOf("") }
    var goformAddressError by remember { mutableStateOf<String?>(null) }
    var goformPasswordError by remember { mutableStateOf<String?>(null) }

    // v3（2026-08-11）：Toast 反馈（UfiToastHost 持有 state）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // v3（2026-08-11）：自动搜索 Job 句柄——手动切走时 cancel，防止 busy 卡住/结果覆盖 phase
    var discoveryJob: kotlinx.coroutines.Job? = null

    fun startDiscovery() {
        discoveryJob?.cancel()  // 取消上一次探测（若在跑）
        phase = SetupPhase.DISCOVER
        busy = true
        message = null
        isError = false
        discoveryJob = scope.launch {
            // v3（2026-08-11 修复"点一下秒超时"）：探测常见网关列表，且无论成功/失败至少展示 5s 雷达动画。
            // 2026-08-21 修复「已找到设备却仍被 5 秒超时强制退出到手动输入」：
            //   旧实现把"补足 5s 雷达动画"的 delay 也包在 withTimeoutOrNull(5000) 内——设备一旦
            //   被找到(foundAny=true)后仍在 timeout 作用域内 delay，5000ms 边界上 timeout 取消与
            //   block 返回的竞态会偶发把 found 判成 null，进而 if(!found) 把 phase 强制改回 MANUAL，
            //   覆盖用户已点进去的 CANDIDATES/CONFIRM（配对/设密码）状态。
            //   修复要点：① 超时只用于「放弃继续探测」，绝不用于判定 found（找到即 return，保留 found=true）；
            //           ② 补足动画的 delay 移到 if(!found) 分支，处于 timeout 作用域之外，消除竞态。
            val start = System.currentTimeMillis()
            val gwCandidates = buildList {
                getOwnGateway(context)?.let { add(it) }
                addAll(listOf("192.168.0.1", "192.168.1.1", "192.168.31.1"))
            }.distinct()
            var found = false
            withTimeoutOrNull(5000) {
                for (gw in gwCandidates) {
                    when (val r = httpGetPairingInfo(gw, PAIRING_PORT)) {
                        is InfoOutcome.Success -> {
                            candidates = listOf(DiscoveredDevice(r.info.deviceId, gw, PAIRING_PORT, r.info))
                            phase = SetupPhase.CANDIDATES
                            busy = false
                            found = true
                            return@withTimeoutOrNull
                        }
                        is InfoOutcome.AlreadyPaired -> {
                            toastMessage = ToastMessage("检测到网关设备已完成配对，请在设备端或配对网页点击「解除配对」后重试，或手动输入其他地址", ToastType.WARNING)
                            phase = SetupPhase.MANUAL
                            busy = false
                            found = true
                            return@withTimeoutOrNull
                        }
                        is InfoOutcome.Error, is InfoOutcome.Unreachable -> { /* 继续试下一个 */ }
                    }
                }
            }
            // 超时或遍历完毕仍未找到 → 补足 5s 雷达动画后回手动输入兜底 + Toast 提示
            if (!found) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 5000) kotlinx.coroutines.delay(5000 - elapsed)
                phase = SetupPhase.MANUAL
                busy = false
                toastMessage = ToastMessage("未发现附近设备（5 秒超时），请手动输入设备 IP 与端口", ToastType.INFO)
            }
            // found=true 时到此结束，绝不再触碰 phase —— 避免覆盖用户已进入的 CANDIDATES/CONFIRM 状态
        }
    }

    fun selectDevice(device: DiscoveredDevice) {
        selected = device
        // 2026-08-22 修复「登录/配对混淆」：删除 isPrevPairedDevice 静默重连分支。
        //   旧逻辑只要本地 serverIp:port 命中就静默 confirm——
        //     ① 后端重置（初始化态）会被静默以 admin 落库配对，跳过"设置密码+GoForm"首次配对向导；
        //     ② 指纹被设备移除时 confirm 回 InvalidCode → 退回 MANUAL，手动输入又命中同一分支 → 死循环，
        //       永远到不了密码登录界面。
        //   现在统一走 CONFIRM 阶段，模式完全由后端权威字段 has_default_password 派生：
        //     true  → 配对（首次初始化）：两步向导（设置配对密码 → GoForm 后台配置 → 配对）；
        //     false → 登录（普通连接）：仅输入配对密码即可连接（后端已支持配对码消耗后凭密码登录）。
        pairingInfo = null
        busy = true
        message = null
        isError = false
        phase = SetupPhase.CONFIRM
        // 若 discovery 阶段已缓存 PairingInfo，直接使用，避免重复请求触发速率限制
        device.cachedInfo?.let {
            pairingInfo = it
            busy = false
            return
        }
        scope.launch {
            when (val r = httpGetPairingInfo(device.host, device.port)) {
                is InfoOutcome.Success -> {
                    pairingInfo = r.info
                    busy = false
                }
                is InfoOutcome.AlreadyPaired -> {
                    // v3：已配对时不再卡住界面，弹 toast 提示后回 MANUAL 让用户重选
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage("设备 ${device.deviceId} 已完成配对，请在设备端或配对网页点击「解除配对」后重试，或手动输入其他地址", ToastType.WARNING)
                    selected = null
                    pairingInfo = null
                }
                is InfoOutcome.Error -> {
                    // v3：错误时自动回 MANUAL，toast 提示
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage("获取设备信息失败: ${r.msg}", ToastType.ERROR)
                    selected = null
                    pairingInfo = null
                }
                is InfoOutcome.Unreachable -> {
                    // v3：设备不可达自动回 MANUAL（解决'卡在获取设备信息中'）
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage("设备不可达: ${r.msg}，请检查 IP 与端口后重试", ToastType.ERROR)
                    selected = null
                    pairingInfo = null
                }
            }
        }
    }

    fun connectManual() {
        val ip = manualIp.trim()
        val port = manualPort.toIntOrNull()
        // IP 非空校验
        if (ip.isBlank()) {
            toastMessage = ToastMessage("请输入设备 IP 地址", ToastType.WARNING)
            return
        }
        // IPv4 格式校验（每段 0–255）
        if (!isValidIpv4(ip)) {
            toastMessage = ToastMessage("IP 地址格式不正确（示例：192.168.0.1）", ToastType.WARNING)
            return
        }
        // 端口校验：1–65535（UfiDigitField 仅允许数字，空值 toIntOrNull 为 null 也落入此分支）
        if (port == null || port !in 1..65535) {
            toastMessage = ToastMessage("端口需为 1–65535", ToastType.WARNING)
            return
        }
        // 校验通过，使用用户输入的真实 IP:端口，绝不静默回退 8088
        selectDevice(DiscoveredDevice("", ip, port))
    }

    fun doPair() {
        val device = selected ?: return
        val info = pairingInfo ?: return
        val needsGoformSetup = info.hasDefaultPassword
        // 前端密码校验（v3 2026-08-11）：默认态密码为空 → 自动 fallback 默认 admin（不再弹按钮）；
        // 否则校验两次一致 + 长度 4-64 + ASCII only（由 onValueChange 过滤）。非默认态单框必填。
        if (info.hasDefaultPassword) {
            if (password.isBlank() && confirmPassword.isBlank()) {
                // v3：密码为空自动用默认 admin（无需用户点按钮）
                password = "admin"
                confirmPassword = "admin"
            } else {
                if (password != confirmPassword) {
                    toastMessage = ToastMessage("两次输入的密码不一致", ToastType.WARNING)
                    return
                }
                if (password.length < 4 || password.length > 64) {
                    toastMessage = ToastMessage("配对密码长度需为 4-64 位", ToastType.WARNING)
                    return
                }
            }
        } else if (password.isBlank()) {
            toastMessage = ToastMessage("请输入配对密码", ToastType.WARNING)
            return
        }
        // GoForm 配置解析与校验（仅在「初始化状态/首次设置」时收集并提交）：
        //   「正常状态」（后端已被配置过）不再让用户重填 GoForm，goform_ip/port/password 全部传 null，
        //   后端 persistGoformSettings 不会覆盖已有值（参考 PairingManager.confirm 的 goform 参数处理）。
        var gfIpToSend: String? = null
        var gfPortToSend: Int? = null
        var gfPwToSend: String? = null
        if (needsGoformSetup) {
            val gfParts = goformAddress.split(":")
            val gfIp = gfParts.getOrNull(0)?.trim().orEmpty()
            val gfPort = gfParts.getOrNull(1)?.trim()?.toIntOrNull()
            if (gfIp.isNotBlank() && (!isValidIpv4(gfIp) || gfPort == null || gfPort !in 1..65535)) {
                goformAddressError = "格式应为 IP:端口，例如 192.168.0.1:8080"
                toastMessage = ToastMessage("GoForm 地址格式不正确", ToastType.WARNING)
                busy = false
                return
            }
            gfIpToSend = gfIp.takeIf { it.isNotBlank() }
            gfPortToSend = gfPort
            gfPwToSend = goformPassword.takeIf { it.isNotBlank() }
        }
        busy = true
        message = null
        isError = false
        scope.launch {
            val deviceName = readDeviceName(context)
            when (val r = httpConfirmPairing(context, device.host, device.port, info.pairingCode, password, deviceName, gfIpToSend, gfPortToSend, gfPwToSend)) {
                is ConfirmOutcome.Success -> {
                    val prefs = AppPreferences(context)
                    prefs.serverIp = device.host
                    prefs.serverPort = device.port
                    prefs.token = r.token
                    prefs.isSetupComplete = true
                    if (!gfIpToSend.isNullOrBlank()) prefs.gatewayIp = gfIpToSend
                    if (gfPortToSend != null) prefs.goformPort = gfPortToSend
                    if (!gfPwToSend.isNullOrBlank()) prefs.goformPasswordSet = true
                    busy = false
                    onSetupComplete(device.host, device.port, r.token)
                }
                is ConfirmOutcome.AlreadyPaired -> {
                    busy = false
                    // v3（2026-08-11）：底部消息提示改用 Toast 宿主，message 字段不再渲染——失败分支必须用 toastMessage
                    toastMessage = ToastMessage("设备已被其他设备配对，请先在设备端解除配对。", ToastType.WARNING)
                }
                is ConfirmOutcome.Invalid -> {
                    busy = false
                    toastMessage = ToastMessage("配对码无效或已失效，请返回重新选择设备。", ToastType.WARNING)
                }
                is ConfirmOutcome.InvalidPassword -> {
                    busy = false
                    toastMessage = ToastMessage("配对密码错误，请重试", ToastType.WARNING)
                }
                is ConfirmOutcome.PasswordLocked -> {
                    busy = false
                    toastMessage = ToastMessage("密码错误次数过多，请稍后再试", ToastType.WARNING)
                }
                is ConfirmOutcome.DeviceKeyRejected -> {
                    busy = false
                    toastMessage = ToastMessage("设备身份校验失败，请重试配对", ToastType.WARNING)
                }
                is ConfirmOutcome.Unreachable -> {
                    busy = false
                    toastMessage = ToastMessage(r.msg.ifBlank { "设备不可达，请检查网络后重试" }, ToastType.ERROR)
                }
                is ConfirmOutcome.Error -> {
                    busy = false
                    toastMessage = ToastMessage(r.msg.ifBlank { "配对失败，请稍后重试" }, ToastType.ERROR)
                }
            }
        }
    }

    LaunchedEffect(Unit) { startDiscovery() }
    // 切换设备/重新拉取信息时重置密码输入与子步骤（避免沿用上一台/上一次输入，并回到第 1 步）。
    // 2026-08-22：needsGoformSetup 派生自 pairingInfo.hasDefaultPassword，每次切设备自动重新派生，不再需要单独的 reconnectMode state 重置。
    LaunchedEffect(pairingInfo) { password = ""; confirmPassword = ""; confirmStep = 0 }

    Scaffold(
        containerColor = palette.pageBg,
        // ★★ 2026-09-04（转场区满屏化 · 全库第二处）★★
        //
        // 本页**不走 MainNavGraph**（`MainActivity` 在未配对时直接渲染它），所以上一轮
        // 只改 `MainNavGraph` 那个 Scaffold 时漏掉了这里。默认的
        // `ScaffoldDefaults.contentWindowInsets`（= safeDrawing）会把 `padding` 加在
        // **verticalScroll 之外**，于是滚动内容在「安全区边界」而不是屏幕边缘被裁掉：
        // 往下滚时卡片消失在手势条上方一条线处，那条线以下是 `containerColor` 静态色带，
        // 内容再也不会经过 —— 这就是用户说的其他界面的「静止带」。
        // 页内还有一个满宽的阶段切换 `AnimatedContent`（见下方 `setupPhaseContent`），
        // 也一并被压在这块内缩矩形里。
        //
        // 清零后容器真正满屏，安全区改由下面那个 Column 在 **scroll 之内**消费
        // （`windowInsetsPadding` 会消费掉 inset，故不会与任何外层重复叠加）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth()
                // `padding` 现在四边恒为 0，保留它是 Scaffold 契约的一部分
                // （后人加 topBar / bottomBar 时槽位高度只会从这里下发）。
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // ★ 安全区的唯一落点，且刻意排在 verticalScroll **之后** ——
                //   于是它是「滚动内容的内边距」而不是「滚动视口的外边距」：
                //   内容能一路滚到屏幕物理边缘再被裁掉，不留静止带。
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Spacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Large)
        ) {
            Spacer(Modifier.height(Spacing.XLarge))

            // App 品牌标记（2026-08-11 v3：静态呈现，移除所有缩放/呼吸/光环/淡入动画）
            // 用户反馈"标题顶部的图标不要动来动去"——保持克制安静，依赖纯视觉权重传达品牌
            // 2026-09-06：从 Icons.Default.CellTower 换成 App 自己的图标（launcher 前景层同一份几何，
            // 见 res/drawable/ic_app_logo.xml）。尺寸取 100dp 而非 56dp：那份矢量沿用 adaptive-icon
            // 的 108dp 画布，图形本体只占中间约 60/108，不放大会比原来的 CellTower 明显小一圈。
            // 2026-09-07：改为直接渲染「应用自己的启动图标」，不再用手抄的矢量副本 + tint。
            // 旧写法（ic_app_logo.xml 副本 + Icon(tint = accent) + 12% 强调色圆底）有两个问题：
            //   ① Icon 会把矢量按 alpha 整体重染成单色，图标自带配色全部丢失；
            //   ② 再叠一层低不透明度强调色圆底，观感就是「灰底 + 单色线条」，不是真正的应用图标。
            // 为什么走 PackageManager 而不是 painterResource：本屏在 :app:feature-settings（library）
            // 模块里，依赖方向是 :app → :app:feature-settings，反向拿不到 :app 的 @mipmap/ic_launcher。
            // getApplicationIcon() 取的正是 manifest android:icon 指向的那份 adaptive-icon
            // （背景层 + 前景层都在），所以换图标时这里自动跟随，不需要再维护任何副本。
            val iconSizePx = with(LocalDensity.current) { 120.dp.roundToPx() }
            val appIconBitmap = remember(iconSizePx) {
                val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
                val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, iconSizePx, iconSizePx)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            }
            Image(
                bitmap = appIconBitmap,
                contentDescription = null,
                // adaptive-icon 直接绘制出来是整块方形画布（含背景层），
                // 这里按启动器的做法裁成圆形，得到与桌面图标一致的观感
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
            )

            // 标题 + 副标题（v3：去掉淡入动画，立即可见——保持稳定）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UFI-AXIS", style = UfiTextStyles.heroValue)
                Text("连接至 UFI-AXIS-Core 后端服务", style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary)
            }

            Spacer(Modifier.height(Spacing.Medium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .ufiCardShadow(elevation = 6.dp, shape = UfiCardDefaults.legacyShape)
                    .clip(UfiCardDefaults.legacyShape)
                    .background(palette.cardBg, UfiCardDefaults.legacyShape)
            ) {
                Column(Modifier.padding(Spacing.XLarge), verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
// 阶段切换过渡动画（2026-08-11：首次配对 UI 增强）
                    // scaleIn/scaleOut 让卡片内容从中心放大出现，fadeIn/Out 配合
                    AnimatedContent(
                        targetState = phase,
                        // v3（2026-08-11）：卡片外层不动，仅内部元素 fade + slide 过渡。
                        transitionSpec = {
                            // 2026-09-04（P2b）：入场原为裸 `tween(260)` ×2 → Duration.Gentle（280，
                            // +20ms，在吸附容差内）。这本来就是"页面内区块切换"，正是 Gentle 档的定义；
                            // 260 与 280 并存只是历史遗留。离场那两处早已走 Duration.Base。
                            (fadeIn(tween(UfiMotion.Duration.Gentle)) + slideInVertically(tween(UfiMotion.Duration.Gentle)) { it / 8 }) togetherWith
                                (fadeOut(tween(UfiMotion.Duration.Base)) + slideOutVertically(tween(UfiMotion.Duration.Base)) { -it / 8 })
                        },
                        label = "setupPhaseContent"
                    ) { currentPhase ->
                        // 阶段4（2026-08-11）：每阶段内容外层包 Column(spacedBy)，修复
                        // IP/按钮/提示文字 无间距重叠的 UI 异常；之前 when 分支直接放顶层 Box
                        // 内（卡片 Column）导致元素紧贴（Compose 默认无间距）。
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            // v3（2026-08-11）：阶段标题在 AnimatedContent 内 → phase 切换时随内容一起 fade 过渡
                            val phaseTitle = when (currentPhase) {
                                SetupPhase.DISCOVER -> "自动发现中"
                                SetupPhase.CANDIDATES -> "选择设备"
                                SetupPhase.MANUAL -> "连接设备"
                                // 已初始化设备 = 登录（仅密码）；初始化态设备 = 配对（两步向导）
                                SetupPhase.CONFIRM -> if (pairingInfo?.hasDefaultPassword == false) "登录设备" else "配对"
                            }
                            Text(
                                text = phaseTitle,
                                style = UfiTextStyles.screenTitle,
                                color = palette.textPrimary,
                                modifier = Modifier.padding(bottom = Spacing.Small)
                            )
                            when (currentPhase) {
                            SetupPhase.DISCOVER -> {
                                // 雷达扫描波纹（2026-08-11 v3）：3 层同心圆 stagger 扩散（1500ms 周期，每层延迟 500ms）
                                val infiniteTransition = rememberInfiniteTransition(label = "scan")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier.size(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(palette.accent)
                                        )
                                        for (i in 0..2) {
                                            // 2026-09-04（P2b）：下面两处扫描波纹原为裸 `durationMillis = 1500`
                                            // → Duration.Pulse（值不变）。1500 是"扩散脉冲一轮的周期"这一独立
                                            // 语义，距最近档位 Ambient 1200 有 300ms，远超吸附容差，故为它建档。
                                            // delayMillis = i * 500 是三圈波纹的**相位错开**，不是时长，不入梯度。
                                            val scale by infiniteTransition.animateFloat(
                                                initialValue = 0.4f,
                                                targetValue = 1.4f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(
                                                        durationMillis = UfiMotion.Duration.Pulse,
                                                        delayMillis = i * 500,
                                                    ),
                                                    repeatMode = RepeatMode.Restart
                                                ),
                                                label = "scanScale$i"
                                            )
                                            val alpha by infiniteTransition.animateFloat(
                                                initialValue = 0.7f,
                                                targetValue = 0f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(
                                                        durationMillis = UfiMotion.Duration.Pulse,
                                                        delayMillis = i * 500,
                                                    ),
                                                    repeatMode = RepeatMode.Restart
                                                ),
                                                label = "scanAlpha$i"
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                                    .alpha(alpha)
                                                    .clip(CircleShape)
                                                    .background(palette.accent)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(Spacing.Medium))
                                    Text(
                                        "正在搜索附近的设备…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textSecondary
                                    )
                                }
                                UfiButtonRow {
                                    UfiButton(
                                        variant = UfiButtonVariant.Secondary,
                                        text = "手动输入",
                                        onClick = {
                                            // v3（2026-08-11）：取消探测 + 复位 busy（否则连接按钮被禁用直到 5s 超时）
                                            discoveryJob?.cancel()
                                            phase = SetupPhase.MANUAL
                                            busy = false
                                            message = null
                                            isError = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            SetupPhase.CANDIDATES -> {
                                Text("发现以下设备，请选择要连接的设备：", style = MaterialTheme.typography.bodyMedium)
                                candidates.forEach { d ->
                                    val label = d.cachedInfo?.deviceName?.takeIf { it.isNotBlank() }
                                        ?: if (d.deviceId.isNotBlank()) d.deviceId else d.host
                                    UfiButton(
                                        text = "$label  (${d.host}:${d.port})",
                                        onClick = { selectDevice(d) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                UfiButtonRow {
                                    UfiButton(variant = UfiButtonVariant.Secondary, text = "重新搜索", onClick = { startDiscovery() }, modifier = Modifier.weight(1f))
                                    UfiButton(variant = UfiButtonVariant.Secondary, text = "手动输入", onClick = {
                                        // v3：取消探测 + 复位 busy（保险）
                                        discoveryJob?.cancel()
                                        phase = SetupPhase.MANUAL
                                        busy = false
                                        message = null
                                        isError = false
                                    }, modifier = Modifier.weight(1f))
                                }
                            }

                            SetupPhase.CONFIRM -> {
                                val info = pairingInfo
                                if (info != null) {
                                    // needsGoformSetup 派生自 info.hasDefaultPassword（后端权威字段）。
                                    //   true=初始化：后端从未被任何客户端首次设置过，需要引导走两步（密码→GoForm）。
                                    //   false=正常：后端已被配置过（密码+GoForm 都在），仅验证密码即可完成配对。
                                    val needsGoformSetup = info.hasDefaultPassword
                                    // 步骤指示器：初始化两步 / 正常仅密码
                                    Text(
                                        when {
                                            needsGoformSetup && confirmStep == 0 -> "第 1 步 / 共 2 步 · 设置配对密码"
                                            needsGoformSetup && confirmStep == 1 -> "第 2 步 / 共 2 步 · GoForm 后台设置"
                                            else -> "重新连接设备 · 输入配对密码"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accent,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small)
                                    )
                                    Text(
                                        text = info.deviceName.ifBlank { info.deviceId },
                                        style = UfiTextStyles.screenTitle,
                                        color = palette.textPrimary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        if (needsGoformSetup) "正在与该设备配对" else "设备已配置过，输入配对密码即可登录",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small)
                                    )

                                    when {
                                        // 初始化步 0：默认态留空自动用 admin；非默认态单框必填
                                        needsGoformSetup && confirmStep == 0 -> {
                                            if (info.hasDefaultPassword) {
                                                Text(
                                                    "设置配对密码（留空使用默认 admin）",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textSecondary,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                var passwordFormatError by remember { mutableStateOf<String?>(null) }
                                                var confirmPasswordFormatError by remember { mutableStateOf<String?>(null) }
                                                UfiPasswordField(
                                                    value = password,
                                                    onValueChange = { input ->
                                                        if (input.all { it.code in 33..126 }) {
                                                            password = input.take(64)
                                                            passwordFormatError = null
                                                        } else {
                                                            passwordFormatError = "密码仅限英文、数字和符号"
                                                        }
                                                    },
                                                    label = "新密码（4-64 位，留空用 admin）",
                                                    isError = passwordFormatError != null,
                                                    errorMessage = passwordFormatError,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                UfiPasswordField(
                                                    value = confirmPassword,
                                                    onValueChange = { input ->
                                                        if (input.all { it.code in 33..126 }) {
                                                            confirmPassword = input.take(64)
                                                            confirmPasswordFormatError = null
                                                        } else {
                                                            confirmPasswordFormatError = "密码仅限英文、数字和符号"
                                                        }
                                                    },
                                                    label = "确认新密码",
                                                    isError = confirmPasswordFormatError != null || (confirmPassword.isNotEmpty() && confirmPassword != password),
                                                    errorMessage = confirmPasswordFormatError
                                                        ?: if (confirmPassword.isNotEmpty() && confirmPassword != password) "两次输入的密码不一致" else null,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            } else {
                                                Text(
                                                    "输入配对密码后点击下一步",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textSecondary,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small)
                                                )
                                                var nonDefaultPasswordFormatError by remember { mutableStateOf<String?>(null) }
                                                UfiPasswordField(
                                                    value = password,
                                                    onValueChange = { input ->
                                                        if (input.all { it.code in 33..126 }) {
                                                            password = input.take(64)
                                                            nonDefaultPasswordFormatError = null
                                                        } else {
                                                            nonDefaultPasswordFormatError = "密码仅限英文、数字和符号"
                                                        }
                                                    },
                                                    label = "配对密码",
                                                    isError = nonDefaultPasswordFormatError != null,
                                                    errorMessage = nonDefaultPasswordFormatError,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        // 初始化步 1：GoForm 后台配置（首次配对一并提交给后端；升级为独立第二步）
                                        needsGoformSetup && confirmStep == 1 -> {
                                            Text(
                                                "如需通过 GoForm 接口（设备原厂后台）读取更详细的网络/信号数据，请填写其地址与密码。已按常见配置预填，可直接使用。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textSecondary,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small)
                                            )
                                            val gfParts = goformAddress.split(":")
                                            val gfIpPreview = gfParts.getOrNull(0)?.trim().orEmpty()
                                            val gfPortPreview = gfParts.getOrNull(1)?.trim()?.toIntOrNull()
                                            val gfValidPreview = gfIpPreview.isBlank() || (isValidIpv4(gfIpPreview) && gfPortPreview != null && gfPortPreview in 1..65535)
                                            UfiTextField(
                                                value = goformAddress,
                                                onValueChange = { goformAddress = it; goformAddressError = null },
                                                label = "GoForm 地址 (IP:端口)",
                                                placeholder = "192.168.0.1:8080",
                                                isError = goformAddressError != null || !gfValidPreview,
                                                errorMessage = goformAddressError
                                                    ?: if (!gfValidPreview) "格式应为 IP:端口，例如 192.168.0.1:8080" else null,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            UfiPasswordField(
                                                value = goformPassword,
                                                onValueChange = { goformPassword = it; goformPasswordError = null },
                                                label = "GoForm 密码",
                                                isError = goformPasswordError != null,
                                                errorMessage = goformPasswordError,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        // 正常状态：仅密码步（单框必填，跳过 GoForm 步）
                                        else -> {
                                            Text(
                                                "输入配对密码后点击配对",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textSecondary,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Small)
                                            )
                                            var normalPasswordFormatError by remember { mutableStateOf<String?>(null) }
                                            UfiPasswordField(
                                                value = password,
                                                onValueChange = { input ->
                                                    if (input.all { it.code in 33..126 }) {
                                                        password = input.take(64)
                                                        normalPasswordFormatError = null
                                                    } else {
                                                        normalPasswordFormatError = "密码仅限英文、数字和符号"
                                                    }
                                                },
                                                label = "配对密码",
                                                isError = normalPasswordFormatError != null,
                                                errorMessage = normalPasswordFormatError,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                } else {
                                    // 加载动画居中显示
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(Spacing.Medium))
                                        Text("正在获取设备信息…", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                // 按钮行：根据 needsGoformSetup 与 confirmStep 决定文案与下一步行为
                                //   needsGoformSetup && confirmStep == 0 → 返回/下一步
                                //   其它（含正常状态/初始化步 1）→ 返回/配对
                                val infoNow = pairingInfo
                                val needsGoformSetupNow = infoNow?.hasDefaultPassword ?: true
                                UfiButtonRow {
                                    UfiButton(variant = UfiButtonVariant.Secondary, text = "返回", onClick = {
                                        if (needsGoformSetupNow && confirmStep == 1) {
                                            confirmStep = 0
                                        } else {
                                            phase = if (candidates.isNotEmpty()) SetupPhase.CANDIDATES else SetupPhase.MANUAL
                                            selected = null
                                            pairingInfo = null
                                        }
                                    }, modifier = Modifier.weight(1f))
                                    if (needsGoformSetupNow && confirmStep == 0) {
                                        UfiButton(text = "下一步", onClick = {
                                            // 轻校验密码：默认态留空即 admin；其余（一致/长度 4-64）由最终 配对 时 doPair 兜底，这里只拦明显非法
                                            val ok = if (infoNow?.hasDefaultPassword == true) {
                                                (password.isBlank() && confirmPassword.isBlank()) ||
                                                    (password == confirmPassword && password.length in 4..64)
                                            } else {
                                                password.isNotBlank()
                                            }
                                            if (!ok) {
                                                toastMessage = ToastMessage(
                                                    "请检查密码：两次输入需一致且长度 4-64 位（或留空使用默认 admin）",
                                                    ToastType.WARNING
                                                )
                                                return@UfiButton
                                            }
                                            confirmStep = 1
                                        }, enabled = !busy && infoNow != null, modifier = Modifier.weight(1f))
                                    } else {
                                        // 初始化态（第 2 步）→ 配对；已配置设备 → 登录（仅密码）
                                        UfiButton(
                                            text = if (needsGoformSetupNow) "配对" else "登录",
                                            onClick = { doPair() },
                                            enabled = !busy && infoNow != null, loading = busy, modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            SetupPhase.MANUAL -> {
                                // 阶段4 修复：IP 输入框 + 端口 + 按钮 + 提示文字 顺序清晰 + spacedBy 间距
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                    UfiTextField(
                                        value = manualIp,
                                        onValueChange = { manualIp = it; isError = false },
                                        label = "设备 IP 地址",
                                        placeholder = "192.168.0.1",
                                        isError = isError && (manualIp.isBlank() || !isValidIpv4(manualIp))
                                    )
                                    UfiDigitField(
                                        value = manualPort,
                                        onValueChange = { manualPort = it },
                                        label = "端口",
                                        isError = isError && manualPort.toIntOrNull() !in 1..65535,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                // v3（2026-08-11）：按钮分散对齐——Row spacedBy(Large) + weight(1f) 让两端贴边+中间留空
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                                ) {
                                    // v3（2026-08-11）：自动搜索按钮加 loading 反馈（解决"点击无反馈"）
                                    // busy 时显示 spinner + 文案"搜索中…" + 禁用；phase 切走时 busy 自动 false
                                    UfiButton(
                                        variant = UfiButtonVariant.Secondary,
                                        text = if (busy && phase == SetupPhase.DISCOVER) "搜索中…" else "自动搜索",
                                        onClick = { startDiscovery() },
                                        enabled = !busy,
                                        loading = busy && phase == SetupPhase.DISCOVER,
                                        modifier = Modifier.weight(1f)
                                    )
                                    UfiButton(
                                        text = "连接",
                                        onClick = { connectManual() },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            }
                        }
                    }
                }
            }

            // v3（2026-08-11）：底部消息提示改用 Toast（更轻量），保留 message 字段兼容但不再渲染卡片
            // 如需主动触发 Toast：toastMessage = ToastMessage(..., ToastType.XXX)

            Spacer(Modifier.height(Spacing.XLarge))
        }

        // v3（2026-08-11）：Toast 反馈宿主（搜索超时提示等）
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

// ───────────────────────────────────────────────────────────
// 模块级网络 / 发现辅助（直接使用 HttpURLConnection，支持动态地址；不走固定 baseUrl 的 Retrofit）
// ───────────────────────────────────────────────────────────

/** 读取本机 DHCP 网关 IP（用于网关探测兜底；设备即网关）。 */
private fun getOwnGateway(context: Context): String? {
    return runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(active) ?: return null
        lp.dhcpServerAddress?.hostAddress
    }.getOrNull()
}

/** 读取本机设备名（Settings.Global.DEVICE_NAME），读不到返回 null（confirm 时可不带 device_name）。 */
private fun readDeviceName(context: Context): String? {
    return runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * GET /pairing/info（免鉴权），返回设备信息或状态。
 * 429（后端每 IP 3s 限流）：等待限流窗口后自动重试一次，仍 429 才报错——
 * 避免"自动发现刚探测过设备→立即选择/手动连接"被误判为设备异常（不可达）。
 */
private suspend fun httpGetPairingInfo(host: String, port: Int, allowRetry: Boolean = true): InfoOutcome =
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$host:$port/pairing/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            when (val code = conn.responseCode) {
                200 -> {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
                    val json = JSONObject(text)
                    val storage = json.optJSONObject("storage_status")
                    InfoOutcome.Success(
                        PairingInfo(
                            deviceId = json.optString("device_id", ""),
                            pairingCode = json.optString("pairing_code", ""),
                            hasRoot = storage?.optBoolean("hasRoot") ?: false,
                            isExternalStorageManager = storage?.optBoolean("isExternalStorageManager") ?: false,
                            deviceName = json.optString("device_name", ""),
                            hasDefaultPassword = json.optBoolean("has_default_password", true)
                        )
                    )
                }
                409 -> InfoOutcome.AlreadyPaired
                429 -> if (allowRetry) {
                    // 后端限流窗口 1s：稍等越过窗口后重试一次
                    kotlinx.coroutines.delay(1200)
                    httpGetPairingInfo(host, port, allowRetry = false)
                } else {
                    InfoOutcome.Error("操作过于频繁 (HTTP 429)，请稍候重试")
                }
                401, 403 -> InfoOutcome.Error("无权限访问配对端点（HTTP $code）")
                else -> InfoOutcome.Error("设备返回异常 (HTTP $code)")
            }
        } catch (e: Exception) {
            InfoOutcome.Unreachable("设备不可达: ${e.message}")
        }
    }

/**
 * POST /pairing/challenge 取一次性挑战。
 * 免鉴权（此刻还没有凭据），失败返回 null，调用方按"设备不可达/异常"处理。
 */
private suspend fun httpGetPairingChallenge(host: String, port: Int): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://$host:$port/pairing/challenge").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode != 200) return@runCatching null
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
            JSONObject(text).optString("challenge", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

/**
 * POST /pairing/confirm {pairing_code, device_pubkey, challenge, signature, device_hwid,
 * password, device_name, goform_ip, goform_port, goform_password}，成功返回 {token, fingerprint}。
 *
 * 设备身份是 [DeviceKeyStore] 里**不可导出**的私钥：这里上报公钥并对服务端下发的挑战签名，
 * 服务端据公钥自己算指纹。客户端不再上报 `app_fingerprint`——那个值可被任意伪造，
 * 是"Web 端所有浏览器共用一个指纹导致配对上限失效"的同源问题。
 *
 * @param context 用于读取 [AppPreferences.deviceHwid]（硬件派生的稳定标识）。后端**仅**用它
 *   合并"同一台手机换了密钥"产生的重复记录，不参与任何安全判定。
 */
private suspend fun httpConfirmPairing(
    context: Context,
    host: String,
    port: Int,
    code: String,
    password: String,
    deviceName: String?,
    goformIp: String? = null,
    goformPort: Int? = null,
    goformPassword: String? = null
): ConfirmOutcome =
    withContext(Dispatchers.IO) {
        val challenge = httpGetPairingChallenge(host, port)
            ?: return@withContext ConfirmOutcome.Unreachable("无法获取配对挑战，请检查设备连接")
        val pubKey = runCatching { DeviceKeyStore.publicKeySpki() }.getOrNull()
            ?: return@withContext ConfirmOutcome.Error("无法生成设备身份密钥（Keystore 不可用）")
        val signature = DeviceKeyStore.sign(challenge)
            ?: return@withContext ConfirmOutcome.Error("设备身份签名失败，请重启应用后重试")
        try {
            val url = URL("http://$host:$port/pairing/confirm")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("pairing_code", code)
                put("device_pubkey", pubKey)
                put("challenge", challenge)
                put("signature", signature)
                // 硬件级稳定标识：仅供后端合并「同一台设备换密钥后」的重复记录
                put("device_hwid", AppPreferences(context).deviceHwid)
                put("password", password)
                if (!deviceName.isNullOrBlank()) put("device_name", deviceName)
                // GoForm 后台连接配置（可选，首次配对一并提交，后端 persistGoformSettings 落库）
                if (!goformIp.isNullOrBlank()) put("goform_ip", goformIp)
                if (goformPort != null) put("goform_port", goformPort)
                if (!goformPassword.isNullOrBlank()) put("goform_password", goformPassword)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (val codeResp = conn.responseCode) {
                200 -> {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
                    val json = JSONObject(text)
                    val token = json.optString("token", "")
                    val fingerprint = json.optString("fingerprint", "")
                    if (token.isBlank()) ConfirmOutcome.Error("配对响应缺少凭据")
                    else ConfirmOutcome.Success(token, fingerprint)
                }
                409 -> ConfirmOutcome.AlreadyPaired
                // 403 只有一种成因：core 把"首次配对"限制在局域网内（隧道来源被拒），
                // 与配对码/密码错误无关，不能落到 Invalid 上显示成"配对码无效"。
                403 -> ConfirmOutcome.Error(
                    readErrorMessage(conn).ifBlank { "该操作只能在设备所在的局域网内完成" }
                )
                400, 401 -> {
                    val errorCode = readErrorCode(conn)
                    when (errorCode) {
                        "INVALID_PASSWORD" -> ConfirmOutcome.InvalidPassword
                        "PASSWORD_REQUIRED" -> ConfirmOutcome.Error("配对密码已设置，请输入配对密码")
                        "PASSWORD_LOCKED" -> ConfirmOutcome.PasswordLocked
                        "INVALID_DEVICE_KEY", "INVALID_CHALLENGE" -> ConfirmOutcome.DeviceKeyRejected
                        else -> ConfirmOutcome.Invalid
                    }
                }
                429 -> {
                    val errorCode = readErrorCode(conn)
                    if (errorCode == "PASSWORD_LOCKED") ConfirmOutcome.PasswordLocked
                    else ConfirmOutcome.Error("请求过于频繁，请稍后再试")
                }
                else -> ConfirmOutcome.Error("配对失败 (HTTP $codeResp)")
            }
        } catch (e: Exception) {
            ConfirmOutcome.Unreachable("设备不可达: ${e.message}")
        }
    }

/** 从错误响应体读取 Core 错误码字段（错误格式 {error, code}）。 */
private fun readErrorCode(conn: HttpURLConnection): String {
    return try {
        val text = BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).readText()
        JSONObject(text).optString("code", "")
    } catch (e: Exception) {
        ""
    }
}

/**
 * 从错误响应体读取面向用户的文案（错误格式 {error, code}）。
 *
 * 与 [readErrorCode] 二选一调用：errorStream 只能读一次。
 */
private fun readErrorMessage(conn: HttpURLConnection): String {
    return try {
        val text = BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).readText()
        JSONObject(text).optString("error", "")
    } catch (e: Exception) {
        ""
    }
}