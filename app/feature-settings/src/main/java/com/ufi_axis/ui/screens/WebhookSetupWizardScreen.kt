package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.WebhookConfigPatch
import com.ufi_axis.data.model.WebhookSecretTarget
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * Webhook **首次配置向导**（2026-09-21）。
 *
 * 路由 [com.ufi_axis.ui.navigation.Routes.DETAIL_WEBHOOK_SETUP]；入口在 [WebhookNotifyScreen]
 * 顶部，只在 core 回的 `configured == false` 时出现。
 *
 * ## 为什么不是把 [WebhookNotifyScreen] 改成向导
 * 那一页是**日常微调面板**：启用 / 级别 / 上限 / 场景 / 免打扰各自独立，改完立即下发。
 * 套进向导就得给这些开关造一个不存在的「完成」时刻 —— 而它们本来就没有提交动作。
 * 但「从 0 配一条 Webhook」确实有顺序：选预设 → 填那一样密钥 → 定规则 → 保存并启用，
 * 现在这四件事散在一页十张卡里，"配到哪一步了"只能靠翻。所以是多一条路，不是改原路。
 *
 * ## 与原页面共用的判据
 * - [resolveSecretField]：把「用户真正要填的那一样东西」从存储值里切出来（换算规则由
 *   `WebhookSecretFieldTest` 钉住）。这里**不另写一份**，否则两条路径对"填没填完"的看法会分叉；
 * - 场景清单用同一份 [NOTIFY_SCENE_LABELS]；级别选项用 core 回的 `cfg.levels`。
 *
 * ## 下发时机
 * - **选预设当场下发**（只发 `preset` 一个字段）：core 侧每个预设各存一份「发到哪 / 怎么发」，
 *   不切过去就读不到那一份的默认地址与模板，后面两步无从下手。这一点与原页面一致，
 *   同样不把本地表单的值一起 PUT 上去（那会把"从未配过"坐实成"配过"）。
 * - **其余全部攒到末步一次 PUT**：密钥 + 级别 + 场景 + `enabled = true` 一起发。
 *   core 的 patch 是字段级合并，所以一次请求就能落地，不会出现"配了一半就启用了"。
 */
@Composable
fun WebhookSetupWizardScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.webhookState.collectAsState()

    LaunchedEffect(Unit) { viewModel.tools.loadWebhookConfig() }

    var currentStep by remember { mutableStateOf(0) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    /** 刚下发的是"换预设"：落地后要再拉一次 GET，界面只吃 core 的回读（理由同原页面）。 */
    var presetJustSwitched by remember { mutableStateOf(false) }
    /** 末步那次 PUT 已发出，等结算。与换预设分开记，否则两种保存会互相误判。 */
    var submitted by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    val cfg = state.config
    val selectedPreset = cfg?.let { c -> c.presets.firstOrNull { it.name == c.preset } }
    val secretField = cfg?.let { resolveSecretField(it, selectedPreset) }

    // 草稿按预设分家：换了预设，上一档填的密钥就没有意义了
    var secretDraft by remember(cfg?.preset) {
        mutableStateOf(secretField?.value?.takeIf { it.isNotBlank() && !it.contains('<') }.orEmpty())
    }
    var urlDraft by remember(cfg?.preset) {
        // 含 `<占位>` 的地址是预设默认值、不是用户填的，当成空
        mutableStateOf(cfg?.url?.takeIf { it.isNotBlank() && !it.contains('<') }.orEmpty())
    }
    var levelDraft by remember(cfg?.preset) { mutableStateOf(cfg?.min_level.orEmpty()) }
    var sceneDraft by remember(cfg?.preset) { mutableStateOf(cfg?.scenes?.toSet() ?: emptySet()) }

    LaunchedEffect(state.saveTick) {
        if (state.saveTick == 0) return@LaunchedEffect
        val err = state.saveError
        val switched = presetJustSwitched
        presetJustSwitched = false
        if (switched) {
            if (err == null) viewModel.tools.loadWebhookConfig()
            else toastMessage = ToastMessage("切换预设失败", ToastType.ERROR, subtitle = err)
            return@LaunchedEffect
        }
        if (!submitted) return@LaunchedEffect
        submitted = false
        if (err == null) {
            toastMessage = ToastMessage("Webhook 已配置并启用", ToastType.SUCCESS)
            finished = true
        } else {
            // core 的 400 文案写明了是哪一条约束不过，原样转出去比"保存失败"有用
            toastMessage = ToastMessage("保存失败", ToastType.ERROR, subtitle = err)
        }
    }

    UfiScreenScaffold(
        title = "配置 Webhook",
        navController = navController,
        showBack = true
    ) { padding ->
        if (cfg == null) {
            // 与原页面同口径：读不到就不画表单。拿默认值画出来让用户改，一保存就覆盖设备上的真配置。
            UfiPageBackground(modifier = Modifier.padding(padding)) {
                if (!state.loaded) {
                    UfiSkeletonList(rows = WEBHOOK_SETUP_SKELETON_ROWS)
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
            }
            return@UfiScreenScaffold
        }

        /** 密钥步能否离开。没有简易输入框的档（CUSTOM / 结构被改过）退化成校验请求地址。 */
        fun secretError(): String? {
            val field = secretField
            return if (field != null && field.value != null) {
                when {
                    secretDraft.isBlank() -> "请填写「${field.label}」"
                    // 留着裸 `<` core 会判成"没配完"、一条都不投
                    secretDraft.contains('<') -> "还有 <占位> 没换成真实值"
                    else -> null
                }
            } else {
                when {
                    urlDraft.isBlank() -> "请填写请求地址"
                    !urlDraft.startsWith("http://", true) && !urlDraft.startsWith("https://", true) ->
                        "地址需以 http:// 或 https:// 开头"
                    urlDraft.contains('<') -> "地址里还有 <占位> 没换成真实值"
                    else -> null
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            UfiWizard(
                steps = listOf(
                    UfiWizardStep(
                        label = "预设",
                        heading = "要推到哪个服务",
                        description = "选好预设，请求地址与请求体模板由设备端按该服务填好；" +
                            "选「自定义」则全部手填。",
                        // 换预设是当场下发的，落地前别让用户往下走：下一步读的是切换后的那一份
                        validate = { if (state.saving) "正在切换预设…" else null }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            if (cfg.presets.isEmpty()) {
                                // 老 core 不回预设表：没得选，直接走自定义那条路
                                UfiDialogInfoRow("当前预设", cfg.preset)
                                UfiDialogNote("设备端未提供预设列表，下一步请手工填写请求地址。")
                            } else {
                                UfiOptionGrid(
                                    options = cfg.presets.map { p ->
                                        UfiOptionItem(
                                            value = p.name,
                                            label = p.display_name.ifBlank { p.name }
                                        )
                                    },
                                    selectedValue = cfg.preset,
                                    onSelect = { name ->
                                        if (name != cfg.preset) {
                                            presetJustSwitched = true
                                            viewModel.tools.saveWebhookConfig(
                                                WebhookConfigPatch(preset = name)
                                            )
                                        }
                                    },
                                    columns = 2
                                )
                                selectedPreset?.user_fills?.takeIf { it.isNotBlank() }?.let {
                                    UfiDialogNote("这个预设需要你准备：$it")
                                }
                            }
                        }
                    },
                    UfiWizardStep(
                        label = "密钥",
                        heading = "填上那一样只有你有的东西",
                        description = "就是服务商给你的那串标识；它会被写进设备端的请求配置里。",
                        validate = ::secretError
                    ) {
                        val field = secretField
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            if (field != null && field.value != null) {
                                UfiDialogTextField(
                                    label = field.label,
                                    value = secretDraft,
                                    onValueChange = { secretDraft = it },
                                    placeholder = field.marker
                                )
                                UfiDialogNote("会写进「${field.targetLabel}」的对应位置，其余部分保持预设默认值。")
                            } else {
                                UfiDialogTextField(
                                    label = "请求地址",
                                    value = urlDraft,
                                    onValueChange = { urlDraft = it },
                                    placeholder = "https://example.com/hook"
                                )
                                UfiDialogNote(
                                    if (secretField == null && selectedPreset != null) SECRET_DETACHED_NOTE
                                    else "自定义预设需要自己给出完整地址；请求体模板可在配置页里改。"
                                )
                            }
                        }
                    },
                    UfiWizardStep(
                        label = "规则",
                        heading = "什么样的事件要推",
                        description = "这两项之后都能随时改；不勾场景时只有手动试发会发出去。"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            if (cfg.levels.isNotEmpty()) {
                                UfiDialogChipSelector(
                                    label = "最低级别",
                                    options = cfg.levels.map { lv ->
                                        lv.name to lv.label.ifBlank { lv.name }
                                    },
                                    selectedValue = levelDraft,
                                    onSelect = { levelDraft = it }
                                )
                            }
                            UfiDialogField("触发场景") {
                                UfiMultiChipSelector(
                                    options = NOTIFY_SCENE_LABELS,
                                    selectedValues = sceneDraft,
                                    onToggle = { id ->
                                        sceneDraft = if (id in sceneDraft) sceneDraft - id else sceneDraft + id
                                    }
                                )
                            }
                            if (sceneDraft.isEmpty()) {
                                UfiDialogNote("一个场景都没勾：配置会保存并启用，但不会有任何自动通知发出。")
                            }
                        }
                    },
                    UfiWizardStep(
                        label = "确认",
                        heading = "核对一遍就启用",
                        description = "保存后会直接启用这条渠道；之后在配置页可以试发、改模板与请求头。"
                    ) {
                        val field = secretField
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiWizardReviewCard(
                                title = "推送目标",
                                icon = Icons.Default.VpnKey,
                                rows = buildList {
                                    add(
                                        UfiWizardReviewRow(
                                            "预设",
                                            selectedPreset?.display_name?.ifBlank { cfg.preset } ?: cfg.preset
                                        )
                                    )
                                    if (field != null && field.value != null) {
                                        add(UfiWizardReviewRow(field.label, maskSecretForReview(secretDraft)))
                                        add(UfiWizardReviewRow("写入位置", field.targetLabel))
                                    } else {
                                        add(UfiWizardReviewRow("请求地址", urlDraft.trim()))
                                    }
                                }
                            )
                            UfiWizardReviewCard(
                                title = "推送规则",
                                icon = Icons.Default.Tune,
                                rows = listOf(
                                    UfiWizardReviewRow(
                                        "最低级别",
                                        cfg.levels.firstOrNull { it.name == levelDraft }
                                            ?.label?.ifBlank { levelDraft }
                                            ?: levelDraft.ifBlank { "未设置" }
                                    ),
                                    UfiWizardReviewRow(
                                        "触发场景",
                                        if (sceneDraft.isEmpty()) "未勾选" else "已选 ${sceneDraft.size} 项"
                                    )
                                )
                            )
                            UfiWizardReviewCard(
                                title = "保存后",
                                icon = Icons.Default.Notifications,
                                rows = listOf(
                                    UfiWizardReviewRow("渠道开关", "启用"),
                                    UfiWizardReviewRow("每日上限 / 免打扰", "保持设备现值，可在配置页调整")
                                )
                            )
                        }
                    }
                ),
                currentStep = currentStep,
                onStepChange = { currentStep = it },
                onFinish = {
                    val field = secretField
                    // 一次 PUT 把"能不能投"需要的全部字段一起落地：core 是字段级合并，
                    // 分多次发会出现"启用了但密钥还没写进去"的中间态。
                    val patch = when {
                        field != null && field.value != null &&
                            field.target == WebhookSecretTarget.BODY ->
                            WebhookConfigPatch(
                                body_template = field.merged(secretDraft),
                                min_level = levelDraft.takeIf { it.isNotBlank() },
                                scenes = sceneDraft.toList(),
                                enabled = true
                            )
                        field != null && field.value != null ->
                            WebhookConfigPatch(
                                url = field.merged(secretDraft),
                                min_level = levelDraft.takeIf { it.isNotBlank() },
                                scenes = sceneDraft.toList(),
                                enabled = true
                            )
                        else ->
                            WebhookConfigPatch(
                                url = urlDraft.trim(),
                                min_level = levelDraft.takeIf { it.isNotBlank() },
                                scenes = sceneDraft.toList(),
                                enabled = true
                            )
                    }
                    submitted = true
                    viewModel.tools.saveWebhookConfig(patch)
                },
                finishText = "保存并启用",
                finishLoading = state.saving && submitted,
                onStepBlocked = { toastMessage = ToastMessage(it, ToastType.WARNING) }
            )
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
        // 先让 toast 挂出去再退页（toast 挂在 Activity 的 decorView 上，不随本页销毁）
        LaunchedEffect(finished) { if (finished) navController.popBackStack() }
    }
}

/**
 * 确认页上密钥的显示：只留首尾几位。
 *
 * 确认页的用途是"我填的是不是那一个"，首尾就够认；而这一页很可能被截图发给别人问"为什么发不出去"。
 * 短到看不出首尾时整串打码，别给出一个几乎等于明文的"脱敏"。
 */
private fun maskSecretForReview(value: String): String {
    val v = value.trim()
    if (v.isEmpty()) return "未填写"
    if (v.length <= SETUP_MASK_MIN_LEN) return "•".repeat(v.length)
    return v.take(SETUP_MASK_KEEP) + "•".repeat(v.length - SETUP_MASK_KEEP * 2) + v.takeLast(SETUP_MASK_KEEP)
}

/** 短于这个长度就整串打码（首尾各留 3 位的话中间会一位不剩）。 */
private const val SETUP_MASK_MIN_LEN = 8

/** 脱敏后首尾各保留的位数。 */
private const val SETUP_MASK_KEEP = 3

/** 首屏骨架的行数：向导第一步大约就是这么高，行数对不上会让骨架→内容时明显跳一下。 */
private const val WEBHOOK_SETUP_SKELETON_ROWS = 4
