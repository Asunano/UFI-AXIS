// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 目录选择弹窗：浏览设备目录树并挑出目录，单选 / 多选共用同一套交互。
 *
 * ## 为什么在这一层
 * 2026-09-20 从 `feature-media` 的 `MediaScanDirsDialog` 上提。"选目录"这件事本身与媒体库
 * 毫无关系 —— 下载管理的新建任务要选保存目录、媒体三页要选扫描范围，两边要的浏览交互
 * （当前路径、上一级、子目录列表、加载/空态）是同一份。留在 feature 模块里第二个用它的人
 * 只能抄一遍，抄完两处的行为就开始各自漂移。
 *
 * 业务语义**不在**这里：多选的"空列表 = 不限目录"、单选的"必须选一个"这类含义由调用方
 * 通过 [multiSelect] / [emptySelectionHint] / [extraAction] 表达，本组件只负责浏览与勾选。
 *
 * ## 取数为什么走 lambda
 * 本组件在 `:app:ui`，这一层**不允许**依赖 viewmodel / data 模块（否则公共 UI 反过来绑死
 * 业务实现，任何 feature 想用都得先把那条依赖拖进来）。所以列目录的能力由 [browse] 注入，
 * 调用方各自把自己模块的实现传进来（媒体给 `MediaModule.browseDirs`、下载给
 * `DownloadModule.browseDirs`，两边都只是 `/api/files/list` 的一次"只留目录"过滤）。
 *
 * ## 两种用法
 * - 单选（[multiSelect] = false）：选中一个目录后确认。**空选择不允许确认** —— 单选场景
 *   （下载保存目录）拿不到目录就没有意义，确认键置灰比让调用方事后兜底更直白。
 * - 多选（[multiSelect] = true）：勾若干个目录。空列表是合法结果（媒体扫描范围的
 *   "不限目录 = 整个媒体库"），所以确认键**不**置灰。
 *
 * @param root 浏览起点。刻意**没有默认值**：设备存储根是业务知识（core 白名单前缀），
 *   由调用方显式给出；需要"用户存储根"的直接传 [UFI_DEVICE_STORAGE_ROOT]。
 * @param initialSelection 进入时的已选目录。单选下只取第一个（多余的是调用方数据问题，
 *   这里静默截断而不是抛错 —— 弹窗不该因为脏数据打不开）。
 * @param confirmText 确认键文案。默认"确定"；媒体那边历史上是"保存"，由调用方保留。
 * @param emptySelectionHint 没有任何选中时顶部那行说明。**必须由调用方给** ——
 *   "整个媒体库（不限目录）"和"未选择目录"是两件事，公共层没资格替业务下定义。
 * @param extraAction 额外动作按钮（如媒体的「清空（整个媒体库）」），null = 不显示。
 * @param browse 列目录：给一个路径，回 (子目录绝对路径列表, 父目录或 null)。
 *   实现方负责吞异常并回空列表 —— 本组件把"读不到"与"空目录"显示成同一种状态。
 */
@Composable
fun UfiDirectoryPickerDialog(
    visible: Boolean,
    title: String,
    root: String,
    initialSelection: List<String> = emptyList(),
    multiSelect: Boolean = false,
    confirmText: String = "确定",
    emptySelectionHint: String = "未选择目录",
    extraAction: UfiDirectoryPickerAction? = null,
    browse: suspend (String) -> Pair<List<String>, String?>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    if (!visible) return
    val palette = LocalResolvedPalette.current
    // 草稿选择：确认才回吐给调用方，取消就整份丢掉。
    var selection by remember(initialSelection, multiSelect) {
        mutableStateOf(if (multiSelect) initialSelection else initialSelection.take(1))
    }
    // 浏览起点固定是 root，不是"已选的那个目录"：已选目录可能已被删/换过卡，
    // 直接落进去会拿到空列表且 parent == null，用户连"上一级"都点不动，等于卡死在死路上。
    var path by remember(root) { mutableStateOf(root) }
    var parent by remember { mutableStateOf<String?>(null) }
    var children by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        val (dirs, up) = browse(path)
        children = dirs
        parent = up
        loading = false
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = title,
        confirmButton = {
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读拿到的是"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(
                text = confirmText,
                onClick = { close { onConfirm(selection) } },
                // 多选允许空（"不限目录"本身是一种选择）；单选空着确认没有意义。
                enabled = multiSelect || selection.isNotEmpty()
            )
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(text = "取消", onClick = { close(onDismiss) }, variant = UfiButtonVariant.Subtle)
        }
    ) {
        // 块间距统一由 UfiDialogBody 给（12dp），这里不再自己加 Spacer。
        UfiDialogBody {
            Text(
                when {
                    selection.isEmpty() -> emptySelectionHint
                    multiSelect -> "已选 ${selection.size} 个目录"
                    else -> "已选目录"
                },
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )

            selection.forEach { dir ->
                // 多选下点已选项 = 取消勾选（比逼人逐条找地方删更顺手）；
                // 单选下确认键已经要求必选，"点一下把唯一选择去掉"只会把自己点进置灰状态。
                val onSelectedClick: (() -> Unit)? =
                    if (multiSelect) ({ selection = selection - dir }) else null
                UfiListRowCard(
                    title = dir.substringAfterLast('/').ifBlank { dir },
                    subtitle = dir,
                    selected = true,
                    onClick = onSelectedClick,
                    leading = {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(DIRECTORY_ROW_ICON_SIZE)
                        )
                    }
                )
            }

            if (extraAction != null && extraAction.visible(selection)) {
                UfiButton(
                    text = extraAction.text,
                    onClick = { extraAction.onClick { next -> selection = next } },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            }

            UfiDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { parent?.let { path = it } },
                    enabled = parent != null
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "上一级",
                        tint = if (parent != null) palette.accent else palette.textSecondary,
                        modifier = Modifier.size(DIRECTORY_ROW_ICON_SIZE)
                    )
                }
                Text(
                    path,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 目标目录常常就是"我现在正站着的这个"（它不会出现在自己的子目录列表里），
                // 所以必须有一颗"选当前路径"的键，否则用户只能选到叶子目录的上一层。
                UfiButton(
                    text = if (multiSelect) "加入此目录" else "选择此目录",
                    onClick = {
                        selection = if (multiSelect) {
                            if (path in selection) selection else selection + path
                        } else {
                            listOf(path)
                        }
                    },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    icon = if (multiSelect) Icons.Default.Add else Icons.Default.Check
                )
            }

            when {
                // 换目录就是换一份列表，用骨架而不是转圈：转圈只说"在忙"，骨架还说明
                // "马上会出现一份列表"（见 UfiListStates 的口径）。
                loading -> UfiListLoadingState(rows = DIRECTORY_SKELETON_ROWS)

                // "读不到"与"真的没有子目录"在这里合并成一句话：对用户而言下一步动作相同
                // （往上退或选当前目录），分两种文案只会让人去猜是不是坏了。
                children.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(DIRECTORY_STATE_HEIGHT)
                ) {
                    UfiListEmptyState(text = "这个目录下没有子目录")
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 必须钉上限：目录数量由设备决定（几百个子目录很常见），
                        // 不限高时弹窗会被内容撑出屏幕，底部确认键直接点不到。
                        .heightIn(max = DIRECTORY_LIST_MAX_HEIGHT),
                    contentPadding = PaddingValues(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    items(children, key = { it }) { dir ->
                        UfiListRowCard(
                            title = dir.substringAfterLast('/').ifBlank { dir },
                            selected = dir in selection,
                            // 点行 = 进下一层，勾选走右侧按钮：两个动作都很常用，
                            // 挤到同一个手势上必然误触（想进去结果选中了）。
                            onClick = { path = dir },
                            leading = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(DIRECTORY_ROW_ICON_SIZE)
                                )
                            },
                            trailing = {
                                UfiButton(
                                    text = if (dir in selection) "已选" else "选择",
                                    onClick = {
                                        selection = when {
                                            !multiSelect -> listOf(dir)
                                            dir in selection -> selection - dir
                                            else -> selection + dir
                                        }
                                    },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * [UfiDirectoryPickerDialog] 的额外动作按钮。
 *
 * [onClick] 收的是"改写当前选择"的回调，而不是一个无参 lambda：这类动作（媒体的
 * 「清空（整个媒体库）」）改的是**弹窗内部的草稿选择**，调用方手上没有那份状态。
 *
 * @param text 按钮文案
 * @param visible 按当前选择决定是否露出（「清空」只在有选中项时才有意义）
 * @param onClick 点击处理；参数是 setter，传入新的选择集合即可
 */
class UfiDirectoryPickerAction(
    val text: String,
    val visible: (selection: List<String>) -> Boolean = { true },
    val onClick: (setSelection: (List<String>) -> Unit) -> Unit
)

/**
 * 设备用户存储根：`/storage/emulated/0`。
 *
 * 放在这里只是为了让两个调用方（媒体扫描目录、下载保存目录）不各写一遍同一个字面量；
 * 它**不是** [UfiDirectoryPickerDialog] 的默认值 —— 起点属于业务决定，必须显式传。
 * 这个前缀同时是 core 文件接口的白名单前缀之一，文件管理器也从这里起步。
 */
const val UFI_DEVICE_STORAGE_ROOT = "/storage/emulated/0"

/** 子目录列表的高度上限：约 3 行卡片，再多就靠滚动，保证确认键始终在屏内。 */
private val DIRECTORY_LIST_MAX_HEIGHT = 260.dp

/** 空态占位高度：与列表首屏高度接近，避免"空 → 有内容"时弹窗高度大幅跳变。 */
private val DIRECTORY_STATE_HEIGHT = 120.dp

/** 行内图标（勾、文件夹、上一级）统一尺寸。 */
private val DIRECTORY_ROW_ICON_SIZE = 18.dp

/** 加载骨架行数：弹窗内可见区只有两三行，铺满一屏的 6 行会把弹窗撑高又立刻缩回。 */
private const val DIRECTORY_SKELETON_ROWS = 2
