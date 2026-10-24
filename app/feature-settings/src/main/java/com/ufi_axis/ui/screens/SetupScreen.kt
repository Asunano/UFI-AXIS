package com.ufi_axis.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 *
 * 2026-10-24（布局与动效收敛）：
 *   - **卡片单独垂直居中**；品牌位于卡片上方、随卡片一起移动，但不参与卡片的居中计算
 *     （卡片下方补一段与「品牌 + 间距」等高的留白抵消其高度）。内容超高时整组滚动。
 *   - 每个阶段 / 首配子步骤是一张完整的卡片；外层 `AnimatedContent` 以
 *     `SetupCardKey` 为目标状态，让**整张卡**（背景、边框、按钮、内容）一起做
 *     fade + scale 过渡，而不是只有卡片下半部分变高。
 *   - 应用图标固定 88dp：删除进入配对向导时 88 → 76 的尺寸切换，消除切页时的“异常缩放”。
 *   - 错误反馈从“只弹顶部 Toast”改为字段级 `supportingText` + 红框；Toast 仍保留给
 *     网络 / 服务端 / 批准流程这类无法落到单个字段的结果。
 *   - 设备列表返回时用 `cacheStale` 只作废旧配对码，保留 `cachedInfo.deviceName`，
 *     不会再退回设备 ID。
 */

/** 后端默认监听端口（来自 AppSettings.DEFAULT_PORT / HttpServer 默认 8088），用于网关探测与手动兜底。 */
private const val PAIRING_PORT = 8088

// 2026-10-14：地址校验（isValidIpv4 / isValidHost）抽到同包的 ServerAddress.kt，
// 纯函数 + internal 才能被 ServerAddressTest 逐条覆盖（规则漂移不会有任何异常信号）。
// 支持域名后，"服务器地址"这一栏走 isValidHost；网关探测（startDiscovery）那条路仍然是纯 IP。

private enum class SetupPhase { DISCOVER, CANDIDATES, CONFIRM, MANUAL }

/**
 * 发现/手动输入的设备。
 *
 * [cacheStale] 与 `cachedInfo == null` 分开：返回设备列表时只把缓存**标记为过期**，
 * 保留 `cachedInfo` 里的设备名用于列表展示；下一次选中时按 `cacheStale` 强制重取
 * `/pairing/info`（配对码可能已被服务端轮换），不会拿旧码去 confirm。
 */
private data class DiscoveredDevice(
    val deviceId: String,
    val host: String,
    val port: Int,
    val cachedInfo: PairingInfo? = null,
    val cacheStale: Boolean = false
)

/**
 * 卡片整体过渡的目标状态。用独立类型而不是 `phase`，是为了让 `AnimatedContent`
 * 的**旧卡片**继续按进入时的状态渲染（否则切换瞬间新旧两张卡都会读到新 phase，
 * 既看不到交叉过渡，也会在过渡期闪内容）。
 */
private sealed interface SetupCardKey {
    data object Discover : SetupCardKey
    data object Candidates : SetupCardKey
    data object Manual : SetupCardKey
    data object Loading : SetupCardKey
    data object Login : SetupCardKey
    data class Pairing(val step: Int) : SetupCardKey
}

/** 配对密码的校验结果：分别对应「新密码」和「确认新密码」两个输入框。 */
private data class PasswordValidation(
    val passwordError: String?,
    val confirmError: String?
)

/**
 * 配对密码的唯一校验口径。
 *
 * 首配（[needsSetup] = true）允许两个框都留空 —— 表示使用默认密码 admin；
 * 但只填一个、长度不对、两次不一致都必须拦下。登录（false）只要求非空，
 * 长度与正确性由服务端判定。
 */
private fun validatePairingPassword(
    password: String,
    confirmPassword: String,
    needsSetup: Boolean
): PasswordValidation {
    if (!needsSetup) {
        return PasswordValidation(
            passwordError = if (password.isBlank()) "请输入配对密码" else null,
            confirmError = null
        )
    }

    val bothBlank = password.isBlank() && confirmPassword.isBlank()
    val passwordError = when {
        bothBlank -> null
        password.isBlank() -> "请输入新密码"
        password.length !in 4..64 -> "配对密码长度需为 4–64 位"
        else -> null
    }
    val confirmError = when {
        bothBlank -> null
        confirmPassword.isBlank() -> "请再次输入新密码"
        confirmPassword != password -> "两次输入的密码不一致"
        else -> null
    }
    return PasswordValidation(passwordError, confirmError)
}

private data class PairingInfo(
    val deviceId: String,
    val pairingCode: String,
    val hasRoot: Boolean,
    val isExternalStorageManager: Boolean,
    val deviceName: String = "",
    val hasDefaultPassword: Boolean = true,
    /** 设备上已配对记录数（`/pairing/info` 的 `paired_count`，2026-10-22 起返回）。 */
    val pairedCount: Int = 0
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

    /**
     * 新设备需要机主批准（2026-10-21，审计 §A1）。
     *
     * core 的批准闸门对所有设备生效（`pairingApprovalRequired` + 已有已配对设备 + 指纹未配对），
     * 之前 App 端完全没有请求方链路，于是这个开关一开新手机就永远配不上。
     * 收到本结果 → 登记一次申请并进入等待态（见 `startApprovalRequest`）。
     */
    object ApprovalRequired : ConfirmOutcome()
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // 品牌块的实际高度（含 animateContentSize 的中间帧）。卡片要**单独**垂直居中，
    // 需要在卡片下方补一段与「品牌 + 间距」等高的留白，抵消品牌占掉的高度。
    var brandHeight by remember { mutableStateOf(152.dp) }
    var phase by remember { mutableStateOf(SetupPhase.DISCOVER) }
    var candidates by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pairingInfo by remember { mutableStateOf<PairingInfo?>(null) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("$PAIRING_PORT") }
    var manualError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 配对密码（阶段3）：首次配对设置（新+确认）/ 再次配对校验（单框）
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    /** 提交后才点亮字段错误，避免用户刚进页面就看到一片红。输入变化时清除。 */
    var showPasswordErrors by remember { mutableStateOf(false) }
    /** 服务端明确拒绝密码后的字段提示（登录时显示在密码框下方）。 */
    var passwordServerError by remember { mutableStateOf<String?>(null) }

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

    // v3（2026-08-11）：Toast 反馈（UfiToastHost 持有 state）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 自动搜索 Job 必须 remember 才有意义：普通局部变量每次重组都会归 null，
    // 用户点「手动输入地址」时 `discoveryJob?.cancel()` 实际取消不到任何东西，
    // 5 秒超时分支随后会把已进入 CONFIRM 的用户强行拉回 MANUAL。
    var discoveryJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 每次搜索/取消都自增；旧 Job 即使因 HttpURLConnection 不可协作取消而晚返回，
    // 也会在写 phase 前发现 token 已过期而放弃 —— 单靠 Job.cancel() 挡不住这种竞态。
    var discoveryToken by remember { mutableStateOf(0L) }
    // 每次选中设备/返回列表都自增：Loading 卡片上的「返回」不能让上一次 /pairing/info
    // 晚到的成功结果把界面重新拉回那台设备。
    var selectionToken by remember { mutableStateOf(0L) }

    // ── 新设备批准：请求方这一半（2026-10-21，审计 §A1）──
    //
    // core 的批准闸门对**所有**设备生效（没有"只覆盖 Web"的判据），所以 App 必须也能走
    // 「登记申请 → 等机主批准 → 带同一个 sid 重试 confirm」，否则该开关一开新手机就永远配不上。
    var approvalWaiting by remember { mutableStateOf(false) }
    var approvalSid by remember { mutableStateOf("") }
    var approvalStatusText by remember { mutableStateOf("") }
    var approvalRetryCode by remember { mutableStateOf("") }
    var approvalPollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** 需要"发起一次申请"（doPair 收到 403 时置位，由 LaunchedEffect 接力，避免局部函数互递归）。 */
    var approvalRequestPending by remember { mutableStateOf(false) }
    /** 已批准、需要重试 confirm（同上，由 LaunchedEffect 接力）。 */
    var approvalRetryPending by remember { mutableStateOf(false) }
    /** 「本机旧配对自救」弹窗（2026-10-21 决策 D6-2）。 */
    var showSelfRescue by remember { mutableStateOf(false) }
    var selfRescuePwd by remember { mutableStateOf("") }
    var selfRescueBusy by remember { mutableStateOf(false) }

    /** 取消当前探测，并让旧 Job 在返回后不再写任何界面状态。 */
    fun cancelDiscovery() {
        discoveryToken += 1
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun startDiscovery() {
        cancelDiscovery()
        keyboardController?.hide()
        focusManager.clearFocus()
        val token = discoveryToken
        phase = SetupPhase.DISCOVER
        busy = true
        manualError = false
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
                    // Job.cancel() 对阻塞中的 HttpURLConnection 不一定立刻生效；
                    // token 是第二道闸：旧搜索晚返回时不能再改 phase。
                    if (discoveryToken != token) return@withTimeoutOrNull
                    when (val r = httpGetPairingInfo(gw, PAIRING_PORT)) {
                        is InfoOutcome.Success -> {
                            if (discoveryToken != token) return@withTimeoutOrNull
                            candidates = listOf(
                                DiscoveredDevice(
                                    deviceId = r.info.deviceId,
                                    host = gw,
                                    port = PAIRING_PORT,
                                    cachedInfo = r.info
                                )
                            )
                            phase = SetupPhase.CANDIDATES
                            busy = false
                            found = true
                            return@withTimeoutOrNull
                        }
                        is InfoOutcome.AlreadyPaired -> {
                            if (discoveryToken != token) return@withTimeoutOrNull
                            toastMessage = ToastMessage(
                                "检测到该设备已完成配对。请先在设备端或配对网页解除配对，然后重试；也可以手动输入其他设备地址。",
                                ToastType.WARNING
                            )
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
                if (discoveryToken != token) return@launch
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 5000) kotlinx.coroutines.delay(5000 - elapsed)
                if (discoveryToken != token) return@launch
                phase = SetupPhase.MANUAL
                busy = false
                toastMessage = ToastMessage("未发现附近设备，请手动输入设备地址和端口。", ToastType.INFO)
            }
            // found=true 时到此结束，绝不再触碰 phase —— 避免覆盖用户已进入的 CANDIDATES/CONFIRM 状态
        }
    }

    fun selectDevice(device: DiscoveredDevice) {
        // 手动输入时初始探测可能还在跑：这里先取消，避免它 5 秒后把用户从 CONFIRM 拉回 MANUAL。
        cancelDiscovery()
        selectionToken += 1
        val token = selectionToken
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
        manualError = false
        showPasswordErrors = false
        passwordServerError = null
        phase = SetupPhase.CONFIRM
        // 只有**新鲜**缓存才直接复用；返回设备列表时缓存的配对码可能已被服务端轮换，
        // 复用它会必然 INVALID_CODE。cacheStale 只丢弃码，保留设备名用于列表展示。
        if (!device.cacheStale) {
            device.cachedInfo?.let {
                pairingInfo = it
                busy = false
                return
            }
        }
        scope.launch {
            val result = httpGetPairingInfo(device.host, device.port)
            // 用户已经返回列表/选了别的设备：这次晚到的结果必须丢弃。
            if (selectionToken != token) return@launch
            when (result) {
                is InfoOutcome.Success -> {
                    val fresh = device.copy(cachedInfo = result.info, cacheStale = false)
                    selected = fresh
                    pairingInfo = result.info
                    // 同步候选列表里的缓存与名称，返回列表时不会退回设备 ID。
                    candidates = candidates.map { c ->
                        if (c.deviceId == device.deviceId && c.host == device.host && c.port == device.port) {
                            c.copy(cachedInfo = result.info, cacheStale = false)
                        } else {
                            c
                        }
                    }
                    busy = false
                }
                is InfoOutcome.AlreadyPaired -> {
                    // 已配对时不再卡住界面，提示后回 MANUAL 让用户重选
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage(
                        "该设备已完成配对。请先在设备端或配对网页解除配对，然后重试。",
                        ToastType.WARNING
                    )
                    selected = null
                    pairingInfo = null
                }
                is InfoOutcome.Error -> {
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage("获取设备信息失败：${result.msg}", ToastType.ERROR)
                    selected = null
                    pairingInfo = null
                }
                is InfoOutcome.Unreachable -> {
                    busy = false
                    phase = SetupPhase.MANUAL
                    toastMessage = ToastMessage("无法连接设备：${result.msg}。请检查地址和端口后重试。", ToastType.ERROR)
                    selected = null
                    pairingInfo = null
                }
            }
        }
    }

    fun connectManual() {
        // 2026-10-14：主机校验与端口校验合并到 checkServerEndpoint（ServerAddress.kt）。
        // 判定规则与设置页「连接配置」共用同一份；具体原因由输入框的 supportingText 显示，
        // 不再用一条 3 秒后消失的顶部 Toast 承担。
        when (val r = checkServerEndpoint(manualIp, manualPort)) {
            is ServerEndpoint.Valid -> selectDevice(DiscoveredDevice("", r.host, r.port))
            ServerEndpoint.BlankHost,
            ServerEndpoint.InvalidHost,
            ServerEndpoint.InvalidPort -> manualError = true
        }
    }

    fun doPair() {
        if (busy) return
        val device = selected ?: return
        val info = pairingInfo ?: return
        val needsGoformSetup = info.hasDefaultPassword
        // 重新提交时先摘掉上一次的服务端错误，避免它盖住本次的格式校验提示。
        passwordServerError = null

        // 密码校验与「下一步」共用同一个纯函数，避免两处规则/顺序漂移。
        val validation = validatePairingPassword(password, confirmPassword, needsGoformSetup)
        if (validation.passwordError != null || validation.confirmError != null) {
            showPasswordErrors = true
            if (needsGoformSetup) confirmStep = 0
            toastMessage = ToastMessage(
                validation.passwordError ?: validation.confirmError!!,
                ToastType.WARNING
            )
            return
        }
        showPasswordErrors = false
        // 默认密码只在本次请求里生效，不写回输入框 —— 失败后复核页仍然显示「使用默认密码 admin」。
        val effectivePassword = if (
            needsGoformSetup && password.isBlank() && confirmPassword.isBlank()
        ) {
            "admin"
        } else {
            password
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
            // GoForm 后台地址同样放行域名（2026-10-14）：路由器后台用 `tplogin.cn` /
            // `router.local` 是真实场景，而且 core 侧 goform_ip 只是个字符串（Web 端
            // 只查非空就能用），本屏若继续只认 IPv4 会与既有链路不一致。
            if (gfIp.isNotBlank() && (!isValidHost(gfIp) || gfPort == null || gfPort !in 1..65535)) {
                // 跳回 GoForm 步骤，让红框和 supportingText 可见，而不是只在顶部闪一句 Toast。
                confirmStep = 1
                toastMessage = ToastMessage("GoForm 地址格式不正确，请检查后重试。", ToastType.WARNING)
                return
            }
            gfIpToSend = gfIp.takeIf { it.isNotBlank() }
            gfPortToSend = gfPort
            gfPwToSend = goformPassword.takeIf { it.isNotBlank() }
        }
        // 提交后收起键盘：加载态与后续卡片不会被输入法挡住，也避免焦点留在即将离场的输入框上。
        focusManager.clearFocus()
        keyboardController?.hide()
        busy = true
        scope.launch {
            val deviceName = readDeviceName(context)
            when (
                val r = httpConfirmPairing(
                    context, device.host, device.port, info.pairingCode, effectivePassword, deviceName,
                    gfIpToSend, gfPortToSend, gfPwToSend,
                    // 2026-10-21（审计 §A1）：批准流程的两个字段。没走批准时都是空，
                    // core 按"不需要批准"处理，行为与改动前完全一致。
                    sid = approvalSid,
                    approvalCode = approvalRetryCode.takeIf { it.isNotBlank() }
                )
            ) {
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
                    toastMessage = ToastMessage("该设备已被其他设备配对，请先在设备端解除配对。", ToastType.WARNING)
                }
                is ConfirmOutcome.Invalid -> {
                    busy = false
                    toastMessage = ToastMessage("配对码无效或已失效，请返回并重新选择设备。", ToastType.WARNING)
                }
                is ConfirmOutcome.InvalidPassword -> {
                    busy = false
                    // 登录场景把原因固定显示在密码框下方；首配的密码是本次新设的，
                    // 服务端不会返回这个分支，保持顶部提示即可。
                    if (!needsGoformSetup) {
                        passwordServerError = "配对密码错误，请重新输入。"
                        confirmStep = 0
                    }
                    toastMessage = ToastMessage("配对密码错误，请重新输入。", ToastType.WARNING)
                }
                is ConfirmOutcome.PasswordLocked -> {
                    busy = false
                    if (!needsGoformSetup) {
                        passwordServerError = "密码错误次数过多，请稍后再试。"
                    }
                    toastMessage = ToastMessage("密码错误次数过多，请稍后再试。", ToastType.WARNING)
                }
                is ConfirmOutcome.DeviceKeyRejected -> {
                    busy = false
                    toastMessage = ToastMessage("设备身份校验失败，请重新配对。", ToastType.WARNING)
                }
                is ConfirmOutcome.ApprovalRequired -> {
                    // 2026-10-21（审计 §A1）：机主开了「新设备需批准」。登记一次申请并进等待态。
                    // 用一个 state 标记转交，而不是直接调 startApprovalRequest()：
                    // 那个函数要调 doPair()（批准后重试），而 doPair() 又要调它 —— Kotlin 的
                    // 局部函数不支持前向引用/互递归，用 LaunchedEffect 接力最干净。
                    busy = false
                    approvalRequestPending = true
                }
                is ConfirmOutcome.Unreachable -> {
                    busy = false
                    toastMessage = ToastMessage(
                        r.msg.ifBlank { "无法连接设备，请检查网络后重试。" },
                        ToastType.ERROR
                    )
                }
                is ConfirmOutcome.Error -> {
                    busy = false
                    toastMessage = ToastMessage(r.msg.ifBlank { "配对失败，请稍后重试。" }, ToastType.ERROR)
                }
            }
        }
    }

    /** 收尾：停轮询、清等待态与 sid（下次要重新登记）。 */
    fun cancelApproval() {
        approvalPollJob?.cancel()
        approvalPollJob = null
        approvalWaiting = false
        approvalSid = ""
        approvalRetryCode = ""
        approvalStatusText = ""
    }

    /**
     * 每 1.5 秒问一次批准状态；批准后交给 LaunchedEffect 去重试 confirm。
     *
     * 15 分钟上限与服务端的待批准 TTL 对齐：过了 TTL 服务端已经把这条请求清掉，
     * 再问只会一直拿到 expired。
     */
    fun pollApprovalStatus(device: DiscoveredDevice) {
        approvalPollJob?.cancel()
        approvalPollJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(1_500)
                if (System.currentTimeMillis() - startedAt > 15 * 60_000L) {
                    cancelApproval()
                    toastMessage = ToastMessage("配对申请已过期，请重新发起。", ToastType.WARNING)
                    return@launch
                }
                when (httpGetApprovalStatus(device.host, device.port, approvalSid)) {
                    "approved" -> {
                        approvalStatusText = "已批准，正在完成配对…"
                        approvalRetryPending = true
                        return@launch
                    }
                    "rejected" -> {
                        cancelApproval()
                        toastMessage = ToastMessage("机主已拒绝本次配对申请。", ToastType.WARNING)
                        return@launch
                    }
                    "expired" -> {
                        cancelApproval()
                        toastMessage = ToastMessage("配对申请已过期，请重新发起。", ToastType.WARNING)
                        return@launch
                    }
                    // null = 这次请求本身失败（网络抖动 / 429 限速），继续等下一轮；
                    // 绝不能把抖动当成过期 —— 与 web 端 fetchApprovalStatus 同一约定。
                    else -> Unit
                }
            }
        }
    }

    /**
     * 登记一次批准申请并开始轮询（2026-10-21，审计 §A1）。
     *
     * 只在 doPair 收到 403 APPROVAL_REQUIRED 之后调用 —— 与 web 端同一原则：
     * **不能**每次配对都先登记一次，否则机主把批准关着时，每次登录都会凭空造一条待批准记录。
     */
    fun startApprovalRequest() {
        val device = selected ?: return
        approvalWaiting = true
        approvalRetryCode = ""
        approvalStatusText = "正在提交配对申请…"
        scope.launch {
            val pub = runCatching { DeviceKeyStore.publicKeySpki() }.getOrNull().orEmpty()
            val sid = newApprovalSid()
            approvalSid = sid
            val registered = httpRequestApproval(
                device.host, device.port, sid, pub, readDeviceName(context).orEmpty()
            )
            if (!registered) {
                approvalWaiting = false
                approvalSid = ""
                approvalStatusText = ""
                toastMessage = ToastMessage("提交配对申请失败，可能操作过于频繁，请稍后重试。", ToastType.ERROR)
                return@launch
            }
            approvalStatusText = "等待机主在已配对设备上点「允许」…"
            pollApprovalStatus(device)
        }
    }

    LaunchedEffect(Unit) {
        // 2026-10-22：上一次是被"踢回来"的吗？读一次、清掉、把原因告诉用户。
        // 没有这句，用户只会看到"又要配对"，而掉线原因（凭据被移除/被拉黑/设备库恢复）
        // 在界面上完全不可见 —— "每次打开都要重新登录"这类问题就是这样变得无从查起的。
        val reasonPrefs = AppPreferences(context)
        val lastReason = reasonPrefs.lastLogoutReason
        if (lastReason.isNotBlank()) {
            reasonPrefs.lastLogoutReason = ""
            toastMessage = ToastMessage("上次连接已失效：$lastReason", ToastType.WARNING)
        }
        startDiscovery()
    }
    // 批准流程的两个"接力"（避免局部函数互递归，见 approvalRequestPending 的注释）
    LaunchedEffect(approvalRequestPending) {
        if (approvalRequestPending) {
            approvalRequestPending = false
            startApprovalRequest()
        }
    }
    LaunchedEffect(approvalRetryPending) {
        if (approvalRetryPending) {
            approvalRetryPending = false
            doPair()
        }
    }
    // 切换设备/重新拉取信息时重置密码输入与子步骤（避免沿用上一台/上一次输入，并回到第 1 步）。
    // 2026-08-22：needsGoformSetup 派生自 pairingInfo.hasDefaultPassword，每次切设备自动重新派生，不再需要单独的 reconnectMode state 重置。
    LaunchedEffect(pairingInfo) {
        password = ""
        confirmPassword = ""
        confirmStep = 0
        showPasswordErrors = false
        passwordServerError = null
        manualError = false
        // GoForm 密码属于「这台设备的后台」，换设备必须清空，避免把上一台的密码提交给下一台。
        goformPassword = ""
    }

    Scaffold(
        containerColor = palette.pageBg,
        // ★★ 2026-09-04（转场区满屏化 · 全库第二处）★★
        //
        // 本页**不走 MainNavGraph**（`MainActivity` 在未配对时直接渲染它）。
        // 默认的 `ScaffoldDefaults.contentWindowInsets`（= safeDrawing）会把 padding 加在
        // 滚动视口之外，内容在安全区边界被裁掉、下方留一条静态色带。
        // 这里清零，由页内自己分层处理：
        //   - 外层 Column：systemBars ∪ displayCutout（品牌贴顶时避开状态栏 / 导航栏 / 刘海）；
        //   - 卡片滚动区：ime（键盘弹出时卡片在键盘上方重新居中，品牌不跟着跳）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        // App 品牌标记（2026-08-11 v3：静态呈现，移除所有缩放/呼吸/光环/淡入动画）
        // 用户反馈"标题顶部的图标不要动来动去"——保持克制安静，依赖纯视觉权重传达品牌
        // 2026-09-07：改为直接渲染「应用自己的启动图标」，不再用手抄的矢量副本 + tint。
        // 旧写法（ic_app_logo.xml 副本 + Icon(tint = accent) + 12% 强调色圆底）有两个问题：
        //   ① Icon 会把矢量按 alpha 整体重染成单色，图标自带配色全部丢失；
        //   ② 再叠一层低不透明度强调色圆底，观感就是「灰底 + 单色线条」，不是真正的应用图标。
        // 为什么走 PackageManager 而不是 painterResource：本屏在 :app:feature-settings（library）
        // 模块里，依赖方向是 :app → :app:feature-settings，反向拿不到 :app 的 @mipmap/ic_launcher。
        // getApplicationIcon() 取的正是 manifest android:icon 指向的那份 adaptive-icon
        // （背景层 + 前景层都在），所以换图标时这里自动跟随，不需要再维护任何副本。
        //
        // 所有阶段共用同一张品牌块与同一份图标位图；按 120dp 解码、显示时固定 88dp，
        // 既保留高分屏余量，也不会因为阶段切换改尺寸而重新解码或缩放。
        val iconSizePx = with(LocalDensity.current) { 120.dp.roundToPx() }
        val appIconBitmap = remember(iconSizePx) {
            val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
            val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, iconSizePx, iconSizePx)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }

        /** 回到设备选择。返回时保留设备名，只把配对码缓存标记为过期。 */
        fun backToDeviceChoice() {
            focusManager.clearFocus()
            keyboardController?.hide()
            // 让还在路上的 /pairing/info 结果作废，并结束 Loading 态。
            selectionToken += 1
            busy = false
            phase = if (candidates.isNotEmpty()) SetupPhase.CANDIDATES else SetupPhase.MANUAL
            selected = null
            pairingInfo = null
            // 2026-10-21（审计 §B6）：core 在 confirm 任何失败后都可能轮换配对码。
            // 不能继续复用 cachedInfo 里的旧码，否则返回后重选同一台设备必然 INVALID_CODE。
            // 但也不能把 cachedInfo 整个丢掉 —— 设备名就在里面，丢了列表会退回设备 ID。
            // cacheStale 只让 selectDevice 重新拉一次 /pairing/info，展示字段原样保留。
            candidates = candidates.map { it.copy(cacheStale = true) }
            // 批准等待态一并收尾：换了设备，旧的 sid 已经没有意义
            cancelApproval()
            showPasswordErrors = false
            passwordServerError = null
            manualError = false
        }

        val info = pairingInfo
        val needsGoformNow = info?.hasDefaultPassword == true
        val deviceLabel = info?.deviceName?.ifBlank { info.deviceId }.orEmpty()
        val deviceAddress = selected?.let { "${it.host}:${it.port}" }.orEmpty()
        val passwordValidation = validatePairingPassword(password, confirmPassword, needsGoformNow)

        /** 卡片整体过渡的目标状态：phase + 首配子步骤 + 是否已拿到 pairingInfo。 */
        val cardKey: SetupCardKey = when {
            phase == SetupPhase.DISCOVER -> SetupCardKey.Discover
            phase == SetupPhase.CANDIDATES -> SetupCardKey.Candidates
            phase == SetupPhase.MANUAL -> SetupCardKey.Manual
            info == null -> SetupCardKey.Loading
            needsGoformNow -> SetupCardKey.Pairing(confirmStep)
            else -> SetupCardKey.Login
        }
        // 小屏上整组（品牌 + 卡片）可能滚到底部才点「下一步」；换卡后必须回到顶部，
        // 否则新卡片会带着上一张的滚动偏移出现。
        val contentScrollState = rememberScrollState()
        LaunchedEffect(cardKey) { contentScrollState.scrollTo(0) }

        /** GoForm 步能否离开。地址留空 = 不配置，后端不会覆盖已有值。 */
        fun goformError(): String? {
            val parts = goformAddress.split(":")
            val host = parts.getOrNull(0)?.trim().orEmpty()
            val port = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (host.isBlank()) return null
            return if (!isValidHost(host) || port == null || port !in 1..65535) {
                "格式应为 地址:端口，例如 192.168.0.1:8080 或 router.local:80"
            } else {
                null
            }
        }

        /** 主按钮：首配分步推进（每步先校验），登录/末步直接提交。 */
        fun onPrimaryAction() {
            if (!needsGoformNow) {
                doPair()
                return
            }
            when (confirmStep) {
                0 -> {
                    val validation = validatePairingPassword(password, confirmPassword, needsSetup = true)
                    if (validation.passwordError != null || validation.confirmError != null) {
                        showPasswordErrors = true
                        toastMessage = ToastMessage(
                            validation.passwordError ?: validation.confirmError!!,
                            ToastType.WARNING
                        )
                    } else {
                        showPasswordErrors = false
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        confirmStep = 1
                    }
                }
                1 -> {
                    val err = goformError()
                    if (err != null) {
                        toastMessage = ToastMessage("GoForm 地址格式不正确，请检查后重试。", ToastType.WARNING)
                    } else {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        confirmStep = 2
                    }
                }
                else -> doPair()
            }
        }

        BackHandler(
            // 对话框自己处理返回键；DISCOVER 是根页面，按返回退出 App 符合预期。
            // 注意**不能**把 busy 写进 enabled：BackHandler 关闭时系统返回会直接 finish Activity，
            // 登录/读信息请求在途时按返回会变成「退出 App」，比原来的「无拦截」更糟。
            enabled = !approvalWaiting && !showSelfRescue && phase != SetupPhase.DISCOVER
        ) {
            when {
                busy && phase == SetupPhase.CONFIRM && info == null -> {
                    // 读取 pairingInfo 期间：等价于 Loading 卡片上的「返回」，并让晚到的结果作废。
                    backToDeviceChoice()
                }
                busy -> {
                    // 登录 / 配对提交在途：消费返回键，避免退出 App；请求有超时，不会永久卡住。
                    toastMessage = ToastMessage("正在处理，请稍候…", ToastType.INFO)
                }
                phase == SetupPhase.CONFIRM && needsGoformNow && confirmStep > 0 -> {
                    confirmStep -= 1
                    showPasswordErrors = false
                    passwordServerError = null
                }
                phase == SetupPhase.CONFIRM -> backToDeviceChoice()
                phase == SetupPhase.MANUAL -> {
                    if (candidates.isNotEmpty()) {
                        phase = SetupPhase.CANDIDATES
                        manualError = false
                    } else {
                        startDiscovery()
                    }
                }
                phase == SetupPhase.CANDIDATES -> startDiscovery()
            }
        }

        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize(), useGradient = true) {
            SetupDots()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 品牌 + 卡片同列；下面再补一段品牌等高的留白，抵消品牌后卡片单独垂直居中。
                    // 品牌位于卡片上方并随卡片移动。内容超高时整组滚动；
                    // safeDrawing 含 IME，键盘弹出时卡片在键盘上方居中。
                    .verticalScroll(contentScrollState)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = Spacing.XLarge, vertical = Spacing.XLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val inWizardNow = phase == SetupPhase.CONFIRM && info != null
                val brandSubtitle = when {
                    inWizardNow -> ""
                    phase == SetupPhase.DISCOVER -> "连接到 UFI-AXIS-Core 服务"
                    phase == SetupPhase.MANUAL -> "输入设备地址，或自动搜索"
                    phase == SetupPhase.CANDIDATES -> "发现 ${candidates.size} 台设备，请选择要连接的设备"
                    // CONFIRM 且尚未拿到 pairingInfo：状态由卡片内的「正在读取设备信息…」承担，
                    // 不在品牌下重复一遍。
                    else -> ""
                }
                SetupBrand(
                    bitmap = appIconBitmap,
                    title = when {
                        !inWizardNow -> "UFI-AXIS"
                        needsGoformNow -> "配对新设备"
                        else -> deviceLabel.ifBlank { "登录设备" }
                    },
                    subtitle = brandSubtitle,
                    deviceName = if (inWizardNow && needsGoformNow) deviceLabel else "",
                    // 读取 pairingInfo 期间（inWizardNow 还是 false）也保留地址，
                    // 用户始终知道正在连哪台设备。
                    address = if (phase == SetupPhase.CONFIRM) deviceAddress else "",
                    modifier = Modifier.onSizeChanged {
                        brandHeight = with(density) { it.height.toDp() }
                    }
                )
                Spacer(Modifier.height(24.dp))

                // 品牌与卡片同属一条居中竖列；外层 Column 负责整组滚动，这里不再单独定高。
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedContent(
                            targetState = cardKey,
                            transitionSpec = {
                                val enter = fadeIn(tween(220)) + scaleIn(
                                    animationSpec = tween(280),
                                    initialScale = 0.92f
                                )
                                val exit = fadeOut(tween(160)) + scaleOut(
                                    animationSpec = tween(180),
                                    targetScale = 0.96f
                                )
                                enter.togetherWith(exit).using(SizeTransform(clip = false))
                            },
                            contentAlignment = Alignment.Center,
                            label = "setupCard",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) { key ->
                            SetupCard {
                                when (key) {
                                    SetupCardKey.Discover -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                                        ) {
                                            // 雷达扫描（三层波纹错相位），颜色全部取自 palette
                                            val infiniteTransition = rememberInfiniteTransition(label = "setupScan")
                                            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                                Box(
                                                    Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(palette.accent)
                                                )
                                                for (i in 0..2) {
                                                    val scale by infiniteTransition.animateFloat(
                                                        initialValue = 0.4f,
                                                        targetValue = 1.4f,
                                                        animationSpec = infiniteRepeatable(
                                                            animation = tween(
                                                                durationMillis = UfiMotion.Duration.Pulse,
                                                                delayMillis = i * 500
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
                                                                delayMillis = i * 500
                                                            ),
                                                            repeatMode = RepeatMode.Restart
                                                        ),
                                                        label = "scanAlpha$i"
                                                    )
                                                    Box(
                                                        Modifier
                                                            .size(24.dp)
                                                            .graphicsLayer { scaleX = scale; scaleY = scale }
                                                            .alpha(alpha)
                                                            .clip(CircleShape)
                                                            .background(palette.accent)
                                                    )
                                                }
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = "正在搜索附近的设备…",
                                                    style = UfiTextStyles.bodyEmphasis,
                                                    color = palette.textPrimary
                                                )
                                                Text(
                                                    text = "同一局域网内通常可在 5 秒内完成",
                                                    style = UfiTextStyles.caption,
                                                    color = palette.textSecondary
                                                )
                                            }
                                        }
                                        UfiButton(
                                            size = UfiButtonSize.Large,
                                            text = "手动输入地址",
                                            variant = UfiButtonVariant.Secondary,
                                            onClick = {
                                                cancelDiscovery()
                                                phase = SetupPhase.MANUAL
                                                busy = false
                                                manualError = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    SetupCardKey.Candidates -> {
                                        candidates.forEach { d ->
                                            val label = d.cachedInfo?.deviceName?.takeIf { it.isNotBlank() }
                                                ?: if (d.deviceId.isNotBlank()) d.deviceId else d.host
                                            SetupDeviceRow(
                                                title = label,
                                                subtitle = "${d.host}:${d.port}",
                                                onClick = { selectDevice(d) }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                                        ) {
                                            // 设备行本身是主操作，这两个是次要动作：
                                            // 用 Standard 档（48dp）而不是 Large，320dp 窄屏也不会被省略号截断。
                                            UfiButton(
                                                text = "重新搜索",
                                                variant = UfiButtonVariant.Secondary,
                                                onClick = { startDiscovery() },
                                                modifier = Modifier.weight(1f)
                                            )
                                            UfiButton(
                                                text = "手动输入",
                                                variant = UfiButtonVariant.Secondary,
                                                onClick = {
                                                    cancelDiscovery()
                                                    phase = SetupPhase.MANUAL
                                                    busy = false
                                                    manualError = false
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    SetupCardKey.Manual -> {
                                        val hostError = if (manualError) {
                                            when {
                                                manualIp.isBlank() -> "请输入设备地址"
                                                !isValidHost(manualIp.trim()) ->
                                                    "设备地址格式不正确，例如 192.168.0.1 或 nas.local"
                                                else -> null
                                            }
                                        } else {
                                            null
                                        }
                                        val portError = if (
                                            manualError && manualPort.toIntOrNull() !in 1..65535
                                        ) {
                                            "端口号需在 1–65535 范围内"
                                        } else {
                                            null
                                        }

                                        UfiTextField(
                                            value = manualIp,
                                            onValueChange = { manualIp = it; manualError = false },
                                            label = "设备地址（IP 或域名）",
                                            placeholder = "192.168.0.1 或 nas.local",
                                            isError = hostError != null,
                                            errorMessage = hostError,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Next
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        UfiDigitField(
                                            value = manualPort,
                                            onValueChange = { manualPort = it; manualError = false },
                                            label = "端口",
                                            isError = portError != null,
                                            errorMessage = portError,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        val lastHost = setupPrefs.serverIp
                                        val lastPort = setupPrefs.serverPort
                                        if (
                                            lastHost.isNotBlank() &&
                                            (lastHost != manualIp || lastPort.toString() != manualPort)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .clickable(role = Role.Button) {
                                                        manualIp = lastHost
                                                        manualPort = lastPort.toString()
                                                        manualError = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "上次连接：$lastHost:$lastPort · 点击回填",
                                                    style = UfiTextStyles.caption,
                                                    color = palette.textSecondary,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                        UfiButton(
                                            size = UfiButtonSize.Large,
                                            text = "连接",
                                            onClick = { connectManual() },
                                            enabled = !busy,
                                            loading = busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        UfiButton(
                                            variant = UfiButtonVariant.Secondary,
                                            text = if (busy) "搜索中…" else "自动搜索",
                                            onClick = { startDiscovery() },
                                            enabled = !busy,
                                            loading = busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    SetupCardKey.Loading -> {
                                        UfiLoadingIndicator(modifier = Modifier.size(28.dp))
                                        Text(
                                            text = "正在读取设备信息…",
                                            style = UfiTextStyles.onboardingNote,
                                            color = palette.textSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                        UfiButton(
                                            variant = UfiButtonVariant.Secondary,
                                            text = "返回",
                                            onClick = { backToDeviceChoice() },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    SetupCardKey.Login -> {
                                        SetupStepText(
                                            heading = "输入配对密码",
                                            description = "输入这台设备的配对密码即可登录。"
                                        )
                                        val fieldError = passwordServerError
                                            ?: if (showPasswordErrors) {
                                                passwordValidation.passwordError
                                            } else {
                                                null
                                            }
                                        UfiPasswordField(
                                            value = password,
                                            onValueChange = { input ->
                                                if (input.all { it.code in 33..126 }) {
                                                    password = input.take(64)
                                                    showPasswordErrors = false
                                                    passwordServerError = null
                                                } else {
                                                    toastMessage = ToastMessage(
                                                        "配对密码仅支持英文字母、数字和符号。",
                                                        ToastType.WARNING
                                                    )
                                                }
                                            },
                                            label = "配对密码",
                                            isError = fieldError != null,
                                            errorMessage = fieldError,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(onDone = { onPrimaryAction() }),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        UfiButton(
                                            size = UfiButtonSize.Large,
                                            text = "登录",
                                            onClick = { onPrimaryAction() },
                                            enabled = !busy,
                                            loading = busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        // 次要动作也必须是按钮（2026-10-23 用户反馈）：
                                        // M3 的 TextButton 没有描边也没有底色，在卡内看起来就是一行普通文字。
                                        UfiButton(
                                            variant = UfiButtonVariant.Secondary,
                                            text = "选择其他设备",
                                            onClick = { backToDeviceChoice() },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    is SetupCardKey.Pairing -> {
                                        val step = key.step
                                        SetupStepDots(
                                            count = 3,
                                            index = step,
                                            label = when (step) {
                                                0 -> "1 / 3 · 密码"
                                                1 -> "2 / 3 · 原厂后台"
                                                else -> "3 / 3 · 确认"
                                            }
                                        )
                                        UfiDivider()
                                        when (step) {
                                            0 -> {
                                                SetupStepText(
                                                    heading = "设置配对密码",
                                                    description = "用于在其他设备上登录本设备，请妥善保管。"
                                                )
                                                UfiPasswordField(
                                                    value = password,
                                                    onValueChange = { input ->
                                                        if (input.all { it.code in 33..126 }) {
                                                            password = input.take(64)
                                                            showPasswordErrors = false
                                                        } else {
                                                            toastMessage = ToastMessage(
                                                                "配对密码仅支持英文字母、数字和符号。",
                                                                ToastType.WARNING
                                                            )
                                                        }
                                                    },
                                                    label = "新密码（4–64 位）",
                                                    isError = showPasswordErrors &&
                                                        passwordValidation.passwordError != null,
                                                    errorMessage = if (showPasswordErrors) {
                                                        passwordValidation.passwordError
                                                    } else {
                                                        null
                                                    },
                                                    keyboardOptions = KeyboardOptions(
                                                        keyboardType = KeyboardType.Password,
                                                        imeAction = ImeAction.Next
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                UfiPasswordField(
                                                    value = confirmPassword,
                                                    onValueChange = { input ->
                                                        if (input.all { it.code in 33..126 }) {
                                                            confirmPassword = input.take(64)
                                                            showPasswordErrors = false
                                                        } else {
                                                            toastMessage = ToastMessage(
                                                                "配对密码仅支持英文字母、数字和符号。",
                                                                ToastType.WARNING
                                                            )
                                                        }
                                                    },
                                                    label = "确认新密码",
                                                    isError = showPasswordErrors &&
                                                        passwordValidation.confirmError != null,
                                                    errorMessage = if (showPasswordErrors) {
                                                        passwordValidation.confirmError
                                                    } else {
                                                        null
                                                    },
                                                    keyboardOptions = KeyboardOptions(
                                                        keyboardType = KeyboardType.Password,
                                                        imeAction = ImeAction.Done
                                                    ),
                                                    keyboardActions = KeyboardActions(
                                                        onDone = { onPrimaryAction() }
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Text(
                                                    text = "仅支持英文字母、数字和符号，长度 4–64 位；" +
                                                        "留空将使用默认密码 admin（不推荐）。",
                                                    style = UfiTextStyles.caption,
                                                    color = palette.textSecondary,
                                                    textAlign = TextAlign.Center
                                                )
                                            }

                                            1 -> {
                                                SetupStepText(
                                                    heading = "配置原厂后台（可选）",
                                                    description = "配置后可获取更详细的网络与信号数据。地址已按常见配置预填；" +
                                                        "如不配置，请保持地址为空并继续，设备现有设置不会被修改。"
                                                )
                                                val goformErr = goformError()
                                                UfiTextField(
                                                    value = goformAddress,
                                                    onValueChange = { goformAddress = it },
                                                    label = "GoForm 地址（IP 或域名:端口）",
                                                    placeholder = "192.168.0.1:8080 或 router.local:80",
                                                    isError = goformErr != null,
                                                    errorMessage = goformErr,
                                                    keyboardOptions = KeyboardOptions(
                                                        keyboardType = KeyboardType.Uri,
                                                        imeAction = ImeAction.Next
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                UfiPasswordField(
                                                    value = goformPassword,
                                                    onValueChange = { goformPassword = it },
                                                    label = "GoForm 密码（留空则不修改）",
                                                    keyboardOptions = KeyboardOptions(
                                                        keyboardType = KeyboardType.Password,
                                                        imeAction = ImeAction.Done
                                                    ),
                                                    keyboardActions = KeyboardActions(
                                                        onDone = { onPrimaryAction() }
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            else -> {
                                                SetupStepText(
                                                    heading = "确认配对信息",
                                                    description = "以下配置将写入设备，并在其他设备登录时沿用。"
                                                )
                                                SetupReviewRow(
                                                    "配对密码",
                                                    if (password.isBlank() && confirmPassword.isBlank()) {
                                                        "使用默认密码 admin"
                                                    } else {
                                                        "已设置（${password.length} 位）"
                                                    }
                                                )
                                                SetupReviewRow("GoForm 地址", goformAddress.ifBlank { "不配置" })
                                                SetupReviewRow(
                                                    "GoForm 密码",
                                                    if (goformPassword.isBlank()) {
                                                        "保持设备当前密码"
                                                    } else {
                                                        "已填写"
                                                    }
                                                )
                                            }
                                        }
                                        UfiButton(
                                            size = UfiButtonSize.Large,
                                            text = if (step == 2) "完成配对" else "下一步",
                                            onClick = { onPrimaryAction() },
                                            enabled = !busy,
                                            loading = busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        UfiButton(
                                            variant = UfiButtonVariant.Secondary,
                                            text = if (step > 0) "上一步" else "选择其他设备",
                                            onClick = {
                                                if (step > 0) {
                                                    confirmStep = (step - 1).coerceAtLeast(0)
                                                    showPasswordErrors = false
                                                    passwordServerError = null
                                                } else {
                                                    backToDeviceChoice()
                                                }
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 卡片单独居中的关键：在卡片下方补一段与「品牌 + 24dp 间距」等高的留白。
                // 整组居中时，这段留白恰好抵消品牌在卡片上方占掉的高度，
                // 使卡片中心 = 可用区域中心；品牌仍紧贴卡片上方并随之移动。
                Spacer(Modifier.height(brandHeight + 24.dp))
            }
        }

        // v3（2026-08-11）：Toast 反馈宿主（搜索超时提示等）
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })

        // ── 等待机主批准（2026-10-21，审计 §A1）──
        //
        // App 作为**请求方**的这一半：core 的批准闸门对所有设备生效，
        // 之前这里缺了整条链路，导致开关一开新手机永远配不上。
        // 三条出路：机主在已配对设备上点「允许」（轮询自动完成）/ 输入外部通知里的 6 位码 /
        // 放弃这次申请重新来。
        if (approvalWaiting) {
            UfiScrollableDialog(
                visible = true,
                onDismiss = { cancelApproval() },
                title = "等待机主批准",
                showCloseButton = false,
                actions = {
                    UfiDialogActions(
                        onDismiss = { cancelApproval() },
                        onConfirm = {
                            // 带输入框里的码重试 confirm：码正确时 core 直接视为已批准。
                            // 注意**不能**先 cancelApproval() —— 那会把 approvalSid 和刚输入的码一起清掉，
                            // 服务端就匹配不回这条待批准请求了。只收起弹窗，让 doPair 用现有 sid + 码去试。
                            approvalPollJob?.cancel()
                            approvalPollJob = null
                            approvalWaiting = false
                            approvalStatusText = ""
                            doPair()
                        },
                        confirmText = "重新验证",
                        enabled = approvalRetryCode.isBlank() || approvalRetryCode.length == 6
                    )
                }
            ) {
                UfiDialogBody {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        UfiLoadingIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            approvalStatusText.ifBlank { "已向设备发出配对申请，请稍候…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                    UfiDialogTextField(
                        label = "6 位批准码（没有可留空）",
                        value = approvalRetryCode,
                        onValueChange = { v ->
                            // 码是大写字母 + 数字：统一转大写并剔除其它字符（审计 §C5）
                            approvalRetryCode = v.uppercase().filter { it.isLetterOrDigit() }.take(6)
                        }
                    )
                    UfiDialogNote(
                        "批准码由机主在已配对设备的弹窗中查看，或通过机主配置的邮件、Webhook、" +
                            "系统通知发送；它不是设备屏幕上显示的配对码。"
                    )
                    // 自救入口（2026-10-21 决策 D6-2；2026-10-22 按 paired_count 收紧）：
                    // 设备里可能还留着一条**已经死掉**的旧配对记录（本机清过数据 / 重装，
                    // 密钥已删、永远登录不上），它仍然满足"存在已配对设备"，于是这台手机永远
                    // 等不到批准。这里给一条自救路：用配对密码清空整份配对后重新配对。
                    //
                    // 文案按实际条数生成 —— 之前固定写"这台手机就是唯一已配对设备"，在设备上
                    // 还有别的手机时是假话，也让用户误以为和配对码重复。
                    val pairedCount = pairingInfo?.pairedCount ?: 0
                    if (pairedCount > 0) {
                        TextButton(onClick = { showSelfRescue = true }) {
                            Text(
                                if (pairedCount == 1) {
                                    "设备上只有 1 条配对记录，但不是这台手机？清除后重新配对"
                                } else {
                                    "设备上有 $pairedCount 条配对记录，都不是这台手机？清除后重新配对"
                                }
                            )
                        }
                    }
                }
            }
        }

        // 自救：清空整份配对（需要配对密码，走 root 端点，不放宽任何安全边界）
        if (showSelfRescue) {
            UfiScrollableDialog(
                visible = true,
                onDismiss = { if (!selfRescueBusy) { showSelfRescue = false; selfRescuePwd = "" } },
                title = "删除旧配对并重新开始",
                showCloseButton = false,
                actions = {
                    UfiDialogActions(
                        onDismiss = { if (!selfRescueBusy) { showSelfRescue = false; selfRescuePwd = "" } },
                        onConfirm = {
                            val device = selected ?: return@UfiDialogActions
                            selfRescueBusy = true
                            scope.launch {
                                val err = httpUnpairAll(device.host, device.port, selfRescuePwd)
                                selfRescueBusy = false
                                if (err != null) {
                                    toastMessage = ToastMessage(err, ToastType.ERROR)
                                    return@launch
                                }
                                showSelfRescue = false
                                selfRescuePwd = ""
                                // 旧记录已清空：收尾批准等待态并重新拉一次 /pairing/info
                                cancelApproval()
                                pairingInfo = null
                                selectDevice(device.copy(cachedInfo = null))
                                toastMessage = ToastMessage("已清空配对记录，请重新配对。", ToastType.SUCCESS)
                            }
                        },
                        confirmText = "确认删除",
                        confirmDestructive = true,
                        enabled = selfRescuePwd.isNotBlank() && !selfRescueBusy
                    )
                }
            ) {
                UfiDialogBody {
                    val rescueCount = pairingInfo?.pairedCount ?: 0
                    Text(
                        buildString {
                            append("此操作会清空设备上的全部配对记录")
                            if (rescueCount > 0) append("（当前 $rescueCount 条）")
                            append("，所有客户端都需要重新配对，正在使用的其他手机会立即离线。\n\n")
                            append("适用场景：本机清除过应用数据或重新安装过 App，设备上仍保留已失效的旧配对记录，")
                            append("导致新的配对申请无法获得批准。\n\n")
                            append("如果设备上还有其他正常使用的手机，请先在那一台手机上点击「允许」，不要使用此功能。")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                    UfiDialogPasswordField(
                        label = "配对密码",
                        value = selfRescuePwd,
                        onValueChange = { selfRescuePwd = it }
                    )
                }
            }
        }
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
                            hasDefaultPassword = json.optBoolean("has_default_password", true),
                            pairedCount = json.optInt("paired_count", 0)
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
 * ## 2026-10-21 新增两个字段（审计 §A1）
 * core 的「新设备需批准」闸门对**所有**设备生效（没有"只覆盖 Web"的判据），所以 App 也必须
 * 走请求方那一半：先 `POST /pairing/request-approval` 拿 `sid`，confirm 时带上它；
 * 机主批准后同一个 sid 重试即可通过。`approval_code` 是"机主从通知里读到的 6 位码"那条路。
 *
 * @param context 用于读取 [AppPreferences.deviceHwid]（硬件派生的稳定标识）。后端**仅**用它
 *   合并"同一台手机换了密钥"产生的重复记录，不参与任何安全判定。
 * @param sid 批准会话随机数；未走批准流程时为空串。
 * @param approvalCode 机主回传的 6 位配对码；没有则 null。
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
    goformPassword: String? = null,
    sid: String = "",
    approvalCode: String? = null
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
            conn.connectTimeout = 10_000
            // 2026-10-21（审计 §B5）：readTimeout 必须盖住 core 的**失败退避阶梯**
            // （`PairingAttemptLimiter`：1s→2s→4s→8s→30s→2min→10min，且是在 confirm 之前 delay）。
            // 之前是 5s：用户连错两三次密码之后，服务端还在退避里等待，客户端已经先超时，
            // 界面报"设备不可达: Read timed out" —— 真实原因（密码错/被限流）被完全掩盖，
            // 用户按"网络问题"继续点，退避越涨越高。30s 覆盖到前四档，更长的档位靠文案说明。
            conn.readTimeout = 30_000
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
                // 批准流程（2026-10-21）：登记过的会话随机数 + 机主回传的 6 位码。
                // 两者都没走时留空 —— core 按"不需要批准"处理。
                if (sid.isNotBlank()) put("sid", sid)
                if (!approvalCode.isNullOrBlank()) put("approval_code", approvalCode.trim().uppercase())
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
                403 -> {
                    // 2026-10-21（审计 §B3）：403 不止"隧道来源"一种。
                    // 之前一律按局域网限制来写文案，于是「新设备需批准」和「已拉黑/冷却中」
                    // 都被显示成"该操作只能在局域网内完成"——用户完全不知道下一步该做什么。
                    // 注意 errorStream 只能读一次，所以 code/error/retry 一起取（见 readErrorBody）。
                    val err = readErrorBody(conn)
                    when (err.code) {
                        "APPROVAL_REQUIRED" -> ConfirmOutcome.ApprovalRequired
                        "DEVICE_BLOCKED" -> {
                            // 2026-10-21（决策 D4）：把"还要等多久"直接写出来。
                            val wait = describeCooldown(err.retryAfterSeconds)
                            ConfirmOutcome.Error(
                                "本设备已被拉黑，无法配对。" +
                                    if (wait.isNotBlank()) "$wait（解除拉黑不会立即生效）。" else "若刚解除拉黑，需等冷却结束再试。"
                            )
                        }
                        else -> ConfirmOutcome.Error(
                            err.error.ifBlank { "该操作只能在设备所在的局域网内完成" }
                        )
                    }
                }
                400, 401 -> {
                    val err = readErrorBody(conn)
                    when (err.code) {
                        "INVALID_PASSWORD" -> ConfirmOutcome.InvalidPassword
                        "PASSWORD_REQUIRED" -> ConfirmOutcome.Error("配对密码已设置，请输入配对密码")
                        "PASSWORD_LOCKED" -> ConfirmOutcome.PasswordLocked
                        "INVALID_DEVICE_KEY", "INVALID_CHALLENGE" -> ConfirmOutcome.DeviceKeyRejected
                        // 2026-10-21（审计 §B4）：后台地址格式错被归到"配对码无效"，指错方向。
                        "INVALID_GOFORM_CONFIG" -> ConfirmOutcome.Error(
                            err.error.ifBlank { "设备后台配置无效（IP/端口/密码格式错误）" }
                        )
                        // core 对「首次配对要配对码但没给」回 400 BAD_REQUEST，别再让人以为是码错了。
                        "BAD_REQUEST" -> ConfirmOutcome.Error(
                            err.error.ifBlank { "配对参数缺失，请返回重新选择设备" }
                        )
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

/**
 * 一次读完错误体的 `code` / `error` / `retry_after_seconds`（2026-10-21）。
 *
 * `errorStream` 只能读一次 —— 先 [readErrorCode] 再 [readErrorMessage] 时第二次必然拿到空串，
 * 于是"按 code 分流、再退回服务端文案"的写法会静默丢掉服务端那句话。
 */
private data class ErrorBody(
    val code: String,
    val error: String,
    val retryAfterSeconds: Long = 0L
)

private fun readErrorBody(conn: HttpURLConnection): ErrorBody {
    return try {
        val text = BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).readText()
        val obj = JSONObject(text)
        ErrorBody(
            code = obj.optString("code", ""),
            error = obj.optString("error", ""),
            retryAfterSeconds = obj.optLong("retry_after_seconds", 0L)
        )
    } catch (e: Exception) {
        ErrorBody("", "")
    }
}

/**
 * 把"还要等多久"翻成一句人话（2026-10-21 决策 D4）。
 *
 * core 在 403 DEVICE_BLOCKED 里带 `retry_after_seconds`：解除拉黑不清冷却（最长 1 小时），
 * 之前只能写"最长 1 小时"，用户不知道该等 5 分钟还是一小时，反复重试反而在刷新冷却。
 */
private fun describeCooldown(seconds: Long): String = when {
    seconds <= 0L -> ""
    seconds < 60L -> "还需等待约 $seconds 秒"
    else -> "还需等待约 ${(seconds + 59L) / 60L} 分钟"
}

/**
 * `POST /pairing/unpair`（root，需要配对密码）→ 清空整份配对并重新进入配对模式。
 *
 * 2026-10-21（决策 D6-2）：这是 App 侧**唯一**能自救"设备里还留着一条已死记录、
 * 而自己又被批准闸门挡住"的入口 —— 那条记录的密钥已经随「清除数据 / 重新配对」消失，
 * 永远登录不上，却仍然满足"存在已配对设备"，于是没人能批准新的这台。
 * 走的是带密码的 root 端点，不需要放宽任何安全边界。
 *
 * @return null = 成功；否则是给人看的错误文案。
 */
private suspend fun httpUnpairAll(host: String, port: Int, password: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://$host:$port/pairing/unpair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val body = JSONObject().put("password", password).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                200 -> null
                401 -> "配对密码错误"
                403 -> "设备只允许在局域网内解除配对"
                429 -> "密码错误次数过多，请稍后再试"
                else -> "解除失败（HTTP ${conn.responseCode}）"
            }
        }.getOrElse { "设备不可达: ${it.message}" }
    }

/** 生成一次批准申请的会话随机数（128-bit hex，与 web 的 sid 同构）。 */
private fun newApprovalSid(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * `POST /pairing/request-approval` 登记一次待批准请求（2026-10-21，审计 §A1）。
 *
 * 与 confirm 同一套裸 `HttpURLConnection`：此刻 App 还没有任何凭据，走不了 Retrofit。
 * @return true = 已登记（含"本来就在等"的幂等命中）
 */
private suspend fun httpRequestApproval(
    host: String,
    port: Int,
    sid: String,
    pubKeySpki: String,
    deviceName: String
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL("http://$host:$port/pairing/request-approval").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val body = JSONObject().apply {
            put("sid", sid)
            put("device_pubkey", pubKeySpki)
            put("device_name", deviceName)
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // 200 = 登记成功；429 = 申请过于频繁；501 = 设备端没启用批准（旧 core）
        conn.responseCode == 200
    }.getOrDefault(false)
}

/**
 * `GET /pairing/approval-status?sid=` → `pending` / `approved` / `rejected` / `expired`。
 *
 * @return 状态字符串；**网络/服务端失败返回 null**（调用方继续等，别把抖动当过期 ——
 *   这条约定与 web 端 `fetchApprovalStatus` 完全一致）。
 */
private suspend fun httpGetApprovalStatus(host: String, port: Int, sid: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://$host:$port/pairing/approval-status?sid=$sid").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode != 200) return@runCatching null
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
            JSONObject(text).optString("status", "").ifBlank { null }
        }.getOrNull()
    }

// ───────────────────────────────────────────────────────────
// 配对 / 登录屏的视觉组件（2026-10-22）
//
// 全部取自主题：LocalResolvedPalette 的颜色 token、Spacing 的间距、UfiCardDefaults 形状。
// **这里不新增任何颜色常量** —— 换皮肤 / 深色模式时这一屏跟着走。
// ───────────────────────────────────────────────────────────

/** 背景装饰：点阵 + 同心圆，颜色由 palette token 派生（纯装饰，不承载信息）。 */
@Composable
private fun SetupDots() {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val step = 22.dp.toPx()
                val dotColor = palette.textSecondary.copy(alpha = 0.10f)
                val maxY = size.height * 0.72f
                var y = step
                while (y < maxY) {
                    var x = step
                    while (x < size.width) {
                        drawCircle(dotColor, radius = 1.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
                val cx = size.width / 2f
                val ring = palette.accent.copy(alpha = 0.08f)
                drawCircle(ring, radius = 270.dp.toPx(), center = Offset(cx, 0f), style = Stroke(1.dp.toPx()))
                drawCircle(
                    ring.copy(alpha = 0.06f),
                    radius = 206.dp.toPx(),
                    center = Offset(cx, 0f),
                    style = Stroke(1.dp.toPx())
                )
            }
    )
}

/** 主卡片：所有阶段共用一张（内容 / 按钮都在卡内，整体由外层 AnimatedContent 缩放过渡）。 */
@Composable
private fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.largeShape
    Column(
        modifier = Modifier
            // 先夹宽度再 fillMaxWidth：宽屏 / 横屏下卡片不会铺成一条横带。
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            // 同一张卡内部的高度变化（错误 supportingText 出现 / 消失）也要平滑；
            // 换页面/换步骤的整卡过渡由外层 AnimatedContent 负责。
            .animateContentSize(animationSpec = tween(240), alignment = Alignment.TopCenter)
            .ufiCardShadow(elevation = 4.dp, shape = shape)
            .clip(shape)
            .background(palette.cardBg, shape)
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content
    )
}

/**
 * 品牌块：图标 + 标题 +（设备名 / 地址 / 说明）。
 *
 * 位于卡片上方，与卡片同属一条竖列并随卡片一起移动。图标**始终 88dp**：
 * 旧实现会在进入配对向导时从 88 切成 76，用户看到的就是「切页面时图标莫名缩放」。
 * 标题/地址/说明随阶段变化时只让品牌块高度平滑变化，图标本身不缩放。
 *
 * 设备名与地址各占一行：拼成「客厅路由 · 192.168.8.1:8088」时，长 IP/域名会把设备名
 * 挤成省略号，而且两者字号与字体本就不同（名字用正文字体，地址用等宽）。
 */
@Composable
private fun SetupBrand(
    bitmap: ImageBitmap,
    title: String,
    subtitle: String = "",
    deviceName: String = "",
    address: String = "",
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            // 固定宽度后 animateContentSize 只动高度：标题/设备名/地址切换时
            // 不会出现品牌块横向拉伸，也不会带动上方图标做视觉缩放。
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220), alignment = Alignment.TopCenter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 2026-10-23：不要大圆角 —— 与全局图标圆角一致（Spacing.CornerTag = 5dp）。
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(Spacing.CornerTag))
        )
        Text(
            text = title,
            style = UfiTextStyles.onboardingTitle,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        if (deviceName.isNotBlank()) {
            Text(
                text = deviceName,
                style = UfiTextStyles.panelTitleStrong,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
        }
        if (address.isNotBlank()) {
            Text(
                text = address,
                style = UfiTextStyles.noteLead.copy(fontFamily = FontFamily.Monospace),
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = UfiTextStyles.onboardingNote,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 步骤点：当前步用短胶囊，其余用圆点（不画整条步骤条，给卡片省高度）。 */
@Composable
private fun SetupStepDots(count: Int, index: Int, label: String) {
    val palette = LocalResolvedPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(count) { i ->
                val active = i == index
                Box(
                    Modifier
                        .height(8.dp)
                        .width(if (active) 24.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) palette.accent else palette.divider)
                )
            }
        }
        Text(label, style = UfiTextStyles.tag, color = palette.textSecondary)
    }
}

/** 步骤标题 + 说明（居中）。 */
@Composable
private fun SetupStepText(heading: String, description: String) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        Text(
            text = heading,
            style = UfiTextStyles.screenTitle,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = UfiTextStyles.onboardingNote,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/** 确认步的一行「键 - 值」。 */
@Composable
private fun SetupReviewRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UfiTextStyles.onboardingNote, color = palette.textSecondary)
        Spacer(Modifier.width(Spacing.Large))
        Text(
            text = value,
            style = UfiTextStyles.onboardingNote.copy(fontWeight = FontWeight.Medium),
            color = palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 设备行：卡内的一条内嵌行（不是第二张卡）。 */
@Composable
private fun SetupDeviceRow(title: String, subtitle: String, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.smallShape)
            .background(palette.pageBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(UfiCardDefaults.smallShape)
                .background(palette.cardBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Router,
                contentDescription = null,
                tint = palette.iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = UfiTextStyles.onboardingNote.copy(fontWeight = FontWeight.Medium),
                color = palette.textPrimary
            )
            Text(text = subtitle, style = UfiTextStyles.noteLead, color = palette.textSecondary)
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}
