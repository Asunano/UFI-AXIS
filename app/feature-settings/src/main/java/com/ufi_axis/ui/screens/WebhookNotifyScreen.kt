package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.WebhookConfigPatch
import com.ufi_axis.data.model.WebhookConfigResponse
import com.ufi_axis.data.model.WebhookPresetDto
import com.ufi_axis.data.model.WebhookSecretTarget
import com.ufi_axis.data.model.WebhookTestResponse


import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「Webhook 通知」配置页（2026-09-09）。入口在 [PushChannelsScreen]。
 *
 * core 侧真源：`WebhookConfig` / `WebhookPreset`，端点 `/api/notify/webhook/{config,test}`。
 * 本页**只渲染 core 的数据**：预设默认模板、可用占位符、"能不能投"的判据（`configured`）
 * 全部来自 GET 响应，app 里没有第二份（抄一份就会出现"照着界面填完却发不出去"）。
 *
 * ## 页面结构（一项一卡，节奏与 [EmailNotifyScreen] 一致）
 *
 * 1. 启用开关 —— 副文案直接说"能不能投"，读的是 core 的 `configured`
 * 2. 预设 —— [UfiPopupAnchor] 下拉；**只改"当前选哪个"**，不覆盖任何字段
 * 3. **预设要求填的那一样东西** —— 一个输入框，标签用 core 回的 `secret_label`
 * 4. **请求体模板** —— 常驻入口，所有预设都能进（不在高级设置里）
 * 5. **高级设置（默认折叠）** —— 请求地址 / 方法 / 超时 / Content-Type / 请求头
 * 6. 最低级别 / 每日上限 / 今日用量 —— 三条渠道共用件（见 `NotifyChannelRules.kt`）
 * 7. 触发场景 —— 与邮件同一套 [NOTIFY_SCENE_LABELS]
 * 8. 遵守免打扰（副文案带「严重事件兜底」提示）
 * 9. 发送测试 + 最近测试结果（HTTP 状态码 + 响应体摘要）
 * 10. 最近投递 —— 入口，指向 [DeliveryHistoryScreen]（channel = webhook）
 *
 * ## 2026-09-09：选了预设就只剩一个输入框
 *
 * 七个预设里用户真正要填的只有一样（device key / topic / SENDKEY / token…），位置却不同：
 * 多数在 URL 里，PushPlus 在**请求体模板**里。core 把这件事声明成了机器可读的元数据
 * （`secret_label` / `secret_marker` / `secret_target`），本页据此：
 *
 * - 有那一样东西时，页面上只给它一个输入框（[resolveSecretField]），其余全部收进「高级设置」；
 * - `CUSTOM`（`secret_target = none`）本来就是全手填，高级设置默认展开；
 * - 折叠不是删功能 —— 改过高级字段的用户展开就能改回去。
 *
 * 存储里存的是**最终**的 url / body_template（marker 已被替换掉，找不回来），所以简易输入框
 * 与存储之间用「按预设默认值前后缀切分」双向换算，判据与边界都在 [resolveSecretField]。

 *
 * ## 2026-09-11：切换预设不再覆盖任何东西
 *
 * core 已改成**每个预设各存一份**「发到哪 / 怎么发」（url / method / headers / 模板 /
 * content-type / 超时），顶层只留"当前选哪个"与那组闸门（开关 / 场景 / 级别 / 上限 / 配额）。
 * 于是本页：
 *
 * - 切换预设**只发 `preset` 这一个字段**。绝不把本地表单里的 url / 模板一起 PUT 上去 ——
 *   core 会把它们写进**新**预设的槽位，"从未配过"就变成了"配成了旧预设的值"；
 * - 切完以 core 的回读为准重画（PUT 回显 + 再拉一次 GET），不拿本地旧值渲染；
 * - **不弹"要覆盖了"的确认**：切换不覆盖任何东西，弹一句警告只是制造一个不存在的风险；
 * - app **不主动把预设默认值填进表单再提交**：默认值由 core 在"读不到"时提供且不落盘，
 *   客户端提交一次默认值就把"从未配过"坐实成"配过"。
 *
 * ## 2026-09-11：请求体模板提到高级设置外面
 *
 * 它原来躺在「高级设置」里，而高级设置对非自定义预设默认收起 —— 观感就是"只有自定义才能改模板"。
 * 模板是这条渠道的主要可调项（所有预设都可能要加字段 / 改文案），所以做成常驻入口。
 * 副文案要提前说清一件事：PushPlus 那一档的密钥就在模板里，改坏 marker 的上下文会让
 * [resolveSecretField] 切不出前后缀 → 上面那个简易输入框消失、改成在模板里直接编辑。
 *
 * ## 2026-09-10：补上「规则同构」的两个旋钮

 *
 * core 给三条渠道配了同一组规则（最低级别 + 每日上限），本页的第 6 组就是它 ——
 * 组件与措辞直接用 `NotifyChannelRules.kt` 的共用件，与邮件 / 本机短信逐字一致。
 * 本渠道的区间是 `0..1000` 且**允许 0（不限）**，与本机短信刻意不同；
 * 那句差异由共用件按 `daily_limit_min` 写进界面。
 *
 * ## 写入时机
 *
 * 开关类改完立即下发；弹窗是暂存-确认，点「保存」才下发。两者都走同一个 `persist(...)`，
 * 下发的是**字段级 patch**（未传的键 core 保留现值），所以不需要像邮件那样"把没编辑的字段原样带回"。
 * 例外是 `headers`：core 收到就整体替换，所以改头部时必须传全量 map。
 *
 * ## 读不到配置时
 *
 * 不渲染任何表单，只给一个空态 + 重试。用默认值把界面画出来等于让用户对着假配置改，
 * 一保存就把设备上的真配置覆盖掉。
 *
 * @param viewModel 读 `webhookState`，写 `tools.saveWebhookConfig` / `tools.testWebhook`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhookNotifyScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.webhookState.collectAsState()
    // 只为「最近投递」入口卡的右侧摘要
    val toolsState by viewModel.toolsState.collectAsState()

    // 「严重事件兜底」的本机镜像（真源在 core）。只读它来决定免打扰 / 场景那两处提示怎么写 ——
    // 兜底关着却写"严重事件会穿透"就是承诺一件不会发生的事。默认值只在 NotifyPrefs 定义一处。
    // 不在本页刷新那份配置：它由持有开关的「通知与守护」负责（本页的上游路径），
    // 这里只在恢复前台时重读，于是"去关掉兜底再回来"这条路径上文案会跟着变。
    val context = LocalContext.current
    val readCriticalOverride = {
        NotifyPrefs.switchOn(
            context,
            NotificationCenter.KEY_CRITICAL_OVERRIDE,
            NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
        )
    }
    var criticalOverrideOn by remember { mutableStateOf(readCriticalOverride()) }
    rememberResumeRefresh { criticalOverrideOn = readCriticalOverride() }

    LaunchedEffect(Unit) {
        viewModel.tools.loadWebhookConfig()
        viewModel.tools.loadDeliveryHistory(DELIVERY_CHANNEL_WEBHOOK)
    }

    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 打开哪个弹窗。用一个 sealed 状态而不是四个布尔：四个布尔可以同时为真，
    // 而"两个弹窗同时开着"是个没人想要的状态。
    var dialog by remember { mutableStateOf<WebhookDialog?>(null) }
    /**
     * 刚刚下发的是一次"换预设"。
     *
     * 换预设会让 core 换到另一份「发到哪 / 怎么发」的槽位，回来的是一整套新值；这一位让
     * 结算处在 PUT 落地之后**再拉一次 GET**，界面只吃 core 的回读。
     * 之所以不在点下去的同时并发 GET：那次 GET 可能先于 PUT 完成，拿到的是切换前那一份。
     */
    var presetJustSwitched by remember { mutableStateOf(false) }

    // 当前预设与「用户真正要填的那一样东西」。页面主体与弹窗都要读它，所以在这一层算一次
    // （弹窗那段在 UfiPageBackground 之外，拿不到页面主体里的局部变量）。
    val selectedPreset = state.config?.let { c -> c.presets.firstOrNull { it.name == c.preset } }
    val secretField = state.config?.let { resolveSecretField(it, selectedPreset) }

    /** 下发 patch。null 字段 = 不改（core 的 merge 把 JsonNull 当成"没传"）。 */
    fun persist(patch: WebhookConfigPatch) = viewModel.tools.saveWebhookConfig(patch)

    // 保存结算：saveTick 每次 PUT 有结果就 +1。用递增计数而不是嗅 saving 的下降沿，
    // 布尔边沿会被 snapshot 合并掉（本仓已在骨架屏上踩过同一个坑）。
    LaunchedEffect(state.saveTick) {
        if (state.saveTick == 0) return@LaunchedEffect
        val err = state.saveError
        toastMessage = if (err == null) {
            ToastMessage("配置已保存", ToastType.SUCCESS)
        } else {
            // core 的 400 文案里写明了是哪一条约束不过（url scheme / method / 超时区间 /
            // header 换行 / 未知场景），原样转出去比"保存失败"有用。
            ToastMessage("保存失败", ToastType.ERROR, subtitle = err)
        }
        // 换预设成功 → 重读。失败也要清掉这一位，否则下一次普通保存会莫名多拉一次。
        val switched = presetJustSwitched
        presetJustSwitched = false
        if (switched && err == null) viewModel.tools.loadWebhookConfig()
    }


    // 测试结算：状态码与响应体摘要必须显示出来 —— 那是用户排 Webhook 的唯一线索。
    LaunchedEffect(state.testTick) {
        if (state.testTick == 0) return@LaunchedEffect
        val result = state.lastTest ?: return@LaunchedEffect
        toastMessage = buildTestToast(result)
        // 响应体长到 toast 副标题装不下时直接摊开：截断了就等于没给。
        if ((result.response_body?.length ?: 0) > RESPONSE_BODY_INLINE_CHARS) {
            dialog = WebhookDialog.TestResult
        }
    }

    UfiScreenScaffold(title = "Webhook 通知", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err) }

            val cfg = state.config
            if (cfg == null) {
                // 还没读完 → 骨架；读完了还是 null → 真读失败，给重试。
                // 两种都不画表单：拿默认值画出来的表单一保存就覆盖设备上的真配置。
                if (!state.loaded) {
                    UfiSkeletonList(rows = WEBHOOK_SKELETON_ROWS)
                } else {
                    UfiEmptyState(
                        icon = Icons.Default.CloudOff,
                        message = "读不到 Webhook 配置",
                        hint = "设备可能未连接，或设备端版本不支持该功能",
                        action = {
                            UfiButton(
                                text = "重试",
                                size = UfiButtonSize.Small,
                                onClick = { viewModel.tools.loadWebhookConfig() }
                            )
                        }
                    )
                }
                return@UfiPageBackground
            }

            // 高级设置的开合。`remember(cfg.preset)` —— 换预设就回到那一档的默认状态：
            // 有简易输入框的档默认收起，全手填（或结构已被改过）的档默认展开。
            // null = 用户还没手动开合过；手动开合之后就听用户的，别在重组里把它盖回默认。
            var advancedOverride by remember(cfg.preset) { mutableStateOf<Boolean?>(null) }
            val advancedOpen = advancedOverride ?: (secretField?.value == null)

            // ── ① 启用开关。副文案说的是"现在能不能投"，判据来自 core 的 configured ──
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "启用 Webhook 通知",
                    description = when {
                        cfg.configured -> "配置完整，勾选的场景会按模板发出 HTTP 请求"
                        cfg.url.isBlank() -> "还需在「高级设置 → 请求配置」里填写请求地址"
                        // 有简易输入框却没填时，直接点名那一样东西 —— 用户不必去猜"哪个 <占位> 没换"。
                        secretField != null && !secretField.filled ->
                            "还需填写「${secretField.label}」，当前无法发送"
                        else -> "请求地址或请求体模板中仍有 <占位> 未替换为真实值，当前无法发送"
                    },
                    checked = cfg.enabled,
                    onCheckedChange = { persist(WebhookConfigPatch(enabled = it)) }
                )
            }

            // ── ② 预设。选中态走 UfiPopupOption.isSelected（组件自带 ✓ + accent），
            //    不在 label 里手拼「（当前）」—— 那会让选中态在不同页面长得不一样。
            //
            // 点选只发 `preset` 一个字段：每个预设在 core 侧各有一份地址与模板，
            // 顺手把本地表单的值一起 PUT 上去会写进新预设的槽位，把"从未配过"变成"配过"。
            UfiSettingsRowCard {
                if (cfg.presets.isEmpty()) {
                    // 老 core 不回预设表。此时不给下拉（[UfiPopupAnchor] 选项为空时整块不渲染，
                    // 直接用它会让这一行凭空消失），只把当前值显示出来。
                    UfiSettingsValue(
                        title = "预设",
                        description = "设备端未提供预设列表，需手工填写下面的请求配置",
                        value = cfg.preset
                    )
                } else {
                    UfiPopupAnchor(
                        options = cfg.presets.map { preset ->
                            UfiPopupOption(
                                id = preset.name,
                                label = preset.display_name,
                                isSelected = preset.name == cfg.preset,
                                onClick = {
                                    if (preset.name != cfg.preset) {
                                        presetJustSwitched = true
                                        persist(WebhookConfigPatch(preset = preset.name))
                                    }
                                }
                            )
                        }
                    ) { toggle ->
                        UfiSettingsValue(
                            title = "预设",
                            description = selectedPreset?.user_fills
                                ?.let { "需手工填写：$it · $PRESET_ISOLATION_NOTE" }
                                ?: PRESET_ISOLATION_NOTE,
                            value = selectedPreset?.display_name ?: cfg.preset,
                            onClick = toggle
                        )
                    }
                }
            }

            // ── ③ 预设要求填的那一样东西。它是这条渠道唯一需要用户操心的值，所以紧跟预设 ──
            //
            // 标签、标记、写到哪一处全部读 core 回的元数据；app 不维护第二张表
            // （marker 抄错的表现是密钥没进请求，界面上看不出原因）。
            secretField?.let { field ->
                UfiSettingsRowCard {
                    if (field.value == null) {
                        // 当前 url / 模板与预设默认结构不同（用户手工改过别的部分），
                        // 切不出"用户填的那一段"。此时给简易输入框会把他改过的部分吞掉。
                        UfiSettingsItem(title = field.label, description = SECRET_DETACHED_NOTE)
                    } else {
                        UfiSettingsValue(
                            title = field.label,
                            description = if (field.filled) {
                                "发送时会写入${field.targetLabel}；点击可更换"
                            } else {
                                "这条渠道只需要这一个值，填好即可发送"
                            },
                            value = if (field.filled) maskCredential(field.value) else "未填写",
                            onClick = { dialog = WebhookDialog.Secret }
                        )
                    }
                }
            }

            // ── ④ 请求体模板（常驻入口，所有预设都能进）──
            //
            // 它原来在「高级设置」里，而高级设置对有简易输入框的预设默认收起 —— 观感是
            // "只有自定义预设才能改模板"。模板是这条渠道最常要动的东西（加字段、改文案），
            // 所以提到外面。副文案分两档：密钥就写在模板里的那一档（PushPlus）要提前说清
            // 改坏 marker 上下文的后果，否则简易输入框消失时用户不知道发生了什么。
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "请求体模板",
                    value = if (cfg.body_template.isBlank()) "空" else "${cfg.body_template.length} 字",
                    description = bodyTemplateNote(secretField),
                    onClick = { dialog = WebhookDialog.BodyTemplate }
                )
            }

            // ── ⑤ 高级设置（默认折叠）──
            //
            // 折叠的理由是选了预设之后这些字段都已经填好了，摊在页面上只会让人以为还要动它们。
            // 但**不是删功能**：改过这些字段的用户展开就能改回去，折叠态也不影响已存的值。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    title = "高级设置",
                    description = "请求地址、请求方法、超时、Content-Type 与请求头",
                    onClick = { advancedOverride = !advancedOpen },
                    trailing = {
                        // 与本页其它行尾动作（请求头的「添加」/「删除」）同一种按钮，
                        // 不再手写一段 accent 小字 —— 那是全站唯一一处这么做的地方。
                        UfiButton(
                            text = if (advancedOpen) "收起" else "展开",
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            onClick = { advancedOverride = !advancedOpen }
                        )
                    }
                )
            }

            // 展开/收起走公共动画容器：它在收起播完后不再发子节点，所以本页
            // `Arrangement.spacedBy` 的卡间距不会凭空多出一道（裸 AnimatedVisibility 的老坑，
            // 详见 UfiExpandSection 的 KDoc）。
            UfiExpandSection(expanded = advancedOpen) {
                // ── 请求配置（URL / 方法 / 超时 / Content-Type）。四项都在同一个请求里生效 ──
                UfiSettingsRowCard {
                    UfiSettingsValue(
                        title = "请求配置",
                        description = "请求地址、方法、超时与 Content-Type",
                        value = summarizeRequest(cfg),
                        onClick = { dialog = WebhookDialog.Request }
                    )
                }

                // ── 请求头 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "请求头",
                        description = "凭据类头部（Authorization / token / key …）在列表中只显示首尾几位，" +
                            "进入编辑时显示完整内容",
                        trailing = {
                            UfiButton(
                                text = "添加",
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small,
                                onClick = { dialog = WebhookDialog.Header(originalName = null) }
                            )
                        }
                    )
                    if (cfg.headers.isEmpty()) {
                        UfiEmptyState(
                            icon = Icons.Default.VpnKey,
                            message = "没有自定义请求头",
                            hint = "Bark / 群机器人通常无需自定义请求头；自建服务的鉴权头可在此添加"
                        )
                    }
                }

                cfg.headers.forEach { (name, value) ->
                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            title = name,
                            description = maskHeaderValueForDisplay(name, value),
                            onClick = { dialog = WebhookDialog.Header(originalName = name) },
                            descriptionMaxLines = 1,
                            trailing = {
                                UfiButton(
                                    text = "删除",
                                    variant = UfiButtonVariant.Danger,
                                    size = UfiButtonSize.Small,
                                    // headers 传了就整体替换，所以删一项要把剩下的全部带上。
                                    onClick = {
                                        persist(WebhookConfigPatch(headers = cfg.headers - name))
                                    }
                                )
                            }
                        )
                    }
                }
            }

            // ── ⑥ 最低级别 · 每日上限 · 今日用量 ──
            //
            // 三行走 NotifyChannelRules.kt 的共用件（与邮件 / 本机短信逐字一致）：
            // core 已经把三条渠道的规则做成同构的，界面也必须长成同一种。
            // metered = false —— 这条渠道走数据网，不按条计费，所以级别提示后面不接费用说明。
            NotifyMinLevelRow(
                levels = cfg.levels,
                current = cfg.min_level,
                metered = false,
                onSelect = { persist(WebhookConfigPatch(min_level = it)) }
            )

            NotifyDailyLimitRow(
                dailyLimit = cfg.daily_limit,
                min = cfg.daily_limit_min,
                max = cfg.daily_limit_max,
                onClick = { dialog = WebhookDialog.DailyLimit }
            )

            NotifyQuotaRow(
                sentToday = cfg.sent_today,
                dailyLimit = cfg.daily_limit,
                quotaRemaining = cfg.quota_remaining
            )

            // ── ⑦ 触发场景。与邮件共用 NOTIFY_SCENE_LABELS ──
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "触发场景",
                    description = "勾选的场景触发时会同时发送一条 Webhook 请求",
                    value = if (cfg.scenes.isEmpty()) "未选择" else "${cfg.scenes.size} 个场景",
                    onClick = { dialog = WebhookDialog.Scenes }
                )
            }

            // ── ⑧ 遵守免打扰。时段本身在「通知与守护 → 通知管理」里改，这里只管"要不要跟" ──
            //    副文案末尾带兜底提示：兜底开着时严重事件会穿透静默时段，
            //    不写出来会被当成免打扰失灵。
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "遵守免打扰时段",
                    description = if (cfg.respect_dnd) {
                        "免打扰时段内不发 Webhook（时段在「通知管理」里设置）· " +
                            notifyCriticalOverrideNote(criticalOverrideOn)
                    } else {
                        "免打扰时段内仍会发 Webhook"
                    },
                    checked = cfg.respect_dnd,
                    onCheckedChange = { persist(WebhookConfigPatch(respect_dnd = it)) }
                )
            }

            // ── ⑨ 发送测试 + 最近结果 ──
            UfiSettingsGroup {
                UfiSectionHeader(title = "测试")
                // 只看本行自己的请求在飞，不看别人的 loading（那是邮件页踩过的坑①）。
                // 未启用时不给点：core 的 /test 在 `!cfg.enabled` 时直接回错，
                // 让按钮可点等于请用户去撞一个必然失败的接口。
                val canTest = cfg.enabled && !state.testing
                UfiSettingsItem(
                    title = "发送测试通知",
                    description = if (cfg.enabled) {
                        "不受总开关、免打扰与场景勾选约束，仅验证这条 HTTP 链路是否可用"
                    } else {
                        "需先启用 Webhook 通知"
                    },
                    enabled = canTest,
                    // enabled = false 时 UfiSettingsItem 自己吞掉点击，不必再叠一层 clickable。
                    onClick = { viewModel.tools.testWebhook() },
                    trailing = {
                        // 请求在飞时由按钮自己转圈（loading 会顺带禁用点击），
                        // 不再手写一段"发送中…"的 accent 小字。
                        UfiButton(
                            text = "试发",
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            enabled = canTest,
                            loading = state.testing,
                            onClick = { viewModel.tools.testWebhook() }
                        )
                    }
                )

                state.lastTest?.let { result ->
                    UfiDivider()
                    UfiSettingsValue(
                        title = "最近测试结果",
                        description = summarizeTestOutcome(result),
                        value = webhookStatusValue(result),
                        onClick = { dialog = WebhookDialog.TestResult }
                    )
                }
            }

            // ── ⑩ 最近投递。位置与另两条渠道页一致（测试之后、页尾之前）：
            //    先给"能不能通"，再给"实际通了几次" ──
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = DELIVERY_HISTORY_ENTRY_TITLE,
                    description = DELIVERY_HISTORY_ENTRY_DESCRIPTION,
                    value = deliveryHistorySummary(
                        // 三条渠道共用一个 state 槽位，先确认里面装的就是 Webhook 那一份。
                        ready = toolsState.deliveryHistoryLoaded &&
                            toolsState.deliveryHistoryChannel == DELIVERY_CHANNEL_WEBHOOK,
                        total = toolsState.deliveryHistoryTotal,
                        failedTotal = toolsState.deliveryHistoryFailedTotal,
                        skippedTotal = toolsState.deliveryHistorySkippedTotal
                    ),
                    onClick = {
                        navController.navigate(Routes.deliveryHistory(DELIVERY_CHANNEL_WEBHOOK))
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        // ── 弹窗 ──
        when (val open = dialog) {
            null -> Unit
            WebhookDialog.Request -> RequestConfigDialog(
                config = state.config,
                saving = state.saving,
                onDismiss = { dialog = null },
                onSave = { url, method, timeoutMs, contentType ->
                    dialog = null
                    persist(
                        WebhookConfigPatch(
                            url = url,
                            method = method,
                            timeout_ms = timeoutMs,
                            content_type = contentType
                        )
                    )
                }
            )
            WebhookDialog.Secret -> SecretEditDialog(
                field = secretField,
                saving = state.saving,
                onDismiss = { dialog = null },
                onSave = { merged ->
                    dialog = null
                    // 只改 marker 所在的那一处；另一处保持现值（patch 的未传字段 core 会保留）。
                    persist(
                        when (secretField?.target) {
                            WebhookSecretTarget.BODY -> WebhookConfigPatch(body_template = merged)
                            else -> WebhookConfigPatch(url = merged)
                        }
                    )
                }
            )

            is WebhookDialog.Header -> HeaderEditDialog(
                headers = state.config?.headers ?: emptyMap(),
                originalName = open.originalName,
                onDismiss = { dialog = null },
                onSave = { name, value ->
                    dialog = null
                    val base = state.config?.headers ?: emptyMap()
                    // 改名等于"删旧键 + 加新键"：headers 是整体替换，一次算好完整 map 再发。
                    val next = (if (open.originalName != null) base - open.originalName else base) +
                        (name to value)
                    persist(WebhookConfigPatch(headers = next))
                }
            )
            WebhookDialog.BodyTemplate -> BodyTemplateDialog(
                config = state.config,
                onDismiss = { dialog = null },
                onSave = { template ->
                    dialog = null
                    persist(WebhookConfigPatch(body_template = template))
                }
            )
            WebhookDialog.DailyLimit -> NotifyDailyLimitDialog(
                current = state.config?.daily_limit ?: 0,
                min = state.config?.daily_limit_min ?: 0,
                max = state.config?.daily_limit_max ?: 0,
                saving = state.saving,
                onDismiss = { dialog = null },
                onSave = { limit ->
                    dialog = null
                    persist(WebhookConfigPatch(daily_limit = limit))
                }
            )
            WebhookDialog.Scenes -> NotifyScenesDialog(
                title = "触发场景",
                leadNote = "未勾选任何场景时，只有手动测试会发送；自动通知仍受「全局通知」总开关约束。",
                selected = state.config?.scenes ?: emptyList(),
                criticalOverrideOn = criticalOverrideOn,
                onDismiss = { dialog = null },
                onSave = { next ->
                    dialog = null
                    persist(WebhookConfigPatch(scenes = next))
                }
            )
            WebhookDialog.TestResult -> TestResultDialog(
                result = state.lastTest,
                onDismiss = { dialog = null }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

// ══════════════════════════════════════════════════════════════
// 弹窗
// ══════════════════════════════════════════════════════════════

/** 本页可能打开的弹窗。互斥，所以是一个可空的 sealed 值而不是一堆布尔。 */
private sealed interface WebhookDialog {
    data object Request : WebhookDialog

    /** 预设要求填的那一样东西（device key / topic / token…）。 */
    data object Secret : WebhookDialog

    /** @param originalName null = 新增；非 null = 编辑该头部（允许连键名一起改）。 */
    data class Header(val originalName: String?) : WebhookDialog
    data object BodyTemplate : WebhookDialog
    data object DailyLimit : WebhookDialog
    data object Scenes : WebhookDialog
    data object TestResult : WebhookDialog
}


/**
 * 请求配置弹窗（暂存-确认）：URL + 方法 + 超时 + Content-Type。
 *
 * 超时对用户按**秒**呈现、对 core 按毫秒下发：`timeout_ms` 的合法区间是 1000..60000，
 * 让用户在毫秒上填一个五位数只会填错。区间在这里也校验一遍 ——
 * core 会拒，但等 PUT 回来才报错等于让用户白填一次。
 *
 * Content-Type 也在这里：它决定占位符按 JSON 还是纯文本转义（core 的 `WebhookDelivery.isJson`），
 * 与 URL / 模板是同一个请求上的事。**不能留空** —— core 会拒（`content_type 不能为空`），
 * 所以空值在这里就拦住。
 */
@Composable
private fun RequestConfigDialog(
    config: WebhookConfigResponse?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (url: String, method: String, timeoutMs: Long, contentType: String) -> Unit
) {
    var url by remember { mutableStateOf(config?.url ?: "") }
    var method by remember { mutableStateOf(config?.method ?: DEFAULT_METHOD) }
    var timeoutSec by remember {
        mutableStateOf(((config?.timeout_ms ?: 0L) / MILLIS_PER_SECOND).toString())
    }
    var contentType by remember { mutableStateOf(config?.content_type ?: "") }

    val trimmedUrl = url.trim()
    // 空 URL 是合法的（= 还没配完，core 会判它不可投），但填了就必须是 http(s)。
    val urlError = if (trimmedUrl.isNotEmpty() && !isHttpUrl(trimmedUrl)) {
        "必须以 http:// 或 https:// 开头"
    } else null
    val sec = timeoutSec.toLongOrNull()
    val timeoutError = if (sec == null || sec !in MIN_TIMEOUT_SEC..MAX_TIMEOUT_SEC) {
        "请填 $MIN_TIMEOUT_SEC–$MAX_TIMEOUT_SEC 之间的秒数"
    } else null
    val trimmedContentType = contentType.trim()
    val contentTypeError = if (trimmedContentType.isEmpty()) "不能为空" else null
    val valid = urlError == null && timeoutError == null && contentTypeError == null

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "请求配置",
        // 按钮必须走 actions 槽位：塞进 UfiDialogBody 会让弹窗按 32dp 预算 footer，
        // 底部多出一块空白（组件注释里的 FIX-24）。
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    if (sec != null) {
                        onSave(trimmedUrl, method, sec * MILLIS_PER_SECOND, trimmedContentType)
                    }
                },
                confirmText = "保存",
                enabled = valid,
                loading = saving
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "请求地址",
                value = url,
                onValueChange = { url = it },
                placeholder = "https://…",
                isError = urlError != null,
                errorMessage = urlError
            )
            UfiDialogChipSelector(
                label = "请求方法",
                options = METHOD_OPTIONS,
                selectedValue = method,
                onSelect = { method = it }
            )
            UfiDialogField(label = "超时（秒）") {
                UfiDigitField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it },
                    label = "",
                    isError = timeoutError != null,
                    errorMessage = timeoutError
                )
            }
            UfiDialogTextField(
                label = "Content-Type",
                value = contentType,
                onValueChange = { contentType = it },
                placeholder = "application/json; charset=utf-8",
                isError = contentTypeError != null,
                errorMessage = contentTypeError
            )
            UfiDialogNote(
                "预设地址中的 <占位> 需替换为真实值，否则设备会判定「配置未填完」并拒绝发送。"
            )
        }
    }
}

/**
 * 「预设要求填的那一样东西」的编辑弹窗（暂存-确认）。
 *
 * 页面上只显示已填/未填与脱敏后的首尾几位，全文在这里给：用户要改它，星号回显之后
 * 再提交就把真值覆盖掉了（口径同 [HeaderEditDialog]）。
 *
 * 保存时拼回 `prefix + 输入 + suffix` 并整段替换掉预设的 marker（**连尖括号一起**）——
 * core 的 `isConfigured` 判据是"url / body 里含裸 `<` 就算没配完"，
 * 写成 `<abc123>` 会让这条渠道永远不投递，而界面上看不出原因。
 *
 * 留空保存 = **清空**：写回的是整段 marker（见 [WebhookSecretField.merged]），
 * 配置退回"未配置齐全"、core 继续拦住投递。与 web 侧同一口径 ——
 * 一端允许清空、另一端不允许，同一个功能就有两种行为。
 */
@Composable
private fun SecretEditDialog(
    field: WebhookSecretField?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    if (field?.value == null) return

    // 未填时给空框而不是把 marker（`<device_key>`）填进去：那会让弹窗一打开就顶着
    // 「不要带尖括号」的错误，看起来像是界面坏了。
    var draft by remember { mutableStateOf(if (field.filled) field.value else "") }
    val trimmed = draft.trim()
    val error = when {
        trimmed.isEmpty() -> null // 空 = 清空，是合法动作（写回 marker），不是错误
        trimmed.any { it == PLACEHOLDER_MARK || it == '>' } ->
            "不要带尖括号：预设里的 ${field.marker} 会被整段替换掉"
        trimmed.any { it.isWhitespace() } -> "不能含空格"
        else -> null
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = field.label,
        confirmButton = {
            UfiButton(
                // 空输入时按钮改说「清空」：点下去的后果与"保存一个值"不是一回事
                // （渠道会退回未配置并停止发送），措辞必须先说清楚。
                text = if (trimmed.isEmpty()) "清空" else "保存",
                enabled = error == null && !saving,
                loading = saving,
                onClick = { onSave(field.merged(draft)) }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = field.label,
                value = draft,
                onValueChange = { draft = it },
                isError = error != null,
                errorMessage = error
            )
            UfiDialogNote(
                if (trimmed.isEmpty()) {
                    "留空保存会清除已填的值：${field.targetLabel}恢复成预设默认的 ${field.marker}，" +
                        "这条渠道随即退回「未配置」并停止发送。"
                } else {
                    "保存后会写入${field.targetLabel}，其余部分按预设默认值保持不变；" +
                        "需要改动请求体模板或其它字段时，用页面上对应的入口。"
                }
            )
        }
    }
}


/**
 * 请求头编辑弹窗（新增 / 修改共用）。
 *
 * 值在这里显示**全文**（列表页才脱敏）：用户要改它，星号回显之后再提交就把真值覆盖掉了 ——
 * core 的 `WebhookRoutes` 文件头专门解释过这一点（headers 是任意键值对，
 * 不适用 `smtp_pass_set` 那套"星号回显、原值保留"）。
 */
@Composable
private fun HeaderEditDialog(
    headers: Map<String, String>,
    originalName: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit
) {
    var name by remember { mutableStateOf(originalName ?: "") }
    var value by remember { mutableStateOf(originalName?.let { headers[it] } ?: "") }

    val trimmedName = name.trim()
    val nameError = when {
        trimmedName.isEmpty() -> null // 还没填，不报错，只是不让保存
        trimmedName.any { it == '\r' || it == '\n' } -> "头部名不能含换行"
        trimmedName != originalName && headers.containsKey(trimmedName) -> "已经有同名头部了"
        else -> null
    }
    // 换行会被下游解析成额外的头部（HTTP 头注入），core 也会拒 —— 提前拦住。
    val valueError = if (value.any { it == '\r' || it == '\n' }) "头部值不能含换行" else null

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = if (originalName == null) "添加请求头" else "编辑请求头",
        confirmButton = {
            UfiButton(
                text = "保存",
                enabled = trimmedName.isNotEmpty() && nameError == null && valueError == null,
                onClick = { onSave(trimmedName, value) }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "名称",
                value = name,
                onValueChange = { name = it },
                placeholder = "Authorization",
                isError = nameError != null,
                errorMessage = nameError
            )
            UfiDialogTextField(
                label = "值",
                value = value,
                onValueChange = { value = it },
                placeholder = "Bearer …",
                isError = valueError != null,
                errorMessage = valueError
            )
        }
    }
}

/**
 * 请求体模板弹窗。
 *
 * 占位符清单**读 core 回来的 `placeholders`**（名字 + 说明），不在 app 里写死一份 ——
 * 抄错一个名字的表现是"填了占位符却不替换"，抄错说明的表现是"照说明填了但取到的不是那个值"，
 * 两样在界面上都看不出原因。
 *
 * ## 占位符区的形态：一排按钮，说明收起
 *
 * 点一下 chip 就把 `{{名字}}` 写到**光标处**（[UfiTextField] 的 `selection` /
 * `onSelectionChange` 把光标位置透出来；光标由本弹窗持有，否则每次重组都会跳到末尾）。
 *
 * 十个占位符各占一行（名字 + 说明 + 按钮）会把弹窗撑成一条长列表，而用户九成的动作只是
 * "把某个名字塞进模板" —— 所以默认只给一排 [UfiActionChipRow]，名字与说明的对照表收进
 * 默认折叠的「占位符说明」里。
 *
 * `highlight` 的风险提示**不跟着折叠**：它可能是短信验证码原文，插进模板等于把验证码发给
 * 第三方服务。这句话藏在收起区里等于没说。它是 app 侧的风险提示，core 的 `desc` 不该被改成这个。
 */
@Composable
private fun BodyTemplateDialog(
    config: WebhookConfigResponse?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var template by remember { mutableStateOf(config?.body_template ?: "") }
    // 光标位置。初始摆在末尾：弹窗刚打开时用户还没点过输入框，"追加"是最合理的默认。
    var caret by remember { mutableStateOf(TextRange(template.length)) }
    // 名字↔说明对照表默认收起：默认视图就该是那一排能点的按钮。
    var descriptionsOpen by remember { mutableStateOf(false) }
    val placeholders = config?.placeholders ?: emptyList()

    /** 把 `{{name}}` 插到光标处（有选区就替换掉选区），并把光标挪到插入内容之后。 */
    fun insert(name: String) {
        val token = "{{$name}}"
        val start = caret.start.coerceIn(0, template.length)
        val end = caret.end.coerceIn(start, template.length)
        template = template.take(start) + token + template.drop(end)
        caret = TextRange(start + token.length)
    }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "请求体模板",
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = { onSave(template) },
                confirmText = "保存"
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogField(label = "模板") {
                UfiTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = "",
                    singleLine = false,
                    minLines = BODY_TEMPLATE_MIN_LINES,
                    selection = caret,
                    onSelectionChange = { caret = it }
                )
            }
            if (placeholders.isNotEmpty()) {
                UfiDialogField(label = "可用占位符（点一下插到光标处）") {
                    UfiActionChipRow(
                        // id = 占位符名字，label 带上双花括号 —— 用户在模板里看到的就是这个形状
                        options = placeholders.map { it.name to "{{${it.name}}}" },
                        onClick = { name -> insert(name) }
                    )
                }
                if (placeholders.any { it.name == PLACEHOLDER_HIGHLIGHT }) {
                    UfiDialogWarning(HIGHLIGHT_RISK_NOTE)
                }
                UfiButton(
                    text = if (descriptionsOpen) "收起占位符说明" else "查看占位符说明",
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    onClick = { descriptionsOpen = !descriptionsOpen }
                )
                // 间距按弹窗内的节奏传，别吃页面级的卡间距（默认值是给页面用的）。
                UfiExpandSection(expanded = descriptionsOpen, spacing = Spacing.Small) {
                    placeholders.forEach { placeholder ->
                        UfiSettingsItem(
                            title = "{{${placeholder.name}}}",
                            description = placeholder.desc,
                            // 说明行也能点着插入：已经看到这一行了，还要回上面找那颗 chip 是多一步。
                            onClick = { insert(placeholder.name) }
                        )
                    }
                }
            }
            UfiDialogNote(
                "JSON 类模板的占位符会先做 JSON 转义再替换，正文里的引号与换行不会破坏请求体格式。"
            )
        }
    }
}



/**
 * 测试结果弹窗：状态码 + 完整响应体摘要。
 *
 * 用可滚动弹窗是因为响应体可能是一整段 JSON，截断了就等于没给
 * （`{"code":40001,"msg":"invalid token"}` 里有用的部分常在后半截）。
 */
@Composable
private fun TestResultDialog(result: WebhookTestResponse?, onDismiss: () -> Unit) {
    val palette = LocalResolvedPalette.current
    if (result == null) return

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "测试结果",
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onDismiss,
                confirmText = "知道了",
                dismissText = null
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogInfoRow(label = "结果", value = if (result.success) "已送达" else "未送达")
            // 本次**根本没投递**（闸门拦下、配额用尽 …）时 core 不再回诊断三件套
            // （status_code / response_body / attempted_at 整个键都不出现）。
            // 所以判据是 attempted_at 有没有：
            //  - 缺失 = 没发起过请求 → 不摆 HTTP 行，原因在下面的「错误」里；
            //    摆一个"无（请求未完成）"会让人以为请求发出去了只是没回来。
            //  - 有值 = 真发起了，此时 status_code 仍可能为 null（超时 / DNS / TLS）。
            if (result.attempted_at != null) {
                UfiDialogInfoRow(
                    label = "HTTP 状态码",
                    value = result.status_code?.toString() ?: "无（请求未完成）"
                )
            }
            UfiDialogInfoRow(
                label = "自动通知总开关",
                value = if (result.auto_notify_enabled) "开启" else "关闭"
            )
            if (!result.auto_notify_enabled) {
                UfiDialogWarning(
                    "测试请求不受总开关约束，自动通知受其约束。总开关关闭时这条测试可以送达，" +
                        "但自动通知不会发送；在「通知与守护」中打开「全局通知」后自动通知才会发出。"
                )
            }
            result.error?.takeIf { it.isNotBlank() }?.let {
                UfiDialogField(label = "错误") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.error)
                }
            }
            result.response_body?.takeIf { it.isNotBlank() }?.let {
                UfiDialogField(label = "响应体摘要") {
                    Text(it, style = UfiTextStyles.label, color = palette.textPrimary)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 纯函数：摘要 / 脱敏 / 判据
// ══════════════════════════════════════════════════════════════

/** 请求配置行的右侧摘要：`POST · api.day.app`。URL 只取 host，不把 token 摊在列表上。 */
private fun summarizeRequest(cfg: WebhookConfigResponse): String {
    val host = hostOf(cfg.url)
    return if (host.isEmpty()) "未填地址" else "${cfg.method} · $host"
}

/**
 * 请求体模板行的副文案。
 *
 * 密钥写在模板里的那一档（PushPlus）要多说一句：模板一旦被改得对不上预设的前后缀，
 * [resolveSecretField] 就切不出"用户填的那一段"，页面上那个简易输入框会消失。
 * 这句话必须在**点进模板之前**就说，否则输入框凭空不见时用户只会当成 bug。
 */
private fun bodyTemplateNote(secretField: WebhookSecretField?): String {
    if (secretField != null && secretField.target == WebhookSecretTarget.BODY) {
        return "占位符会在发送时替换成通知内容。「${secretField.label}」就写在这份模板里：" +
            "改动它周围的结构后，上面那个输入框会消失，改为在这里直接编辑。"
    }
    return "占位符会在发送时替换成通知内容；每个预设各有一份模板，可随时修改"
}

// ══════════════════════════════════════════════════════════════
// 「预设要求填的那一样东西」与存储之间的换算
// ══════════════════════════════════════════════════════════════

/**
 * 简易输入框的一次求解结果。
 *
 * @param label 输入框的标签，来自 core 的 `secret_label`（如「PushPlus Token」「Device Key」）。
 * @param marker 预设默认值里代表它的标记（如 `<token>`），只用于报错文案。
 * @param target 写回哪一处：[WebhookSecretTarget.URL] / [WebhookSecretTarget.BODY]。
 * @param prefix 预设默认值里 marker **之前**的那一段。
 * @param suffix 预设默认值里 marker **之后**的那一段。
 * @param value 用户已填的那一段；空串 = 未填。
 *   **null = 当前值与预设默认结构不同**（用户手工改过前后缀），此时不能给简易输入框 ——
 *   拼回 `prefix + 输入 + suffix` 会把他改过的部分吞掉。
 *
 * `internal` 而不是 `private`：换算规则由 `WebhookSecretFieldTest` 钉住（切分 / 未填判据 /
 * 结构不符都在那里），而单测只能看到 internal。
 */
internal data class WebhookSecretField(
    val label: String,
    val marker: String,
    val target: String,
    val prefix: String,
    val suffix: String,
    val value: String?
) {
    /** 那一样东西现在算不算填好了。判据与 core 的 `isConfigured` 对齐：留着 `<` 就是没填。 */
    val filled: Boolean
        get() = value != null && value.isNotBlank() && !value.contains(PLACEHOLDER_MARK)

    /** 界面上说"写到哪里"用的人话。 */
    val targetLabel: String
        get() = if (target == WebhookSecretTarget.BODY) "请求体模板" else "请求地址"

    /**
     * 用户输入 → 要存进 url / body_template 的最终值。
     *
     * 空输入（或全空白）= **清空**，此时写回的是整段 [marker] 而不是空串。
     * 直接拼成空会得到 `https://api.day.app/` 这种"不含裸 `<`、却打不到任何目标"的地址：
     * core 的 `isConfigured` 会判成配置完整 → 界面显示"已启用"，而每条通知都发往一个
     * 不存在的地址（本仓禁止的假成功）。把 marker 还原回去，core 才会继续判成"没配完"
     * 并拦住投递 —— 于是"清掉 token 退回未配置"是一条走得通的路。
     *
     * 口径与 web 侧的清空行为一致：同一个功能在两端行为不同本身就是坑。
     */
    fun merged(input: String): String {
        val trimmed = input.trim()
        return prefix + trimmed.ifEmpty { marker } + suffix
    }
}


/**
 * 求解「用户真正要填的那一样东西」现在填了什么。
 *
 * 返回 null = 这个预设没有这个概念（`CUSTOM`，或老 core 没回预设表 / marker 声明缺失），
 * 界面上就不显示简易输入框，直接展开高级设置。
 *
 * ## 为什么要按前后缀切分
 *
 * 存储里存的是**最终**的 url / body_template —— marker 已经被用户填的值替换掉了，
 * 单看存储值没法知道"哪一段是用户填的"。于是拿该预设的默认值当模板：
 * 按 marker 切成 `prefix` + `suffix`，当前值只要头尾对得上，中间那段就是用户填的。
 *
 * 对不上（用户改过服务器地址、换过模板结构…）时返回 `value = null`：那种情况下继续给
 * 简易输入框，保存就会把他改过的部分覆盖掉。
 */
internal fun resolveSecretField(
    cfg: WebhookConfigResponse,
    preset: WebhookPresetDto?
): WebhookSecretField? {
    if (preset == null) return null
    val marker = preset.secret_marker
    if (marker.isEmpty() || preset.secret_target == WebhookSecretTarget.NONE) return null

    val default = when (preset.secret_target) {
        WebhookSecretTarget.URL -> preset.url
        WebhookSecretTarget.BODY -> preset.body_template
        // 认不出的 target（core 加了新档而 app 还没跟上）：当成"没有简易输入框"，
        // 高级设置里所有字段都还在，用户不会被卡住。
        else -> return null
    }
    val markerAt = default.indexOf(marker)
    // core 有单测钉住 marker 一定出现在它声明的那一处（`secretMarkerMismatches`）。
    // 真出现不一致时也不能猜位置：切不出前后缀就退化成全手填。
    if (markerAt < 0) return null

    val prefix = default.take(markerAt)
    val suffix = default.drop(markerAt + marker.length)
    val current = when (preset.secret_target) {
        WebhookSecretTarget.BODY -> cfg.body_template
        else -> cfg.url
    }
    val structural = current.length >= prefix.length + suffix.length &&
        current.startsWith(prefix) &&
        current.endsWith(suffix)

    return WebhookSecretField(
        label = preset.secret_label.ifEmpty { preset.user_fills },
        marker = marker,
        target = preset.secret_target,
        prefix = prefix,
        suffix = suffix,
        value = if (structural) {
            current.substring(prefix.length, current.length - suffix.length)
        } else null
    )
}


/** 从 URL 里取出 host（含端口）；取不到返回空串。不用 `java.net.URI`：非法 URL 会抛。 */
private fun hostOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return ""
    val hostStart = schemeEnd + "://".length
    val end = url.drop(hostStart).indexOfFirst { it == '/' || it == '?' || it == '#' }
    return if (end < 0) url.drop(hostStart) else url.drop(hostStart).take(end)
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/**
 * 列表页用的头部值脱敏。
 *
 * 口径参照 core 的 `WebhookDelivery.maskHeaders`（同一份敏感词表），但呈现不同：
 * core 是给日志的，整值换成 `***(长度)`；这里是给用户核对的，保留首尾几位 ——
 * 「我配的是哪一个 token」看首尾就能认出来，而截图/录屏泄不出完整凭据。
 *
 * 敏感词表在 app 侧重列了一遍：core 那份是 `private`（它服务的是日志脱敏，
 * 属于安全边界），而这里只影响一行文案的显示。两边只要都是"宁可多脱敏一个"就不会出事，
 * 真正的凭据保护仍然在 core 那一侧。
 */
private fun maskHeaderValueForDisplay(name: String, value: String): String {
    val sensitive = SENSITIVE_HEADER_HINTS.any { name.contains(it, ignoreCase = true) }
    if (!sensitive) return value
    return maskCredential(value)
}

/**
 * 凭据的列表态显示：保留首尾几位 + 长度。
 *
 * 「我配的是哪一个 token」看首尾就能认出来，而截图 / 录屏泄不出完整凭据。
 * 预设那个唯一输入框与凭据类头部共用这一份口径 —— 两处长得不一样只会让人以为其中一处出了问题。
 */
private fun maskCredential(value: String): String {
    if (value.length <= MASK_KEEP_HEAD + MASK_KEEP_TAIL) return "***（${value.length} 位）"
    return "${value.take(MASK_KEEP_HEAD)}…${value.takeLast(MASK_KEEP_TAIL)}（${value.length} 位）"
}


/**
 * 状态码的一句话取值（列表行右侧与 toast 共用）。
 *
 * 三档，靠 `attempted_at` **在不在**区分前两档：
 * - 字段缺失 = 本次一次请求都没发起（闸门拦下 / 配额用尽 / 配置不完整）→「未发送」。
 *   这一档 core 不再回诊断三件套，说成"未收到响应"就把"没发"讲成了"发了没回"。
 * - 有 `attempted_at` 但没 `status_code` = 真发起了却没走完（超时 / DNS / TLS）。
 * - 两者都有 = 正常的 HTTP 结果。
 */
private fun webhookStatusValue(result: WebhookTestResponse): String = when {
    result.attempted_at == null -> "未发送"
    result.status_code != null -> "HTTP ${result.status_code}"
    else -> "未收到响应"
}

/** 最近测试结果行的副文案。成功也要给一句，用户才知道那条 HTTP 到底怎么回的。 */
private fun summarizeTestOutcome(result: WebhookTestResponse): String = when {
    !result.success -> result.error?.takeIf { it.isNotBlank() } ?: "未送达（无错误信息）"
    !result.auto_notify_enabled -> "已送达，但通知总开关关闭，自动通知不会发送"
    else -> "已送达"
}

/**
 * 测试结果 toast。
 *
 * 成功也要把状态码写出来：Webhook 的"成功"是 2xx，而某些服务用 200 回业务错误
 * （`{"code":40001}`），只说"成功"会让用户停止排查。
 */
private fun buildTestToast(result: WebhookTestResponse): ToastMessage {
    val statusPart = webhookStatusValue(result)
    val bodyPart = result.response_body?.takeIf { it.isNotBlank() }
        ?.take(RESPONSE_BODY_INLINE_CHARS)
    return when {
        !result.success -> ToastMessage(
            text = "测试失败（$statusPart）",
            type = ToastType.ERROR,
            subtitle = result.error?.takeIf { it.isNotBlank() } ?: bodyPart
        )
        // 送达 ≠ 以后收得到：测试走 manual 口径不受总闸约束，自动通知受。
        !result.auto_notify_enabled -> ToastMessage(
            text = "已送达（$statusPart），但通知总开关当前关闭",
            type = ToastType.WARNING,
            subtitle = "在「通知与守护」中打开「全局通知」后，自动通知才会发出"
        )
        else -> ToastMessage(
            text = "测试已送达（$statusPart）",
            type = ToastType.SUCCESS,
            subtitle = bodyPart
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 常量
// ══════════════════════════════════════════════════════════════

/**
 * 可选请求方法。与 core 的 `WebhookConfig.ALLOWED_METHODS`（真源是 `HttpNotifier.METHOD_NAMES`）
 * 一致；GET 响应里没有回这张表，所以这里列一遍。多列一个 core 会在 PUT 时拒掉并说明原因，
 * 不会静默存进去。
 */
private val METHOD_OPTIONS: List<Pair<String, String>> =
    listOf("POST" to "POST", "GET" to "GET", "PUT" to "PUT")

private const val DEFAULT_METHOD = "POST"

/** 超时的用户可见单位是秒；core 的 `timeout_ms` 合法区间 1000..60000 换算过来就是这两个数。 */
private const val MIN_TIMEOUT_SEC = 1L
private const val MAX_TIMEOUT_SEC = 60L
private const val MILLIS_PER_SECOND = 1_000L

/** 模板输入框的初始可见行数：JSON 模板通常三四行，一行高的框要一直横向滚。 */
private const val BODY_TEMPLATE_MIN_LINES = 4

/**
 * 预设里"这里要你自己填"的记号（core 的 `WebhookDelivery.PLACEHOLDER_MARK`）。
 *
 * 只用于**判断填没填**：core 的 `isConfigured` 认为 url / body 里留着裸 `<` 就是没配完，
 * 界面上的"未填写"必须与它同一个判据，否则会出现"界面说填好了、设备说没填完"。
 */
private const val PLACEHOLDER_MARK = '<'

/**
 * 需要额外挂风险提示的占位符名字。
 *
 * 这不是"第二张占位符表"（清单与说明仍然全部读 core 的 `placeholders`），
 * 只是标出十项里哪一项要多说一句话。
 */
private const val PLACEHOLDER_HIGHLIGHT = "highlight"

/**
 * `{{highlight}}` 的风险提示。
 *
 * 属于 app 侧的呈现，不该写进 core 的 `desc`：core 那句说的是"这个占位符取什么值"，
 * 而这句说的是"把它放进模板意味着什么"。
 */
private const val HIGHLIGHT_RISK_NOTE =
    "{{highlight}} 可能是短信验证码原文。插入模板后，验证码会随通知一起发送给第三方服务；" +
        "只在你信任该服务时使用。"

/**
 * 预设行的副文案：说清"切换不会丢东西"。
 *
 * 这一句不是安慰话 —— core 侧每个预设各存一份地址与模板，用户在 A 预设里填的 token
 * 切到 B 再切回来仍在。不写出来的话，用户会因为"怕丢"而不敢试第二个预设。
 */
private const val PRESET_ISOLATION_NOTE =
    "每个预设的请求地址与请求体模板各自保存，切换不会丢"

/** 当前 url / 模板与预设默认结构不同时的说明（此时不给简易输入框，见 [resolveSecretField]）。 */
private const val SECRET_DETACHED_NOTE =
    "当前配置与预设默认结构不同（请求地址或请求体模板被改过），无法只改这一段。" +
        "请在「请求体模板」或「高级设置 → 请求配置」中直接编辑。"


/**
 * 响应体摘要能塞进 toast 副标题的字数上限；超了就改用可滚动弹窗摊开。
 *
 * 120 是按 toast 两行副标题的容量估的 —— 再多就会被截断，而被截断的排错信息等于没给。
 */
private const val RESPONSE_BODY_INLINE_CHARS = 120

/** 首屏骨架的行数：本页稳态大约就是这么多张卡，行数对不上会让骨架→内容时明显跳一下。 */
private const val WEBHOOK_SKELETON_ROWS = 8

/** 头部名里出现这些词就按凭据处理（口径同 core 的 `WebhookDelivery.SENSITIVE_HEADER_HINTS`）。 */
private val SENSITIVE_HEADER_HINTS =
    listOf("authorization", "token", "key", "secret", "cookie", "auth")

/** 脱敏后保留的首/尾字符数：够用户认出"是哪一个凭据"，又不足以拼回原值。 */
private const val MASK_KEEP_HEAD = 4
private const val MASK_KEEP_TAIL = 2
