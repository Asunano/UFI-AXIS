package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.LocalThemePalette
import com.ufi_axis.ui.theme.ProvideThemePalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.buildColorSchemeFromPalette
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 组件画廊 —— 把共享组件库按族铺开，用于**发现"同一种 UI 在不同界面不一样"**。
 *
 * 为什么需要它（2026-09-03 建立）：`components/common/` 下的组件全部平铺在一个目录里，
 * 在此之前**全库没有任何组件预览页**，重复与不一致只能靠逐页翻真机才能发现。
 *
 * 现状口径（2026-09-07 实测，替换建立时那组已过期的数字）：56 个文件、124 个顶层 Composable；
 * 带文字按钮已收敛为 1 个入口（`UfiButton`，P4c 把 5 个合并掉），其余族的入口数为
 * 对话框壳 9 / 加载态 13 / 卡片 5（广义 11）/ chip 6 / 输入 5+3（弹窗版仍是平行的一套，待收敛）。
 * 这些数字会变，**不要拿它当依据做判断**，需要准数时数一遍。
 *
 * 用法：设置 → 关于本机 → 组件画廊（2026-09-05 从「设置 → 外观」搬来）。顶部可切换明/暗，
 * 用来查"换主题后哪些元素没跟着变"（画廊只覆盖 [LocalResolvedPalette]，因此读
 * `MaterialTheme.colorScheme` 或写死 `Color.White` 的组件会在这里原地暴露出来）。
 *
 * 维护约定：新增共享组件时**必须**在这里补一条预览。删除组件时同步删预览（编译会提醒）。
 *
 * 覆盖范围：按钮 / chip 与徽章 / 列表行 / 卡片 / 容器卡 / 输入 / 开关与勾选 / 反馈态 /
 * 分段控件 / 弹窗 / 图标一致性，共 11 个分区。
 * 刻意不含：需要真实数据或宿主环境的组件（`UfiChart`、`UfiCapsuleTabBar`、页面切换器、
 * 文件与短信等业务组件）—— 它们在画廊里只能造假数据，看了也说明不了一致性问题。
 */
@Composable
fun UfiGalleryScreen(navController: NavHostController) {
    val basePalette = LocalThemePalette.current
    val outerPalette = LocalResolvedPalette.current
    var previewDark by remember { mutableStateOf(outerPalette.isDark) }

    UfiScreenScaffold(
        title = "组件画廊",
        navController = navController,
        showBack = true
    ) {
        GalleryPreviewTheme(basePalette = basePalette, isDark = previewDark) {
            val palette = LocalResolvedPalette.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.pageBg)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(Spacing.GroupSpacing))

                // ── 明暗切换：画廊自己的预览开关，不影响 App 主题 ──
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "预览暗色模式",
                        description = "仅切换本页预览配色，不改 App 主题设置",
                        checked = previewDark,
                        onCheckedChange = { previewDark = it }
                    )
                }

                // 2026-09-04（P4c 已完成）：本分区原来演示 5 个独立按钮组件
                // （UfiPrimaryButton / UfiSecondaryButton / UfiSmallButton / UfiDangerButton /
                // UfiOutlinedActionButton）。它们已合并为唯一入口 UfiButton(variant, size)，
                // 这里改成按 variant × size 铺开，用途是**换配色/改令牌后逐格比对观感**。
                // 新增按钮样式请加 variant，不要新建组件（规约见 UfiButton.kt 文件头）。
                GallerySection("按钮 · 1 个入口 UfiButton（P4c 已由 5 个收敛完成）") {
                    // 2026-09-04（P2f）：按压反馈的验证入口。用户实测反馈"按压动画只有长按才生效"，
                    // 根因与修复见 ui/animation/PressFeedback.kt 文件头。这里写清预期，
                    // 免得下次验证的人又只去长按。
                    Text(
                        "验证按压动效：**短按（快点一下）也应该看得见缩放**，不需要长按。" +
                            "2026-09-04（P2f）前这里只有长按才有反应 —— 按压缩放原来跟随 isPressed " +
                            "做 animateFloatAsState，抬手瞬间就被拉回，短按走不出可见幅度。" +
                            "现在按下相位至少保持 UfiMotion.Duration.Micro（120ms）再弹回。" +
                            "四档幅度（Fab .92 / Cell .94 / Button .96 / Chip .97）不变，" +
                            "所以按钮的缩放本就比下面的 chip 明显一点，这是分层规则、不是 bug。" +
                            "开了系统「减少动效」时应当完全不缩放。",
                        style = UfiTextStyles.note,
                        color = palette.warning
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "variant = Primary（实底 accent + palette.onAccent）· size = Standard —— " +
                            "原 UfiPrimaryButton。Standard 档默认 fillWidth = true。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    UfiButton(text = "主按钮", onClick = {})
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(text = "主按钮 · loading", onClick = {}, loading = true)
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(text = "主按钮 · disabled", onClick = {}, enabled = false)

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "variant = Secondary（accent 描边）· size = Standard —— 原 UfiSecondaryButton。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    UfiButton(variant = UfiButtonVariant.Secondary, text = "次按钮", onClick = {})
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "次按钮 · loading",
                        onClick = {},
                        loading = true
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "次按钮 · disabled",
                        onClick = {},
                        enabled = false
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "variant = Danger（实底 palette.error + palette.onError）· size = Standard —— " +
                            "原 UfiDangerButton。P1c 已把写死的 Color.White 换成 onError 色槽。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    UfiButton(variant = UfiButtonVariant.Danger, text = "危险按钮", onClick = {})
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(
                        variant = UfiButtonVariant.Danger,
                        text = "危险按钮 · loading",
                        onClick = {},
                        loading = true
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(
                        variant = UfiButtonVariant.Danger,
                        text = "危险按钮 · disabled",
                        onClick = {},
                        enabled = false
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "variant = Subtle（dialogBorder 弱描边 + subtleShape 8dp）· size = Standard —— " +
                            "合并前不存在这个组合（旧 UfiOutlinedActionButton 只有小号），现由 variant × size 自然得到。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    UfiButton(variant = UfiButtonVariant.Subtle, text = "弱描边按钮", onClick = {})

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "variant = Donate（DonatePink 描边 + 同色文字，几何同 Secondary）—— " +
                            "唯一用途是关于页的赞赏入口。粉色是域色，**不跟随皮肤**，" +
                            "所以切换上方明暗预览或换配色时这一档观感不变，这是刻意的。" +
                            "实际调用点传的是手绘描边爱心（AppIconHeart，定义在 AboutDeviceScreen），" +
                            "这里用 Favorite 占位演示 icon 槽位。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(
                            variant = UfiButtonVariant.Donate,
                            size = UfiButtonSize.Small,
                            text = "赞赏",
                            icon = Icons.Filled.FavoriteBorder,
                            onClick = {}
                        )
                        UfiButton(
                            variant = UfiButtonVariant.Donate,
                            size = UfiButtonSize.Small,
                            text = "赞赏 · disabled",
                            icon = Icons.Filled.FavoriteBorder,
                            onClick = {},
                            enabled = false
                        )
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "icon 槽位与 loading 互斥：loading = true 时该位置让给转圈，" +
                            "不会出现「图标 + 转圈」同时挤在文字左边。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(
                            size = UfiButtonSize.Small,
                            text = "带图标",
                            icon = Icons.Filled.Download,
                            onClick = {}
                        )
                        UfiButton(
                            size = UfiButtonSize.Small,
                            text = "图标 + loading",
                            icon = Icons.Filled.Download,
                            onClick = {},
                            loading = true
                        )
                    }



                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "size = Small（36dp / 内距 h16 v0 / tagStrong 小字）—— 默认 fillWidth = false，自适应宽度。" +
                            "Primary+Small = 原 UfiSmallButton，Subtle+Small = 原 UfiOutlinedActionButton；" +
                            "Secondary/Danger 的小号是新组合。四者现已等高等内距，同一行能对齐。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(size = UfiButtonSize.Small, text = "小按钮", onClick = {})
                        UfiButton(
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            text = "描边动作",
                            onClick = {}
                        )
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            size = UfiButtonSize.Small,
                            text = "小次按钮",
                            onClick = {}
                        )
                        UfiButton(
                            variant = UfiButtonVariant.Danger,
                            size = UfiButtonSize.Small,
                            text = "小危险",
                            onClick = {}
                        )
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "Small 档的 loading / disabled（合并前小号按钮完全不支持 loading，现已统一支持）",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiButton(size = UfiButtonSize.Small, text = "小 · loading", onClick = {}, loading = true)
                        UfiButton(size = UfiButtonSize.Small, text = "小 · disabled", onClick = {}, enabled = false)
                        UfiButton(
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            text = "弱 · disabled",
                            onClick = {},
                            enabled = false
                        )
                    }

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "fillWidth 的逃生口：Standard 档默认铺满，显式传 fillWidth = false 可自适应" +
                            "（用于「大号但不占整行」，合并前只能降一档高度来凑）。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    UfiButton(text = "Standard · 不铺满", onClick = {}, fillWidth = false)

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiButtonRow（等宽横排容器，不是按钮，故未并入 UfiButton）", style = UfiTextStyles.note, color = palette.textSecondary)
                    UfiButtonRow {
                        UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = {}, modifier = Modifier.weight(1f))
                        UfiButton(text = "确定", onClick = {}, modifier = Modifier.weight(1f))
                    }
                }

                GallerySection("Chip 与徽章 · 按压反馈") {
                    Text(
                        "CategoryChip —— 自绘；按压缩放走 UfiMotion.PressScale.Chip（0.97）+ " +
                            "tween(Duration.Micro)，配色过渡 tween(Duration.Standard)；未加 Ufi 前缀。" +
                            "2026-09-04（P2d）：监控页三个私有筛选 chip 原来各写 0.94，已统一到本档。",
                        style = UfiTextStyles.note,
                        color = palette.warning
                    )
                    var chipSelected by remember { mutableStateOf(true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        CategoryChip(label = "已选中", selected = chipSelected, onClick = { chipSelected = true })
                        CategoryChip(label = "未选中", selected = !chipSelected, onClick = { chipSelected = false })
                        CategoryChip(label = "带图标", selected = false, onClick = {}, leadingIcon = Icons.Filled.Star)
                    }

                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        "UfiSingleChipSelector —— 分段控件：surfaceMuted 轨道 + cardBg 浮起滑块（tabSlider 平移），按压走 ufiPressScale",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    var single by remember { mutableStateOf("b") }
                    UfiSingleChipSelector(
                        options = listOf("a" to "选项 A", "b" to "选项 B", "c" to "选项 C"),
                        selectedValue = single,
                        onSelect = { single = it }
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiMultiChipSelector", style = UfiTextStyles.note, color = palette.textSecondary)
                    var multi by remember { mutableStateOf(setOf("x")) }
                    UfiMultiChipSelector(
                        options = listOf("x" to "多选 X", "y" to "多选 Y", "z" to "多选 Z"),
                        selectedValues = multi,
                        onToggle = { k -> multi = if (k in multi) multi - k else multi + k }
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiBadge · 5 种语义", style = UfiTextStyles.note, color = palette.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        UfiBadge(text = "默认", type = UfiBadgeType.DEFAULT)
                        UfiBadge(text = "成功", type = UfiBadgeType.SUCCESS)
                        UfiBadge(text = "警告", type = UfiBadgeType.WARNING)
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        UfiBadge(text = "错误", type = UfiBadgeType.ERROR)
                        UfiBadge(text = "信息", type = UfiBadgeType.INFO)
                    }
                }

                GallerySection("列表行 · 设置页唯一档位（Group 自带缩进）", contentInset = false) {
                    UfiSettingsGroup {
                        UfiSettingsItem(
                            title = "UfiSettingsItem",
                            description = "带说明与图标的标准行",
                            icon = Icons.Filled.Settings,
                            onClick = {},
                            trailing = { UfiSettingsChevron() }
                        )
                        UfiDivider()
                        var toggle by remember { mutableStateOf(true) }
                        UfiSettingsToggle(
                            title = "UfiSettingsToggle",
                            description = "行内自绘开关（非 M3 Switch）",
                            checked = toggle,
                            onCheckedChange = { toggle = it },
                            icon = Icons.Filled.Lock
                        )
                        UfiDivider()
                        UfiSettingsValue(
                            title = "UfiSettingsValue",
                            description = "右侧展示当前值",
                            value = "已开启",
                            onClick = {}
                        )
                        UfiDivider()
                        UfiSettingsItem(
                            title = "UfiSettingsItem · disabled",
                            description = "禁用态标题与说明降为禁用色",
                            icon = Icons.Filled.Person,
                            enabled = false
                        )
                    }

                    // UfiInfoRow（键值信息行）—— 放 RowCard 里，与上面的 Group 对照
                    UfiSettingsRowCard {
                        UfiInfoRow(label = "设备型号", value = "UFI-AXIS")
                        UfiInfoRow(label = "固件版本", value = "1.0.0")
                    }
                }

                GallerySection("卡片 · 5 个入口") {
                    Text("UfiEntryCard（横向入口）", style = UfiTextStyles.note, color = palette.textSecondary)
                    UfiEntryCard(
                        title = "入口卡片",
                        subtitle = "带副标题、徽标与右箭头",
                        icon = Icons.Filled.Settings,
                        onClick = {},
                        badgeText = "新"
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    UfiEntryCard(
                        title = "入口卡片 · 高亮",
                        subtitle = "highlighted = true",
                        icon = Icons.Filled.Star,
                        onClick = {},
                        highlighted = true
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiGridCard（网格入口，图标居中）", style = UfiTextStyles.note, color = palette.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        UfiGridCard(
                            modifier = Modifier.weight(1f),
                            title = "网格卡",
                            icon = Icons.Filled.Search,
                            description = "描述文案",
                            onClick = {}
                        )
                        UfiGridCard(
                            modifier = Modifier.weight(1f),
                            title = "网格卡",
                            icon = Icons.Filled.Refresh,
                            description = "描述文案",
                            onClick = {}
                        )
                    }

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiStatusCard（一行式状态）", style = UfiTextStyles.note, color = palette.textSecondary)
                    UfiStatusCard(title = "服务状态", statusText = "运行中", statusType = UfiBadgeType.SUCCESS)
                    Spacer(Modifier.height(Spacing.Small))
                    UfiStatusCard(
                        title = "带副标题",
                        statusText = "异常",
                        statusType = UfiBadgeType.ERROR,
                        subtitle = "最近一次检测失败"
                    )

                }

                GallerySection(
                    "容器卡 · Group（阴影 4dp / 内距 20dp）vs RowCard（阴影 2dp / 内距 16dp）",
                    contentInset = false
                ) {
                    UfiSettingsGroup {
                        Text("Group 内容", style = UfiTextStyles.body, color = palette.textPrimary)
                    }
                    UfiSettingsRowCard {
                        Text("RowCard 内容", style = UfiTextStyles.body, color = palette.textPrimary)
                    }
                    // UfiNoticeCard 也自带 CardHorizontalMargin，所以和上面两张并列放在
                    // contentInset = false 的这一节里。
                    UfiNoticeCard(message = "UfiNoticeCard · INFO：页面内联的一段说明。")
                    UfiNoticeCard(
                        severity = UfiNoticeSeverity.WARNING,
                        title = "UfiNoticeCard · WARNING",
                        message = "需要留意的事：淡底 + 淡描边，带标题时标题在图标右侧。"
                    )
                }

                GallerySection("输入 · 6 个入口") {
                    var text by remember { mutableStateOf("") }
                    UfiTextField(value = text, onValueChange = { text = it }, label = "UfiTextField")
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiTextField(
                        value = "非法输入",
                        onValueChange = {},
                        label = "错误态",
                        isError = true,
                        errorMessage = "格式不正确"
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    var pwd by remember { mutableStateOf("secret") }
                    UfiPasswordField(value = pwd, onValueChange = { pwd = it }, label = "UfiPasswordField")
                    Spacer(Modifier.height(Spacing.Medium))
                    var digit by remember { mutableStateOf("8080") }
                    UfiDigitField(value = digit, onValueChange = { digit = it }, label = "UfiDigitField", maxLength = 5)
                    Spacer(Modifier.height(Spacing.Medium))
                    var query by remember { mutableStateOf("") }
                    UfiSearchBar(query = query, onQueryChange = { query = it })
                    Spacer(Modifier.height(Spacing.Medium))
                    var dropdown by remember { mutableStateOf("auto") }
                    // 2026-09-04（P4e）：UfiDropdown 已合并 ScheduleSelector.TimeField 与
                    // UfiDateRangePickerDialog.UfiDropdownField 三套实现，签名改为泛型 + optionLabel。
                    // 两个样例分别覆盖：字符串值 + 自定义显示文案 / Int 值 + 单位后缀 + 长列表滚动。
                    UfiDropdown(
                        selectedValue = dropdown,
                        options = listOf("auto", "manual"),
                        onValueSelected = { dropdown = it },
                        optionLabel = { if (it == "auto") "自动" else "手动" }
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    var dropdownHour by remember { mutableStateOf(9) }
                    UfiDropdown(
                        selectedValue = dropdownHour,
                        options = (0..23).toList(),
                        onValueSelected = { dropdownHour = it },
                        unitSuffix = "小时",
                        optionLabel = { "%02d".format(it) }
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    var slider by remember { mutableStateOf(0.4f) }
                    UfiSlider(
                        value = slider,
                        onValueChange = { slider = it },
                        label = "UfiSlider",
                        valueLabel = "${(slider * 100).toInt()}%"
                    )
                }

                GallerySection("开关与勾选（RowCard 自带缩进）", contentInset = false) {
                    UfiSettingsRowCard {
                        var s1 by remember { mutableStateOf(true) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("UfiSwitch", style = UfiTextStyles.body, color = palette.textPrimary)
                            Spacer(Modifier.width(Spacing.Medium))
                            UfiSwitch(checked = s1, onCheckedChange = { s1 = it })
                            Spacer(Modifier.width(Spacing.Medium))
                            UfiSwitch(checked = false, onCheckedChange = {}, enabled = false)
                        }
                        Spacer(Modifier.height(Spacing.Medium))
                        var c1 by remember { mutableStateOf(true) }
                        UfiCheckbox(
                            checked = c1,
                            onCheckedChange = { c1 = it },
                            label = "UfiCheckbox",
                            description = "带说明的勾选项"
                        )
                        var c2 by remember { mutableStateOf(false) }
                        UfiAnimatedCheckbox(
                            checked = c2,
                            onCheckedChange = { c2 = it },
                            label = "UfiAnimatedCheckbox"
                        )
                    }
                }

                GallerySection("反馈态 · 空态 1 个、加载态 10 个") {
                    Text("UfiEmptyState（全库唯一空态）", style = UfiTextStyles.note, color = palette.textSecondary)
                    UfiEmptyState(
                        icon = Icons.Filled.Info,
                        message = "暂无数据",
                        hint = "下拉刷新试试"
                    )

                    Spacer(Modifier.height(Spacing.Large))
                    Text("UfiErrorBanner", style = UfiTextStyles.note, color = palette.textSecondary)
                    UfiErrorBanner(message = "请求失败：连接超时", onRetry = {})

                    Spacer(Modifier.height(Spacing.Large))
                    Text("加载态：UfiLoadingIndicator / UfiCompactProgressBar / UfiRingProgress", style = UfiTextStyles.note, color = palette.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UfiLoadingIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(Spacing.XLarge))
                        UfiRingProgress(progress = 0.65f, size = 72.dp, strokeWidth = 6.dp, label = "65%")
                    }
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiCompactProgressBar(progress = 0.45f)

                    Spacer(Modifier.height(Spacing.Large))
                    Text("骨架屏：UfiSkeletonLine / Circle / Card", style = UfiTextStyles.note, color = palette.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UfiSkeletonCircle(size = 40.dp)
                        Spacer(Modifier.width(Spacing.Medium))
                        Column(modifier = Modifier.weight(1f)) {
                            UfiSkeletonLine()
                            Spacer(Modifier.height(Spacing.Small))
                            UfiSkeletonLine(widthFraction = 0.6f)
                        }
                    }
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiSkeletonCard(lineCount = 2, showAvatar = true)
                }

                GallerySection("分段控件") {
                    var tab by remember { mutableStateOf(0) }
                    UfiScrollableTabRow(
                        selectedTabIndex = tab,
                        onTabSelected = { tab = it },
                        tabs = listOf("概览", "详情", "设置"),
                        badges = listOf(null, 3, null)
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiAnimatedTabContent(targetState = tab) { index ->
                        Text(
                            "第 ${index + 1} 页内容",
                            style = UfiTextStyles.body,
                            color = palette.textPrimary
                        )
                    }
                }

                GallerySection("弹窗 · 3 个语义化壳（另有 6 个通用壳未列）") {
                    var showAlert by remember { mutableStateOf(false) }
                    var showConfirm by remember { mutableStateOf(false) }
                    var showInput by remember { mutableStateOf(false) }

                    UfiButton(text = "UfiAlertDialog", onClick = { showAlert = true })
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(text = "UfiConfirmDialog（destructive）", onClick = { showConfirm = true })
                    Spacer(Modifier.height(Spacing.Small))
                    UfiButton(text = "UfiInputDialog", onClick = { showInput = true })

                    if (showAlert) {
                        UfiAlertDialog(
                            title = "提示",
                            text = "这是一条提示信息。",
                            onDismiss = { showAlert = false }
                        )
                    }
                    if (showConfirm) {
                        UfiConfirmDialog(
                            title = "确认删除",
                            text = "删除后不可恢复，确定继续？",
                            destructive = true,
                            onConfirm = { showConfirm = false },
                            onDismiss = { showConfirm = false }
                        )
                    }
                    if (showInput) {
                        UfiInputDialog(
                            title = "重命名",
                            initialValue = "旧名称",
                            hint = "输入新名称",
                            onConfirm = { showInput = false },
                            onDismiss = { showInput = false }
                        )
                    }
                }

                GallerySection("图标一致性抽查") {
                    Text(
                        "同一组图标在不同尺寸档下的观感：IconSizeSmall(18) / Medium(20) / Large(22)",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = palette.warning,
                            modifier = Modifier.size(Spacing.IconSizeSmall)
                        )
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = palette.warning,
                            modifier = Modifier.size(Spacing.IconSizeMedium)
                        )
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = palette.error,
                            modifier = Modifier.size(Spacing.IconSizeLarge)
                        )
                    }
                }

                // 底部留白：避开悬浮胶囊导航栏
                Spacer(Modifier.height(Spacing.SectionSpacing))
                Spacer(Modifier.height(Spacing.SectionSpacing))
            }
        }
    }
}

/**
 * 画廊的明暗预览主题。
 *
 * 关键点：**必须同时换 palette 与 `MaterialTheme.colorScheme`**，两者走的是 `UFIAXISTheme`
 * 用的同一个 [buildColorSchemeFromPalette]。
 *
 * 2026-09-03：本页最初只用 `ProvideThemePalette`（只换 palette 的 CompositionLocal），于是所有读
 * `MaterialTheme.colorScheme` 的 M3 组件仍停在外层明暗 —— 典型表现是切到暗色后，错误态输入框里的
 * 文字仍是黑的（`errorTextColor` 未显式指定 → 落到 `colorScheme.onSurface`）。那是预览失真造成的
 * 假阳性，不是组件缺陷。修好之后，画廊里**仍然看着不对的就是真问题**。
 */
@Composable
private fun GalleryPreviewTheme(
    basePalette: com.ufi_axis.ui.theme.ThemePalette,
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val resolved = remember(basePalette, isDark) { basePalette.resolve(isDark) }
    val scheme = remember(resolved) { buildColorSchemeFromPalette(resolved) }
    ProvideThemePalette(palette = basePalette, isDark = isDark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * 画廊分区：标题 + 内容块，统一间距。
 *
 * 关于横向内距（2026-09-03 修）：全站约定是**页面容器横向内距 0**，由组件自己决定缩进
 * （见 `UfiBackground.kt` 的说明）。实测只有 [UfiSettingsGroup] / [UfiSettingsRowCard] 自带
 * `Spacing.CardHorizontalMargin`；[UfiEntryCard] / [UfiGridCard] / [UfiStatusCard] 与所有裸组件
 * 都不带，需要调用方补。所以：
 * - [contentInset] = true（默认）：给内容补 `CardHorizontalMargin`，与自带内距的卡片视觉对齐；
 * - [contentInset] = false：内容里放的是 Group / RowCard，它们自己已经缩进过，不能再补，否则双重内距。
 *
 * 之前本页在最外层滚动容器上加了 `PagePadding`，于是卡片类缩进 12+16=28dp、裸组件只有 12dp，
 * 一眼就能看出左右边距对不齐 —— 这正是画廊该暴露的问题，只不过这次暴露的是画廊自己。
 */
@Composable
private fun GallerySection(
    title: String,
    contentInset: Boolean = true,
    content: @Composable () -> Unit
) {
    Spacer(Modifier.height(Spacing.SectionSpacing))
    UfiSectionHeader(
        title = title,
        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
    )
    Spacer(Modifier.height(Spacing.SectionTitleToCard))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (contentInset) Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                else Modifier
            ),
        // 说明文字与其下方组件之间给固定留白 —— 原先两者贴在一起（0dp），
        // 而分区标题与上一块内容的间距是正常的，视觉上就成了"说明像是黏在组件上"。
        verticalArrangement = Arrangement.spacedBy(Spacing.GroupSpacing)
    ) {
        content()
    }
}
