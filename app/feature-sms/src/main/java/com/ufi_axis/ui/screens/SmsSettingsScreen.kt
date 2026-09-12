package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 验证码缓存自动清理间隔档位（小时；"0" = 永不清理）。
 *
 * 2026-09-08 从 `SmsScreen.kt` 搬来：那边原本是「短信设置」弹窗内联双栏 grid 用的，
 * 弹窗整体改成本页面后，档位表随之搬家（旧的那份连同弹窗一起删掉，不留两份）。
 */
private val SMS_CODE_CLEANUP_OPTIONS = listOf(
    "0" to "永不清理",
    "1" to "1 小时",
    "6" to "6 小时",
    "24" to "24 小时",
    "72" to "3 天",
    "168" to "7 天",
    "720" to "30 天"
)

/**
 * 短信设置页（2026-09-08）。
 *
 * 由短信页顶栏齿轮原来弹的那个 `UfiCustomDialog`（`SmsScreen.kt` 旧 :600-678）**整体改造**而来，
 * 那个弹窗已经删除 —— 齿轮现在直接 `navigate(Routes.DETAIL_SMS_SETTINGS)`，
 * 不保留「弹窗 + 页面」两个入口。
 *
 * 改页面的动机不只是"地方大一点"：拦截规则与已拦截是两个各自带列表的二级页，
 * 从弹窗里再弹一层窗口既没地方放、返回栈也说不清；页面化之后这两个入口就是两张平级的
 * [UfiEntryCard]。
 *
 * 版式是**无分组的单行卡**：一条设置项一张 [UfiSettingsRowCard]，不用 [UfiSettingsGroup]。
 * 卡间距由 [UfiPageBackground] 统一给，页面里不要再手加 `Spacer` 或纵向 padding。
 *
 * 壳用 [UfiPageBackground]（自带 `verticalScroll`）而不是 [UfiPageBackgroundBox]：
 * 本页全是设置行，没有 `LazyColumn`。反过来在 [SmsFilterRulesScreen] / [SmsBlockedScreen]
 * 里必须用 Box 版。
 */
@Composable
fun SmsSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val toolsState by viewModel.toolsState.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 自动复制验证码依赖系统无障碍服务（UfiNotifyAccessibilityService）。
    // 同 BackgroundGuardScreen 无障碍开关：用 mutableStateOf + ON_RESUME 重检，
    // 否则用户从系统设置返回后状态不刷新（2026-08-20 踩过的坑）。
    val a11yServiceName = "com.ufi_axis.notification.UfiNotifyAccessibilityService"
    var a11yEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context, a11yServiceName)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = checkAccessibilityEnabled(context, a11yServiceName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // 豁免开关、规则条数、拦截记录角标各来自不同端点，进页面一次性拉齐。
        // 规则与记录都走静默口径：读不到就显示 0 / 不亮角标，不该在设置页顶部挂错误横幅。
        viewModel.tools.refreshDeviceConfig()
        viewModel.tools.loadSmsRules(silent = true)
        viewModel.tools.loadSmsBlocked()
    }
    rememberResumeRefresh {
        viewModel.tools.loadSmsRules(silent = true)
        viewModel.tools.loadSmsBlocked()
    }

    val cleanupLabel = SMS_CODE_CLEANUP_OPTIONS
        .firstOrNull { it.first == toolsState.smsCodeCleanupHours.toString() }
        ?.second
        ?: "${toolsState.smsCodeCleanupHours} 小时"

    UfiScreenScaffold(
        title = "短信设置",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 2026-09-08：取消「验证码」「拦截」两个分组卡，改成一条设置项一张卡。
            // 本页只有 5 个条目，分两组之后每组的组标题占的高度和组里的内容差不多，
            // 分组带来的信息量抵不上它吃掉的屏幕；而且「验证码豁免关键词拦截」既属于验证码
            // 也属于拦截，放在任一组的组标题下都在暗示一个不存在的归属。
            // 卡间距由 [UfiPageBackground] 的 `spacedBy(Spacing.CardBottomMargin)` 给，这里不加。
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "自动解析验证码",
                    description = "自动识别新收到的验证码短信，完全在本地运行",
                    checked = toolsState.smsCodeEnabled,
                    onCheckedChange = { viewModel.tools.setSmsCodeEnabled(it) },
                    icon = Icons.Default.VerifiedUser
                )
            }

            // 清理间隔只在解析开启时出现：关掉解析之后不会再产生新验证码，
            // 留一个「多久清理一次」的行等于摆一个改了看不出效果的旋钮。
            // 这与它替换掉的那个弹窗行为一致（旧实现也是 `if (smsCodeEnabled)`）。
            if (toolsState.smsCodeEnabled) {
                UfiSettingsRowCard {
                    // 档位选择器用站内下拉菜单（[UfiPopupAnchor] = 锚点 + 坐标采集 + 展开态一体），
                    // 不是 [UfiChoiceSheet] 的底部弹层：清理间隔是「改一个小档位」，
                    // 从屏幕底部升起半屏 ModalBottomSheet 的动静太大，菜单就贴在被点的那一行下面。
                    // 全站禁止 M3 DropdownMenu（部分机型不继承自定义 colorScheme 会白底）。
                    UfiPopupAnchor(
                        options = SMS_CODE_CLEANUP_OPTIONS.map { (hours, label) ->
                            UfiPopupOption(
                                id = hours,
                                label = label,
                                // 选中态走组件自带语义：accent 文字 + Medium 字重 + 前置 ✓，
                                // 不在 label 里手拼「（当前）」这类标记。
                                isSelected = hours == toolsState.smsCodeCleanupHours.toString(),
                                onClick = {
                                    // 档位表的 key 全是数字字面量，理论上不会解析失败；
                                    // 真解析不出来时什么都不做，比拿一个兜底值悄悄改掉用户设置好。
                                    hours.toIntOrNull()
                                        ?.let { viewModel.tools.setSmsCodeCleanupHours(it) }
                                }
                            )
                        }
                    ) { toggle ->
                        UfiSettingsValue(
                            title = "清理间隔",
                            description = "超过该时长的验证码缓存自动删除",
                            value = cleanupLabel,
                            onClick = toggle,
                            // AutoDelete（带表盘的垃圾桶）而不是 Schedule：这一行的语义是
                            // 「到期自动删除」，纯时钟只表达"和时间有关"。
                            icon = Icons.Default.AutoDelete
                        )
                    }
                }

                // 自动复制验证码：依赖系统无障碍服务（UfiNotifyAccessibilityService），未开启则禁用并引导去开启
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "自动复制验证码",
                        description = if (a11yEnabled) {
                            "新解析到的验证码会自动复制到剪贴板（需保持软件在后台运行），无需手动点按"
                        } else {
                            "需在「后台守护」中开启无障碍服务，并保持软件在后台运行"
                        },
                        icon = Icons.Default.ContentCopy,
                        enabled = a11yEnabled,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UfiSwitch(
                                    checked = toolsState.smsCodeAutoCopy,
                                    enabled = a11yEnabled,
                                    onCheckedChange = { viewModel.tools.setSmsCodeAutoCopy(it) }
                                )
                                if (!a11yEnabled) {
                                    Spacer(Modifier.width(Spacing.Small))
                                    UfiButton(
                                        size = UfiButtonSize.Small,
                                        text = "去开启",
                                        onClick = {
                                            navController.navigate(Routes.DETAIL_BACKGROUND_GUARD)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }

            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "验证码豁免关键词拦截",
                    description = "开启后验证码短信不受关键词规则影响，但仍受号码黑名单约束",
                    checked = toolsState.smsFilterExemptVerificationCode,
                    onCheckedChange = { viewModel.tools.setSmsFilterExemptVerificationCode(it) },
                    icon = Icons.Default.FilterAlt
                )
            }

            // 两个二级页做成同款入口卡（而不是一个「值 + 箭头」行、一个入口卡）：
            // 它们是平级的两件事 —— 规则是"设什么"、已拦截是"拦到了什么"。
            //
            // 外面这层 horizontal padding 是必须的：UfiEntryCard 自身 fillMaxWidth、
            // 不带卡片外边距（它原本长在已有横向内距的列表里），直接放进
            // UfiPageBackground 会贴到屏幕两侧、和上面几张单行卡对不齐。
            UfiEntryCard(
                title = "拦截规则",
                subtitle = "号码黑名单与关键词",
                icon = Icons.Default.Rule,
                // 条数走 INFO 角标而不是 WARNING：有规则是正常状态，不是需要处理的异常。
                // 0 条时不亮角标（`takeIf`），空规则表本身由规则页的空态解释。
                badgeText = toolsState.smsRules.size.takeIf { it > 0 }?.let { "$it 条" },
                badgeType = UfiBadgeType.INFO,
                onClick = { navController.navigate(Routes.DETAIL_SMS_FILTER_RULES) },
                modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
            )

            // 拦截是「静默丢消息」的功能，用户能不能发现"我是不是漏了什么"全靠这个入口。
            // 未查看角标挂在这里是唯一选择 —— 记录页的 Tab 栏已经拆掉（badges 槽位随之消失），
            // 顶栏齿轮本身也挂不上角标。
            UfiEntryCard(
                title = "已拦截",
                subtitle = "查看被规则拦下的短信",
                icon = Icons.Default.Block,
                badgeText = toolsState.smsBlockedUnviewed.takeIf { it > 0 }?.toString(),
                badgeType = UfiBadgeType.WARNING,
                onClick = { navController.navigate(Routes.DETAIL_SMS_BLOCKED) },
                modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
            )

            // 2026-09-08：原来这里还有一个「其它」分组（设备短信条数只读行 + 全部标为已读）。
            // 已搬到短信页消息 Tab 的列表首行 —— 那两项讲的是"设备上现在有多少短信、
            // 有没有未读"，属于看消息时顺手要知道的信息，藏在二级设置页里等于没有。
        }
    }
}

/** 检查本应用的无障碍服务（UfiNotifyAccessibilityService）是否已启用；未启用则自动复制验证码不可用。 */
private fun checkAccessibilityEnabled(context: Context, serviceName: String): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == serviceName
        }
}
