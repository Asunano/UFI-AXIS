// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 流量限额设置弹窗（限额值 + 单位 / 告警阈值 / 自动清零 / 到达阈值关网）。
 *
 * ## 原来在哪、为什么抽出来
 * 2026-09-25 之前这 180 行是 `app/feature-tools/.../TrafficManagementScreen.kt` 里
 * 一个**写在页面函数体内的匿名 `if (showLimitDialog) { … }` 块**，闭包捕获了 9 个外层变量
 * （5 个可写 state + `enabled` + `cfg` + `palette` + `viewModel`）。本轮仪表盘 hero 卡
 * 「本月流量」要复用同一个弹窗，而 `:app:feature-dashboard` 不依赖 `:app:feature-tools`
 * —— 不抽出来就只能在仪表盘再写一份 180 行 UI。
 *
 * ## 为什么是「哑组件」（纯值入参 + 回调出参）
 * `app/ui` **看不到** `:app:data` / `:app:viewmodel`（模块边界），所以签名里不能出现
 * `TrafficLimitConfig` / `MainViewModel`。`cfg` → 本组件入参的映射、以及
 * 「`clear_date=0` 会被 core 判 400」那套 `inRange` 值域清洗，都是 **data 层语义**，
 * 留在两个页面侧各写一份（只有映射，UI 不重复）。
 *
 * ## 搬的时候哪些行为必须逐字不变
 * ① **暂存-确认**：弹窗内改的是 `draftXxx`，不碰调用方的已生效值；只有点「保存」才通过
 *    [onConfirm] 把 7 个值一次性回传。历史 bug：弹窗直接编辑页面 state 时，点「取消」后
 *    改动已经留在页面上，紧接着任何一次「启用流量限额」开关（它会带着当前值立刻
 *    调 saveDataLimit）就把没确认的草稿发给了设备 —— 表现是"取消了仍然生效"。
 * ② **draft 的生命周期**：原实现把 `var draftXxx by remember` 写在 `if (showLimitDialog)`
 *    分支**内部**，关闭时这段组合被销毁、下次打开自动按当前值重新初始化。本组件用
 *    `if (!visible) return` 复刻同一条路径 —— 不要改成 `UfiScrollableDialog(visible = visible)`
 *    常驻挂载，那样 draft 会跨次打开残留。
 * ③ **前置校验的三条值域**与 core 的 `validateTrafficLimit` 逐条对齐：限额值 > 0 的整数、
 *    告警阈值 0~100、清零日期 1~31（自动清零关闭时该条不参与判定）。越界时字段标红 +
 *    保存按钮置灰，别让请求发出去 —— core 只会回一句没有原因的 400。
 * ④ 弹窗壳与按钮区：`UfiScrollableDialog` + `actions = UfiDialogActions`（`showCloseButton = false`），
 *    两个 AnimatedVisibility 的时长档位（`UfiMotion.Duration.Standard`）与包裹方式都不变。
 *
 * @param visible false 时本组件不组合任何内容（draft 随之销毁，见上面第 ② 条）。
 * @param autoOffTriggered 本计费周期是否已经因为限额关过网（只读，来自 `cfg.auto_off.triggered`）。
 * @param onConfirm 点「保存」时回传 7 个 draft 值。**关闭弹窗与发请求都由调用方负责** ——
 *   与原实现一致（原来也是在 onConfirm 里写回页面 state、调 saveDataLimit、置 false）。
 *   离场时序由 [UfiDialogActions] 内部经 `LocalUfiDialogClose` 排，调用方不用管。
 */
@Composable
fun UfiDataLimitDialog(
    visible: Boolean,
    limitSize: String,
    limitUnit: String,
    alertPercent: String,
    autoClear: Boolean,
    clearDate: String,
    autoOffEnabled: Boolean,
    autoOffRestore: Boolean,
    autoOffTriggered: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        limitSize: String,
        limitUnit: String,
        alertPercent: String,
        autoClear: Boolean,
        clearDate: String,
        autoOffEnabled: Boolean,
        autoOffRestore: Boolean
    ) -> Unit
) {
    // 复刻原实现的 `if (showLimitDialog) { … }`：关闭即销毁，下次打开按当前值重新初始化 draft。
    if (!visible) return

    val palette = LocalResolvedPalette.current

    // 暂存-确认：弹窗内改的是 draft，**不碰调用方已生效值**，点「保存」才回传。
    var draftSize by remember { mutableStateOf(limitSize) }
    var draftUnit by remember { mutableStateOf(limitUnit) }
    var draftAlert by remember { mutableStateOf(alertPercent) }
    var draftAutoClear by remember { mutableStateOf(autoClear) }
    var draftClearDate by remember { mutableStateOf(clearDate) }
    // 自动关网是 core 自制功能（不是设备字段），真源在 cfg.auto_off
    var draftAutoOff by remember { mutableStateOf(autoOffEnabled) }
    var draftAutoOffRestore by remember { mutableStateOf(autoOffRestore) }

    // ── 前置校验：值域与 core 侧 profile 的 validateTrafficLimit 逐条对齐 ──
    // 越界时 core 回 400 OUT_OF_RANGE，app 只能显示一句没有原因的「保存失败」，
    // 所以这里就把不合法的输入拦住：字段标红 + 保存按钮置灰，别让请求发出去。
    // 限额数值原来是自由文本框（能输字母），非数字会被 core 的 toLongOrNull 判空 ——
    // 那种情况更坑：接口回 success:true 但限额其实没写进设备。
    val sizeNum = draftSize.trim().toLongOrNull()
    val sizeValid = sizeNum != null && sizeNum > 0
    val alertNum = draftAlert.trim().toIntOrNull()
    val alertValid = alertNum != null && alertNum in 0..100
    val dateNum = draftClearDate.trim().toIntOrNull()
    // 自动清零关闭时清零日期不参与判定（它此时不会影响用量统计，但仍随请求下发）
    val dateValid = !draftAutoClear || (dateNum != null && dateNum in 1..31)
    val formValid = sizeValid && alertValid && dateValid

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "限额设置",
        icon = rememberVectorPainter(Icons.Filled.DataUsage),
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    onConfirm(
                        draftSize,
                        draftUnit,
                        draftAlert,
                        draftAutoClear,
                        draftClearDate,
                        draftAutoOff,
                        draftAutoOffRestore
                    )
                },
                confirmText = "保存",
                dismissText = "取消",
                enabled = formValid
            )
        }
    ) {
        UfiDialogBody {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 只收数字：原来是 UfiTextField，能输入字母/小数点，
                // 请求发出去后由 core 判非法（或静默丢弃），报错回来才知道。
                // maxLength=6 够到 999999 TB，够用且挡住误粘贴一长串。
                UfiDigitField(
                    value = draftSize,
                    onValueChange = { draftSize = it },
                    label = "限额大小",
                    modifier = Modifier.weight(1f),
                    maxLength = 6,
                    placeholder = "100",
                    isError = !sizeValid,
                    errorMessage = if (!sizeValid) "请输入大于 0 的整数" else null
                )
                Spacer(Modifier.width(Spacing.Medium))
                // 2026-09-04（P4e 下拉收敛）：UfiDropdown 已从 M3 ExposedDropdownMenuBox 换成
                // 主题化实现（44dp surfaceMuted 卡 + 主题化弹层），原先偏紫的 M3 弹层色随之消失。
                // 不再传 label："单位"作为浮动 label 已无对应槽位，本组件的 unitSuffix 是**值的后缀**
                // （"GB 单位"读不通）。左侧「限额大小」输入框的 label 已经交代了这一行在填什么。
                UfiDropdown(
                    selectedValue = draftUnit,
                    options = listOf("MB", "GB", "TB"),
                    onValueSelected = { draftUnit = it },
                    modifier = Modifier.width(110.dp)
                )
            }

            UfiDigitField(
                value = draftAlert,
                onValueChange = { draftAlert = it },
                label = "告警阈值 (%)",
                maxLength = 3,
                isError = !alertValid,
                errorMessage = if (!alertValid) "取值 0~100" else null
            )
            Text(
                "用量达到此百分比时触发告警",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )

            // 2026-09-04：两处"开关控制的附属块"由裸 `if` 改为 AnimatedVisibility。
            //
            // ⚠ 两个必须注意的点：
            // 1. **不能直接把 AnimatedVisibility 当 UfiDialogBody 的子项**：body 用
            //    `Arrangement.spacedBy(Spacing.Large)`，收起态的 AnimatedVisibility 虽然测量为 0 高，
            //    仍算一个子项 → 会凭空多出一道 12dp 间距（看起来就是"弹窗里莫名多一段空白"）。
            //    所以把「开关 + 附属块」包成一个 Column 当单个子项，附属块的上间距由它自己的
            //    padding 提供，收起时整块真正 0 高。
            // 2. 时长取 Duration.Standard(220)。`DownloadDialogs.kt:86` 记录过 expand/shrink 会让
            //    弹窗高度逐帧补间、输入框跟着抖，那里因此退回裸 if；这里能用是因为**附属块都在
            //    输入区下方**（限额值/阈值/清零日期都在其上），展开不会推动正在编辑的字段，
            //    且本弹窗是 UfiScrollableDialog（内容区带 verticalScroll），变高只滚动、不裁切。
            Column {
                UfiSettingsToggle(
                    title = "自动清零",
                    description = if (draftAutoClear) "每月 $draftClearDate 日自动重置" else "手动管理",
                    checked = draftAutoClear,
                    onCheckedChange = { draftAutoClear = it }
                )
                AnimatedVisibility(
                    visible = draftAutoClear,
                    enter = fadeIn(tween(UfiMotion.Duration.Standard)) +
                        expandVertically(tween(UfiMotion.Duration.Standard)),
                    exit = fadeOut(tween(UfiMotion.Duration.Standard)) +
                        shrinkVertically(tween(UfiMotion.Duration.Standard))
                ) {
                    Column(modifier = Modifier.padding(top = Spacing.Large)) {
                        UfiDigitField(
                            value = draftClearDate,
                            onValueChange = { draftClearDate = it },
                            label = "清零日期 (1-31)",
                            maxLength = 2,
                            isError = !dateValid,
                            errorMessage = if (!dateValid) "取值 1~31" else null
                        )
                    }
                }
            }

            Column {
                UfiSettingsToggle(
                    title = "到达阈值关闭移动数据",
                    description = "达到告警阈值后先发邮件通知，发送成功 1 分钟后关闭移动数据",
                    checked = draftAutoOff,
                    onCheckedChange = { draftAutoOff = it }
                )
                AnimatedVisibility(
                    visible = draftAutoOff,
                    enter = fadeIn(tween(UfiMotion.Duration.Standard)) +
                        expandVertically(tween(UfiMotion.Duration.Standard)),
                    exit = fadeOut(tween(UfiMotion.Duration.Standard)) +
                        shrinkVertically(tween(UfiMotion.Duration.Standard))
                ) {
                    Column(
                        modifier = Modifier.padding(top = Spacing.Large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Large)
                    ) {
                        UfiSettingsToggle(
                            title = "清零后自动重新打开",
                            description = if (draftAutoOffRestore) "流量清零后自动恢复移动数据"
                            else "只关一次，之后需手动打开",
                            checked = draftAutoOffRestore,
                            onCheckedChange = { draftAutoOffRestore = it }
                        )
                        Text(
                            "邮件发送失败时不会关闭网络（避免你以为设备故障）。需要在邮件设置里勾选「流量预警」场景。" +
                                if (autoOffTriggered) "\n本计费周期已触发过，用量清零前不会再次关闭。" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }
    }
}
